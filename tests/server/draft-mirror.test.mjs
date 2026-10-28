import test from 'node:test'
import assert from 'node:assert/strict'
import { DraftMirror } from '../../src/lib/draft-mirror.ts'

function fixture() {
  let text='', revision=0, id=0, current=true
  const operations=new Map(), requests=[], notices=[]
  const state=()=>({userId:'owner',conversationId:'room',draft:text||null,revision:`member:${revision}`})
  const backend=async(url,init)=>{
    if(!init?.body)return Response.json(state())
    const body=JSON.parse(init.body);requests.push(body)
    const previous=operations.get(body.clientId)
    if(previous)return Response.json(previous)
    if(body.expectedRevision!==`member:${revision}`)return Response.json({...state(),outcome:'conflict'},{status:409})
    text=body.draft;revision++
    const receipt={...state(),draft:text,clientId:body.clientId,outcome:'applied',ok:true}
    operations.set(body.clientId,receipt)
    return Response.json(receipt)
  }
  let request=backend
  return {requests,notices,state,backend,retire:()=>{current=false},remote:value=>{text=value;revision++},
    transport:fn=>{request=fn},mirror:new DraftMirror('owner','room',()=>current,(...args)=>request(...args),()=>`operation-${++id}`,m=>notices.push(m))}
}

test('browser mirror never rebases an unobserved remote draft',async()=>{
 const f=fixture();f.remote('other device')
 await f.mirror.submit('mine','')
 assert.equal(f.requests.length,0);assert.equal(f.state().draft,'other device');assert.equal(f.notices.length,1)
 await f.mirror.submit('new local edit','mine')
 assert.equal(f.requests.length,0)
})
test('lost acknowledgement retries the immutable flight before sending newer desired text',async()=>{
 const f=fixture();let lose=true
 f.transport(async(...args)=>{const response=await f.backend(...args);if(args[1]?.body&&lose){lose=false;throw Error('lost response')}return response})
 await f.mirror.submit('first','')
 await f.mirror.submit('second','first')
 assert.equal(f.requests.length,3)
 assert.deepEqual(f.requests[0],f.requests[1])
 assert.notEqual(f.requests[1].clientId,f.requests[2].clientId)
 assert.equal(f.requests[2].expectedRevision,'member:1');assert.equal(f.state().draft,'second')
})
test('a failed baseline GET retains the originally observed text through later local edits',async()=>{
 const f=fixture();let fail=true
 f.transport(async(...args)=>{if(!args[1]?.body&&fail){fail=false;throw Error('offline')}return f.backend(...args)})
 await f.mirror.submit('first','')
 await f.mirror.submit('second','first')
 assert.equal(f.state().draft,'second');assert.equal(f.requests.length,1)
})
test('edit-away-edit-back remains new intent and detects a competing native write',async()=>{
 const f=fixture();let release,committed
 const ready=new Promise(resolve=>{committed=resolve})
 f.transport(async(...args)=>{
   const response=await f.backend(...args)
   if(args[1]?.body&&f.requests.length===1){committed();await new Promise(resolve=>{release=resolve})}
   return response
 })
 const first=f.mirror.submit('same','');await ready
 await f.mirror.submit('away','same');await f.mirror.submit('same','away')
 f.remote('native update');release();await first
 assert.equal(f.requests.length,2);assert.notEqual(f.requests[0].clientId,f.requests[1].clientId)
 assert.equal(f.state().draft,'native update');assert.ok(f.notices.some(x=>x.includes('another device')))
})
test('retired browser account cannot post after a delayed baseline response',async()=>{
 const f=fixture()
 f.transport(async(...args)=>{const response=await f.backend(...args);f.retire();return response})
 await f.mirror.submit('private text','')
 assert.equal(f.requests.length,0);assert.equal(f.state().draft,null)
})
test('wrong-scope acknowledgement cannot advance the browser flight',async()=>{
 const f=fixture();let wrong=true
 f.transport(async(...args)=>{
   const response=await f.backend(...args)
   if(args[1]?.body&&wrong){wrong=false;return Response.json({...await response.json(),userId:'other'})}
   return response
 })
 await f.mirror.submit('first','');await f.mirror.submit('second','first')
 assert.equal(f.requests.length,3);assert.deepEqual(f.requests[0],f.requests[1]);assert.equal(f.state().draft,'second')
})
