"""Exercise migration and audio deletion through the actual sandboxed desktop UI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import uuid

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--fixture',type=Path,required=True);parser.add_argument('--executable',type=Path)
args=parser.parse_args();fixture=args.fixture.resolve()
assert fixture.is_relative_to(ROOT/'.tools') and (fixture/'classnotes.db').is_file()
version=json.loads((ROOT/'desktop/package.json').read_text(encoding='utf-8'))['version']
run=ROOT/'.tools'/('storage-smoke-'+uuid.uuid4().hex);bootstrap=run/'user-data';data=bootstrap/'data'
shutil.copytree(fixture,data)
# Some old UI fixtures deliberately shared a filename. Give each test recording
# an independent copy, as the real upload endpoint does, before the baseline.
with sqlite3.connect(data/'classnotes.db') as db:
    seen=set()
    for identifier,name in db.execute('SELECT id,stored_name FROM recordings').fetchall():
        if name in seen:
            replacement=identifier+Path(name).suffix
            shutil.copy2(data/'audio'/name,data/'audio'/replacement)
            db.execute('UPDATE recordings SET stored_name=? WHERE id=?',(replacement,identifier))
        seen.add(name)
model=bootstrap/'models/model.bin';model.parent.mkdir();model.write_bytes(os.urandom(2*1024*1024))
target=run/'迁移资料 storage';target.mkdir()
feed='https://example.invalid/classnotes/updates/'
(bootstrap/'desktop.json').write_text(json.dumps({'language':'zh-CN','updateUrl':feed}),encoding='utf-8')
key=(data/'settings.key').read_bytes()
def snapshot(directory,tables):
    with sqlite3.connect(directory/'classnotes.db') as db:
        return {name:hashlib.sha256(json.dumps(db.execute(f'SELECT * FROM {name} ORDER BY rowid').fetchall(),ensure_ascii=False).encode()).hexdigest() for name in tables}
tables=['recordings','notes','transcripts','note_versions','note_ai_turns','artifacts','jobs']
before=snapshot(data,tables)
executable=args.executable.resolve() if args.executable else ROOT/'desktop/node_modules/electron/dist/electron.exe'
label='packaged' if args.executable else 'development'
for phase in ['first','restart']:
    output=ROOT/f'artifacts/runtime/storage-{label}-{phase}-{version}.json'
    command=[str(executable)]+([] if args.executable else [str(ROOT/'desktop')])+['--demo',f'--data-dir={bootstrap}',f'--diagnostics={output}',f'--diagnostics-storage={phase}']
    with (run/f'{phase}.log').open('w',encoding='utf-8') as log:
        result=subprocess.run(command,cwd=ROOT,env={**os.environ,'QUEUE_ENABLED':'false'},stdout=log,stderr=subprocess.STDOUT,timeout=300,creationflags=subprocess.CREATE_NO_WINDOW)
    report=json.loads(output.read_text(encoding='utf-8')) if output.exists() else {}
    if result.returncode or not report.get('ok'):raise RuntimeError(f'{phase} failed: {log.name}; {report}')
    assert (target/'data/settings.key').read_bytes()==key
    assert (target/'models/model.bin').read_bytes()==model.read_bytes()
    assert snapshot(data,tables)==before,'Original data was changed'
    if phase=='first':
        assert snapshot(target/'data',tables)==before,'Migration or cleanup changed saved notes/history'
        old_database=snapshot(data,['settings','logs'])
    else:assert snapshot(data,['settings','logs'])==old_database,'Restart wrote to old storage'
    config=json.loads((bootstrap/'desktop.json').read_text(encoding='utf-8'))
    assert config['storageRoot']==str(target) and config['language']=='en' and config['updateUrl']==feed
summary={'ok':True,'version':version,'mode':label,'testRoot':str(run),'target':str(target),'originalDataRetained':True,'keyAndModelCachePreserved':True,
         'allNoteAndTranscriptRowsPreserved':True,'restartUsesNewStorage':True,'backendSha256':hashlib.sha256((ROOT/f'backend/target/class-notes-{version}.jar').read_bytes()).hexdigest()}
(ROOT/f'artifacts/runtime/storage-{label}-data-{version}.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=True))
