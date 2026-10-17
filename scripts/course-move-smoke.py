"""Exercise course movement on an isolated copy of a closed long-note fixture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import time
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--fixture',type=Path,required=True)
args=parser.parse_args()
fixture=args.fixture.resolve()
assert fixture.is_relative_to(ROOT/'.tools') and (fixture/'classnotes.db').is_file()
version=json.loads((ROOT/'desktop/package.json').read_text(encoding='utf-8'))['version']
run=ROOT/'.tools'/('course-move-'+uuid.uuid4().hex)
data=run/'data';run.mkdir(parents=True);shutil.copytree(fixture,data)
with sqlite3.connect(data/'classnotes.db') as db:
    recording_id=db.execute('SELECT id FROM recordings LIMIT 1').fetchone()[0]
    db.execute("UPDATE recordings SET course='线性代数',title='10月14日课堂录音',sync_status='SUCCEEDED' WHERE id=?",(recording_id,))
    db.execute("UPDATE notes SET title='10月14日课堂录音',confirmed_revision=revision,synced_revision=revision,notion_page_id='22222222-2222-2222-2222-222222222222',notion_target='11111111-1111-1111-1111-111111111111',notion_url='https://www.notion.so/22222222222222222222222222222222' WHERE recording_id=?",(recording_id,))
    db.execute("INSERT INTO recordings(id,title,course,recorded_on,category,filename,stored_name,size,created_at,updated_at) SELECT ?, '高等数学示例录音','高等数学',recorded_on,category,filename,stored_name,size,created_at,updated_at FROM recordings WHERE id=?",(str(uuid.uuid4()),recording_id))
    tables=['notes','transcripts','note_versions','note_ai_turns','jobs','artifacts','settings']
    baseline={t:db.execute(f'SELECT * FROM {t} ORDER BY rowid').fetchall() for t in tables}
    original_logs=db.execute('SELECT * FROM logs ORDER BY id').fetchall()
audio_hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (data/'audio').iterdir() if p.is_file()}
with socket.socket() as probe:
    probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
base=f'http://127.0.0.1:{port}'
env={**os.environ,'DATA_DIR':str(data),'PORT':str(port),'APP_DEMO':'false','APP_DESKTOP':'false',
     'APP_API_TOKEN':'','QUEUE_ENABLED':'false','ASR_WORKER_TOKEN':'','LLM_API_KEY':'','NOTION_TOKEN':''}
config={'root':str(ROOT),'base':base,'userData':str(run/'electron'),'recordingId':recording_id,
        'version':version,'output':str(ROOT/f'artifacts/runtime/course-move-ui-{version}.json')}
(run/'ui-config.json').write_text(json.dumps(config,ensure_ascii=False),encoding='utf-8')
with (run/'backend.log').open('w',encoding='utf-8') as log:
    process=subprocess.Popen([str(ROOT/'desktop/resources/java/bin/java.exe'),'--enable-native-access=ALL-UNNAMED',
                              '-jar',str(ROOT/f'backend/target/class-notes-{version}.jar')],cwd=ROOT,env=env,
                             stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        deadline=time.monotonic()+90
        while True:
            try:
                with urllib.request.urlopen(base+'/api/health',timeout=3) as response:health=json.load(response)
                assert health['version']==version
                break
            except OSError:
                if process.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Backend not ready')
                time.sleep(.2)
        subprocess.run([str(ROOT/'desktop/node_modules/electron/dist/electron.exe'),str(ROOT/'scripts/course-move-ui-smoke.cjs'),
                        str(run/'ui-config.json')],cwd=ROOT,check=True,timeout=240,creationflags=subprocess.CREATE_NO_WINDOW)
    finally:
        process.terminate()
        try:process.wait(timeout=20)
        except subprocess.TimeoutExpired:process.kill();process.wait()
with sqlite3.connect(data/'classnotes.db') as db:
    for table,rows in baseline.items():assert db.execute(f'SELECT * FROM {table} ORDER BY rowid').fetchall()==rows,table
    all_logs=db.execute('SELECT * FROM logs ORDER BY id').fetchall()
    assert all_logs[:len(original_logs)]==original_logs and len(all_logs)==len(original_logs)+3
    assert db.execute('SELECT course FROM recordings WHERE id=?',(recording_id,)).fetchone()[0]=='高等数学'
assert audio_hashes=={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (data/'audio').iterdir() if p.is_file()}
assert (fixture/'settings.key').read_bytes()==(data/'settings.key').read_bytes()
result={'ok':True,'version':version,'testData':str(data),'allRelatedRowsAndAudioBytesUnchanged':True,'keyUnchanged':True,
        'backendSha256':hashlib.sha256((ROOT/f'backend/target/class-notes-{version}.jar').read_bytes()).hexdigest()}
(ROOT/f'artifacts/runtime/course-move-data-{version}.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result))
