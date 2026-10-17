const {test}=require('node:test')
const assert=require('node:assert/strict')
const {EventEmitter}=require('node:events')
const {Updates}=require('../src/updater.cjs')
function fixture(info={version:'0.3.0',minimumSchemaVersion:0,dataSchemaVersion:2}){
  const fake=new EventEmitter()
  fake.updateInfoAndProvider={info}
  fake.checkForUpdates=async()=>({updateInfo:info})
  fake.downloadUpdate=async()=>{fake.emit('download-progress',{percent:50});fake.emit('update-downloaded',info)}
  const state={active:false,installed:false}
  const updater=new Updates({app:{isPackaged:true,getVersion:()=> '0.2.0'},config:{updateUrl:'https://updates.example.com/'},
    services:{health:{schemaVersion:2},request:async()=>({active:state.active})},notify(){},install:async()=>{state.installed=true},createUpdater:()=>fake})
  return {updater,state}
}
test('compatible update can be downloaded but cannot interrupt an active job',async()=>{
  const {updater,state}=fixture();assert.equal((await updater.check()).phase,'available')
  await updater.download();assert.equal(updater.state.phase,'downloaded')
  state.active=true;await assert.rejects(()=>updater.apply(),/录音任务/);assert.equal(state.installed,false)
  state.active=false;await updater.apply();assert.equal(state.installed,true)
})
test('lower versions and incompatible data schemas are never offered',async()=>{
  assert.equal((await fixture({version:'0.1.0',minimumSchemaVersion:0,dataSchemaVersion:2}).updater.check()).phase,'current')
  assert.equal((await fixture({version:'0.3.0',minimumSchemaVersion:3,dataSchemaVersion:3}).updater.check()).phase,'error')
})
test('changing feeds cannot swap out a downloaded installer',async()=>{
  const {updater}=fixture();await updater.check();await updater.download()
  assert.throws(()=>updater.configure('https://other.example.com/'),/完成当前更新/)
})
