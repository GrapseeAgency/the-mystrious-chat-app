import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path,db,auth=async(req,user)=>req.headers.get('authorization')==='Bearer '+user?null:Response.json({},{status:403})) {
  const source=stripTypeScriptTypes(await readFile(new URL('../../src/app/api/hub/'+path+'/route.ts',import.meta.url),'utf8')).replace(/^import .*$/gm,'').replace(/export /g,'')
  const deps={db,NextResponse:{json:Response.json},safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',dayKey:d=>d.toISOString().slice(0,10),requireActionOwner:auth}
  return new Function(...Object.keys(deps),source+';return {GET:typeof GET==="function"?GET:null,POST:typeof POST==="function"?POST:null,PATCH:typeof PATCH==="function"?PATCH:null,DELETE:typeof DELETE==="function"?DELETE:null}')(...Object.values(deps))
}
const req=(method,user='me',body={},token=user)=>new Request('https://hub.test?userId='+user,{method,headers:{authorization:'Bearer '+token},...(['POST','PATCH'].includes(method)?{body:JSON.stringify({userId:user,...body})}:{})})
const ctx=id=>({params:Promise.resolve({id})})
async function fixture(run) {
 const dir=await mkdtemp(join(tmpdir(),'personal-hub-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
 try {
  for(const sql of [
   'CREATE TABLE User(id TEXT PRIMARY KEY)',
   'CREATE TABLE UserWallet(userId TEXT PRIMARY KEY,coins INTEGER NOT NULL DEFAULT 0,gems INTEGER NOT NULL DEFAULT 0,streak INTEGER NOT NULL DEFAULT 0,lastCheckIn DATETIME)',
   'CREATE TABLE WalletLedger(id TEXT PRIMARY KEY,userId TEXT,kind TEXT,asset TEXT,amount INTEGER,note TEXT,counterpartyId TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
   'CREATE TABLE HubTask(id TEXT PRIMARY KEY,ownerId TEXT,title TEXT,status TEXT NOT NULL DEFAULT "todo",createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updatedAt DATETIME NOT NULL)',
   "INSERT INTO User VALUES ('me'),('other')",
   "INSERT INTO UserWallet(userId,coins) VALUES ('other',900)",
   "INSERT INTO WalletLedger(id,userId,kind,asset,amount,note) VALUES ('foreign','other','transfer_in','PC',900,'private financial note')",
  ]) await db.$executeRawUnsafe(sql)
  await db.hubTask.create({data:{id:'secret',ownerId:'other',title:'Private task'}})
  await run(db)
 } finally {await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
test('wallet and every personal-task route reject unverified actors before all data access',async()=>{
 let touched=0
 const db=new Proxy({}, {get:()=>{touched++;throw Error('unexpected data access')}})
 for(const [path,methods] of [['wallet',['GET']],['tasks',['GET','POST']],['tasks/[id]',['PATCH','DELETE']]]) {
  const handlers=await load(path,db,async()=>Response.json({},{status:401}))
  for(const method of methods) assert.equal((await handlers[method](req(method,'other',{title:'attack'}),ctx('secret'))).status,401)
 }
 assert.equal(touched,0)
})
test('knowing an owner ID cannot read balances, notes or tasks and cannot mutate that owner',()=>fixture(async db=>{
 const wallet=await load('wallet',db),tasks=await load('tasks',db),task=await load('tasks/[id]',db)
 assert.equal((await wallet.GET(req('GET','other',{},'me'))).status,403)
 assert.equal((await tasks.GET(req('GET','other',{},'me'))).status,403)
 assert.equal((await tasks.POST(req('POST','other',{title:'Injected'},'me'))).status,403)
 assert.equal((await task.PATCH(req('PATCH','other',{title:'Injected'},'me'),ctx('secret'))).status,403)
 assert.equal((await task.DELETE(req('DELETE','other',{},'me'),ctx('secret'))).status,403)
 assert.equal((await task.PATCH(req('PATCH','me',{title:'Injected'}),ctx('secret'))).status,403)
 assert.equal((await task.DELETE(req('DELETE','me'),ctx('secret'))).status,403)
 assert.equal((await db.hubTask.findUnique({where:{id:'secret'}})).title,'Private task')
 const page=await (await tasks.GET(req('GET'))).json();assert.equal(page.userId,'me');assert.deepEqual(page.tasks,[])
 const response=await wallet.GET(req('GET'));assert.equal(response.headers.get('cache-control'),'private, no-store')
 const mine=await response.json();assert.equal(mine.wallet.userId,'me');assert.deepEqual(mine.ledger,[])
 assert.ok(!JSON.stringify(mine).includes('private financial note'))
}))
test('valid personal-task intent returns canonical owner/state and a precise deletion receipt',()=>fixture(async db=>{
 const tasks=await load('tasks',db),task=await load('tasks/[id]',db)
 const create=await tasks.POST(req('POST','me',{title:'  My task  '}));assert.equal(create.status,201)
 const row=(await create.json()).task;assert.equal(row.ownerId,'me');assert.equal(row.title,'My task')
 const moved=(await (await task.PATCH(req('PATCH','me',{status:'done'}),ctx(row.id))).json()).task
 assert.equal(moved.ownerId,'me');assert.equal(moved.status,'done');assert.equal(moved.title,'My task')
 assert.deepEqual(await (await task.DELETE(req('DELETE'),ctx(row.id))).json(),{ok:true,userId:'me',taskId:row.id})
 assert.equal(await db.hubTask.count({where:{ownerId:'other'}}),1)
}))
test('wallet balance and ledger use one transaction; failed ledger reads roll initial wallet creation back',()=>fixture(async db=>{
 let transactions=0
 const broken={$transaction:(fn,options)=>{transactions++;assert.equal(options.isolationLevel,'Serializable');return db.$transaction(tx=>fn(new Proxy(tx,{get:(target,key)=>key==='walletLedger'?{findMany:async()=>{throw Error('ledger fault')}}:target[key]})),options)}}
 const route=await load('wallet',broken)
 await assert.rejects(route.GET(req('GET')),/ledger fault/)
 assert.equal(transactions,1);assert.equal(await db.userWallet.count({where:{userId:'me'}}),0)
}))
