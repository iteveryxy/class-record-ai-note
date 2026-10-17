import { afterEach,describe,expect,it,vi } from 'vitest'
import { readFileSync,readdirSync } from 'node:fs'
import { changeLanguage,initializeLanguage,languagePreference,locale,messageText,resolveLocale,t } from './i18n'
import english from '../../desktop/src/locales/en.json'

afterEach(()=>{vi.unstubAllGlobals();languagePreference.value='system'})
describe('interface language',()=>{
  it('uses Chinese for Chinese system locales and English for other systems',()=>{
    expect(resolveLocale('system','zh-TW')).toBe('zh-CN')
    expect(resolveLocale('system','en-US')).toBe('en')
    expect(resolveLocale('system','fr-FR')).toBe('en')
    expect(resolveLocale('zh-CN','en-US')).toBe('zh-CN')
    expect(resolveLocale('en','zh-CN')).toBe('en')
  })
  it('persists browser preferences and restores them on initialization',async()=>{
    const values=new Map<string,string>()
    vi.stubGlobal('localStorage',{getItem:(key:string)=>values.get(key),setItem:(key:string,value:string)=>values.set(key,value)})
    await changeLanguage('zh-CN');expect(t('系统设置')).toBe('系统设置')
    languagePreference.value='en';await initializeLanguage()
    expect(locale.value).toBe('zh-CN');expect(values.get('classnotes:language')).toBe('zh-CN')
  })
  it('restores desktop preferences with the system language independently of browser storage',async()=>{
    vi.stubGlobal('localStorage',{getItem:()=>{throw new Error('storage unavailable')}})
    vi.stubGlobal('window',{classnotesDesktop:{info:async()=>({language:'system',systemLocale:'zh-CN'})}})
    await initializeLanguage();expect(locale.value).toBe('zh-CN')
    expect(languagePreference.value).toBe('system')
  })
  it('saves desktop preferences before changing the interface and keeps the previous choice on failure',async()=>{
    const save=vi.fn().mockRejectedValueOnce(new Error('disk full')).mockResolvedValueOnce(undefined)
    vi.stubGlobal('window',{classnotesDesktop:{setLanguage:save}})
    vi.stubGlobal('localStorage',{setItem:()=>{throw new Error('browser storage unavailable')}})
    languagePreference.value='zh-CN'
    await expect(changeLanguage('en')).rejects.toThrow('disk full');expect(locale.value).toBe('zh-CN')
    await changeLanguage('en');expect(locale.value).toBe('en');expect(save).toHaveBeenLastCalledWith('en')
  })
  it('does not change the browser preference if persistence fails',async()=>{
    vi.stubGlobal('localStorage',{setItem:()=>{throw new Error('disk full')}})
    languagePreference.value='zh-CN';await expect(changeLanguage('en')).rejects.toThrow('disk full')
    expect(locale.value).toBe('zh-CN')
  })
  it('formats prompts while preserving supplied course names and unknown provider messages',()=>{
    languagePreference.value='en'
    expect(t('移动{0}的课程',{0:'线性代数 {公式}'})).toBe('Move 线性代数 {公式} to another course')
    expect(messageText('已移至「高等数学」，录音与笔记已保留')).toBe('Moved to “高等数学”. The recording and notes are preserved.')
    expect(messageText('Notion error: invalid_token')).toBe('Notion error: invalid_token')
    expect(messageText('此录音有任务排队或运行，请等待完成后再移动课程')).not.toMatch(/[\u3400-\u9fff]/)
  })
  it('has English entries for every literal template translation and no Chinese in English entries',()=>{
    const catalog:Record<string,string>=english
    for(const name of readdirSync(new URL('./',import.meta.url)).filter(name=>name.endsWith('.vue'))){
      const source=readFileSync(new URL(name,import.meta.url),'utf8')
      for(const match of source.matchAll(/\bt\('([^'\n]+)'/g))expect(catalog[match[1]],name+': '+match[1]).toBeTruthy()
    }
    for(const value of Object.values(catalog))expect(value).not.toMatch(/[\u3400-\u9fff]/)
  })
})
