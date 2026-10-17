"""Copy legacy MVP data safely; never delete or modify its database/audio."""
from contextlib import contextmanager
from pathlib import Path
import os
import shutil
import sqlite3


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
