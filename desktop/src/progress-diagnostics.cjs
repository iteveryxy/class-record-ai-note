// Explicit, isolated UI diagnostics; never used during a normal application run.
const fs=require('node:fs')
const path=require('node:path')
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms))

async function verifyProgress({window,services,audio,output}) {
  window.webContents.setBackgroundThrottling(false)
  if((await services.request('/api/recordings')).length) throw new Error('上传进度诊断只允许使用空的独立测试工作空间')
  if(!path.isAbsolute(audio)||!fs.statSync(audio).isFile()) throw new Error('诊断音频不存在')
  const evaluate=source=>window.webContents.executeJavaScript(source)
  const capture=async name=>{fs.writeFileSync(output+'.'+name+'.png',(await window.webContents.capturePage()).toPNG())}
  await evaluate(`window.__uploadEvents=[];window.__nativeUploadEvents=[];
    window.classnotesDesktop.onState(state=>{if(state.upload)window.__nativeUploadEvents.push(state.upload)});
    const OriginalXHR=window.XMLHttpRequest;
    window.XMLHttpRequest=class extends OriginalXHR {
      constructor(){super();this.upload.addEventListener('progress',e=>window.__uploadEvents.push({loaded:e.loaded,total:e.total,known:e.lengthComputable}));}
      addEventListener(type,listener,options){
        if(type==='load') return super.addEventListener(type,event=>setTimeout(()=>typeof listener==='function'?listener.call(this,event):listener?.handleEvent(event),5000),options);
        return super.addEventListener(type,listener,options);
      }
    };
    document.querySelector('.page-heading .btn.primary').click();`)
  await delay(100)
  const debuggerApi=window.webContents.debugger
  debuggerApi.attach('1.3')
  // The isolated probe delays only the client's save-response handler by 5 s
  // to capture a fast local transfer. Byte counts and ASR are real.
  let uploadCapture=false,uploadText=''
  const document=await debuggerApi.sendCommand('DOM.getDocument')
  const input=await debuggerApi.sendCommand('DOM.querySelector',{nodeId:document.root.nodeId,selector:'input[type=file]'})
  await debuggerApi.sendCommand('DOM.setFileInputFiles',{nodeId:input.nodeId,files:[audio]})
  await evaluate(`const input=document.querySelector('input[placeholder="例如：高等数学"]');input.value='进度验证';input.dispatchEvent(new Event('input',{bubbles:true}));`)
  await delay(150)
  await evaluate(`document.querySelector('.modal form').requestSubmit()`)
  const samples=[];let completed=false,recording,asrCapture=false,preparingCapture=false,libraryCapture=false
  const deadline=Date.now()+10*60*1000
  while(Date.now()<deadline) {
    await delay(500)
    const recordings=await services.request('/api/recordings')
    recording=recordings[0]
    if(!recording) continue
    if(!uploadCapture) {
      uploadText=await evaluate(`document.querySelector('.upload-progress')?.innerText||''`)
      if(uploadText.includes('100%')){await capture('upload');uploadCapture=true}
    }
    const progress=recording.transcriptionProgress
    if(progress && (!samples.length||JSON.stringify(samples.at(-1))!==JSON.stringify(progress))) samples.push(progress)
    if(!preparingCapture&&progress?.phase==='loading_model') {
      const preparation=await evaluate(`document.querySelector('.transcription-progress:not(.compact)')?.innerText||''`)
      if(preparation.includes('准备语音模型')) {
        if(preparation.includes('%')) throw new Error('模型准备阶段不应显示百分比')
        await capture('preparing');preparingCapture=true
      }
    }
    if(!asrCapture&&progress?.percent>0&&progress.percent<99) {
      await delay(2600) // Allow the normal 2.5-second frontend poll to render it.
      const shown=await evaluate(`({percent:document.querySelector('.transcription-progress:not(.compact) progress')?.getAttribute('value'),text:document.querySelector('.transcription-progress:not(.compact)')?.innerText})`)
      if(shown.percent&&Number(shown.percent)>0&&Number(shown.percent)<100){
        await capture('transcription');asrCapture=true
        await evaluate(`document.querySelector('.back-link').click()`);await delay(500)
        const library=await evaluate(`!!document.querySelector('.transcription-progress.compact progress')`)
        if(library){await capture('library');libraryCapture=true}
        await evaluate(`document.querySelector('table tbody tr').click()`)
      }
    }
    if(recording.transcriptStatus==='FAILED') throw new Error('真实语音进度诊断失败：'+recording.lastError)
    if(recording.transcriptStatus==='SUCCEEDED'){completed=true;break}
  }
  debuggerApi.detach()
  if(!completed) throw new Error('转录进度诊断超时')
  await delay(2800)
  await capture('completed')
  const uploadEvents=await evaluate('window.__uploadEvents')
  const nativeUploadEvents=await evaluate('window.__nativeUploadEvents')
  const renderer=await evaluate(`({title:document.title,body:document.body.innerText,desktop:!!window.classnotesDesktop})`)
  const intermediate=samples.some(p=>p.percent>0&&p.percent<99)
  if(!uploadCapture||!uploadText.includes('100%')||!nativeUploadEvents.some(e=>e.loaded>0))
    throw new Error('桌面上传进度未能显示：'+(uploadText||'未收到上传事件'))
  if(!intermediate||!asrCapture||recording.transcriptionProgress.percent!==100) throw new Error('转录未观察到实际中间进度或最终完成状态')
  return {ok:true,uploadEvents,nativeUploadEvents,uploadText,asrProgress:samples,intermediateProgressRendered:asrCapture,preparingCapture,libraryCapture,
    completed:true,recordingId:recording.id,renderer}
}
module.exports={verifyProgress}
