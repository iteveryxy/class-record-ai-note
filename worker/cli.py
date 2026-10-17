"""Run the same ASR pipeline without the Java service."""
import argparse
import json
from pathlib import Path
import uuid

from asr import Transcriber

parser = argparse.ArgumentParser(description="Local classroom transcription")
parser.add_argument("audio", type=Path)
parser.add_argument("--output", type=Path, default=Path("../data/asr") / str(uuid.uuid4()))
args = parser.parse_args()
result = Transcriber().run(args.audio.resolve(), args.output.resolve())
print(json.dumps(result, ensure_ascii=False, indent=2))
