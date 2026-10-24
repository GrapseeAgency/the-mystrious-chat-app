import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import * as fs from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const DOC_MAX_BYTES = 10 * 1024 * 1024
async function load(file, bindings, expression) {
  const text = (await fs.readFile(new URL(`../../${file}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, {mode:'transform'}) + `;return ${expression}`)(...Object.values(bindings))
}
const input = await load('src/lib/upload-input.ts', { DOC_MAX_BYTES }, '({readUploadBody,decodeUploadBase64,UploadInputError,MAX_UPLOAD_JSON_BYTES})')
const request = (body, headers = {}) => new Request('https://upload.test/api/uploads', {method:'POST',body:JSON.stringify(body),headers})
const document = bytes => ({dataUrl:'data:text/plain;base64,' + Buffer.from(bytes).toString('base64')})
async function fixture(run, overrides = {}) {
  const dir = await fs.mkdtemp(path.join(tmpdir(), 'upload-contract-'))
  const db = new PrismaClient({datasourceUrl:`file:${path.join(dir,'test.sqlite')}`})
  try {
    await db.$executeRawUnsafe('CREATE TABLE UploadedFile (name TEXT PRIMARY KEY, mime TEXT NOT NULL, bytes BLOB NOT NULL, size INTEGER NOT NULL, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)')
    const bindings = { db, mkdir:fs.mkdir, writeFile:fs.writeFile, readFile:fs.readFile, rename:fs.rename, unlink:fs.unlink, path, randomUUID, NextResponse:Response, DOC_MAX_BYTES,
      UPLOADS_DIR:path.join(dir,'uploads'), UPLOAD_MIME:{txt:'text/plain',png:'image/png'},
      strField:v=>typeof v==='string'?v.trim():'', ...input,
      console:{error:()=>{}}, ...overrides }
    const post = await load('src/app/api/uploads/route.ts',bindings,'POST')
    const get = await load('src/app/api/uploads/[file]/route.ts',bindings,'GET')
    await run({db,post,get,dir})
  } finally { await db.$disconnect(); await fs.rm(dir,{recursive:true,force:true}) }
}
test('upload acknowledgement follows durable storage and survives loss of its disk cache',()=>fixture(async({db,post,get,dir})=>{
  const response = await post(request(document('durable contents')))
  assert.equal(response.status,201); assert.match(response.headers.get('cache-control'),/private.*no-store/)
  const {filePath} = await response.json()
  const saved = await db.uploadedFile.findUnique({where:{name:filePath}})
  assert.equal(Buffer.from(saved.bytes).toString(),'durable contents')
  let download=await get(new Request('https://upload.test'),{params:Promise.resolve({file:filePath})})
  assert.equal(await download.text(),'durable contents');assert.match(download.headers.get('cache-control'),/no-store/)
  await fs.rm(path.join(dir,'uploads'),{recursive:true,force:true})
  download=await get(new Request('https://upload.test'),{params:Promise.resolve({file:filePath})})
  assert.equal(await download.text(),'durable contents');assert.equal(download.headers.get('x-content-type-options'),'nosniff')
}))
test('durable write failure returns retryable failure without writing an ephemeral-only file',()=>{
  let diskWrites=0
  return fixture(async({post,db})=>{
    const response=await post(request(document('must not disappear')))
    assert.equal(response.status,503);assert.equal(response.headers.get('retry-after'),'3')
    assert.equal((await response.json()).filePath,undefined)
    assert.equal(diskWrites,0);assert.equal(await db.uploadedFile.count(),0)
  },{db:{uploadedFile:{upsert:async()=>{throw Error('synthetic database outage')}}},writeFile:async()=>{diskWrites++}})
})
test('disk cache failure does not deny a durable upload or lose its bytes',()=>fixture(async({post,get,db})=>{
  const response=await post(request(document('database is authoritative')))
  assert.equal(response.status,201);const {filePath}=await response.json()
  assert.equal(await db.uploadedFile.count(),1)
  const download=await get(new Request('https://upload.test'),{params:Promise.resolve({file:filePath})})
  assert.equal(await download.text(),'database is authoritative')
},{writeFile:async()=>{throw Error('synthetic disk full')}}))
test('malformed base64 is not acknowledged and native padded plus valid unpadded encodings work',()=>fixture(async({post,db})=>{
  for(const encoded of ['A','AB==','YQ=','YQ===','====','Y=Q=','YQ==AA']) {
    assert.equal((await post(request({dataUrl:'data:text/plain;base64,'+encoded}))).status,400,encoded)
  }
  assert.equal(await db.uploadedFile.count(),0)
  for(const encoded of ['YQ==','YQ']) assert.equal((await post(request({dataUrl:'data:text/plain;base64,'+encoded}))).status,201)
}))
test('bounded request reader handles declared, chunked, empty-progress, JSON and stalled bodies',async()=>{
  const rejected = status => error => error instanceof input.UploadInputError && error.status === status
  await assert.rejects(input.readUploadBody(request({}, {'content-length':'1000'}),64),rejected(413))
  const streamed=stream=>new Request('https://upload.test',{method:'POST',body:stream,duplex:'half'})
  await assert.rejects(input.readUploadBody(streamed(new ReadableStream({start(c){c.enqueue(new Uint8Array(65));c.close()}})),64),rejected(413))
  await assert.rejects(input.readUploadBody(request([]),64),rejected(400))
  await assert.rejects(input.readUploadBody(streamed(new ReadableStream({pull(c){c.enqueue(new Uint8Array())}})),64),rejected(400))
  let cancelled=false
  await assert.rejects(input.readUploadBody(streamed(new ReadableStream({cancel(){cancelled=true}})),64,5),rejected(408))
  assert.equal(cancelled,true)
  assert.deepEqual(await input.readUploadBody(request({dataUrl:'small'}),64),{dataUrl:'small'})
})
test('concurrent uploads are bounded and slots are released after completion',async()=>{
  let release;const hold=new Promise(resolve=>{release=resolve})
  await fixture(async({post})=>{
    const first=post(request(document('one'))),second=post(request(document('two')))
    const busy=await post(request(document('three')))
    assert.equal(busy.status,429);assert.equal(busy.headers.get('retry-after'),'2')
    release();assert.equal((await first).status,201);assert.equal((await second).status,201)
    assert.equal((await post(request(document('after')))).status,201)
  },{readUploadBody:async req=>{await hold;return input.readUploadBody(req)}})
})

test('a durable-store read outage is retryable rather than a permanent missing attachment',()=>fixture(async({get})=>{
  const response=await get(new Request('https://upload.test'),{params:Promise.resolve({file:'missing.txt'})})
  assert.equal(response.status,503);assert.equal(response.headers.get('retry-after'),'3')
  assert.match(response.headers.get('cache-control'),/no-store/)
},{db:{uploadedFile:{findUnique:async()=>{throw Error('synthetic database busy')}}}}))
test('download framing uses actual stored bytes and traversal is refused',()=>fixture(async({db,get})=>{
  await db.uploadedFile.create({data:{name:'known.txt',mime:'text/plain',bytes:new Uint8Array(Buffer.from('hello')),size:999}})
  const response=await get(new Request('https://upload.test'),{params:Promise.resolve({file:'known.txt'})})
  assert.equal(response.headers.get('content-length'),'5');assert.equal(await response.text(),'hello')
  assert.equal((await get(new Request('https://upload.test'),{params:Promise.resolve({file:'../known.txt'})})).status,404)
  assert.equal((await get(new Request('https://upload.test'),{params:Promise.resolve({file:'absent.txt'})})).status,404)
}))
