"""Exercise the push launcher against temporary local bare repositories."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

ROOT=Path(__file__).resolve().parents[1]
run=ROOT/'.tools'/('push tests '+uuid.uuid4().hex)
run.mkdir(parents=True)
env={**os.environ,'GIT_CONFIG_NOSYSTEM':'1','GIT_CONFIG_GLOBAL':os.devnull,'GIT_TERMINAL_PROMPT':'0'}
shell=shutil.which('pwsh') or shutil.which('powershell')
assert shell
def git(directory,*args):
    process=subprocess.run(['git',*args],cwd=directory,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
    if process.returncode:raise RuntimeError(process.stderr)
    return process.stdout.strip()
def commit(local,message):
    git(local,'add','.')
    git(local,'-c','user.name=Push Script Test','-c','user.email=push-test@example.invalid','commit','-m',message)
def fixture(name):
    case=run/name;case.mkdir()
    remote=case/'remote.git';local=case/'local project'
    git(case,'init','--bare','--initial-branch=main',str(remote))
    git(case,'init','--initial-branch=main',str(local))
    (local/'scripts').mkdir()
    shutil.copy2(ROOT/'scripts/push.ps1',local/'scripts/push.ps1')
    shutil.copy2(ROOT/'push.cmd',local/'push.cmd')
    (local/'tracked.txt').write_text('baseline',encoding='utf-8')
    commit(local,'baseline')
    git(local,'remote','add','origin',str(remote));git(local,'push','-u','origin','main')
    return case,local,remote
def invoke(local,*args,expected=0):
    process=subprocess.run([shell,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(local/'scripts/push.ps1'),*args],
                           cwd=run,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40)
    assert process.returncode==expected,(process.returncode,process.stdout,process.stderr)
    return process
results={}
case,local,remote=fixture('normal')
baseline=git(remote,'rev-parse','refs/heads/main')
assert 'Nothing to push' in invoke(local).stdout
results['alreadyUpToDate']=True
(local/'tracked.txt').write_text('first change',encoding='utf-8');commit(local,'first change')
head=git(local,'rev-parse','HEAD')
assert 'Dry run passed' in invoke(local,'-DryRun').stdout
assert git(remote,'rev-parse','refs/heads/main')==baseline
results['dryRunPreservesRemote']=True
process=subprocess.run(['cmd.exe','/d','/c',str(local/'push.cmd')],input='\n',cwd=run,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40)
assert process.returncode==0,(process.stdout,process.stderr)
assert git(remote,'rev-parse','refs/heads/main')==head
results['doubleClickLauncherPushesTrackedBranch']=True
(local/'tracked.txt').write_text('uncommitted text',encoding='utf-8')
(local/'new note.txt').write_text('keep this too',encoding='utf-8')
assert 'uncommitted changes' in invoke(local,expected=1).stdout
assert (local/'tracked.txt').read_text(encoding='utf-8')=='uncommitted text'
assert (local/'new note.txt').read_text(encoding='utf-8')=='keep this too'
assert git(remote,'rev-parse','refs/heads/main')==head
results['dirtyWorktreePreservedAndBlocked']=True
case,local,remote=fixture('remote changes')
other=case/'other';git(case,'clone',str(remote),str(other))
(other/'remote.txt').write_text('remote change',encoding='utf-8');commit(other,'remote change');git(other,'push')
remote_head=git(remote,'rev-parse','refs/heads/main')
assert 'remote has 1 new commit' in invoke(local,expected=1).stdout
assert git(remote,'rev-parse','refs/heads/main')==remote_head
results['remoteAheadBlocked']=True
(local/'local.txt').write_text('local change',encoding='utf-8');commit(local,'local change')
local_head=git(local,'rev-parse','HEAD')
invoke(local,expected=1)
assert git(remote,'rev-parse','refs/heads/main')==remote_head and git(local,'rev-parse','HEAD')==local_head
results['divergedHistoryPreserved']=True
case,local,remote=fixture('no upstream')
git(local,'branch','--unset-upstream')
assert 'no upstream' in invoke(local,expected=1).stdout
results['missingUpstreamBlocked']=True
case,local,remote=fixture('fetch failure')
(local/'tracked.txt').write_text('keep local commit',encoding='utf-8');commit(local,'local change')
head=git(local,'rev-parse','HEAD');git(local,'remote','set-url','origin',str(case/'missing.git'))
invoke(local,expected=1);assert git(local,'rev-parse','HEAD')==head
results['fetchFailurePreservesCommits']=True
case,local,remote=fixture('detached')
git(local,'checkout','--detach','HEAD');invoke(local,expected=1)
results['detachedHeadBlocked']=True
result={'ok':True,'checks':len(results),'results':results,'testRoot':str(run),
        'scriptSha256':hashlib.sha256((ROOT/'scripts/push.ps1').read_bytes()).hexdigest()}
(ROOT/'artifacts/runtime/push-script-smoke.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result))
