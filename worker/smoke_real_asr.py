"""Manual smoke check using a public sample bundled with the official model.

This is deliberately separate from pytest: downloads a model and runs real CPU ASR.
"""
import json
import argparse
from pathlib import Path
import uuid
import wave

from asr import SenseVoiceEngine, Transcriber, normalize_audio

parser=argparse.ArgumentParser()
parser.add_argument("--repeat",type=int,default=1,help="Repeat the public sample to exercise bounded windows")
args=parser.parse_args()
if not 1 <= args.repeat <= 100:
    parser.error("repeat must be between 1 and 100")
engine = SenseVoiceEngine()
model_dir = Path(engine.model.model_path)
examples = model_dir / "example"
candidates = [examples / "zh.mp3", examples / "en.mp3"]
source = next((path for path in candidates if path.is_file()), None)
if source is None:
    raise FileNotFoundError(f"Official model sample not found under {examples}; use cli.py with your own audio")
root = Path("../artifacts/runtime").resolve()
root.mkdir(parents=True, exist_ok=True)
fixture = root / "sensevoice-sample.wav"
normalize_audio(source, fixture)
if args.repeat > 1:
    with wave.open(str(fixture),"rb") as original:
        params=original.getparams()
        samples=original.readframes(original.getnframes())
    fixture=root / "sensevoice-long-sample.wav"
    with wave.open(str(fixture),"wb") as repeated:
        repeated.setparams(params)
        for _ in range(args.repeat):
            repeated.writeframes(samples)
result = Transcriber(lambda: engine).run(fixture, root / ("sensevoice-" + str(uuid.uuid4())))
(root / ("real-asr-long-result.json" if args.repeat>1 else "real-asr-result.json")).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"provider": result["provider"], "durationMs": result["durationMs"],
                  "segments": len(result["segments"]), "text": result["text"], "sample": str(fixture)}, ensure_ascii=False))
