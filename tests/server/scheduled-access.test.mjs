import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
import { messageDeliveryDecision, scheduledDeliveryDecision } from '../../src/lib/scheduled-delivery.ts'
const {PrismaClient,Prisma}=createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path, bindings, expression) {
 const text=(await readFile(new URL(`../../${path}`,import.meta.url),'utf8')).replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm,'').replace(/^export /gm,'')
 return new Function(...Object.keys(bindings),stripTypeScriptTypes(text,{mode:'transform'})+`;return ${expression}`)(...Object.values(bindings))
}
async function fixture(run) {
 const dir=await mkdtemp(join(tmpdir(),'scheduled-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
 try {
  // Real SQLite tables with the generated model's complete scalar columns.
  for(const name of ['User','Conversation','ConversationParticipant','UserBlock','Message','ScheduledMessage']) {
   const model=Prisma.dmmf.datamodel.models.find(m=>m.name===name)
   const columns=model.fields.filter(f=>f.kind!=='object'&&!f.isList).map(f=>{
    const type=['Int','Boolean','BigInt'].includes(f.type)?'INTEGER':f.type==='Float'?'REAL':f.type==='DateTime'?'DATETIME':'TEXT'
    let def=''
    if(f.default!==undefined) {
     if(typeof f.default==='string') def=` DEFAULT '${f.default.replaceAll("'","''")}'`
     else if(typeof f.default==='number'||typeof f.default==='boolean')def=` DEFAULT ${Number(f.default)}`
     else if(f.default?.name==='now')def=' DEFAULT CURRENT_TIMESTAMP'
    }
    return `"${f.name}" ${type}${f.isId?' PRIMARY KEY':''}${def}`
   })
   await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
  }
  for(const id of ['me','other']) await db.$executeRawUnsafe('INSERT INTO User(id,sessionTokenHash,legacyTokenHashes) VALUES(?,?,?)',id,hashSessionToken(`synthetic-${id}`),'[]')
  for(const id of ['room','second']) await db.conversation.create({data:{id,isGroup:true}})
  for(const [id,userId,conversationId]of [['p1','me','room'],['p2','other','room'],['p3','other','second']]) await db.conversationParticipant.create({data:{id,userId,conversationId,lastReadAt:new Date('2026-01-01')}})
  const helper=await load('src/lib/scheduled-operation.ts',{messageDeliveryDecision},'({ScheduledError,scheduledErrorResponse,scheduledHeaders,scheduledPage,createScheduled,cancelScheduled,scheduledDispatchPage})')
  const requireActionOwner=await load('src/lib/wallet-authorization.ts',{db,bearerToken,tokenAccepted},'requireActionOwner')
  const bindings={db,...helper,requireActionOwner,NextResponse:{json:Response.json},safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',MESSAGE_MAX:2000,
   parseIsoDate:v=>typeof v==='string'&&Number.isFinite(Date.parse(v))?new Date(v):null}
  const base=await load('src/app/api/conversations/[id]/scheduled/route.ts',bindings,'({GET,POST})')
  const cancel=await load('src/app/api/scheduled/[id]/route.ts',bindings,'DELETE')
  const events=[]
  const dispatch=await load('src/app/api/maintenance/dispatch/route.ts',{...bindings,scheduledDeliveryDecision,process:{env:{CRON_SECRET:'synthetic'}},MESSAGE_FULL_INCLUDE:{},
   mapMessage:row=>row, memberIdsOf:room=>db.conversationParticipant.findMany({where:{conversationId:room}}).then(rows=>rows.map(r=>r.userId)),
   notifySocket:async(...args)=>events.push(args),console:{error:()=>{}}},'POST')
  const create=(data={})=>db.scheduledMessage.create({data:{id:'queued',conversationId:'room',senderId:'me',content:'Hello',scheduledAt:new Date(Date.now()+3600000),...data}})
  await run(db,{...base,DELETE:cancel,dispatch,events,create,helper})
 } finally {await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
const ctx=(id='room')=>({params:Promise.resolve({id})})
const req=(method,owner='me',token='synthetic-me',body={},cursor=null)=>new Request(`https://schedule.test/?userId=${owner}${cursor?'&cursor='+encodeURIComponent(cursor):''}`,{method,headers:token?{Authorization:`Bearer ${token}`}:{},...(method==='GET'?{}:{body:JSON.stringify({senderId:owner,requesterId:owner,content:' Hello ',scheduledAt:new Date(Date.now()+3600000).toISOString(),...body})})})
const tick=(cursor=null)=>new Request(`https://schedule.test/?${cursor?'cursor='+encodeURIComponent(cursor):''}`,{method:'POST',headers:{'x-pulse-key':'synthetic'}})
test('scheduled read/create/cancel bind owner credentials before dispatch',()=>fixture(async(db,h)=>{
 await h.create()
 for(const method of ['GET','POST','DELETE']) {
  assert.equal((await h[method](req(method,'me',null),ctx(method==='DELETE'?'queued':'room'))).status,401)
  assert.equal((await h[method](req(method,'me','synthetic-other'),ctx(method==='DELETE'?'queued':'room'))).status,403)
 }
 assert.equal(await db.scheduledMessage.count(),1)
 assert.equal((await h.DELETE(req('DELETE','other','synthetic-other'),ctx('queued'))).status,403)
}))
test('paged list covers tied timestamps, excludes other owners/sent rows and survives deleting its cursor row',()=>fixture(async(db,h)=>{
 const at=new Date(Date.now()+3600000)
 for(let i=0;i<71;i++) await h.create({id:`s${String(i).padStart(3,'0')}`,scheduledAt:at,...(i===69?{cancelledAt:new Date(),cancelledReason:'blocked'}:{})})
 await h.create({id:'foreign',senderId:'other'});await h.create({id:'sent',sentAt:new Date()})
 const first=await h.GET(req('GET'),ctx());assert.equal(first.status,200)
 assert.match(first.headers.get('cache-control'),/private.*no-store/);assert.equal(first.headers.get('vary'),'Authorization')
 const page=await first.json();assert.equal(page.scheduleVersion,1);assert.equal(page.userId,'me');assert.equal(page.conversationId,'room');assert.equal(page.items.length,50);assert.equal(page.hasMore,true)
 assert.equal((await h.DELETE(req('DELETE'),ctx(page.items.at(-1).id))).status,200)
 const last=await(await h.GET(req('GET','me','synthetic-me',{},page.nextCursor),ctx())).json()
 assert.equal(last.items.length,21);assert.equal(last.hasMore,false);assert.equal(last.nextCursor,null);assert.ok(last.items.some(r=>r.cancelledReason==='blocked'))
 assert.equal(new Set([...page.items,...last.items].map(r=>r.id)).size,71)
 assert.equal((await h.GET(req('GET','me','synthetic-me',{},'invalid'),ctx())).status,400)
}))
test('removed members cannot read/create but can cancel their own pending messages',()=>fixture(async(db,h)=>{
 await h.create();await db.conversationParticipant.deleteMany({where:{userId:'me'}})
 assert.equal((await h.GET(req('GET'),ctx())).status,403);assert.equal((await h.POST(req('POST'),ctx())).status,403)
 const cancelled=await h.DELETE(req('DELETE'),ctx('queued'))
 assert.deepEqual(await cancelled.json(),{scheduleVersion:1,userId:'me',conversationId:'room',scheduledId:'queued',cancelled:true,ok:true})
}))
test('create validates time/content and current announcement/block policy within the transaction',()=>fixture(async(db,h)=>{
 for(const body of [{content:''},{content:'x'.repeat(2001)},{scheduledAt:'bad'},{scheduledAt:new Date().toISOString()},{scheduledAt:new Date(Date.now()+31*86400000).toISOString()}]) assert.equal((await h.POST(req('POST','me','synthetic-me',body),ctx())).status,400)
 await db.conversation.update({where:{id:'room'},data:{broadcastMode:true}})
 assert.equal((await h.POST(req('POST'),ctx())).status,403)
 await db.conversation.update({where:{id:'room'},data:{isGroup:false,broadcastMode:false}})
 await db.userBlock.create({data:{blockerId:'other',blockedId:'me'}})
 assert.equal((await h.POST(req('POST'),ctx())).status,403)
 await db.userBlock.deleteMany()
 const when=new Date(Date.now()+3600000).toISOString(), created=await h.POST(req('POST','me','synthetic-me',{scheduledAt:when}),ctx())
 assert.equal(created.status,201);const receipt=await created.json();assert.equal(receipt.item.content,'Hello');assert.equal(receipt.item.scheduledAt,when);assert.equal(receipt.item.sentAt,null);assert.equal(await db.scheduledMessage.count(),1)
}))
test('already delivered rows cannot be falsely cancelled; failed deletes roll back',()=>fixture(async(db,h)=>{
 await h.create({sentAt:new Date()})
 assert.equal((await h.DELETE(req('DELETE'),ctx('queued'))).status,409);assert.equal(await db.scheduledMessage.count(),1)
 await db.scheduledMessage.update({where:{id:'queued'},data:{sentAt:null}})
 await db.$executeRawUnsafe("CREATE TRIGGER refuse_cancel BEFORE DELETE ON ScheduledMessage BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
 await assert.rejects(h.DELETE(req('DELETE'),ctx('queued')));assert.equal(await db.scheduledMessage.count(),1)
}))
test('dispatch emits the actual message to sender and peers without marking incoming rows Seen; repeat tick does not resend',()=>fixture(async(db,h)=>{
 await h.create({scheduledAt:new Date(Date.now()-1000)})
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,1)
 assert.equal(await db.message.count(),1);assert.ok((await db.scheduledMessage.findUnique({where:{id:'queued'}})).sentAt)
 assert.deepEqual(new Set(h.events[0][1]),new Set(['me','other']))
 assert.equal((await db.conversationParticipant.findUnique({where:{id:'p1'}})).lastReadAt.toISOString(),'2026-01-01T00:00:00.000Z')
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,0);assert.equal(await db.message.count(),1)
}))
test('slow-mode waiting rows cannot starve other rooms across dispatch pages',()=>fixture(async(db,h)=>{
 await db.conversation.update({where:{id:'room'},data:{slowModeSeconds:3600}})
 await db.message.create({data:{id:'recent',conversationId:'room',senderId:'me',content:'Recent',createdAt:new Date()}})
 const at=new Date(Date.now()-1000)
 for(let i=0;i<25;i++)await h.create({id:`a${String(i).padStart(2,'0')}`,scheduledAt:at})
 await h.create({id:'z-ready',conversationId:'second',senderId:'other',scheduledAt:at})
 const first=await(await h.dispatch(tick())).json();assert.equal(first.dispatched,0);assert.ok(first.nextCursor)
 const second=await(await h.dispatch(tick(first.nextCursor))).json();assert.equal(second.dispatched,1);assert.equal(second.nextCursor,null)
 assert.equal(await db.scheduledMessage.count({where:{conversationId:'room',sentAt:null}}),25)
}))
test('cancel versus delivery contention never acknowledges cancellation of a committed message',()=>fixture(async(db,h)=>{
 await h.create({scheduledAt:new Date(Date.now()-1000)})
 const [cancelled]=await Promise.all([h.DELETE(req('DELETE'),ctx('queued')),h.dispatch(tick())])
 const messages=await db.message.count()
 if(cancelled.status===200){assert.equal(messages,0);assert.equal(await db.scheduledMessage.count(),0)}
 if(messages>0){assert.notEqual(cancelled.status,200);assert.equal(await db.scheduledMessage.count(),1)}
 assert.ok([200,409].includes(cancelled.status))
}))
test('dispatcher ticker carries progress across ticks, retries failures without advancing and prevents overlap',async()=>{
 const source=await readFile(new URL('../../mini-services/pulse-socket/index.ts',import.meta.url),'utf8')
 const text=source.slice(source.indexOf('const NEXT_APP_URL ='),source.indexOf('setInterval(() => void tickDispatch()'))
 let resolve, calls=[]
 const responses=[{dispatched:0,nextCursor:'next+/='},new Error('offline'),{dispatched:1,nextCursor:null}]
 const fetch=async url=>{calls.push(url);const next=responses.shift();if(next instanceof Error)throw next;return Response.json(next)}
 const make=f=>new Function('fetch','process','console',stripTypeScriptTypes(text)+`;return tickDispatch`)(f,{env:{PULSE_ORIGIN:'https://scheduler.test',CRON_SECRET:'synthetic'}},{log:()=>{}})
 const tick=make(fetch)
 await tick();await tick();await tick();await tick()
 assert.equal(new URL(calls[0]).search,'');assert.equal(new URL(calls[1]).searchParams.get('cursor'),'next+/=');assert.equal(calls[1],calls[2]);assert.equal(new URL(calls[3]).search,'')
 let started=0
 const blocked=make(async()=>{started++;await new Promise(r=>{resolve=r});return Response.json({dispatched:0,nextCursor:null})})
 const first=blocked();await blocked();assert.equal(started,1);resolve();await first
})
