import { afterEach, describe, expect, it, vi } from 'vitest'
import { upload, multipartBody, type UploadProgress } from './api'

class Request extends EventTarget {
  static current: Request
  upload = new EventTarget()
  status=0; response: unknown=null; responseType=''; timeout=0; sent: unknown
  constructor(){super();Request.current=this}
  open(){} send(body:unknown){this.sent=body}
  setRequestHeader(){}
}
function progress(loaded:number,total:number,lengthComputable=true){return Object.assign(new Event('progress'),{loaded,total,lengthComputable})}
afterEach(()=>vi.unstubAllGlobals())
describe('upload lifecycle',()=>{
  it('encodes Chinese filenames and binary audio with an exact lazily composed body length',async()=>{
    const form=new FormData();form.append('title','导数与连续\n课堂');form.append('course','高等数学')
    const bytes=new Uint8Array([0,255,13,10,1,128])
    form.append('file',new File([bytes],'课堂"录音.wav',{type:'audio/wav'}))
    const encoded=multipartBody(form)
    expect(encoded.size).toBe((await encoded.arrayBuffer()).byteLength)
    const recovered=await new Response(encoded).formData()
    expect(recovered.get('title')).toBe('导数与连续\n课堂')
    expect(recovered.get('course')).toBe('高等数学')
    expect((recovered.get('file') as File).name).toBe('课堂"录音.wav')
    expect(new Uint8Array(await (recovered.get('file') as File).arrayBuffer())).toEqual(bytes)
  })
  it('uses desktop transport bytes rather than an earlier renderer transfer event',async()=>{
    vi.stubGlobal('XMLHttpRequest',Request)
    let notify:(state:{upload?:UploadProgress})=>void=()=>{}
    const unsubscribe=vi.fn()
    vi.stubGlobal('window',{classnotesDesktop:{uploadProgressManaged:true,onState:(callback:typeof notify)=>{notify=callback;return unsubscribe}}})
    const values:UploadProgress[]=[]
    const result=upload('/recordings',new FormData(),v=>values.push(v))
    notify({upload:{loaded:25,total:100,percent:25,waitingForServer:false}})
    Request.current.upload.dispatchEvent(progress(100,100))
    expect(values.at(-1)?.percent).toBe(25)
    notify({upload:{loaded:100,total:100,percent:100,waitingForServer:true}})
    Request.current.status=201;Request.current.response={id:'saved'};Request.current.dispatchEvent(new Event('load'))
    await result;expect(unsubscribe).toHaveBeenCalledOnce()
  })
  it('reports sent bytes and waits for server persistence after transfer reaches 100%',async()=>{
    vi.stubGlobal('XMLHttpRequest',Request)
    const values:UploadProgress[]=[]
    let settled=false
    const form=new FormData()
    const result=upload<{id:string}>('/recordings',form,v=>values.push(v)).then(v=>{settled=true;return v})
    const request=Request.current
    expect(request.sent).toBe(form)
    request.upload.dispatchEvent(progress(25,100))
    expect(values.at(-1)).toMatchObject({loaded:25,total:100,percent:25,waitingForServer:false})
    request.upload.dispatchEvent(Object.assign(new Event('load'),{loaded:100,total:100,lengthComputable:true}))
    await Promise.resolve()
    expect(settled).toBe(false)
    expect(values.at(-1)).toMatchObject({percent:100,waitingForServer:true})
    request.status=201;request.response={id:'saved'};request.dispatchEvent(new Event('load'))
    expect(await result).toEqual({id:'saved'})
  })
  it('keeps unknown totals indeterminate and exposes server failures',async()=>{
    vi.stubGlobal('XMLHttpRequest',Request)
    const values:UploadProgress[]=[]
    const result=upload('/recordings',new FormData(),v=>values.push(v))
    Request.current.upload.dispatchEvent(progress(40,0,false))
    expect(values.at(-1)).toMatchObject({loaded:40,total:null,percent:null})
    Request.current.status=413;Request.current.response={message:'文件过大'};Request.current.dispatchEvent(new Event('load'))
    await expect(result).rejects.toThrow('文件过大')
  })
  it('rejects interrupted transfers instead of reporting upload success',async()=>{
    vi.stubGlobal('XMLHttpRequest',Request)
    const result=upload('/recordings',new FormData(),()=>{})
    Request.current.dispatchEvent(new Event('error'))
    await expect(result).rejects.toThrow('上传连接中断')
  })
})
