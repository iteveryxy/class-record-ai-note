"""Run the real Java task runner with deterministic ASR/LLM fixtures and actual files."""
import io
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request
import uuid
import wave

ROOT=Path(__file__).resolve().parents[1]
version=json.loads((ROOT/'desktop/package.json').read_text(encoding='utf-8'))['version']
run=ROOT/'.tools'/('retention-smoke-'+uuid.uuid4().hex);run.mkdir()
with socket.socket() as listener:listener.bind(('127.0.0.1',0));port=listener.getsockname()[1]
base=f'http://127.0.0.1:{port}/api'
token=uuid.uuid4().hex
def request(route,method='GET',body=None,content_type='application/json'):
    if body is not None and not isinstance(body,bytes):body=json.dumps(body).encode()
    req=urllib.request.Request(base+route,data=body,method=method,headers={'Authorization':'Bearer '+token,'Content-Type':content_type})
    with urllib.request.urlopen(req,timeout=10) as response:return json.load(response)
def wait_for(action):
    end=time.monotonic()+60
    while time.monotonic()<end:
        try:
            value=action()
            if value:return value
        except (OSError,ValueError):pass
        time.sleep(.2)
    raise TimeoutError('Backend retention check timed out')
def upload(title):
    audio=io.BytesIO()
    with wave.open(audio,'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(bytes(32000))
    boundary='ClassNotes'+uuid.uuid4().hex;parts=[]
    for key,value in {'title':title,'course':'Storage test','recordedOn':'2026-10-17','category':'课堂笔记'}.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
    parts.extend([f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="lecture.wav"\r\nContent-Type: audio/wav\r\n\r\n'.encode(),audio.getvalue(),f'\r\n--{boundary}--\r\n'.encode()])
    return request('/recordings','POST',b''.join(parts),'multipart/form-data; boundary='+boundary)
def finished(identifier):
    detail=request('/recordings/'+identifier)
    return detail if detail['recording']['summaryStatus']=='SUCCEEDED' else None
with (run/'backend.log').open('w',encoding='utf-8') as log:
    process=subprocess.Popen(['java','--enable-native-access=ALL-UNNAMED','-jar',str(ROOT/f'backend/target/class-notes-{version}.jar')],cwd=ROOT,
        env={**os.environ,'DATA_DIR':str(run/'data'),'APP_DEMO':'true','APP_API_TOKEN':token,'QUEUE_ENABLED':'true','PORT':str(port)},stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        wait_for(lambda:request('/health'))
        existing=request('/recordings');assert existing and all(r['audioAvailable'] for r in existing)
        request('/settings','PUT',{'retainRecordings':'false'})
        assert all(request('/recordings/'+r['id'])['recording']['audioAvailable'] for r in existing)
        uploaded=upload('Do not keep audio');removed=wait_for(lambda:finished(uploaded['id']))
        assert not removed['recording']['audioAvailable'] and removed['transcript']['text'] and removed['note']['markdown']
        assert any(a['kind']=='ASR_RESPONSE' for a in request('/recordings/'+uploaded['id']+'/artifacts'))
        request('/settings','PUT',{'retainRecordings':'true'})
        uploaded2=upload('Keep audio');kept=wait_for(lambda:finished(uploaded2['id']))
        assert kept['recording']['audioAvailable'] and kept['note']['markdown']
        assert (run/'data/audio'/f"{uploaded2['id']}.wav").is_file()
        assert all(request('/recordings/'+r['id'])['recording']['audioAvailable'] for r in existing)
        report={'ok':True,'version':version,'realJavaTaskRunner':True,'speechEngine':'demo-fixture','successfulTranscriptionDeletesAudioWhenDisabled':True,
                'successfulTranscriptionKeepsAudioWhenEnabled':True,'existingAudioUntouched':True,'notesAndRawTextOutputPreserved':True,'testRoot':str(run),
                'backendSha256':hashlib.sha256((ROOT/f'backend/target/class-notes-{version}.jar').read_bytes()).hexdigest()}
        (ROOT/f'artifacts/runtime/retention-{version}.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        print(json.dumps(report))
    finally:
        process.terminate()
        try:process.wait(timeout=15)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
