'use strict'
// Project-owned UI regression harness; all model calls are local fixtures.
const {app,BrowserWindow}=require('electron')
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const config=JSON.parse(fs.readFileSync(process.argv[2],'utf8'))
app.setPath('userData',config.userData)
app.disableHardwareAcceleration()
let window
const evaluate=code=>window.webContents.executeJavaScript(code)
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms))
async function waitFor(code){
  const end=Date.now()+90000
  while(Date.now()<end){if(await evaluate(code))return;await pause(150)}
  throw new Error('UI condition timed out: '+code)
}
async function click(text){
  await evaluate(`(()=>{const b=Array.from(document.querySelectorAll('button')).find(b=>b.innerText.trim()===${JSON.stringify(text)});if(!b||b.disabled)throw new Error('Button unavailable: '+${JSON.stringify(text)});b.click()})()`)
}
async function fill(selector,value){
  await evaluate(`(()=>{const field=document.querySelector(${JSON.stringify(selector)});field.value=${JSON.stringify(value)};field.dispatchEvent(new Event('input',{bubbles:true}))})()`)
}
const detail=()=>evaluate(`fetch('/api/recordings/'+${JSON.stringify(config.recordingId)}).then(r=>r.json())`)
async function screenshot(name){
  await evaluate(`new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))`)
  await pause(700);await window.webContents.capturePage();await pause(200)
  fs.writeFileSync(path.join(config.root,'artifacts/screenshots',name),(await window.webContents.capturePage()).toPNG())
}
app.whenReady().then(async()=>{
  window=new BrowserWindow({show:false,width:1440,height:1120,webPreferences:{nodeIntegration:false,contextIsolation:true,sandbox:true,backgroundThrottling:false}})
  await window.loadURL(config.base)
  await waitFor(`document.querySelectorAll('tbody tr').length===1`)
  const original=await detail()
  const noteId=original.note.id
  await evaluate(`document.querySelector('.settings-nav').click()`)
  await waitFor(`document.querySelector('#llm-system-prompt')?.value.includes('课堂笔记')`)
  const defaultPrompt=await evaluate(`document.querySelector('#llm-system-prompt').value`)
  await fill('#llm-system-prompt',config.customPrompt)
  await click('保存配置')
  await waitFor(`document.body.innerText.includes('配置已保存')`)
  assert.equal(await evaluate(`fetch('/api/settings').then(r=>r.json()).then(s=>s.llmSystemPrompt)`),config.customPrompt)
  await evaluate(`Array.from(document.querySelectorAll('.sidebar nav button')).find(b=>b.textContent.includes('录音库')).click()`)
  await waitFor(`document.querySelectorAll('tbody tr').length===1`)
  await evaluate(`document.querySelector('tbody tr').click()`)
  await waitFor(`document.querySelector('.markdown-body')?.textContent.includes('分段80尾部关键点')`)
  await click('AI 对话')
  await waitFor(`document.querySelector('#note-ai-message')&&!document.querySelector('.ai-chat .chat-empty')?.textContent.includes('正在读取')`)
  const instruction='把“分段1尾部关键点”改为“课后重点”，保留全部公式、来源时间和其他分段。'
  await fill('#note-ai-message',instruction)
  await waitFor(`!Array.from(document.querySelectorAll('button')).find(b=>b.innerText.trim()==='发送').disabled`)
  await click('发送')
  await waitFor(`document.querySelector('.ai-chat')?.textContent.includes('已保存 v3')`)
  const edited=await detail()
  assert.equal(edited.note.markdown,original.note.markdown.replace('分段1尾部关键点','课后重点'))
  assert.equal(edited.note.revision,original.note.revision+1)
  assert.equal(edited.note.markdown.split('$$a^2+b^2=c^2$$').length-1,80)
  assert.equal(edited.versions[0].source,'AI_CHAT')
  await fill('#note-ai-message','刚才保留的来源时间有什么用？只解释，不要修改笔记。')
  await waitFor(`!Array.from(document.querySelectorAll('button')).find(b=>b.innerText.trim()==='发送').disabled`)
  await click('发送')
  await waitFor(`document.querySelector('.ai-chat')?.textContent.includes('笔记未改动')&&document.querySelector('.ai-chat')?.textContent.includes('来源时间帮助')`)
  const afterQuestion=await detail()
  assert.equal(afterQuestion.note.markdown,edited.note.markdown)
  assert.equal(afterQuestion.note.revision,edited.note.revision)
  await click('收起 AI 对话');await click('AI 对话')
  await waitFor(`document.querySelectorAll('.chat-turn').length===2`)
  assert.ok(await evaluate(`document.querySelector('.ai-chat').textContent.includes(${JSON.stringify(instruction)})`))
  await evaluate(`document.querySelector('.ai-chat').scrollIntoView({block:'center'})`)
  await screenshot('ai-note-chat-023.png')
  const history=await evaluate(`fetch('/api/notes/'+${JSON.stringify(noteId)}+'/chat').then(r=>r.json())`)
  assert.equal(history.length,2);assert.equal(history[1].changed,false)
  await evaluate(`document.querySelector('.settings-nav').click()`)
  await waitFor(`document.querySelector('#llm-system-prompt')?.value===${JSON.stringify(config.customPrompt)}`)
  const beforeReset=await evaluate(`fetch('/api/settings').then(r=>r.json())`)
  await click('恢复默认')
  await waitFor(`document.body.innerText.includes('已恢复并保存默认 System Prompt')`)
  const afterReset=await evaluate(`fetch('/api/settings').then(r=>r.json())`)
  assert.equal(afterReset.llmSystemPrompt,defaultPrompt)
  assert.equal(afterReset.llmModel,beforeReset.llmModel)
  assert.equal(afterReset.llmChunkChars,beforeReset.llmChunkChars)
  assert.equal(afterReset.autoSummarize,beforeReset.autoSummarize)
  await evaluate(`document.querySelector('.system-prompt-editor').scrollIntoView({block:'center'})`)
  await screenshot('system-prompt-settings-023.png')
  const result={ok:true,version:config.version,longNoteCharacters:edited.note.markdown.length,formulaCount:80,
    exactRequestedEditOnly:true,conversationReopened:true,questionKeepsRevision:true,
    customSystemPromptSaved:true,resetOnlySystemPrompt:true,aiVersion:edited.note.revision,turns:history.length}
  fs.writeFileSync(config.output,JSON.stringify(result,null,2));console.log(JSON.stringify(result));app.exit(0)
}).catch(error=>{fs.writeFileSync(config.output,JSON.stringify({ok:false,error:error.stack},null,2));console.error(error);app.exit(1)})
