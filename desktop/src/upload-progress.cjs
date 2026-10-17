// Measure the actual multipart bytes forwarded by the desktop API proxy.
function uploadBody(body, length, report, now=Date.now) {
  const parsed=Number(length)
  const total=Number.isSafeInteger(parsed)&&parsed>0?parsed:null
  let loaded=0,last=-Infinity
  const reader=body.getReader()
  let current,offset=0
  return new ReadableStream({
    async pull(controller) {
      try {
      while(!current||offset===current.byteLength) {
        const next=await reader.read()
        if(next.done) {
          report({loaded,total,percent:100,waitingForServer:true})
          reader.releaseLock();controller.close();return
        }
        current=next.value;offset=0
      }
      // Electron can supply a whole multipart body as one large buffer. Feed
      // bounded slices under the HTTP consumer's backpressure instead of
      // reporting that entire buffer as transferred in a single callback.
      const end=Math.min(offset+65536,current.byteLength)
      const piece=current.subarray(offset,end)
      offset=end;loaded+=piece.byteLength
      const time=now()
      if(time-last>=100||loaded===total) {
        report({loaded,total,percent:total===null?null:Math.min(100,Math.floor(loaded*100/total)),waitingForServer:false})
        last=time
      }
      controller.enqueue(piece)
      } catch(error){reader.releaseLock();controller.error(error)}
    },
    async cancel(reason){try{await reader.cancel(reason)}finally{reader.releaseLock()}},
  })
}
module.exports={uploadBody}
