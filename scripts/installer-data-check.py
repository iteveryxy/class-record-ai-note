"""Logical data comparison for the project-owned installer smoke workspace."""
import argparse
import hashlib
import json
import sqlite3
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("action", choices=["capture", "verify"])
parser.add_argument("data", type=Path)
parser.add_argument("snapshot", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1] / ".tools"
data = args.data.resolve()
if not data.is_relative_to(root) or not args.snapshot.resolve().is_relative_to(root):
    raise ValueError("Only project-owned smoke directories are accepted")
expected = json.loads(args.snapshot.read_text(encoding="utf-8")) if args.action == "verify" else None
with sqlite3.connect((data / "classnotes.db").as_uri() + "?mode=ro", uri=True) as db:
    tables = ["recordings", "transcripts", "notes", "note_versions", "settings"]
    if expected is not None:
        if "note_ai_turns" in expected:
            tables.append("note_ai_turns")
    elif db.execute("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='note_ai_turns'").fetchone()[0]:
        tables.append("note_ai_turns")
    saved = {table: db.execute(f"SELECT * FROM {table} ORDER BY rowid").fetchall() for table in tables}
saved["keySha256"] = hashlib.sha256((data / "settings.key").read_bytes()).hexdigest()
encoded = json.dumps(saved, ensure_ascii=False, sort_keys=True)
if args.action == "capture":
    args.snapshot.write_text(encoded, encoding="utf-8")
else:
    if json.loads(encoded) != json.loads(args.snapshot.read_text(encoding="utf-8")):
        raise ValueError("Recordings, transcripts, notes, histories, settings or key changed")
    print("录音、转录、笔记、历史版本、设置与密钥保持一致。")
