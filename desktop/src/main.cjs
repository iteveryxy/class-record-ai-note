'use strict'
const { app, BrowserWindow, protocol, net, ipcMain, shell, dialog, Menu } = require('electron')
const fs = require('node:fs')
const path = require('node:path')
const { pathToFileURL } = require('node:url')
const { execFile } = require('node:child_process')
const { promisify } = require('node:util')
const { safeAsset, isInside } = require('./core.cjs')
const { Services } = require('./services.cjs')
const { Updates } = require('./updater.cjs')
const { uploadBody } = require('./upload-progress.cjs')
const { loadPreferences,writePreferences } = require('./preferences.cjs')
const { resolveLocale,validPreference,text,message } = require('./i18n.cjs')
const { storageRoot, migrateStorage, assertStorageIdle: checkStorageIdle } = require('./storage.cjs')

protocol.registerSchemesAsPrivileged([{ scheme: 'classnotes', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } }])
const argument = name => process.argv.find(value => value.startsWith(name+'='))?.slice(name.length+1)
const explicitData = argument('--data-dir')
app.setPath('userData', explicitData ? path.resolve(explicitData) : path.join(app.getPath('appData'), 'ClassNotes'))
const smoke = argument('--diagnostics')
const diagnosticAudio = argument('--diagnostics-upload')
const diagnosticLanguage = argument('--diagnostics-language')
const diagnosticStorage = argument('--diagnostics-storage')
const demo = process.argv.includes('--demo')
let window, services, updates, quitting = false, confirming = false, dirty = false, uploading = false, serviceState = { phase: 'starting', message: '正在启动课记…' }
let diskOperation = '', apiWrites = 0, storageState = { phase: 'idle', message: '' }
const version = app.getVersion()
const root = app.isPackaged ? path.join(process.resourcesPath, 'bundled') : path.join(__dirname, '..', 'resources')
const userData = app.getPath('userData')
fs.mkdirSync(path.join(userData,'logs'),{ recursive: true })
const configPath = path.join(userData,'desktop.json')
const config = loadPreferences(path.join(root,'release.json'),configPath)
const locale=()=>resolveLocale(config.language,app.getLocale())
const tr=(key,params)=>text(locale(),key,params)
const explain=value=>message(locale(),value)
function send(state) { if(window&&!window.isDestroyed()) window.webContents.send('desktop:state',state) }
function owned(event) { return window && event.sender === window.webContents && event.senderFrame?.url.startsWith('classnotes://app/') }
function handle(name, action) { ipcMain.handle('desktop:'+name, async (event,...args) => { if(!owned(event)) throw new Error(tr('无效的桌面调用')); try { return await action(...args) } catch(error) { throw new Error(error.cause?tr('迁移失败，原资料目录保留：{0}',{0:explain(error.cause.message)}):explain(error.message)) } }) }

if (!app.requestSingleInstanceLock()) app.quit()
else {
  app.on('second-instance',()=>{ if(window) { if(window.isMinimized()) window.restore(); window.show(); window.focus() } })
  app.on('before-quit',event=>{ if(!quitting) { event.preventDefault(); requestQuit() } })
  app.whenReady().then(start).catch(async error=>{
    fs.appendFileSync(path.join(userData,'logs','desktop.log'),String(error.stack)+'\n')
    if(smoke) { fs.mkdirSync(path.dirname(smoke),{recursive:true});fs.writeFileSync(smoke,JSON.stringify({ok:false,error:error.message})); quitting=true;await services?.stop();app.exit(1) }
    else { dialog.showErrorBox(tr('课记启动失败'),explain(error.message)+'\n'+tr('诊断日志：')+path.join(userData,'logs'));quitting=true;await services?.stop();app.quit() }
  })
}

async function requestQuit() {
  if(confirming||quitting||diskOperation) return
  confirming=true
  try {
    const active=await services?.request('/api/activity').then(r=>r.active).catch(()=>false)
    if((active||dirty||uploading)&&(!smoke||!!diagnosticLanguage)) {
      const result=await dialog.showMessageBox(window,{type:'question',buttons:[tr('继续使用'),tr('退出课记')],defaultId:0,cancelId:0,
        message:tr(uploading?'录音正在上传':active?'还有录音正在处理':'还有尚未保存的笔记草稿'),detail:tr(uploading?'退出会中止此次上传。建议等待保存完成；若中止，可在下次启动后重新选择录音上传。':'退出会停止本地服务。已保存的数据和本地草稿会保留，中断任务下次启动可重试。')})
      if(result.response!==1) return
    }
    quitting=true;await services?.stop();app.quit()
  } finally { confirming=false }
}

async function importData() {
  if(diskOperation) throw new Error('资料操作正在进行，请等待完成')
  if(uploading) throw new Error('请先等待录音上传完成')
  if((await services.request('/api/recordings')).length) throw new Error('导入仅适用于空工作空间，避免覆盖已有笔记；可先备份或使用新的数据目录')
  if(dirty) throw new Error('请先保存笔记草稿')
  diskOperation='choosing'
  try {
  const chosen=await dialog.showOpenDialog(window,{title:tr('选择旧版 data/local 数据目录'),properties:['openDirectory']})
  if(chosen.canceled) return { imported:false }
  diskOperation='import'
  const source=chosen.filePaths[0], staging=path.join(services.storageRoot,'import-'+Date.now())
  if(!isInside(services.storageRoot,staging)) throw new Error('导入暂存路径无效')
  serviceState={phase:'starting',message:'正在复制旧版录音与笔记…'};send({service:serviceState})
  await services.stop()
  let previous
  try {
    await promisify(execFile)(path.join(root,'worker','ClassNotesWorker.exe'),['--import-source',source,'--import-target',staging],{windowsHide:true,timeout:1800000,maxBuffer:1024*1024})
    const old=path.join(services.storageRoot,'data-before-import-'+Date.now())
    if(!isInside(services.storageRoot,old)) throw new Error('导入备份路径无效')
    fs.renameSync(services.data,old);previous=old;fs.renameSync(staging,services.data)
    await services.start();window.webContents.reload();return {imported:true}
  } catch(error) {
    if(previous && fs.existsSync(previous)) {
      await services.stop()
      if(fs.existsSync(services.data)) fs.renameSync(services.data,path.join(services.storageRoot,'import-failed-'+Date.now()))
      fs.renameSync(previous,services.data)
    }
    await services.start();throw new Error('导入失败，原工作空间保留：'+error.message.slice(0,500))
  }
  } finally { diskOperation='' }
}

async function assertStorageIdle() {
  await checkStorageIdle({services,uploading,apiWrites,dirty})
}
function storageProgress(state) {
  const labels={checking:'正在检查存储目录…',stopping:'正在安全关闭本地服务…',copying:'正在复制录音、笔记与模型…',verifying:'正在校验文件完整性…',starting:'正在新目录验证本地服务…',complete:'资料迁移完成，原目录作为备份保留',error:'迁移失败，原资料目录保留'}
  storageState={...state,message:labels[state.phase]||state.message||'',percent:state.total?Math.min(100,Math.floor(state.loaded*100/state.total)):undefined}
  send({storage:storageState})
}
async function chooseStorage() {
  if(diskOperation) throw new Error('资料操作正在进行，请等待完成')
  diskOperation='choosing'
  try {
    await assertStorageIdle()
    const chosen=await dialog.showOpenDialog(window,{title:tr('选择新的空存储文件夹'),properties:['openDirectory','createDirectory']})
    if(chosen.canceled)return {migrated:false}
    const target=chosen.filePaths[0]
    const answer=await dialog.showMessageBox(window,{type:'question',buttons:[tr('取消'),tr('开始迁移')],defaultId:0,cancelId:0,
      message:tr('将资料迁移到新文件夹？'),detail:tr('录音、笔记、转录、历史、密钥和模型缓存会复制到目标目录。切换前将进行校验，原目录保留为备份。迁移期间请保持课记运行。')+'\n\n'+target})
    if(answer.response!==1)return {migrated:false}
    await assertStorageIdle()
    diskOperation='migrate';storageProgress({phase:'checking'})
    return await migrateStorage({services,target,protectedRoots:[userData,root,app.getAppPath(),path.dirname(app.getPath('exe'))],
      notify:storageProgress,
      persist:value=>{const next={...config,...value};writePreferences(configPath,next);Object.assign(config,value);try{writePreferences(path.join(userData,'desktop.storage-backup.json'),next)}catch{/* The committed primary preference file remains authoritative. */}},
      journal:value=>writePreferences(path.join(userData,'storage-migration.json'),value)})
  } catch(error) { storageProgress({phase:'error'});throw error }
  finally { diskOperation='' }
}

async function start() {
  Menu.setApplicationMenu(null)
  services=new Services({resources:root,userData,storageRoot:storageRoot(config,userData),demo,version,notify:state=>{serviceState=state;send({service:state})}})
  await services.start()
  updates=new Updates({app,config,services,notify:state=>send({update:state}),install:async apply=>{
    if(diskOperation) throw new Error('资料操作正在进行，请等待完成')
    if(uploading) throw new Error('录音正在上传，请完成后再重启安装')
    if(dirty) throw new Error('请先保存或完成当前草稿，再重启安装')
    quitting=true;await services.stop();apply()
  }})
  protocol.handle('classnotes',async request=>{
    const url=new URL(request.url)
    if(url.host!=='app') return new Response('Forbidden',{status:403})
    if(request.initiatorOrigin&&request.initiatorOrigin!=='classnotes://app') return new Response('Forbidden',{status:403})
    if(url.pathname.startsWith('/api/')) {
      const writing=!['GET','HEAD'].includes(request.method)
      if((writing&&diskOperation)||diskOperation==='migrate'||diskOperation==='import')return Response.json({message:tr('资料操作正在进行，请等待完成')},{status:503})
      const headers=new Headers(request.headers);headers.delete('origin');headers.delete('host');headers.delete('cookie');headers.set('Authorization','Bearer '+services.token)
      const options={method:request.method,headers,redirect:'manual'}
      if(!['GET','HEAD'].includes(request.method)) {
        options.body=request.method==='POST'&&url.pathname==='/api/recordings'&&request.body
          ?uploadBody(request.body,request.headers.get('x-classnotes-upload-length')||request.headers.get('content-length'),upload=>send({upload})):request.body
        options.duplex='half'
      }
      if(writing)apiWrites++
      try { return await net.fetch(services.baseUrl+url.pathname+url.search,options) }
      finally { if(writing)apiWrites-- }
    }
    try {
      const response=await net.fetch(pathToFileURL(safeAsset(path.join(root,'ui'),url.pathname)).href)
      const headers=new Headers(response.headers)
      headers.set('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data:; connect-src 'self'; media-src 'self'; object-src 'none'; frame-src 'none'")
      return new Response(response.body,{status:response.status,headers})
    } catch { return new Response('Not found',{status:404}) }
  })
  window=new BrowserWindow({width:1260,height:850,minWidth:900,minHeight:640,frame:false,show:!smoke,backgroundColor:'#f5f7f6',title:tr('课记'),icon:path.join(root,'icon.png'),
    webPreferences:{preload:path.join(__dirname,'preload.cjs'),sandbox:true,contextIsolation:true,nodeIntegration:false,webSecurity:true}})
  window.webContents.setWindowOpenHandler(({url})=>{if(url.startsWith('https://')) shell.openExternal(url);return {action:'deny'}})
  window.webContents.on('will-navigate',(event,url)=>{if(!url.startsWith('classnotes://app/')) {event.preventDefault();if(url.startsWith('https://')) shell.openExternal(url)}})
  window.webContents.session.setPermissionRequestHandler((_contents,_permission,callback)=>callback(false))
  window.on('close',event=>{if(!quitting){event.preventDefault();requestQuit()}})
  handle('info',()=>({version,platform:process.platform,packaged:app.isPackaged,dataDirectory:services.data,storageRoot:services.storageRoot,storage:storageState,configDirectory:userData,updateUrl:config.updateUrl,service:serviceState,update:updates.state,demo,language:config.language,systemLocale:app.getLocale()}))
  handle('language',value=>{
    if(!validPreference(value))throw new Error(tr('语言选项无效'))
    writePreferences(configPath,{...config,language:value});config.language=value;window.setTitle(tr('课记'))
  })
  handle('minimize',()=>window.minimize());handle('maximize',()=>window.isMaximized()?window.unmaximize():window.maximize());handle('close',()=>window.close())
  handle('open-data',()=>shell.openPath(services.storageRoot));handle('import-data',importData)
  handle('migrate-storage',chooseStorage)
  handle('check-updates',()=>updates.check());handle('download-update',()=>updates.download());handle('apply-update',()=>updates.apply())
  handle('configure-updates',url=>{const state=updates.configure(url);writePreferences(configPath,config);return state})
  ipcMain.on('desktop:dirty',(event,value)=>{if(owned(event)) dirty=Boolean(value)})
  ipcMain.on('desktop:uploading',(event,value)=>{if(owned(event)) uploading=Boolean(value)})
  await window.loadURL('classnotes://app/')
  if(smoke) {
    await new Promise(resolve=>setTimeout(resolve,1500))
    if(diagnosticStorage) {
      if(!explicitData)throw new Error('Storage diagnostics require an isolated data directory')
      const result=await require('./storage-diagnostics.cjs').verifyStorage({window,dialog,output:smoke,phase:diagnosticStorage,userData})
      fs.writeFileSync(smoke,JSON.stringify({...result,version,health:services.health},null,2))
      quitting=true;await services.stop();app.quit();return
    }
    if(diagnosticLanguage) {
      if(!explicitData)throw new Error('Language diagnostics require an isolated data directory')
      const result=await require('./language-diagnostics.cjs').verifyLanguage({window,dialog,output:smoke,phase:diagnosticLanguage})
      fs.writeFileSync(smoke,JSON.stringify({...result,version,health:services.health},null,2))
      quitting=true;await services.stop();app.quit();return
    }
    if(diagnosticAudio) {
      if(!explicitData) throw new Error('上传诊断必须指定独立资料目录')
      const result=await require('./progress-diagnostics.cjs').verifyProgress({window,services,audio:diagnosticAudio,output:smoke})
      fs.writeFileSync(smoke,JSON.stringify({...result,version,health:services.health},null,2))
      quitting=true;await services.stop();app.quit();return
    }
    const renderer=await window.webContents.executeJavaScript(`({title:document.title,body:document.body.innerText,desktop:!!window.classnotesDesktop})`)
    fs.mkdirSync(path.dirname(smoke),{recursive:true})
    fs.writeFileSync(smoke+'.png',(await window.webContents.capturePage()).toPNG())
    fs.writeFileSync(smoke,JSON.stringify({ok:(renderer.body.includes('我的课堂录音')||renderer.body.includes('My lecture recordings'))&&renderer.desktop,version,health:services.health,renderer,dataDirectory:services.data,storageRoot:services.storageRoot},null,2))
    quitting=true;await services.stop();app.quit()
  }
}
