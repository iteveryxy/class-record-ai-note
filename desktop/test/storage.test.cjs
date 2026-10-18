const test=require('node:test'),assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),os=require('node:os')
const {storageRoot,validateTarget,migrateStorage,assertStorageIdle}=require('../src/storage.cjs')

function fixture(t){
  const parent=fs.realpathSync(os.tmpdir()),root=fs.mkdtempSync(path.join(parent,'classnotes-storage-test-'))
  t.after(()=>{if(path.dirname(root)!==parent||!path.basename(root).startsWith('classnotes-storage-test-'))throw new Error('unsafe cleanup');fs.rmSync(root,{recursive:true,force:true})})
  const source=path.join(root,'source'),target=path.join(root,'target'),bootstrap=path.join(root,'bootstrap')
  for(const name of [source,target,bootstrap])fs.mkdirSync(name)
  fs.mkdirSync(path.join(source,'data'));fs.writeFileSync(path.join(source,'data','classnotes.db'),'original-data')
  const services={resources:root,storageRoot:source,children:[{}],starts:[],stops:0,
    setStorageRoot(value){this.storageRoot=value},async stop(){this.stops++;this.children=[]},async start(){this.starts.push(this.storageRoot);this.children=[{}]}}
  const config={},events=[],journals=[]
  const options={services,target,protectedRoots:[bootstrap],persist:value=>Object.assign(config,value),journal:value=>journals.push(value),notify:value=>events.push(value),
    copy:async(_exe,_source,staging)=>{fs.mkdirSync(path.join(staging,'data'),{recursive:true});fs.copyFileSync(path.join(source,'data','classnotes.db'),path.join(staging,'data','classnotes.db'));const marker={format:1,storageId:'verified-id'};fs.writeFileSync(path.join(staging,'classnotes-storage.json'),JSON.stringify(marker));return marker}}
  return {root,source,target,bootstrap,services,config,events,journals,options}
}
test('migration switches only after health checks, keeps original data and persists the storage identity',async t=>{
  const f=fixture(t);const result=await migrateStorage(f.options)
  assert.equal(result.migrated,true);assert.equal(f.services.storageRoot,f.target);assert.equal(f.config.storageRoot,f.target)
  assert.deepEqual(f.services.starts,[f.target]);assert.equal(fs.readFileSync(path.join(f.source,'data/classnotes.db'),'utf8'),'original-data')
  assert.equal(storageRoot(f.config,f.bootstrap),f.target);assert.equal(f.journals.at(-1).phase,'complete')
})
test('failed copies restart the original workspace without committing a pointer',async t=>{
  const f=fixture(t);f.options.copy=async()=>{throw new Error('disk full')}
  await assert.rejects(migrateStorage(f.options),/disk full/)
  assert.equal(f.services.storageRoot,f.source);assert.deepEqual(f.services.starts,[f.source]);assert.deepEqual(f.config,{})
})
test('failed target startup rolls back to the original workspace and retains the copy for inspection',async t=>{
  const f=fixture(t),start=f.services.start
  f.services.start=async function(){await start.call(this);if(this.storageRoot===f.target)throw new Error('target health failed')}
  await assert.rejects(migrateStorage(f.options),/target health failed/)
  assert.equal(f.services.storageRoot,f.source);assert.deepEqual(f.services.starts,[f.target,f.source]);assert.deepEqual(f.config,{})
  assert.ok(fs.existsSync(path.join(f.target,'data/classnotes.db')))
})
test('failed preference commit stops the target and restores the source',async t=>{
  const f=fixture(t);f.options.persist=()=>{throw new Error('preferences locked')}
  await assert.rejects(migrateStorage(f.options),/preferences locked/)
  assert.equal(f.services.storageRoot,f.source);assert.equal(f.services.stops,2);assert.deepEqual(f.services.starts,[f.target,f.source])
})
test('a service that cannot stop is never copied or switched',async t=>{
  const f=fixture(t);let copied=false;f.options.copy=async()=>{copied=true};f.services.stop=async()=>{throw new Error('still running')}
  await assert.rejects(migrateStorage(f.options),/still running/)
  assert.equal(copied,false);assert.equal(f.services.storageRoot,f.source);assert.deepEqual(f.services.starts,[])
})
test('startup refuses missing or substituted storage instead of creating an empty workspace',async t=>{
  const f=fixture(t)
  assert.equal(storageRoot({},f.bootstrap),f.bootstrap)
  assert.throws(()=>storageRoot({storageRoot:f.target,storageId:'expected'},f.bootstrap),/不可用/)
  await migrateStorage(f.options)
  assert.throws(()=>storageRoot({...f.config,storageId:'wrong'},f.bootstrap),/不匹配/)
})
test('existing, nested, root and protected destination folders are rejected before services stop',async t=>{
  const f=fixture(t)
  fs.writeFileSync(path.join(f.target,'keep.txt'),'keep')
  assert.throws(()=>validateTarget(f.source,f.target),/必须为空/)
  const nested=path.join(f.source,'nested');fs.mkdirSync(nested)
  assert.throws(()=>validateTarget(f.source,nested),/包含彼此/)
  assert.throws(()=>validateTarget(f.source,path.parse(f.root).root),/磁盘根目录/)
  assert.throws(()=>validateTarget(f.source,f.bootstrap,[f.bootstrap]),/桌面配置目录/)
  await assert.rejects(migrateStorage(f.options),/必须为空/);assert.equal(f.services.stops,0)
})
test('junction aliases cannot bypass source overlap checks',t=>{
  const f=fixture(t),alias=path.join(f.root,'alias')
  fs.symlinkSync(f.source,alias,process.platform==='win32'?'junction':'dir')
  assert.throws(()=>validateTarget(f.source,alias),/包含彼此/)
})
test('uploads, pending saves, drafts and queued jobs block migration; unavailable activity fails closed',async()=>{
  const services={request:async()=>({active:false})}
  for(const flags of [{uploading:true},{apiWrites:1},{dirty:true}])await assert.rejects(assertStorageIdle({services,...flags}))
  await assert.rejects(assertStorageIdle({services:{request:async()=>({active:true})}}),/任务完成/)
  await assert.rejects(assertStorageIdle({services:{request:async()=>{throw new Error('offline')}}}),/offline/)
  await assertStorageIdle({services})
})
test('a lost storage pointer after a completed migration never silently opens the old backup',t=>{
  const f=fixture(t);fs.writeFileSync(path.join(f.bootstrap,'storage-migration.json'),JSON.stringify({phase:'complete',target:f.target}))
  assert.throws(()=>storageRoot({},f.bootstrap),/缺少存储配置/)
})
