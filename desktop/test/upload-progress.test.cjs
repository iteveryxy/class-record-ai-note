const {test}=require('node:test')
const assert=require('node:assert/strict')
const {uploadBody}=require('../src/upload-progress.cjs')
test('desktop upload progress preserves every byte and waits for server acknowledgement',async()=>{
  const input=[new Uint8Array([1,2,3,4]),new Uint8Array([5,6,7,8,9,10])]
  const events=[]
  const body=uploadBody(new ReadableStream({start(controller){input.forEach(v=>controller.enqueue(v));controller.close()}}),'10',v=>events.push(v))
  assert.deepEqual([...new Uint8Array(await new Response(body).arrayBuffer())],input.flatMap(v=>[...v]))
  assert.equal(events[0].percent,40)
  assert.deepEqual(events.at(-1),{loaded:10,total:10,percent:100,waitingForServer:true})
})
test('unknown desktop body sizes report bytes without inventing a total',async()=>{
  const events=[]
  const body=uploadBody(new ReadableStream({start(controller){controller.enqueue(new Uint8Array(40));controller.close()}}),null,v=>events.push(v))
  await new Response(body).arrayBuffer()
  assert.equal(events[0].total,null);assert.equal(events[0].percent,null)
  assert.equal(events.at(-1).loaded,40)
})
test('a large Electron body is forwarded in bounded slices as the consumer reads',async()=>{
  const events=[],bytes=new Uint8Array(1024*1024)
  const body=uploadBody(new ReadableStream({start(controller){controller.enqueue(bytes);controller.close()}}),String(bytes.length),v=>events.push(v))
  const reader=body.getReader()
  const first=await reader.read()
  assert.equal(first.value.byteLength,65536)
  assert.ok(events.at(-1).loaded<bytes.length)
  await reader.cancel()
})
