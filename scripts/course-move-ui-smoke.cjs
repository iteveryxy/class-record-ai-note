'use strict'
const {app,BrowserWindow}=require('electron')
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const config=JSON.parse(fs.readFileSync(process.argv[2],'utf8'))
app.setPath('userData',config.userData);app.disableHardwareAcceleration()
let window
async function evaluate(code){
  try{return await window.webContents.executeJavaScript(code)}
  catch(error){throw new Error('Renderer evaluation failed: '+code+'\n'+error.message)}
}
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms))
async function waitFor(code){
  const end=Date.now()+60000
  while(Date.now()<end){if(await evaluate(code))return;await pause(150)}
  throw new Error('UI condition timed out: '+code)
}
async function click(text){
  await evaluate(`(()=>{const b=Array.from(document.querySelectorAll('button')).find(b=>b.innerText.trim()===${JSON.stringify(text)});if(!b||b.disabled)throw new Error('Button unavailable: '+${JSON.stringify(text)});b.click()})()`)
}
async function choose(value){await evaluate(`(()=>{const f=document.querySelector('#move-course-target');f.value=${JSON.stringify(value)};f.dispatchEvent(new Event(f.tagName==='SELECT'?'change':'input',{bubbles:true}))})()`)}
const detail=()=>evaluate(`fetch('/api/recordings/'+${JSON.stringify(config.recordingId)}).then(r=>r.json())`)
async function screenshot(name){
  await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))')
  await pause(700);await window.webContents.capturePage();await pause(200)
  fs.writeFileSync(path.join(config.root,'artifacts/screenshots',name),(await window.webContents.capturePage()).toPNG())
}
app.whenReady().then(async()=>{
  window=new BrowserWindow({show:false,width:1440,height:1050,webPreferences:{nodeIntegration:false,contextIsolation:true,sandbox:true,backgroundThrottling:false}})
  await window.loadURL(config.base)
  await waitFor("document.querySelectorAll('tbody tr').length===2")
  const original=await detail()
  await evaluate("Array.from(document.querySelectorAll('.course-list button')).find(b=>b.innerText.trim()==='线性代数').click()")
  await waitFor("document.querySelectorAll('tbody tr').length===1")
  await evaluate("document.querySelector('.row-move').click()")
  await waitFor("!!document.querySelector('#move-course-target')")
  assert.equal(await evaluate("!!document.querySelector('.detail-heading')"),false)
  await choose('高等数学');await screenshot('move-course-dialog-024.png')
  await click('确认移动')
  await waitFor("!document.querySelector('.move-course-dialog')&&document.querySelector('.course-list .selected')?.innerText.trim()==='高等数学'&&document.querySelectorAll('tbody tr').length===2")
  const moved=await detail()
  assert.equal(moved.recording.course,'高等数学');assert.equal(moved.recording.syncStatus,'PENDING')
  for(const key of ['note','transcripts','versions','jobs'])assert.deepEqual(moved[key],original[key])
  assert.equal(moved.logs.length,Math.min(500,original.logs.length+1))
  assert.ok(moved.logs[0].message.includes('线性代数 → 高等数学'))
  await Promise.all([new Promise(resolve=>window.webContents.once('did-finish-load',resolve)),window.reload()])
  await waitFor("document.querySelectorAll('tbody tr').length===2")
  assert.equal((await detail()).recording.course,'高等数学')
  await evaluate(`Array.from(document.querySelectorAll('tbody tr')).find(r=>r.innerText.includes(${JSON.stringify(original.recording.title)})).click()`)
  await waitFor("document.querySelector('.detail-heading .course-chip')?.innerText==='高等数学'")
  await waitFor("document.querySelector('.course-sync-hint')?.innerText.includes('原来的 Notion 页面')")
  assert.equal(await evaluate("Array.from(document.querySelectorAll('button')).find(b=>b.innerText.trim()==='同步到 Notion')?.disabled"),false)
  await screenshot('move-course-detail-024.png')
  await click('移动课程')
  if(await evaluate("document.querySelector('#move-course-target')?.tagName==='SELECT'"))await click('输入新课程')
  await waitFor("document.querySelector('#move-course-target')?.tagName==='INPUT'")
  await choose('新建课程验证');await click('确认移动')
  await waitFor("!document.querySelector('.move-course-dialog')&&document.querySelector('.detail-heading .course-chip')?.innerText==='新建课程验证'")
  await click('移动课程');await choose('高等数学');await click('确认移动')
  await waitFor("!document.querySelector('.move-course-dialog')&&document.querySelector('.detail-heading .course-chip')?.innerText==='高等数学'")
  const restored=await detail()
  assert.deepEqual(restored.note,original.note);assert.deepEqual(restored.versions,original.versions)
  await click('移动课程');await click('取消')
  assert.deepEqual((await detail()).recording,restored.recording)
  const result={ok:true,version:config.version,listMove:true,detailMove:true,newCourse:true,filterFollowsTarget:true,
    reloadPersists:true,cancelKeepsData:true,longNoteCharacters:restored.note.markdown.length,
    noteTranscriptVersionsAndJobsUnchanged:true,notionMappingPreserved:true,notionPendingVisible:true}
  fs.writeFileSync(config.output,JSON.stringify(result,null,2));console.log(JSON.stringify(result));app.exit(0)
}).catch(error=>{fs.writeFileSync(config.output,JSON.stringify({ok:false,error:error.stack},null,2));console.error(error);app.exit(1)})
