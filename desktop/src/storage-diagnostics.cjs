'use strict'
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict')

async function verifyStorage({window,dialog,output,phase,userData}){
  const evaluate=code=>window.webContents.executeJavaScript(code)
  const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms))
  async function waitFor(code){const end=Date.now()+90000;while(Date.now()<end){if(await evaluate(code))return;await pause(100)}throw new Error('Storage UI timeout: '+code)}
  const request=(route,method='GET',body)=>evaluate(`fetch('/api'+${JSON.stringify(route)},{method:${JSON.stringify(method)},headers:{'Content-Type':'application/json'},body:${body===undefined?'undefined':JSON.stringify(JSON.stringify(body))}}).then(async r=>({status:r.status,body:await r.json()}))`)
  const snapshot=detail=>Object.fromEntries(['note','transcript','transcripts','versions','jobs'].map(key=>[key,crypto.createHash('sha256').update(JSON.stringify(detail[key])).digest('hex')]))
  async function capture(name){await evaluate('new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))');await pause(700);await window.webContents.capturePage();await pause(200);fs.writeFileSync(path.join(path.dirname(output),'../screenshots',name),(await window.webContents.capturePage()).toPNG())}
  async function settings(){await evaluate("document.querySelector('.settings-nav').click()");await waitFor("!!document.querySelector('.storage-settings')&&document.querySelector('#llm-system-prompt')?.value.length>100&&!document.querySelector('.page-heading .btn.primary').disabled")}
  async function setRetention(value){await evaluate(`(()=>{const f=document.querySelector('.storage-retention input');f.checked=${value};f.dispatchEvent(new Event('change',{bubbles:true}))})()`);await pause(100);await evaluate("document.querySelector('.page-heading .btn.primary').click()");await waitFor("!document.querySelector('.page-heading .btn.primary').disabled&&!document.querySelector('.migrate-storage').disabled")}
  await waitFor("document.querySelectorAll('tbody tr').length>=2")
  const initial=await evaluate('window.classnotesDesktop.info()')
  const recordings=(await request('/recordings')).body,recording=recordings.find(r=>r.noteId)
  assert.ok(recording,'Fixture must include a note')
  const target=path.join(path.dirname(userData),'迁移资料 storage')
  if(phase==='restart'){
    assert.equal(initial.storageRoot,target)
    assert.equal(recording.audioAvailable,false)
    assert.equal((await request('/settings')).body.retainRecordings,'false')
    assert.equal((await request('/settings')).body.llmApiKeyConfigured,true)
    await settings();assert.equal(await evaluate("document.querySelector('.storage-retention input').checked"),false)
    const helper=recordings.find(r=>r.id!==recording.id)
    assert.equal((await request(`/recordings/${helper.id}/jobs`,'POST',{stage:'TRANSCRIBE'})).status,202)
    const blocked=await evaluate("window.classnotesDesktop.migrateStorage().then(()=>'',e=>e.message)")
    assert.ok(blocked.includes('tasks to finish')||blocked.includes('任务完成'))
    return {ok:true,phase,storagePersisted:true,retentionPersisted:true,encryptedCredentialReadable:true,queuedJobBlocksMigration:true,target}
  }
  const before=(await request('/recordings/'+recording.id)).body,original=snapshot(before)
  assert.equal(recording.audioAvailable,true)
  await settings()
  await evaluate("document.querySelector('.storage-settings').scrollIntoView({block:'center'})")
  await capture('storage-settings-zh-026.png')
  await evaluate("window.classnotesDesktop.setUploading(true)");await pause(100)
  const uploadBlock=await evaluate("window.classnotesDesktop.migrateStorage().then(()=>'',e=>e.message)")
  assert.ok(uploadBlock.includes('上传'));await evaluate('window.classnotesDesktop.setUploading(false)')
  await evaluate("(()=>{const f=document.querySelector('.storage-retention input');f.checked=false;f.dispatchEvent(new Event('change',{bubbles:true}))})()")
  await waitFor("document.querySelector('.migrate-storage').disabled")
  const dirtyBlock=await evaluate("window.classnotesDesktop.migrateStorage().then(()=>'',e=>e.message)")
  assert.ok(dirtyBlock.includes('保存笔记和设置'))
  await setRetention(false)
  assert.equal((await request('/recordings/'+recording.id)).body.recording.audioAvailable,true)
  await setRetention(true)
  await request('/settings','PUT',{llmApiKey:'storage-regression-key'})
  const invalid=path.join(path.dirname(userData),'nonempty');fs.mkdirSync(invalid,{recursive:true});fs.writeFileSync(path.join(invalid,'keep.txt'),'keep')
  const open=dialog.showOpenDialog,confirm=dialog.showMessageBox
  try{
    dialog.showMessageBox=async()=>({response:1})
    dialog.showOpenDialog=async()=>({canceled:false,filePaths:[invalid]})
    await evaluate("document.querySelector('.migrate-storage').click()")
    await waitFor("!!document.querySelector('.storage-settings [role=alert]')&&!document.querySelector('.migrate-storage').disabled")
    assert.equal(await evaluate("document.querySelector('.storage-settings [role=alert]').textContent.includes('remote method')"),false)
    assert.equal((await evaluate('window.classnotesDesktop.info()')).storageRoot,userData)
    assert.equal(fs.readFileSync(path.join(invalid,'keep.txt'),'utf8'),'keep')
    dialog.showOpenDialog=async()=>({canceled:false,filePaths:[target]})
    await evaluate("document.querySelector('.migrate-storage').click()")
    await waitFor("!!document.querySelector('.storage-migration-overlay')")
    await capture('storage-migrating-026.png')
    await waitFor("!document.querySelector('.storage-migration-overlay')&&!!document.querySelector('.storage-settings [role=status]')")
  }finally{dialog.showOpenDialog=open;dialog.showMessageBox=confirm}
  const afterInfo=await evaluate('window.classnotesDesktop.info()')
  assert.equal(afterInfo.storageRoot,target);assert.equal(afterInfo.dataDirectory,path.join(target,'data'))
  const after=(await request('/recordings/'+recording.id)).body
  assert.deepEqual(snapshot(after),original);assert.equal((await request('/settings')).body.llmApiKeyConfigured,true)
  await evaluate(`Array.from(document.querySelectorAll('.sidebar nav button')).find(b=>b.textContent.includes('录音库')).click()`)
  await waitFor("document.querySelectorAll('tbody tr').length>=2")
  await evaluate(`Array.from(document.querySelectorAll('tbody tr')).find(r=>r.textContent.includes(${JSON.stringify(recording.title)})).click()`)
  await waitFor("!!document.querySelector('.audio-storage-actions button')&&!document.querySelector('.audio-storage-actions button').disabled")
  await evaluate("document.querySelector('.audio-storage-actions button').click()")
  await waitFor("!!document.querySelector('.confirm-audio-delete')")
  await capture('storage-delete-confirm-026.png')
  await evaluate("document.querySelector('.audio-cleanup-modal .modal-footer .secondary').click()")
  assert.equal((await request('/recordings/'+recording.id)).body.recording.audioAvailable,true)
  await evaluate("document.querySelector('.audio-storage-actions button').click()")
  await waitFor("!!document.querySelector('.confirm-audio-delete')")
  await evaluate("document.querySelector('.confirm-audio-delete').click()")
  await waitFor("!document.querySelector('.audio-cleanup-modal')&&!document.querySelector('.audio-player audio')")
  const cleaned=(await request('/recordings/'+recording.id)).body
  assert.equal(cleaned.recording.audioAvailable,false);assert.deepEqual(snapshot(cleaned),original)
  assert.ok(await evaluate("Array.from(document.querySelectorAll('.rail-actions button')).find(b=>b.textContent.includes('重新转录')).disabled"))
  assert.equal((await request('/recordings/'+recording.id+'/audio')).status,404)
  await capture('storage-audio-deleted-026.png')
  await settings();await setRetention(false)
  await evaluate("(()=>{const f=document.querySelector('#interface-language');f.value='en';f.dispatchEvent(new Event('change',{bubbles:true}))})()")
  await waitFor("document.documentElement.lang==='en'")
  await evaluate("document.querySelector('.storage-settings').scrollIntoView({block:'center'})")
  await capture('storage-settings-en-026.png')
  return {ok:true,phase,uploadAndDraftGuards:true,nonemptyDestinationRejected:true,verifiedMigration:true,encryptedCredentialReadable:true,
    manualDeletionConfirmed:true,noteTranscriptVersionsAndJobsPreserved:true,original,recordingId:recording.id,target}
}
module.exports={verifyStorage}
