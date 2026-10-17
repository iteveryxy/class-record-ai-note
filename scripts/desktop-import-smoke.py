"""Verify the frozen legacy importer and packaged desktop against copied fixtures."""
import argparse
import hashlib
import json
import shutil
import sqlite3
import subprocess
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1]


def snapshot(db, tables):
    result = {}
    for table, columns in tables.items():
        rows = db.execute(f'SELECT {",".join(columns)} FROM {table} ORDER BY rowid').fetchall()
        result[table] = rows
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True, help="Closed project test data directory")
    args = parser.parse_args()
    source = args.source.resolve()
    if not source.is_relative_to(root / ".tools"):
        raise ValueError("Only project-owned test fixtures are accepted")
    run = root / ".tools" / ("desktop-import-" + uuid.uuid4().hex)
    old = run / "legacy"
    old.mkdir(parents=True)
    with sqlite3.connect((source / "classnotes.db").as_uri() + "?mode=ro", uri=True) as original:
        with sqlite3.connect(old / "classnotes.db") as copied:
            original.backup(copied)
    shutil.copy2(source / "settings.key", old / "settings.key")
    for folder in ["audio", "asr"]:
        if (source / folder).exists():
            shutil.copytree(source / folder, old / folder)
    marker = "旧版导入保留检查-" + uuid.uuid4().hex[:8]
    with sqlite3.connect(old / "classnotes.db") as db:
        db.execute("UPDATE recordings SET title=? WHERE rowid=(SELECT min(rowid) FROM recordings)", (marker,))
        db.execute("PRAGMA user_version=0")
        db.execute("DROP TABLE IF EXISTS app_metadata")
        db.execute("DROP TABLE IF EXISTS note_ai_turns")
        db.execute("ALTER TABLE notes DROP COLUMN notion_pending_target")
        tables = {name: [row[1] for row in db.execute(f"PRAGMA table_info({name})")]
                  for name in ["recordings", "transcripts", "notes", "note_versions", "settings"]}
        before = snapshot(db, tables)
    user = run / "desktop-data"
    imported = user / "data"
    worker = root / "artifacts/desktop/win-unpacked/resources/bundled/worker/ClassNotesWorker.exe"
    executable = root / "artifacts/desktop/win-unpacked/ClassNotes.exe"
    options = {"creationflags": subprocess.CREATE_NO_WINDOW}
    transfer = subprocess.run([str(worker), "--import-source", str(old), "--import-target", str(imported)],
                              capture_output=True, text=True, timeout=120, check=True, **options)
    assert json.loads(transfer.stdout)["imported"]
    output = root / "artifacts/runtime/desktop-import-smoke.json"
    subprocess.run([str(executable), "--demo", "--data-dir=" + str(user), "--diagnostics=" + str(output)],
                   timeout=120, check=True, **options)
    rendering = json.loads(output.read_text(encoding="utf-8"))
    assert rendering["ok"] and marker in rendering["renderer"]["body"]
    with sqlite3.connect(imported / "classnotes.db") as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 3
        assert snapshot(db, tables) == before
    with sqlite3.connect(old / "classnotes.db") as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 0
    assert (old / "settings.key").read_bytes() == (imported / "settings.key").read_bytes()
    for original in (old / "audio").iterdir():
        assert hashlib.sha256(original.read_bytes()).digest() == hashlib.sha256((imported / "audio" / original.name).read_bytes()).digest()
    assert list((imported / "backups").glob("*.db")) and list((imported / "backups").glob("*.key"))
    proof = {"imported": True, "sourceRetained": True, "recordsNotesHistoryAndKeysRetained": True,
             "audioRetained": True, "migratedToSchema": 2, "backupCreated": True, "rendered": True,
             "testDirectory": str(run)}
    (root / "artifacts/runtime/desktop-import-result.json").write_text(json.dumps(proof, indent=2), encoding="utf-8")
    print(json.dumps(proof))


if __name__ == "__main__":
    main()
