"""Run the actual desktop bridge and bilingual UI with an isolated data copy."""
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
parser=argparse.ArgumentParser()
parser.add_argument('--fixture',type=Path,required=True)
args=parser.parse_args()
fixture=args.fixture.resolve()
assert fixture.is_relative_to(ROOT/'.tools') and (fixture/'classnotes.db').is_file()
version=json.loads((ROOT/'desktop/package.json').read_text(encoding='utf-8'))['version']
run=ROOT/'.tools'/('language-smoke-'+uuid.uuid4().hex)
data=run/'data';run.mkdir(parents=True);shutil.copytree(fixture,data)
def snapshot():
    with sqlite3.connect(data/'classnotes.db') as db:
        tables=[r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
        rows={name:db.execute(f'SELECT * FROM {name} ORDER BY rowid').fetchall() for name in tables}
    return hashlib.sha256(json.dumps(rows,ensure_ascii=False,sort_keys=True).encode('utf-8')).hexdigest()
baseline=snapshot()
key_before=(data/'settings.key').read_bytes()
feed='https://example.invalid/classnotes/updates/'
(run/'desktop.json').write_text(json.dumps({'updateUrl':feed,'language':'system'}),encoding='utf-8')
env={**os.environ,'QUEUE_ENABLED':'false'}
for phase in ['first','restart']:
    output=ROOT/f'artifacts/runtime/language-{phase}-{version}.json'
    with (run/f'{phase}.log').open('w',encoding='utf-8') as log:
        result=subprocess.run([str(ROOT/'desktop/node_modules/electron/dist/electron.exe'),str(ROOT/'desktop'),
                               '--demo',f'--data-dir={run}',f'--diagnostics={output}',f'--diagnostics-language={phase}'],
                              cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=240,creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:raise RuntimeError(f'{phase} failed: {log.name}; {output.read_text(encoding="utf-8") if output.exists() else "no report"}')
    report=json.loads(output.read_text(encoding='utf-8'));assert report['ok'] and report['version']==version
    assert snapshot()==baseline,'Database rows changed'
    assert (data/'settings.key').read_bytes()==key_before,'Credential key changed'
    config=json.loads((run/'desktop.json').read_text(encoding='utf-8'))
    assert config['language']=='en' and config['updateUrl']==feed
report={'ok':True,'version':version,'testRoot':str(run),'databaseRowsUnchanged':True,'keyUnchanged':True,'updateUrlPreserved':True,
        'languagePersisted':'en','backendSha256':hashlib.sha256((ROOT/f'backend/target/class-notes-{version}.jar').read_bytes()).hexdigest()}
(ROOT/f'artifacts/runtime/language-data-{version}.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
