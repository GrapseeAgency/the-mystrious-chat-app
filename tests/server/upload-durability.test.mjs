import test from 'node:test'
import assert from 'node:assert/strict'
import { randomUUID, createHash } from 'node:crypto'
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
const receipts = await load('src/lib/upload-operation.ts', { createHash }, '({uploadOperation,replayUploadOperation,commitUploadOperation,UploadOperationError})')
const request = (body, headers = {}) => new Request('https://upload.test/api/uploads', {method:'POST',body:JSON.stringify(body),headers})
const document = bytes => ({dataUrl:'data:text/plain;base64,' + Buffer.from(bytes).toString('base64')})
async function fixture(run, overrides = {}) {
  const dir = await fs.mkdtemp(path.join(tmpdir(), 'upload-contract-'))
  const db = new PrismaClient({datasourceUrl:`file:${path.join(dir,'test.sqlite')}`})
  try {
    await db.$executeRawUnsafe('CREATE TABLE UploadedFile (name TEXT PRIMARY KEY, mime TEXT NOT NULL, bytes BLOB NOT NULL, size INTEGER NOT NULL, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)')
    await db.$executeRawUnsafe('CREATE TABLE UploadOperation (clientKey TEXT PRIMARY KEY, contentHash TEXT NOT NULL, name TEXT NOT NULL, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)')
    const bindings = { db, mkdir:fs.mkdir, writeFile:fs.writeFile, readFile:fs.readFile, rename:fs.rename, unlink:fs.unlink, path, randomUUID, NextResponse:Response, DOC_MAX_BYTES,
      UPLOADS_DIR:path.join(dir,'uploads'), UPLOAD_MIME:{txt:'text/plain',png:'image/png'},
      strField:v=>typeof v==='string'?v.trim():'', ...input, ...receipts,
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
test('a lost-ack retry with a stable clientKey replays the same file instead of storing a second orphan',()=>fixture(async({db,post})=>{
  const upload = document('retry me')
  const first = await post(request({...upload, clientKey:'stable-upload-key-12345'}))
  assert.equal(first.status,201)
  const {filePath} = await first.json()
  // The HTTP response above is "lost": the native outbox re-POSTs the byte-identical payload.
  const retry = await post(request({...upload, clientKey:'stable-upload-key-12345'}))
  assert.equal(retry.status,201)
  assert.equal((await retry.json()).filePath,filePath)
  assert.equal(await db.uploadedFile.count(),1)
  const receipts = await db.$queryRawUnsafe('SELECT clientKey, contentHash, name FROM UploadOperation')
  assert.equal(receipts.length,1); assert.equal(receipts[0].name,filePath)
}))
test('a clientKey reused for different bytes is a conflict, never a silent second acknowledgement',()=>fixture(async({db,post})=>{
  const first = await post(request({...document('first bytes'), clientKey:'conflicting-key-123456'}))
  assert.equal(first.status,201)
  const second = await post(request({...document('different bytes'), clientKey:'conflicting-key-123456'}))
  assert.equal(second.status,409)
  assert.equal(await db.uploadedFile.count(),1)
}))
test('legacy callers without a clientKey keep the receipt-less path',()=>fixture(async({db,post})=>{
  const response = await post(request(document('legacy')))
  assert.equal(response.status,201)
  const receipts = await db.$queryRawUnsafe('SELECT COUNT(*) as count FROM UploadOperation').catch(()=>({count:0}))
  assert.equal(Number(receipts[0]?.count ?? 0),0)
  assert.equal(await db.uploadedFile.count(),1)
}))
test('a malformed clientKey is rejected before anything is stored',()=>fixture(async({db,post})=>{
  for (const clientKey of ['short','has spaces in it','x'.repeat(129),42]) {
    const response = await post(request({...document('bytes'), clientKey}))
    assert.equal(response.status,400,String(clientKey))
  }
  assert.equal(await db.uploadedFile.count(),0)
}))
test('images and audio carry the decoded media ceiling the encoded cap implies',()=>fixture(async({post,db})=>{
  // R42 parity: one decoded byte past the 3,375,000 media bound is refused
  // (the 4.5M encoded cap fires first on this shape; the decoded check is
  // defense-in-depth mirroring the native bounded readers).
  const bytes = Buffer.alloc(3_375_001, 1)
  const encoded = bytes.toString('base64').replace(/=+$/,'')
  const response = await post(request({dataUrl:`data:image/jpeg;base64,${encoded}`}))
  assert.equal(response.status,413)
  assert.equal(await db.uploadedFile.count(),0)
  const ok = await post(request({dataUrl:'data:image/jpeg;base64,'+Buffer.alloc(16,1).toString('base64')}))
  assert.equal(ok.status,201)
}))
test('an upload receipt commit that loses a duplicate-key race resolves to the winner without a second file',async()=>{
  const dir = await fs.mkdtemp(path.join(tmpdir(),'upload-race-'))
  const db = new PrismaClient({datasourceUrl:`file:${path.join(dir,'db.sqlite')}`})
  try {
    await db.$executeRawUnsafe('CREATE TABLE UploadedFile (name TEXT PRIMARY KEY, mime TEXT NOT NULL, bytes BLOB NOT NULL, size INTEGER NOT NULL, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)')
    await db.$executeRawUnsafe('CREATE TABLE UploadOperation (clientKey TEXT PRIMARY KEY, contentHash TEXT NOT NULL, name TEXT NOT NULL, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)')
    const op = receipts.uploadOperation({clientKey:'race-key-1234567890'},'text/plain',Buffer.from('same bytes').toString('base64'))
    // A winner receipt already committed for the same key+hash, plus its file.
    await db.$executeRawUnsafe(`INSERT INTO UploadOperation (clientKey, contentHash, name) VALUES ('race-key-1234567890', '${op.contentHash}', 'winner.txt')`)
    await db.uploadedFile.create({data:{name:'winner.txt',mime:'text/plain',bytes:new Uint8Array(Buffer.from('same bytes')),size:10}})
    // The racing commit loses its transaction to the duplicate key (its store
    // writes roll back with it) - it must resolve to the winner's receipt,
    // not throw and not leave a second file behind.
    let stored = 0
    const racing = {
      $queryRaw: (...args) => db.$queryRaw(...args),
      $transaction: async () => { throw Error('UNIQUE constraint failed: UploadOperation.clientKey') },
    }
    await receipts.commitUploadOperation(racing, op, 'loser.txt', async () => { stored++ })
    assert.equal(stored,0)
    assert.equal(await db.uploadedFile.count(),1)
    const rows = await db.$queryRawUnsafe('SELECT name FROM UploadOperation')
    assert.equal(rows.length,1); assert.equal(rows[0].name,'winner.txt')
  } finally { await db.$disconnect(); await fs.rm(dir,{recursive:true,force:true}) }
})
test('a receipt read outage is retryable and stores nothing',()=>fixture(async({post})=>{
  let stored = 0
  const response = await post(request({...document('bytes'), clientKey:'upgrade-key-12345678'}))
  assert.equal(response.status,503); assert.equal(response.headers.get('retry-after'),'3')
  assert.equal(stored,0)
},{db:{$queryRaw: async()=>{throw Error('no such table: UploadOperation')}, uploadedFile:{upsert:async()=>{stored++; throw Error('must not store')}}}}))
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
