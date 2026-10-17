const fs=require('node:fs'),path=require('node:path')
const yaml=require('js-yaml')
const output=path.resolve(__dirname,'../../artifacts/desktop')
const pkg=require('../package.json')
const manifest=path.join(output,'latest.yml')
const crypto=require('node:crypto')
const installer=`ClassNotes-Setup-${pkg.version}-x64.exe`
if(!fs.existsSync(path.join(output,installer)))throw new Error('找不到安装包')
const bytes=fs.readFileSync(path.join(output,installer))
const sha512=crypto.createHash('sha512').update(bytes).digest('base64')
// Rebuilds may replace an installer without changing the version. Never reuse
// an old manifest's digest or length: verify the exact bytes being released.
const value={version:pkg.version,files:[{url:installer,sha512,size:bytes.length}],path:installer,sha512,releaseDate:new Date().toISOString()}
value.minimumSchemaVersion=0;value.dataSchemaVersion=3
fs.writeFileSync(manifest,yaml.dump(value,{lineWidth:120}))
console.log('安装包、卸载程序和 latest.yml 更新兼容清单已生成')
