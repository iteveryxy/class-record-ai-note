'use strict'
const english=require('./locales/en.json')
const validPreference=value=>['system','zh-CN','en'].includes(value)
function resolveLocale(preference,systemLocale='en'){
  const value=validPreference(preference)&&preference!=='system'?preference:systemLocale
  return String(value).toLowerCase().startsWith('zh')?'zh-CN':'en'
}
const format=(value,params={})=>value.replace(/\{(\w+)\}/g,(match,key)=>params[key]===undefined?match:String(params[key]))
function text(locale,key,params){return format(locale==='en'?(english[key]||key):key,params)}
const escape=value=>value.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')
const patterns=Object.entries(english).filter(([key])=>key.includes('{')).sort((a,b)=>b[0].length-a[0].length).map(([key,value])=>{
  const names=[],parts=[];let end=0
  for(const match of key.matchAll(/\{(\w+)\}/g)){parts.push(escape(key.slice(end,match.index)),'([\\s\\S]*?)');names.push(match[1]);end=match.index+match[0].length}
  parts.push(escape(key.slice(end)))
  return {regex:new RegExp('^'+parts.join('')+'$'),names,value}
})
function message(locale,value){
  if(!value||locale!=='en')return value||''
  if(english[value])return english[value]
  for(const pattern of patterns){const match=pattern.regex.exec(value);if(match)return format(pattern.value,Object.fromEntries(pattern.names.map((name,index)=>[name,match[index+1]])))}
  return value
}
module.exports={resolveLocale,validPreference,text,message}
