'use strict'
const fs=require('node:fs'),crypto=require('node:crypto')
const {validPreference}=require('./i18n.cjs')
function loadPreferences(bundled,user){
  let value={updateUrl:'',language:'system'}
  for(const filename of [bundled,user]){
    try{const parsed=JSON.parse(fs.readFileSync(filename,'utf8'));if(parsed&&typeof parsed==='object'&&!Array.isArray(parsed))value={...value,...parsed}}catch{}
  }
  if(!validPreference(value.language))value.language='system'
  return value
}
function writePreferences(filename,value){
  const temporary=filename+'.'+crypto.randomUUID()+'.tmp'
  try{fs.writeFileSync(temporary,JSON.stringify(value,null,2),{encoding:'utf8',mode:0o600});fs.renameSync(temporary,filename)}
  finally{try{fs.unlinkSync(temporary)}catch{}}
}
module.exports={loadPreferences,writePreferences}
