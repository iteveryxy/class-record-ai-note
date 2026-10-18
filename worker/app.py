from __future__ import annotations

import importlib.util
import logging
import os
from pathlib import Path
import secrets
import uuid

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel

from asr import Transcriber, ffmpeg_executable

log = logging.getLogger("classnotes.asr")
DATA_DIR = Path(os.getenv("DATA_DIR", "../data")).resolve()
transcriber = Transcriber(
    window_seconds=float(os.getenv("ASR_WINDOW_SECONDS", "28")),
    overlap_seconds=float(os.getenv("ASR_OVERLAP_SECONDS", "2")),
    dedupe=os.getenv("ASR_DEDUPE", "true").lower() == "true",
)
app = FastAPI(title="Class Notes ASR Worker", version="0.2.6")


def authorize(authorization: str | None = Header(default=None)) -> None:
    token = os.getenv("ASR_WORKER_TOKEN", "")
    if token and not secrets.compare_digest(authorization or "", "Bearer " + token):
        raise HTTPException(status_code=401, detail="Worker token 无效")


class TranscribeRequest(BaseModel):
    inputPath: str
    jobId: uuid.UUID


def validated_input(path: str) -> Path:
    source = Path(path).resolve()
    audio_dir = (DATA_DIR / "audio").resolve()
    if not source.is_relative_to(audio_dir) or source.suffix.lower() not in {".wav", ".m4a"}:
        raise HTTPException(status_code=400, detail="音频路径必须在 DATA_DIR/audio 内")
    if not source.is_file():
        raise HTTPException(status_code=404, detail="音频文件不存在；Java 与 Worker 须使用同一 DATA_DIR")
    return source


@app.get("/health", dependencies=[Depends(authorize)])
def health():
    return {"status": "ok", "provider": "custom" if os.getenv("ASR_ENGINE_MODULE") else "sensevoice-small",
            "modelLoaded": transcriber.engine is not None,
            "asrInstalled": importlib.util.find_spec("funasr") is not None,
            "ffmpeg": ffmpeg_executable(), "timestampKind": "window"}


@app.post("/transcribe", dependencies=[Depends(authorize)])
def transcribe(request: TranscribeRequest):
    source = validated_input(request.inputPath)
    output = DATA_DIR / "asr" / str(request.jobId)
    # Recover a completed result after a Java HTTP timeout, without re-running inference.
    cached = output / "result.json"
    if cached.is_file():
        import json
        return json.loads(cached.read_text(encoding="utf-8"))
    if output.exists():
        raise HTTPException(status_code=409, detail={"message": "此任务曾中断或仍在运行；后端重试会分配新任务 ID", "artifactPath": str(output)})
    try:
        return transcriber.run(source, output)
    except ModuleNotFoundError as error:
        log.warning("ASR dependency missing: %s", error.name)
        raise HTTPException(status_code=503, detail={"message": "语音模型依赖缺失，请安装 requirements-asr.txt（Python 3.11/3.12）", "artifactPath": str(output)}) from error
    except RuntimeError as error:
        raise HTTPException(status_code=503, detail={"message": str(error), "artifactPath": str(output)}) from error
    except Exception as error:
        log.exception("Transcription failed for job %s", request.jobId)
        raise HTTPException(status_code=422, detail={"message": str(error)[:2000], "artifactPath": str(output)}) from error
