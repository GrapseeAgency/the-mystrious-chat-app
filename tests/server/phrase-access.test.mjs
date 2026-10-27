import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
const {PrismaClient}=createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path,bindings,expression) {
 const text=(await readFile(new URL(`../../${path}`,import.meta.url),'utf8')).replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm,'').replace(/^export /gm,'')
 return new Function(...Object.keys(bindings),stripTypeScriptTypes(text)+`;return ${expression}`)(...Object.values(bindings))
}
async function fixture(run) {
 const dir=await mkdtemp(join(tmpdir(),'phrase-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
 try {
  for(const sql of [
   'CREATE TABLE User(id TEXT PRIMARY KEY, sessionTokenHash TEXT, legacyTokenHashes TEXT)',
   'CREATE TABLE QuickPhrase(id TEXT PRIMARY KEY, userId TEXT, text TEXT, position INTEGER DEFAULT 0, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
  ]) await db.$executeRawUnsafe(sql)
  for(const id of ['me','other']) await db.$executeRawUnsafe('INSERT INTO User VALUES (?,?,?)',id,hashSessionToken(`synthetic-${id}`),'[]')
  await db.quickPhrase.create({data:{id:'p1',userId:'me',text:'On my way',position:0}})
  const requireActionOwner=await load('src/lib/wallet-authorization.ts',{db,bearerToken,tokenAccepted},'requireActionOwner')
  const h=await load('src/app/api/users/[id]/phrases/route.ts',{db,requireActionOwner,NextResponse:{json:Response.json},safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():''},'({GET,POST,DELETE})')
  await run(db,h)
 }finally{await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
const ctx=(id='me')=>({params:Promise.resolve({id})})
const req=(method,token='synthetic-me',body={text:' Hello '},id='p1')=>new Request(`https://phrase.test/?phraseId=${id}`,{method,headers:token?{Authorization:`Bearer ${token}`}:{},...(method==='GET'?{}:{body:JSON.stringify(body)})})
test('phrase GET/create/delete reject missing or other-owner credentials before touching private rows',()=>fixture(async(db,h)=>{
 for(const method of ['GET','POST','DELETE']) {
  assert.equal((await h[method](req(method,null),ctx())).status,401)
  assert.equal((await h[method](req(method,'synthetic-other'),ctx())).status,403)
 }
 assert.equal(await db.quickPhrase.count(),1)
 const page=await h.GET(req('GET'),ctx());assert.match(page.headers.get('cache-control'),/private.*no-store/)
 assert.equal(page.headers.get('vary'),'Authorization');assert.equal((await page.json()).phrases[0].id,'p1')
}))
test('create returns the actual row; deletion receipt identifies the exact owned phrase',()=>fixture(async(db,h)=>{
 const created=await h.POST(req('POST'),ctx());assert.equal(created.status,201)
 const {phrase}=await created.json();assert.ok(phrase.id);assert.equal(phrase.text,'Hello');assert.equal(phrase.position,1)
 assert.equal((await h.DELETE(req('DELETE','synthetic-other'),ctx('other'))).status,404)
 assert.equal(await db.quickPhrase.count(),2)
 const deleted=await h.DELETE(req('DELETE'),ctx());assert.deepEqual(await deleted.json(),{ok:true,phraseId:'p1',userId:'me',deleted:true})
 assert.equal(await db.quickPhrase.count(),1)
 assert.equal((await h.DELETE(req('DELETE'),ctx())).status,404)
}))
test('invalid drafts do not create rows and concurrent appends cannot exceed the twelve-phrase quota',()=>fixture(async(db,h)=>{
 for(const text of ['', ' '.repeat(4), 'x'.repeat(121), null]) assert.equal((await h.POST(req('POST','synthetic-me',{text}),ctx())).status,400)
 for(let i=1;i<11;i++)await db.quickPhrase.create({data:{userId:'me',text:`Phrase ${i}`,position:i}})
 const results=await Promise.all([h.POST(req('POST'),ctx()),h.POST(req('POST'),ctx())])
 assert.ok(results.every(r=>[201,400,409].includes(r.status)))
 assert.ok(await db.quickPhrase.count({where:{userId:'me'}})<=12)
 assert.ok(results.filter(r=>r.status===201).length<=1)
}))
