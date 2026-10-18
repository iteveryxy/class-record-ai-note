from pathlib import Path
import sqlite3
import pytest
from storage import import_legacy, migrate_storage, legacy_lock

def source(root:Path):
    root.mkdir()
    with sqlite3.connect(root / 'classnotes.db') as db:
        db.executescript("CREATE TABLE recordings(id TEXT,title TEXT);INSERT INTO recordings VALUES('r','旧版笔记');CREATE TABLE settings(key TEXT,value TEXT);")
    (root / 'settings.key').write_bytes(bytes(range(32)))
    (root / 'audio').mkdir();(root / 'audio/a.wav').write_bytes(b'audio')
    return root

def test_import_copies_database_key_and_audio_without_removing_source(tmp_path):
    old=source(tmp_path/'old');new=tmp_path/'new'
    assert import_legacy(old,new)['imported']
    with sqlite3.connect(new/'classnotes.db') as db:
        assert db.execute('SELECT title FROM recordings').fetchone()[0]=='旧版笔记'
    assert (new/'settings.key').read_bytes()==(old/'settings.key').read_bytes()
    assert (new/'audio/a.wav').read_bytes()==b'audio'
    assert (old/'classnotes.db').exists()

def test_import_rejects_existing_destination_and_nested_paths(tmp_path):
    old=source(tmp_path/'old');new=tmp_path/'new';new.mkdir()
    with pytest.raises(ValueError,match='覆盖'):import_legacy(old,new)
    with pytest.raises(ValueError,match='包含'):import_legacy(old,old/'new')

def test_import_refuses_future_schema_and_missing_key(tmp_path):
    old=source(tmp_path/'old')
    with sqlite3.connect(old/'classnotes.db') as db:db.execute('PRAGMA user_version=99')
    with pytest.raises(ValueError,match='较新版本'):import_legacy(old,tmp_path/'future')
    with sqlite3.connect(old/'classnotes.db') as db:
        db.execute('PRAGMA user_version=0');db.execute("INSERT INTO settings VALUES('notionToken','encrypted')")
    (old/'settings.key').unlink()
    with pytest.raises(ValueError,match='settings.key'):import_legacy(old,tmp_path/'withoutkey')


def test_import_preserves_v3_chat_history(tmp_path):
    old=source(tmp_path/'old')
    with sqlite3.connect(old/'classnotes.db') as db:
        db.execute('PRAGMA user_version=3')
        db.executescript("CREATE TABLE note_ai_turns(id TEXT,user_message TEXT);INSERT INTO note_ai_turns VALUES('t','保留所有公式');")
    new=tmp_path/'new'
    assert import_legacy(old,new)['schemaVersion']==3
    with sqlite3.connect(new/'classnotes.db') as db:
        assert db.execute('SELECT user_message FROM note_ai_turns').fetchone()[0]=='保留所有公式'


def workspace(root):
    root.mkdir();data=source(root/'data')
    with sqlite3.connect(data/'classnotes.db') as db:
        db.execute('PRAGMA user_version=3')
        db.executescript("CREATE TABLE note_ai_turns(id TEXT,message TEXT);INSERT INTO note_ai_turns VALUES('a','保留公式');")
    (root/'models').mkdir();(root/'models/model.pt').write_bytes(b'model-bytes')
    (data/'asr').mkdir();(data/'asr/raw.json').write_text('{"raw":"课堂公式"}',encoding='utf-8')
    return root


def test_migration_verifies_database_key_audio_raw_results_and_model_cache(tmp_path):
    old=workspace(tmp_path/'old');new=tmp_path/'new';states=[]
    marker=migrate_storage(old,new,states.append)
    assert marker['schemaVersion']==3
    assert marker['copiedFiles']>=5
    for name in ('data/settings.key','data/audio/a.wav','data/asr/raw.json','models/model.pt'):
        assert (new/name).read_bytes()==(old/name).read_bytes()
    with sqlite3.connect(new/'data/classnotes.db') as db:
        assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        assert db.execute('SELECT message FROM note_ai_turns').fetchone()[0]=='保留公式'
    assert {'checking','copying','verifying'} <= {s['phase'] for s in states}
    assert (old/'data/classnotes.db').exists()


def test_migration_captures_committed_wal_data(tmp_path):
    old=workspace(tmp_path/'old')
    with sqlite3.connect(old/'data/classnotes.db') as db:
        db.execute('PRAGMA journal_mode=WAL');db.execute('PRAGMA wal_autocheckpoint=0')
        db.execute("INSERT INTO note_ai_turns VALUES('wal','最新修改')");db.commit()
        migrate_storage(old,tmp_path/'new')
    with sqlite3.connect(tmp_path/'new/data/classnotes.db') as copied:
        assert copied.execute("SELECT message FROM note_ai_turns WHERE id='wal'").fetchone()[0]=='最新修改'


def test_migration_does_not_copy_a_running_backend(tmp_path):
    import os
    if os.name!='nt':pytest.skip('POSIX record locks are per process; exercised by Windows desktop')
    old=workspace(tmp_path/'old')
    with legacy_lock(old/'data'):
        with pytest.raises(ValueError,match='仍在运行'):migrate_storage(old,tmp_path/'new')
    assert not (tmp_path/'new').exists()


def test_migration_detects_corrupted_copy_and_preserves_original(tmp_path,monkeypatch):
    import storage
    old=workspace(tmp_path/'old');actual=storage.shutil.copystat
    def corrupt(src,dst):
        actual(src,dst)
        if dst.name=='model.pt':dst.write_bytes(b'corrupted')
    monkeypatch.setattr(storage.shutil,'copystat',corrupt)
    with pytest.raises(ValueError,match='文件校验失败'):migrate_storage(old,tmp_path/'new')
    assert (old/'models/model.pt').read_bytes()==b'model-bytes'
    assert not (tmp_path/'new/classnotes-storage.json').exists()


def test_migration_rejects_insufficient_space_without_creating_copy(tmp_path,monkeypatch):
    from collections import namedtuple
    import storage
    old=workspace(tmp_path/'old');usage=namedtuple('usage','total used free')
    monkeypatch.setattr(storage.shutil,'disk_usage',lambda _:usage(1,1,0))
    with pytest.raises(ValueError,match='空间不足'):migrate_storage(old,tmp_path/'new')
    assert not (tmp_path/'new').exists()


def test_migration_rejects_junctions_and_preserves_external_files(tmp_path):
    import os
    old=workspace(tmp_path/'old');external=tmp_path/'external';external.mkdir();(external/'private').write_bytes(b'keep')
    if os.name=='nt':
        import subprocess
        result=subprocess.run(['cmd','/c','mklink','/J',str(old/'models/linked'),str(external)],capture_output=True)
        assert result.returncode==0
    else:(old/'models/linked').symlink_to(external,target_is_directory=True)
    with pytest.raises(ValueError,match='包含链接'):migrate_storage(old,tmp_path/'new')
    assert (external/'private').read_bytes()==b'keep'
