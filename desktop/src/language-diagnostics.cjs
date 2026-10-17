'use strict'
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const {resolveLocale}=require('./i18n.cjs')

async function verifyLanguage({window,dialog,output,phase}){
  const evaluate=async code=>{try{return await window.webContents.executeJavaScript(code)}catch(error){throw new Error(code+'\n'+error.message)}}
  const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms))
  async function waitFor(code){const end=Date.now()+60000;while(Date.now()<end){if(await evaluate(code))return;await pause(100)}throw new Error('UI timeout: '+code)}
  async function click(text){await evaluate(`(()=>{const b=Array.from(document.querySelectorAll('button')).find(b=>b.innerText.trim()===${JSON.stringify(text)});if(!b||b.disabled)throw new Error('Unavailable button: '+${JSON.stringify(text)});b.click()})()`)}
  async function choose(value){await evaluate(`(()=>{const f=document.querySelector('#interface-language');f.value=${JSON.stringify(value)};f.dispatchEvent(new Event('change',{bubbles:true}))})()`);await waitFor(`!document.querySelector('#interface-language').disabled&&document.querySelector('#interface-language').value===${JSON.stringify(value)}`)}
  async function capture(name){await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');await pause(700);await window.webContents.capturePage();await pause(200);fs.writeFileSync(path.join(path.dirname(output),'../screenshots',name),(await window.webContents.capturePage()).toPNG())}
  await waitFor("document.querySelectorAll('tbody tr').length===2")
  const info=await evaluate('window.classnotesDesktop.info()')
  const initial=await evaluate('document.documentElement.lang')
  assert.equal(initial,resolveLocale(info.language,info.systemLocale))
  if(phase==='restart'){
    assert.equal(info.language,'en');assert.equal(initial,'en')
    assert.ok(await evaluate("document.querySelector('.settings-nav').textContent.includes('Settings')"))
    return {ok:true,phase,restartRemembersLanguage:true,locale:initial,desktopBridge:true}
  }
  const recordings=await evaluate("fetch('/api/recordings').then(r=>r.json())")
  const recording=recordings.find(r=>r.noteId)
  const detail=()=>evaluate(`fetch('/api/recordings/'+${JSON.stringify(recording.id)}).then(r=>r.json())`)
  const original=await detail()
  await evaluate("document.querySelector('.settings-nav').click()")
  await waitFor("!!document.querySelector('#interface-language')&&document.querySelector('#llm-system-prompt')?.value.length>100&&!document.querySelector('#llm-system-prompt').disabled")
  const prompt=await evaluate("document.querySelector('#llm-system-prompt').value")
  const draft=prompt+'\n保留现有公式与条件。'
  await evaluate(`(()=>{const f=document.querySelector('#llm-system-prompt');f.value=${JSON.stringify(draft)};f.dispatchEvent(new Event('input',{bubbles:true}))})()`)
  await choose('en');await waitFor("document.documentElement.lang==='en'")
  assert.equal(await evaluate("document.querySelector('#llm-system-prompt').value"),draft)
  assert.equal((await evaluate('window.classnotesDesktop.info()')).language,'en')
  await capture('language-settings-en-025.png')
  await choose('zh-CN');await waitFor("document.documentElement.lang==='zh-CN'")
  assert.equal(await evaluate("document.querySelector('#llm-system-prompt').value"),draft)
  assert.ok(await evaluate("document.querySelector('.settings-nav').textContent.includes('系统设置')"))
  await capture('language-settings-zh-025.png')
  await choose('system');await waitFor(`document.documentElement.lang===${JSON.stringify(resolveLocale('system',info.systemLocale))}`)
  await choose('en');await waitFor("document.documentElement.lang==='en'")
  await evaluate("document.querySelector('.sidebar nav button').click()")
  await waitFor("document.querySelectorAll('tbody tr').length===2")
  await click('Upload recording');await waitFor("!!document.querySelector('#upload-title')")
  assert.equal(await evaluate("document.querySelector('#upload-title').textContent"),'Upload lecture recording')
  await click('Cancel')
  await evaluate(`Array.from(document.querySelectorAll('tbody tr')).find(r=>r.innerText.includes(${JSON.stringify(recording.title)})).click()`)
  await waitFor("!!document.querySelector('.detail-heading')&&!!document.querySelector('.markdown-body')")
  await click('Move to course');await waitFor("!!document.querySelector('#move-course-title')")
  assert.equal(await evaluate("document.querySelector('#move-course-title').textContent"),'Move to course')
  await click('Cancel')
  await click('AI chat');await waitFor("document.querySelectorAll('.chat-turn').length===2")
  assert.equal(await evaluate("document.querySelector('.ai-chat h3').textContent"),'Revise your notes with AI')
  await capture('language-note-en-025.png')
  const after=await detail()
  for(const key of ['recording','note','transcripts','versions','jobs','logs'])assert.deepEqual(after[key],original[key])
  let confirmation
  const show=dialog.showMessageBox
  try{
    dialog.showMessageBox=async(_owner,options)=>{confirmation=options;return {response:0}}
    await evaluate('window.classnotesDesktop.setDirty(true);window.classnotesDesktop.close()')
    const end=Date.now()+10000;while(!confirmation&&Date.now()<end)await pause(50)
    assert.ok(confirmation);assert.ok(confirmation.buttons.includes('Quit ClassNotes'))
    assert.equal(confirmation.message,'You have an unsaved note draft')
    await evaluate('window.classnotesDesktop.setDirty(false)')
  }finally{dialog.showMessageBox=show}
  return {ok:true,phase,instantSwitch:true,systemLanguage:true,unsavedPromptPreserved:true,nativeQuitDialogEnglish:true,
    recordingTranscriptNotesVersionsAndChatsUnchanged:true,uploadMoveAndAiChatEnglish:true,locale:'en'}
}
module.exports={verifyLanguage}
