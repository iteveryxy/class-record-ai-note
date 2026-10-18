"""Copy legacy MVP data safely; never delete or modify its database/audio."""
from contextlib import contextmanager
from pathlib import Path
import os
import shutil
import sqlite3
import hashlib
import json
import uuid
from datetime import datetime, timezone


@contextmanager
def legacy_lock(source: Path):
    lock_file=source / "backend.lock"
    with lock_file.open("a+b") as handle:
        handle.seek(0)
        try:
            if os.name=="nt":
                import msvcrt
                msvcrt.locking(handle.fileno(),msvcrt.LK_NBLCK,1)
            else:
                import fcntl
                fcntl.lockf(handle,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except OSError as error:
            raise ValueError("旧版服务仍在运行，请先关闭旧版课记再导入") from error
        try:
            yield
        finally:
            if os.name=="nt":
                handle.seek(0);msvcrt.locking(handle.fileno(),msvcrt.LK_UNLCK,1)
            else:
                fcntl.lockf(handle,fcntl.LOCK_UN)


def import_legacy(source: Path,target: Path):
    source=source.resolve();target=target.resolve()
    if source==target or target.is_relative_to(source) or source.is_relative_to(target):
        raise ValueError("源目录和目标目录不能包含彼此")
    if target.exists():
        raise ValueError("导入暂存目录必须尚不存在，避免覆盖数据")
    if not (source / "classnotes.db").is_file():
        raise ValueError("请选择包含 classnotes.db 的旧版 data/local 目录")
    for path in source.rglob("*"):
        if path.is_symlink():
            raise ValueError("数据目录包含符号链接，请先核对真实文件后再导入")
    with legacy_lock(source),sqlite3.connect((source / "classnotes.db").as_uri()+"?mode=ro",uri=True) as original:
        version=original.execute("PRAGMA user_version").fetchone()[0]
        if version>3:
            raise ValueError("此数据由较新版本生成，请安装较新的课记")
        original.execute("SELECT count(*) FROM recordings").fetchone()
        secret_count=original.execute("SELECT count(*) FROM settings WHERE key IN ('llmApiKey','notionToken','workerToken') AND value<>''").fetchone()[0]
        if secret_count and not (source / "settings.key").is_file():
            raise ValueError("缺少凭证密钥 settings.key，不能安全导入")
        target.mkdir(parents=True)
        with sqlite3.connect(target / "classnotes.db") as copied:
            original.backup(copied)
        if (source / "settings.key").exists():
            shutil.copy2(source / "settings.key",target / "settings.key")
        for name in ["audio","asr","backups"]:
            if (source / name).is_dir():
                shutil.copytree(source / name,target / name)
    return {"imported":True,"schemaVersion":version}


def migrate_storage(source: Path, target: Path, report=lambda state: None):
    """Create a verified, locked copy. The desktop activates it only after a health check.

    target is an application-owned staging directory in an otherwise empty folder.
    Originals are never removed, even when copying or verification fails.
    """
    source = source.resolve(); target = target.resolve()
    if source == target or target.is_relative_to(source) or source.is_relative_to(target):
        raise ValueError("源目录和目标目录不能包含彼此")
    if target.exists():
        raise ValueError("迁移暂存目录必须尚不存在")
    database = source / "data" / "classnotes.db"
    if not database.is_file():
        raise ValueError("资料数据库不存在，已停止迁移")
    files = []
    for name in ("data", "models"):
        directory = source / name
        if directory.is_symlink() or directory.is_junction():
            raise ValueError("资料目录包含链接，请先核对真实文件")
        if not directory.exists():
            continue
        for item in directory.rglob("*"):
            if item.is_symlink() or item.is_junction():
                raise ValueError("资料目录包含链接，请先核对真实文件")
            if item.is_file() and item.relative_to(source).as_posix() not in (
                "data/backend.lock", "data/classnotes.db", "data/classnotes.db-wal", "data/classnotes.db-shm"
            ):
                files.append(item)
    total = sum(item.stat().st_size for item in files) + database.stat().st_size
    if shutil.disk_usage(target.parent).free < total + max(total // 20, 64 * 1024 * 1024):
        raise ValueError("目标磁盘空间不足，请选择空间更大的文件夹")
    copied_bytes = 0; manifest = []
    def progress(phase):
        report({"phase": phase, "loaded": copied_bytes, "total": total})
    progress("checking")
    with legacy_lock(source / "data"), sqlite3.connect(database.as_uri()+"?mode=ro", uri=True) as original:
        version = original.execute("PRAGMA user_version").fetchone()[0]
        if version > 3:
            raise ValueError("此数据由较新版本生成，请安装较新的课记")
        if original.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise ValueError("原数据库校验失败，已停止迁移")
        secret_count = original.execute("SELECT count(*) FROM settings WHERE key IN ('llmApiKey','notionToken','workerToken') AND value<>''").fetchone()[0]
        key = source / "data" / "settings.key"
        if secret_count and (not key.is_file() or key.stat().st_size != 32):
            raise ValueError("缺少有效的 settings.key，不能安全迁移")
        (target / "data").mkdir(parents=True)
        with sqlite3.connect(target / "data" / "classnotes.db") as copied:
            original.backup(copied)
            if copied.execute("PRAGMA quick_check").fetchone()[0] != "ok":
                raise ValueError("复制后的数据库校验失败，原目录保留")
            tables = [row[0] for row in original.execute("SELECT name FROM sqlite_master WHERE type='table'")]
            for table in tables:
                quoted = '"' + table.replace('"', '""') + '"'
                if original.execute("SELECT count(*) FROM " + quoted).fetchone() != copied.execute("SELECT count(*) FROM " + quoted).fetchone():
                    raise ValueError("复制后的数据数量不一致，原目录保留")
        copied_bytes += database.stat().st_size
        progress("copying")
        for item in files:
            relative = item.relative_to(source)
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256()
            with item.open("rb") as reader, destination.open("xb") as writer:
                while block := reader.read(4 * 1024 * 1024):
                    writer.write(block); digest.update(block); copied_bytes += len(block)
                    progress("copying")
                writer.flush(); os.fsync(writer.fileno())
            shutil.copystat(item, destination)
            manifest.append((destination, digest.digest()))
        progress("verifying")
        for destination, expected in manifest:
            with destination.open("rb") as reader:
                if hashlib.file_digest(reader, "sha256").digest() != expected:
                    raise ValueError("文件校验失败，原目录保留")
        marker = {"format": 1, "storageId": str(uuid.uuid4()), "schemaVersion": version,
                  "copiedFiles": len(manifest) + 1, "copiedBytes": copied_bytes,
                  "createdAt": datetime.now(timezone.utc).isoformat()}
        (target / "classnotes-storage.json").write_text(json.dumps(marker, indent=2), encoding="utf-8")
    return marker
