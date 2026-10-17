import { computed, ref, watchEffect } from 'vue'
import english from '../../desktop/src/locales/en.json'
import type { DesktopBridge } from './desktop'

export type LanguagePreference='system'|'zh-CN'|'en'
export type Locale='zh-CN'|'en'
type Params=Record<string,string|number|undefined>
const catalog:Record<string,string>=english
const key='classnotes:language'
export const validPreference=(value:unknown):value is LanguagePreference=>['system','zh-CN','en'].includes(String(value))
export function resolveLocale(preference:LanguagePreference,system:string):Locale{return (preference==='system'?system:preference).toLowerCase().startsWith('zh')?'zh-CN':'en'}
const systemLanguage=ref(typeof navigator==='undefined'?'en':navigator.language)
export const languagePreference=ref<LanguagePreference>('system')
export const locale=computed(()=>resolveLocale(languagePreference.value,systemLanguage.value))
const bridge=():DesktopBridge|undefined=>typeof window==='undefined'?undefined:window.classnotesDesktop
const format=(value:string,params:Params={})=>value.replace(/\{(\w+)\}/g,(match,name)=>params[name]===undefined?match:String(params[name]))
export function t(source:string,params?:Params):string{return format(locale.value==='en'?(catalog[source]||source):source,params)}
const escape=(value:string)=>value.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')
const patterns=Object.entries(catalog).filter(([source])=>source.includes('{')).sort((a,b)=>b[0].length-a[0].length).map(([source,value])=>{
  const names:string[]=[],parts:string[]=[];let end=0
  for(const match of source.matchAll(/\{(\w+)\}/g)){parts.push(escape(source.slice(end,match.index)),'([\\s\\S]*?)');names.push(match[1]);end=match.index!+match[0].length}
  parts.push(escape(source.slice(end)))
  return {regex:new RegExp('^'+parts.join('')+'$'),names,value}
})
export function messageText(source?:string):string{
  if(!source)return ''
  if(locale.value!=='en')return source
  if(catalog[source])return catalog[source]
  for(const pattern of patterns){const match=pattern.regex.exec(source);if(match)return format(pattern.value,Object.fromEntries(pattern.names.map((name,index)=>[name,match[index+1]])))}
  return source
}
export async function initializeLanguage(){
  try{const saved=localStorage.getItem(key);if(validPreference(saved))languagePreference.value=saved}catch{}
  try{const info=await bridge()?.info();if(info){if(validPreference(info.language))languagePreference.value=info.language;if(info.systemLocale)systemLanguage.value=info.systemLocale}}catch{}
}
export async function changeLanguage(value:LanguagePreference){
  if(!validPreference(value))throw new Error('语言选项无效')
  const desktop=bridge()
  if(desktop?.setLanguage){await desktop.setLanguage(value);try{localStorage.setItem(key,value)}catch{}}
  else localStorage.setItem(key,value)
  languagePreference.value=value
}
watchEffect(()=>{if(typeof document!=='undefined'){document.documentElement.lang=locale.value;document.title=t('课记 · 课堂录音 AI 笔记')}})
