export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api${path}`, { ...options, headers: {
    ...(options.body && !(options.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}), ...options.headers,
  } })
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new Error(body?.message || `请求失败（HTTP ${response.status}）`)
  }
  return await response.json() as T
}
export const post = <T>(path: string, body: unknown) => api<T>(path, { method: 'POST', body: JSON.stringify(body) })
export const put = <T>(path: string, body: unknown) => api<T>(path, { method: 'PUT', body: JSON.stringify(body) })

export interface UploadProgress { loaded: number; total: number | null; percent: number | null; waitingForServer: boolean }
export function multipartBody(form: FormData): Blob {
  // A composite Blob keeps File data lazy while giving the native proxy the
  // exact encoded length, including multipart headers and text fields.
  const boundary='----classnotes'+crypto.randomUUID().replaceAll('-','')
  const parts:BlobPart[]=[]
  const quote=(value:string)=>value.replaceAll('\r','%0D').replaceAll('\n','%0A').replaceAll('"','%22')
  for(const [name,value] of form.entries()) {
    const disposition=`--${boundary}\r\nContent-Disposition: form-data; name="${quote(name)}"`
    if(typeof value==='string') parts.push(disposition+'\r\n\r\n'+value+'\r\n')
    else {
      const type=/^[\w.+-]+\/[\w.+-]+$/.test(value.type)?value.type:'application/octet-stream'
      parts.push(disposition+`; filename="${quote(value.name)}"\r\nContent-Type: ${type}\r\n\r\n`,value,'\r\n')
    }
  }
  parts.push(`--${boundary}--\r\n`)
  return new Blob(parts,{type:`multipart/form-data; boundary=${boundary}`})
}
export function upload<T>(path: string, body: FormData, onProgress: (progress: UploadProgress) => void): Promise<T> {
  return new Promise((resolve,reject) => {
    const xhr = new XMLHttpRequest()
    let latest: UploadProgress = { loaded: 0, total: null, percent: 0, waitingForServer: false }
    const bridge=typeof window==='undefined'?undefined:window.classnotesDesktop
    const native=!!bridge?.uploadProgressManaged
    const unsubscribe=native?bridge!.onState(state=>{if(state.upload){latest=state.upload;onProgress(latest)}}):undefined
    const report = (event: ProgressEvent, waitingForServer = false) => {
      const total = event.lengthComputable && event.total > 0 ? event.total : null
      latest = { loaded: event.loaded, total, percent: waitingForServer ? 100 : total === null ? null : Math.min(100,Math.floor(event.loaded * 100 / total)), waitingForServer }
      onProgress(latest)
    }
    xhr.upload.addEventListener('progress',event => {if(!native)report(event)})
    xhr.upload.addEventListener('load',event => {if(!native)report(event,true)})
    xhr.open('POST',`/api${path}`)
    xhr.responseType = 'json'
    xhr.timeout = 30 * 60 * 1000
    xhr.addEventListener('load',() => {
      unsubscribe?.()
      if (xhr.status >= 200 && xhr.status < 300) resolve(xhr.response as T)
      else reject(new Error(xhr.response?.message || `上传失败（HTTP ${xhr.status}）`))
    })
    xhr.addEventListener('error',() => {unsubscribe?.();reject(new Error('上传连接中断，文件仍保留在表单中，可重新上传'))})
    xhr.addEventListener('timeout',() => {unsubscribe?.();reject(new Error('上传超时，请检查连接后重试'))})
    xhr.addEventListener('abort',() => {unsubscribe?.();reject(new Error('上传已中止'))})
    onProgress(latest)
    try {
      if(native) {
        const encoded=multipartBody(body)
        xhr.setRequestHeader('Content-Type',encoded.type)
        xhr.setRequestHeader('X-ClassNotes-Upload-Length',String(encoded.size))
        xhr.send(encoded)
      } else xhr.send(body)
    } catch(error){unsubscribe?.();reject(error)}
  })
}
