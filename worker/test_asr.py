from pathlib import Path
import json
import subprocess
import uuid
import wave

from fastapi.testclient import TestClient
import pytest

from asr import Transcriber, ffmpeg_executable, normalize_audio, remove_exact_overlap
import app as worker_app


def wav(path: Path, seconds: float = 2.4, channels: int = 2, rate: int = 8000) -> Path:
    with wave.open(str(path), "wb") as audio:
        audio.setnchannels(channels); audio.setsampwidth(2); audio.setframerate(rate)
        audio.writeframes(b"\0\0" * round(rate * seconds) * channels)
    return path


class FakeEngine:
    name = "test-engine"
    def __init__(self, fail_at: int = 0):
        self.calls = 0
        self.fail_at = fail_at
    def recognize(self, path: Path):
        self.calls += 1
        with wave.open(str(path), "rb") as audio:
            assert audio.getnchannels() == 1
            assert audio.getframerate() == 16000
            assert audio.getnframes() <= 16000
        if self.calls == self.fail_at:
            raise ValueError("simulated inference failure")
        return f"课堂知识第{self.calls}段", {"text": f"raw-{self.calls}"}


def test_conversion_and_bounded_overlapping_windows(tmp_path):
    source = wav(tmp_path / "stereo.wav")
    engine = FakeEngine()
    result = Transcriber(lambda: engine, window_seconds=1, overlap_seconds=.2).run(source, tmp_path / "run")
    assert result["durationMs"] == 2400
    assert len(result["segments"]) == 3
    assert [(s["startMs"], s["endMs"]) for s in result["segments"]] == [(0,1000),(800,1800),(1600,2400)]
    assert all(s["timestampKind"] == "window" for s in result["segments"])
    journal = [json.loads(line) for line in (tmp_path / "run/chunks.jsonl").read_text(encoding="utf-8").splitlines()]
    assert journal[1]["raw"]["text"] == "raw-2"
    assert (tmp_path / "run/result.json").exists()
    assert not list((tmp_path / "run").glob("chunk-*.wav"))


def test_real_m4a_conversion(tmp_path):
    source = wav(tmp_path / "original.wav", seconds=1)
    m4a = tmp_path / "source.m4a"
    subprocess.run([ffmpeg_executable(),"-nostdin","-y","-loglevel","error","-i",str(source),"-c:a","aac",str(m4a)],check=True)
    target = tmp_path / "normalized.wav"
    normalize_audio(m4a,target)
    with wave.open(str(target),"rb") as audio:
        assert audio.getnchannels() == 1
        assert audio.getframerate() == 16000
        assert audio.getnframes() > 0


def test_failure_retains_partial_raw_output(tmp_path):
    source = wav(tmp_path / "source.wav")
    with pytest.raises(ValueError,match="simulated"):
        Transcriber(lambda: FakeEngine(fail_at=2),window_seconds=1,overlap_seconds=.2).run(source,tmp_path / "run")
    assert len((tmp_path / "run/chunks.jsonl").read_text(encoding="utf-8").splitlines()) == 1
    assert (tmp_path / "run/error.json").exists()
    assert (tmp_path / "run/normalized.wav").exists()
    assert not (tmp_path / "run/result.json").exists()
    progress=json.loads((tmp_path / "run/progress.json").read_text(encoding="utf-8"))
    assert progress["phase"]=="failed" and progress["completedChunks"]==1
    assert progress["processedMs"]==1000 and progress["totalChunks"]==3


def test_live_progress_follows_persisted_windows_and_finishes_at_full_duration(tmp_path):
    source=wav(tmp_path / "source.wav");output=tmp_path / "run"
    observed=[]
    class ObservedEngine(FakeEngine):
        def recognize(self,path):
            progress=json.loads((output / "progress.json").read_text(encoding="utf-8"))
            observed.append(progress)
            assert progress["completedChunks"]==len((output / "chunks.jsonl").read_text(encoding="utf-8").splitlines())
            return super().recognize(path)
    def factory():
        assert json.loads((output / "progress.json").read_text())["phase"]=="loading_model"
        return ObservedEngine()
    Transcriber(factory,window_seconds=1,overlap_seconds=.2).run(source,output)
    assert [p["processedMs"] for p in observed]==[0,1000,1800]
    final=json.loads((output / "progress.json").read_text(encoding="utf-8"))
    assert final=={"phase":"completed","processedMs":2400,"durationMs":2400,"completedChunks":3,"totalChunks":3}


def test_progress_file_contention_cannot_fail_successful_inference(tmp_path,monkeypatch):
    import asr
    def busy(*args): raise PermissionError("progress temporarily busy")
    monkeypatch.setattr(asr.os,"replace",busy)
    result=Transcriber(lambda:FakeEngine(),window_seconds=1,overlap_seconds=.2).run(wav(tmp_path/'source.wav'),tmp_path/'run')
    assert result['text'] and (tmp_path/'run/result.json').exists()


def test_only_exact_overlap_is_removed():
    assert remove_exact_overlap("上一段定义连续可导关系", "连续可导关系是重要知识") == "是重要知识"
    assert remove_exact_overlap("定义连续可导关系", "连续和可导关系不同") == "连续和可导关系不同"


def test_api_path_confinement_auth_and_completed_job_recovery(tmp_path,monkeypatch):
    monkeypatch.setattr(worker_app,"DATA_DIR",tmp_path)
    monkeypatch.setenv("ASR_WORKER_TOKEN","test-secret")
    client = TestClient(worker_app.app)
    assert client.get("/health").status_code == 401
    headers={"Authorization":"Bearer test-secret"}
    job_id=str(uuid.uuid4())
    response=client.post("/transcribe",headers=headers,json={"inputPath":str(tmp_path / "outside.wav"),"jobId":job_id})
    assert response.status_code == 400
    (tmp_path / "audio").mkdir()
    source=wav(tmp_path / "audio/source.wav")
    fake = Transcriber(lambda:FakeEngine(),window_seconds=1,overlap_seconds=.2)
    monkeypatch.setattr(worker_app,"transcriber",fake)
    payload={"inputPath":str(source),"jobId":job_id}
    first=client.post("/transcribe",headers=headers,json=payload)
    assert first.status_code == 200
    calls=fake.engine.calls
    second=client.post("/transcribe",headers=headers,json=payload)
    assert second.json() == first.json()
    assert fake.engine.calls == calls
    traversal=client.post("/transcribe",headers=headers,json={"inputPath":str(tmp_path / "audio/../outside.wav"),"jobId":str(uuid.uuid4())})
    assert traversal.status_code == 400


def test_invalid_window_configuration():
    with pytest.raises(ValueError):
        Transcriber(window_seconds=31)
    with pytest.raises(ValueError):
        Transcriber(window_seconds=1,overlap_seconds=1)


def test_worker_serializes_inference_without_erasing_a_running_job(tmp_path):
    runner=Transcriber(lambda:FakeEngine(),window_seconds=1,overlap_seconds=.2)
    runner.lock.acquire()
    try:
        with pytest.raises(RuntimeError,match="其他任务"):
            runner.run(tmp_path / "x.wav",tmp_path / "run")
        assert not (tmp_path / "run").exists()
    finally:
        runner.lock.release()
