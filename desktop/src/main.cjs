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

protocol.registerSchemesAsPrivileged([{ scheme: 'classnotes', privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } }])
const argument = name => process.argv.find(value => value.startsWith(name+'='))?.slice(name.length+1)
const explicitData = argument('--data-dir')
app.setPath('userData', explicitData ? path.resolve(explicitData) : path.join(app.getPath('appData'), 'ClassNotes'))
const smoke = argument('--diagnostics')
const diagnosticAudio = argument('--diagnostics-upload')
const demo = process.argv.includes('--demo')
let window, services, updates, quitting = false, confirming = false, dirty = false, uploading = false, serviceState = { phase: 'starting', message: '正在启动课记…' }
const version = app.getVersion()
const root = app.isPackaged ? path.join(process.resourcesPath, 'bundled') : path.join(__dirname, '..', 'resources')
const userData = app.getPath('userData')
fs.mkdirSync(path.join(userData,'logs'),{ recursive: true })
const configPath = path.join(userData,'desktop.json')
let config = { updateUrl: '' }
try { config = { ...config, ...JSON.parse(fs.readFileSync(path.join(root,'release.json'),'utf8')), ...JSON.parse(fs.readFileSync(configPath,'utf8')) } }
catch { try { config = { ...config, ...JSON.parse(fs.readFileSync(path.join(root,'release.json'),'utf8')) } } catch {} }
function send(state) { if(window&&!window.isDestroyed()) window.webContents.send('desktop:state',state) }
function owned(event) { return window && event.sender === window.webContents && event.senderFrame?.url.startsWith('classnotes://app/') }
function handle(name, action) { ipcMain.handle('desktop:'+name, async (event,...args) => { if(!owned(event)) throw new Error('无效的桌面调用'); return action(...args) }) }

if (!app.requestSingleInstanceLock()) app.quit()
else {
  app.on('second-instance',()=>{ if(window) { if(window.isMinimized()) window.restore(); window.show(); window.focus() } })
  app.on('before-quit',event=>{ if(!quitting) { event.preventDefault(); requestQuit() } })
  app.whenReady().then(start).catch(async error=>{
    fs.appendFileSync(path.join(userData,'logs','desktop.log'),String(error.stack)+'\n')
    if(smoke) { fs.mkdirSync(path.dirname(smoke),{recursive:true});fs.writeFileSync(smoke,JSON.stringify({ok:false,error:error.message})); quitting=true;await services?.stop();app.exit(1) }
    else { dialog.showErrorBox('课记启动失败',error.message+'\n诊断日志：'+path.join(userData,'logs'));quitting=true;await services?.stop();app.quit() }
  })
}

async function requestQuit() {
  if(confirming||quitting) return
  confirming=true
  try {
    const active=await services?.request('/api/activity').then(r=>r.active).catch(()=>false)
    if((active||dirty||uploading)&&!smoke) {
      const result=await dialog.showMessageBox(window,{type:'question',buttons:['继续使用','退出课记'],defaultId:0,cancelId:0,
        message:uploading?'录音正在上传':active?'还有录音正在处理':'还有尚未保存的笔记草稿',detail:uploading?'退出会中止此次上传。建议等待保存完成；若中止，可在下次启动后重新选择录音上传。':'退出会停止本地服务。已保存的数据和本地草稿会保留，中断任务下次启动可重试。'})
      if(result.response!==1) return
    }
    quitting=true;await services?.stop();app.quit()
  } finally { confirming=false }
}

async function importData() {
  if(uploading) throw new Error('请先等待录音上传完成')
  if((await services.request('/api/recordings')).length) throw new Error('导入仅适用于空工作空间，避免覆盖已有笔记；可先备份或使用新的数据目录')
  if(dirty) throw new Error('请先保存笔记草稿')
  const chosen=await dialog.showOpenDialog(window,{title:'选择旧版 data/local 数据目录',properties:['openDirectory']})
  if(chosen.canceled) return { imported:false }
  const source=chosen.filePaths[0], staging=path.join(userData,'import-'+Date.now())
  if(!isInside(userData,staging)) throw new Error('导入暂存路径无效')
  serviceState={phase:'starting',message:'正在复制旧版录音与笔记…'};send({service:serviceState})
  await services.stop()
  let previous
  try {
    await promisify(execFile)(path.join(root,'worker','ClassNotesWorker.exe'),['--import-source',source,'--import-target',staging],{windowsHide:true,timeout:1800000,maxBuffer:1024*1024})
    const old=path.join(userData,'data-before-import-'+Date.now())
    if(!isInside(userData,old)) throw new Error('导入备份路径无效')
    fs.renameSync(services.data,old);previous=old;fs.renameSync(staging,services.data)
    await services.start();window.webContents.reload();return {imported:true}
  } catch(error) {
    if(previous && fs.existsSync(previous)) {
      if(fs.existsSync(services.data)) fs.renameSync(services.data,path.join(userData,'import-failed-'+Date.now()))
      fs.renameSync(previous,services.data)
    }
    await services.start();throw new Error('导入失败，原工作空间保留：'+error.message.slice(0,500))
  }
}

async function start() {
  Menu.setApplicationMenu(null)
  services=new Services({resources:root,userData,demo,version,notify:state=>{serviceState=state;send({service:state})}})
  await services.start()
  updates=new Updates({app,config,services,notify:state=>send({update:state}),install:async apply=>{
    if(uploading) throw new Error('录音正在上传，请完成后再重启安装')
    if(dirty) throw new Error('请先保存或完成当前草稿，再重启安装')
    quitting=true;await services.stop();apply()
  }})
  protocol.handle('classnotes',async request=>{
    const url=new URL(request.url)
    if(url.host!=='app') return new Response('Forbidden',{status:403})
    if(request.initiatorOrigin&&request.initiatorOrigin!=='classnotes://app') return new Response('Forbidden',{status:403})
    if(url.pathname.startsWith('/api/')) {
      const headers=new Headers(request.headers);headers.delete('origin');headers.delete('host');headers.delete('cookie');headers.set('Authorization','Bearer '+services.token)
      const options={method:request.method,headers,redirect:'manual'}
      if(!['GET','HEAD'].includes(request.method)) {
        options.body=request.method==='POST'&&url.pathname==='/api/recordings'&&request.body
          ?uploadBody(request.body,request.headers.get('x-classnotes-upload-length')||request.headers.get('content-length'),upload=>send({upload})):request.body
        options.duplex='half'
      }
      return net.fetch(services.baseUrl+url.pathname+url.search,options)
    }
    try {
      const response=await net.fetch(pathToFileURL(safeAsset(path.join(root,'ui'),url.pathname)).href)
      const headers=new Headers(response.headers)
      headers.set('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data:; connect-src 'self'; media-src 'self'; object-src 'none'; frame-src 'none'")
      return new Response(response.body,{status:response.status,headers})
    } catch { return new Response('Not found',{status:404}) }
  })
  window=new BrowserWindow({width:1260,height:850,minWidth:900,minHeight:640,frame:false,show:!smoke,backgroundColor:'#f5f7f6',title:'课记',icon:path.join(root,'icon.png'),
    webPreferences:{preload:path.join(__dirname,'preload.cjs'),sandbox:true,contextIsolation:true,nodeIntegration:false,webSecurity:true}})
  window.webContents.setWindowOpenHandler(({url})=>{if(url.startsWith('https://')) shell.openExternal(url);return {action:'deny'}})
  window.webContents.on('will-navigate',(event,url)=>{if(!url.startsWith('classnotes://app/')) {event.preventDefault();if(url.startsWith('https://')) shell.openExternal(url)}})
  window.webContents.session.setPermissionRequestHandler((_contents,_permission,callback)=>callback(false))
  window.on('close',event=>{if(!quitting){event.preventDefault();requestQuit()}})
  handle('info',()=>({version,platform:process.platform,packaged:app.isPackaged,dataDirectory:services.data,updateUrl:config.updateUrl,service:serviceState,update:updates.state,demo}))
  handle('minimize',()=>window.minimize());handle('maximize',()=>window.isMaximized()?window.unmaximize():window.maximize());handle('close',()=>window.close())
  handle('open-data',()=>shell.openPath(userData));handle('import-data',importData)
  handle('check-updates',()=>updates.check());handle('download-update',()=>updates.download());handle('apply-update',()=>updates.apply())
  handle('configure-updates',url=>{const state=updates.configure(url);fs.writeFileSync(configPath,JSON.stringify(config,null,2));return state})
  ipcMain.on('desktop:dirty',(event,value)=>{if(owned(event)) dirty=Boolean(value)})
  ipcMain.on('desktop:uploading',(event,value)=>{if(owned(event)) uploading=Boolean(value)})
  await window.loadURL('classnotes://app/')
  if(smoke) {
    await new Promise(resolve=>setTimeout(resolve,1500))
    if(diagnosticAudio) {
      if(!explicitData) throw new Error('上传诊断必须指定独立资料目录')
      const result=await require('./progress-diagnostics.cjs').verifyProgress({window,services,audio:diagnosticAudio,output:smoke})
      fs.writeFileSync(smoke,JSON.stringify({...result,version,health:services.health},null,2))
      quitting=true;await services.stop();app.quit();return
    }
    const renderer=await window.webContents.executeJavaScript(`({title:document.title,body:document.body.innerText,desktop:!!window.classnotesDesktop})`)
    fs.mkdirSync(path.dirname(smoke),{recursive:true})
    fs.writeFileSync(smoke+'.png',(await window.webContents.capturePage()).toPNG())
    fs.writeFileSync(smoke,JSON.stringify({ok:renderer.body.includes('我的课堂录音')&&renderer.desktop,version,health:services.health,renderer},null,2))
    quitting=true;await services.stop();app.quit()
  }
}
