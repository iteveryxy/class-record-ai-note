"""Bounded-memory audio conversion and pluggable local ASR.

Window timestamps identify audio boundaries, never fabricated word alignment.
Every raw window result is flushed before merging; failed runs remain inspectable.
"""
from __future__ import annotations

import importlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
from typing import Any, Protocol
import wave


class Engine(Protocol):
    name: str

    def recognize(self, audio: Path) -> tuple[str, Any]: ...


class SenseVoiceEngine:
    name = "sensevoice-small"

    def __init__(self) -> None:
        # Import lazily: conversion, health checks and tests need no Torch installation.
        from funasr import AutoModel
        from funasr.utils.postprocess_utils import rich_transcription_postprocess

        self.postprocess = rich_transcription_postprocess
        self.model = AutoModel(
            model=os.getenv("ASR_MODEL", "iic/SenseVoiceSmall"),
            hub=os.getenv("ASR_HUB", "ms"),
            device=os.getenv("ASR_DEVICE", "cpu"),
            trust_remote_code=False,
            disable_update=True,
        )

    def recognize(self, audio: Path) -> tuple[str, Any]:
        import numpy as np
        with wave.open(str(audio), "rb") as wav:
            samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
        result = self.model.generate(
            input=samples, fs=16000, cache={}, language=os.getenv("ASR_LANGUAGE", "auto"),
            use_itn=True, batch_size=1,
        )
        tagged = result[0].get("text", "") if result else ""
        clean = self.postprocess(tagged)
        # Keep original tagged model output in the journal; clean the displayed text.
        clean = re.sub(r"<\|[^|]+\|>", "", clean).strip()
        if not any(char.isalnum() for char in clean):
            clean = ""  # Event/emotion glyphs alone are not a classroom transcript.
        return clean, result


def make_engine() -> Engine:
    """Alternative module must expose create_engine() -> Engine (same contract)."""
    module = os.getenv("ASR_ENGINE_MODULE", "")
    if module:
        return importlib.import_module(module).create_engine()
    return SenseVoiceEngine()


def builtin(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(k): builtin(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [builtin(v) for v in value]
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    if hasattr(value, "tolist"):
        return value.tolist()
    if hasattr(value, "item"):
        return value.item()
    return str(value)


def ffmpeg_executable() -> str:
    configured = os.getenv("FFMPEG_PATH", "")
    if configured:
        return configured
    installed = shutil.which("ffmpeg")
    if installed:
        return installed
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def normalize_audio(source: Path, target: Path) -> None:
    command = [ffmpeg_executable(), "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
               "-i", str(source), "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(target)]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=1800)
    if result.returncode:
        raise ValueError("音频转换失败：" + result.stderr[-2000:])


def remove_exact_overlap(previous: str, current: str, min_chars: int = 6) -> str:
    """Remove only an exact suffix/prefix match. Raw text is always retained."""
    for size in range(min(len(previous), len(current), 200), min_chars - 1, -1):
        if previous[-size:] == current[:size]:
            return current[size:]
    return current


class Transcriber:
    def __init__(self, engine_factory=make_engine, window_seconds: float = 28, overlap_seconds: float = 2,
                 dedupe: bool = True) -> None:
        if not 0 < window_seconds <= 30 or not 0 <= overlap_seconds < window_seconds:
            raise ValueError("分段应不超过 30 秒，重叠应小于分段长度")
        self.engine_factory = engine_factory
        self.window_seconds = window_seconds
        self.overlap_seconds = overlap_seconds
        self.dedupe = dedupe
        self.engine: Engine | None = None
        self.lock = threading.Lock()

    def run(self, source: Path, output_dir: Path) -> dict:
        # One model instance; concurrent HTTP retries do not share model caches.
        if not self.lock.acquire(blocking=False):
            raise RuntimeError("Worker 正在处理其他任务，请稍后重试")
        try:
            return self._run(source, output_dir)
        finally:
            self.lock.release()

    def _run(self, source: Path, output_dir: Path) -> dict:
        output_dir.mkdir(parents=True, exist_ok=False)
        normalized = output_dir / "normalized.wav"
        journal = output_dir / "chunks.jsonl"
        progress = {"phase": "converting", "processedMs": 0, "durationMs": 0,
                    "completedChunks": 0, "totalChunks": 0}

        def report(phase: str, **values):
            progress.update(phase=phase, **values)
            # Readers see either the last complete snapshot or the next one.
            try:
                temporary = output_dir / "progress.json.tmp"
                temporary.write_text(json.dumps(progress, ensure_ascii=False), encoding="utf-8")
                os.replace(temporary, output_dir / "progress.json")
            except OSError:
                # Advisory progress must not interrupt inference or erase its journal.
                pass

        try:
            report("converting")
            normalize_audio(source, normalized)
            segments: list[dict] = []
            full_text: list[str] = []
            previous = ""
            with wave.open(str(normalized), "rb") as audio, journal.open("w", encoding="utf-8") as raw_file:
                if audio.getframerate() != 16000 or audio.getnchannels() != 1 or audio.getsampwidth() != 2:
                    raise ValueError("规范化音频必须为 16kHz 单声道 PCM16")
                sample_rate = audio.getframerate()
                frames = audio.getnframes()
                duration_ms = round(frames * 1000 / sample_rate)
                if frames == 0:
                    raise ValueError("音频没有有效采样")
                window = round(self.window_seconds * sample_rate)
                stride = round((self.window_seconds - self.overlap_seconds) * sample_rate)
                if stride < 1:
                    raise ValueError("分段步长过小")
                total_chunks = 1 + (max(0, frames - window) + stride - 1) // stride
                report("loading_model", durationMs=duration_ms, totalChunks=total_chunks)
                if self.engine is None:
                    self.engine = self.engine_factory()
                report("transcribing")
                offset = 0
                index = 0
                while offset < frames:
                    end = min(offset + window, frames)
                    audio.setpos(offset)
                    samples = audio.readframes(end - offset)
                    chunk_path = output_dir / f"chunk-{index:05d}.wav"
                    with wave.open(str(chunk_path), "wb") as chunk:
                        chunk.setnchannels(1); chunk.setsampwidth(2); chunk.setframerate(sample_rate)
                        chunk.writeframes(samples)
                    text, raw = self.engine.recognize(chunk_path)
                    text = text.strip()
                    start_ms = round(offset * 1000 / sample_rate)
                    end_ms = round(end * 1000 / sample_rate)
                    raw_file.write(json.dumps({"index": index, "startMs": start_ms, "endMs": end_ms,
                                               "text": text, "raw": builtin(raw)}, ensure_ascii=False) + "\n")
                    raw_file.flush()
                    os.fsync(raw_file.fileno())
                    merged = remove_exact_overlap(previous, text) if self.dedupe and offset else text
                    # Keep original per-window text on the timeline; merge only the full-text view.
                    if text:
                        segments.append({"startMs": start_ms, "endMs": end_ms, "text": text,
                                         "timestampKind": "window"})
                    if merged:
                        full_text.append(merged)
                    previous = text
                    chunk_path.unlink()  # normalized audio + raw journal are sufficient for recovery.
                    report("transcribing", processedMs=end_ms, completedChunks=index + 1)
                    if end == frames:
                        break
                    offset += stride
                    index += 1
            if not full_text:
                raise ValueError("未识别到有效语音，原始结果已保留")
            report("finalizing")
            result = {"provider": self.engine.name, "durationMs": duration_ms,
                      "text": "\n".join(full_text), "segments": segments,
                      "raw": {"journal": str(journal), "normalizedAudio": str(normalized),
                              "timestampKind": "window", "windowSeconds": self.window_seconds,
                              "overlapSeconds": self.overlap_seconds, "dedupe": self.dedupe}}
            (output_dir / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
            report("completed")
            return result
        except Exception as error:
            (output_dir / "error.json").write_text(json.dumps({"error": str(error), "rawJournal": str(journal)}, ensure_ascii=False), encoding="utf-8")
            report("failed")
            raise
