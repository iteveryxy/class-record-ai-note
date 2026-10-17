from pathlib import Path
import sqlite3
import pytest
from storage import import_legacy

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
