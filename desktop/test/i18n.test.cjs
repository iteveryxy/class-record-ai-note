const {test}=require('node:test')
const assert=require('node:assert/strict')
const fs=require('node:fs'),path=require('node:path'),os=require('node:os')
const {resolveLocale,text,message}=require('../src/i18n.cjs')
const {loadPreferences,writePreferences}=require('../src/preferences.cjs')
function cleanup(dir){assert.equal(path.dirname(path.resolve(dir)),path.resolve(os.tmpdir()));assert.ok(path.basename(dir).startsWith('classnotes-locale-'));fs.rmSync(dir,{recursive:true,force:true})}

test('native language resolution follows explicit choices or the system locale',()=>{
  assert.equal(resolveLocale('system','zh-HK'),'zh-CN')
  assert.equal(resolveLocale('system','de-DE'),'en')
  assert.equal(resolveLocale('en','zh-CN'),'en')
  assert.equal(resolveLocale('zh-CN','en-US'),'zh-CN')
  assert.equal(text('en','退出课记'),'Quit ClassNotes')
  assert.equal(message('en','发现新版本 0.2.5'),'Version 0.2.5 is available')
  assert.equal(message('zh-CN','发现新版本 0.2.5'),'发现新版本 0.2.5')
})
test('saved language loads even when no bundled release configuration exists',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'classnotes-locale-'))
  try{
    const config=path.join(dir,'desktop.json');writePreferences(config,{language:'en',updateUrl:'https://example.com/updates/'})
    assert.deepEqual(loadPreferences(path.join(dir,'missing.json'),config),{language:'en',updateUrl:'https://example.com/updates/'})
  }finally{cleanup(dir)}
})
test('language changes preserve update settings and survive reloading',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'classnotes-locale-'))
  try{
    const bundled=path.join(dir,'release.json'),user=path.join(dir,'desktop.json')
    fs.writeFileSync(bundled,JSON.stringify({updateUrl:'https://example.com/',channel:'stable'}))
    writePreferences(user,{...loadPreferences(bundled,user),language:'zh-CN'})
    assert.deepEqual(loadPreferences(bundled,user),{updateUrl:'https://example.com/',channel:'stable',language:'zh-CN'})
  }finally{cleanup(dir)}
})
test('invalid preferences fall back to system language without losing a valid update URL',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'classnotes-locale-'))
  try{
    const bundled=path.join(dir,'release.json'),user=path.join(dir,'desktop.json')
    fs.writeFileSync(bundled,'{"updateUrl":"https://example.com/","language":"xx"}')
    fs.writeFileSync(user,'{broken')
    assert.deepEqual(loadPreferences(bundled,user),{updateUrl:'https://example.com/',language:'system'})
  }finally{cleanup(dir)}
})
test('failed atomic preference writes do not replace an existing target',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'classnotes-locale-'))
  try{
    const target=path.join(dir,'desktop.json');fs.mkdirSync(target);fs.writeFileSync(path.join(target,'marker'),'keep')
    assert.throws(()=>writePreferences(target,{language:'en'}))
    assert.equal(fs.readFileSync(path.join(target,'marker'),'utf8'),'keep')
    assert.deepEqual(fs.readdirSync(dir),['desktop.json'])
  }finally{cleanup(dir)}
})
