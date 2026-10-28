import test from 'node:test'
import { enqueueScheduledNotice, drainScheduledNotices, scheduledPushDelivered, recordScheduledPush } from '../../src/lib/scheduled-notice.ts'
import { createHash } from 'node:crypto'
import { deliverMessageNotification } from '../../src/lib/message-notification.ts'
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
 const notificationWork=[]
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
  await db.$executeRawUnsafe(await readFile(new URL('../../prisma/patches/20261024_scheduled_creation_operations.sql',import.meta.url),'utf8'))
  await db.$executeRawUnsafe(await readFile(new URL('../../prisma/patches/20261028_scheduled_semantic_index.sql',import.meta.url),'utf8'))
  for(const sql of (await readFile(new URL('../../prisma/patches/20261028_scheduled_delivery_notices.sql',import.meta.url),'utf8')).split(';').filter(s=>s.trim())) await db.$executeRawUnsafe(sql)
  for(const id of ['me','other']) await db.$executeRawUnsafe('INSERT INTO User(id,name,sessionTokenHash,legacyTokenHashes) VALUES(?,?,?,?)',id,'Name '+id,hashSessionToken(`synthetic-${id}`),'[]')
  for(const id of ['room','second']) await db.conversation.create({data:{id,isGroup:true}})
  for(const [id,userId,conversationId]of [['p1','me','room'],['p2','other','room'],['p3','other','second']]) await db.conversationParticipant.create({data:{id,userId,conversationId,lastReadAt:new Date('2026-01-01')}})
  const helper=await load('src/lib/scheduled-operation.ts',{messageDeliveryDecision,createHash},'({ScheduledError,scheduledErrorResponse,scheduledHeaders,scheduledPage,createScheduled,cancelScheduled,scheduledDispatchPage})')
  const requireActionOwner=await load('src/lib/wallet-authorization.ts',{db,bearerToken,tokenAccepted},'requireActionOwner')
  const bindings={db,...helper,requireActionOwner,NextResponse:{json:Response.json},safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',MESSAGE_MAX:2000,
   parseIsoDate:v=>typeof v==='string'&&Number.isFinite(Date.parse(v))?new Date(v):null}
  const base=await load('src/app/api/conversations/[id]/scheduled/route.ts',bindings,'({GET,POST})')
  const POSTv2=await load('src/app/api/conversations/[id]/scheduled/create-v2/route.ts',{...bindings,createScheduledRequest:base.POST},'POST')
  const cancel=await load('src/app/api/scheduled/[id]/route.ts',bindings,'DELETE')
  const events=[], pushes=[], faults={socket:false,push:false}
  const flushNotifications=()=>Promise.all(notificationWork)
  const dispatch=await load('src/app/api/maintenance/dispatch/route.ts',{...bindings,enqueueScheduledNotice,drainScheduledNotices,scheduledPushDelivered,recordScheduledPush,scheduledDeliveryDecision,process:{env:{CRON_SECRET:'synthetic'}},MESSAGE_FULL_INCLUDE:{},
   mapMessage:row=>row, memberIdsOf:room=>db.conversationParticipant.findMany({where:{conversationId:room}}).then(rows=>rows.map(r=>r.userId)),
   notifySocket:async(...args)=>{if(faults.socket)throw Error('socket failed');events.push(args)},
   deliverMessageNotification:(...args)=>{const work=deliverMessageNotification(...args);notificationWork.push(work.catch(()=>false));return work},
   fanoutPush:async(recipients,payload,options)=>{if(faults.push)throw Error('provider failed');pushes.push({recipients,payload,bodies:recipients.map(options.bodyFor)})},
   console:{error:()=>{}}},'POST')
  const create=(data={})=>db.scheduledMessage.create({data:{id:'queued',conversationId:'room',senderId:'me',content:'Hello',scheduledAt:new Date(Date.now()+3600000),...data}})
  await run(db,{...base,POSTv2,DELETE:cancel,dispatch,events,pushes,faults,flushNotifications,create,helper})
 } finally {await Promise.all(notificationWork);await db.$disconnect();await rm(dir,{recursive:true,force:true})}
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
test('already delivered rows cannot be falsely cancelled; failed cancellation writes roll back',()=>fixture(async(db,h)=>{
 await h.create({sentAt:new Date()})
 assert.equal((await h.DELETE(req('DELETE'),ctx('queued'))).status,409);assert.equal(await db.scheduledMessage.count(),1)
 await db.scheduledMessage.update({where:{id:'queued'},data:{sentAt:null}})
 await db.$executeRawUnsafe("CREATE TRIGGER refuse_cancel BEFORE UPDATE ON ScheduledMessage BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
 await assert.rejects(h.DELETE(req('DELETE'),ctx('queued')));assert.equal(await db.scheduledMessage.count(),1)
 const unchanged=await db.scheduledMessage.findUnique({where:{id:'queued'}});assert.equal(unchanged.content,'Hello');assert.equal(unchanged.cancelledAt,null)
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
 if(cancelled.status===200){assert.equal(messages,0);assert.equal(await db.scheduledMessage.count({where:{cancelledReason:'user-cancelled'}}),1)}
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

test('a lost cancellation acknowledgement can be retried without resending or leaking discarded text',()=>fixture(async(db,h)=>{
 await h.create({scheduledAt:new Date(Date.now()-1000)})
 const first=await h.DELETE(req('DELETE'),ctx('queued'))
 assert.equal(first.status,200)
 const receipt=await first.json()
 const row=await db.scheduledMessage.findUnique({where:{id:'queued'}})
 assert.equal(row.content,'');assert.equal(row.cancelledReason,'user-cancelled');assert.ok(row.cancelledAt)
 assert.deepEqual(await(await h.DELETE(req('DELETE'),ctx('queued'))).json(),receipt)
 assert.equal((await h.DELETE(req('DELETE','other','synthetic-other'),ctx('queued'))).status,403)
 assert.equal((await h.DELETE(req('DELETE'),ctx('never-existed'))).status,404)
 assert.deepEqual((await(await h.GET(req('GET'),ctx())).json()).items,[])
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,0)
 assert.equal(await db.message.count(),0)
 assert.equal((await db.scheduledMessage.findUnique({where:{id:'queued'}})).cancelledAt.getTime(),row.cancelledAt.getTime())
}))

const createKey='scheduled-operation-0001'
test('scheduled creation receipt replays one row across reopen and refuses another device duplicating it',()=>fixture(async(db,h)=>{
 const when=new Date(Date.now()+3600000).toISOString(), body={clientId:createKey,scheduledAt:when}
 const first=await(await h.POSTv2(req('POST','me','synthetic-me',body),ctx())).json()
 assert.equal(first.clientId,createKey);assert.equal(first.replayed,false)
 const again=await(await h.POSTv2(req('POST','me','synthetic-me',body),ctx())).json()
 assert.equal(again.replayed,true);assert.deepEqual(again.item,first.item)
 await db.$disconnect();await db.$connect()
 const reopened=await h.helper.createScheduled(db,'me','room','Hello',new Date(when),createKey)
 assert.equal(reopened.item.id,first.item.id);assert.equal(reopened.replayed,true)
 const duplicate=await h.POSTv2(req('POST','me','synthetic-me',{...body,clientId:'scheduled-operation-0002'}),ctx())
 assert.equal(duplicate.status,409)
 assert.equal((await duplicate.json()).code,'schedule_duplicate')
 assert.equal(await db.scheduledMessage.count(),1)
}))
test('same-key races settle to one schedule and changed instructions or a removed member are refused',()=>fixture(async(db,h)=>{
 const when=new Date(Date.now()+3600000).toISOString(), body={clientId:createKey,scheduledAt:when}
 const responses=await Promise.all([h.POSTv2(req('POST','me','synthetic-me',body),ctx()),h.POSTv2(req('POST','me','synthetic-me',body),ctx())])
 for(const response of responses) assert.ok([201,409].includes(response.status))
 assert.equal(await db.scheduledMessage.count(),1)
 assert.equal((await db.$queryRaw`SELECT * FROM ScheduledCreationOperation`).length,1)
 assert.equal((await h.POSTv2(req('POST','me','synthetic-me',{...body,content:'Changed'}),ctx())).status,409)
 assert.equal((await h.POSTv2(req('POST','me','synthetic-me',{...body,scheduledAt:new Date(Date.parse(when)+1000).toISOString()}),ctx())).status,409)
 await db.conversationParticipant.deleteMany({where:{userId:'me'}})
 assert.equal((await h.POSTv2(req('POST','me','synthetic-me',body),ctx())).status,403)
}))
test('cancelled, delivered or deleted scheduled rows cannot be recreated by replay',()=>fixture(async(db,h)=>{
 for(const terminal of ['cancelled','sent','deleted']) {
  const key=createKey+'-'+terminal, when=new Date(Date.now()+3600000).toISOString(), body={clientId:key,scheduledAt:when}
  const first=await(await h.POSTv2(req('POST','me','synthetic-me',body),ctx())).json()
  if(terminal==='cancelled') await h.DELETE(req('DELETE'),ctx(first.item.id))
  if(terminal==='sent') await db.scheduledMessage.update({where:{id:first.item.id},data:{sentAt:new Date()}})
  if(terminal==='deleted') await db.scheduledMessage.delete({where:{id:first.item.id}})
  const count=await db.scheduledMessage.count()
  const response=await h.POSTv2(req('POST','me','synthetic-me',body),ctx())
  assert.equal(response.status,410);const receipt=await response.json()
  assert.equal(receipt.code,'schedule_finished');assert.equal(receipt.clientId,key)
  assert.equal(await db.scheduledMessage.count(),count)
 }
}))
test('receipt write failure rolls back creation and missing upgrade fails closed',()=>fixture(async(db,h)=>{
 for(const clientId of ['',null,1,{},'too-short','a'.repeat(129)]) {
  assert.equal((await h.POSTv2(req('POST','me','synthetic-me',{clientId}),ctx())).status,400)
 }
 await db.$executeRawUnsafe("CREATE TRIGGER refuse_schedule_receipt BEFORE INSERT ON ScheduledCreationOperation BEGIN SELECT RAISE(ABORT,'receipt failed'); END")
 await assert.rejects(h.POSTv2(req('POST','me','synthetic-me',{clientId:createKey}),ctx()))
 assert.equal(await db.scheduledMessage.count(),0)
 await db.$executeRawUnsafe('DROP TABLE ScheduledCreationOperation')
 assert.equal((await h.POSTv2(req('POST','me','synthetic-me',{clientId:createKey}),ctx())).status,503)
 assert.equal(await db.scheduledMessage.count(),0)
}))
test('retry admission is owner scoped and is checked before the new-send time horizon',()=>fixture(async(db,h)=>{
 const when=new Date(Date.now()+3600000), content='Hello'
 const first=await h.helper.createScheduled(db,'me','room',content,when,createKey)
 const other=await h.helper.createScheduled(db,'other','room',content,when,createKey)
 assert.notEqual(first.item.id,other.item.id)
 // Simulate an old committed receipt with a due-but-not-yet-dispatched row.
 const past=new Date(Date.now()-1000)
 const hash=createHash('sha256').update(JSON.stringify(['room',content,past.toISOString()])).digest('hex')
 await db.scheduledMessage.update({where:{id:first.item.id},data:{scheduledAt:past}})
 await db.$executeRaw`UPDATE ScheduledCreationOperation SET requestHash=${hash} WHERE userId='me' AND clientId=${createKey}`
 const replay=await h.helper.createScheduled(db,'me','room',content,past,createKey)
 assert.equal(replay.item.id,first.item.id);assert.equal(replay.replayed,true)
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,1)
 assert.equal(await db.message.count(),1)
 const finished=await h.POSTv2(req('POST','me','synthetic-me',{clientId:createKey,scheduledAt:past.toISOString()}),ctx())
 assert.equal(finished.status,410)
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,0)
}))

test('versioned scheduled creation requires a retry key before touching the legacy create path',()=>fixture(async(db,h)=>{
 assert.equal((await h.POSTv2(req('POST'),ctx())).status,400)
 assert.equal(await db.scheduledMessage.count(),0)
}))

test('scheduled dispatch plans offline notifications from the actual committed message without duplicate retries',()=>fixture(async(db,h)=>{
 await h.create({scheduledAt:new Date(Date.now()-1000)})
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,1)
 await h.flushNotifications()
 const message=await db.message.findFirst()
 assert.equal(h.pushes.length,1)
 assert.deepEqual(h.pushes[0].recipients,['other'])
 assert.equal(h.pushes[0].payload.messageId,message.id)
 assert.equal(h.pushes[0].payload.conversationId,'room')
 assert.equal(h.pushes[0].payload.kind,'message')
 assert.deepEqual(h.pushes[0].bodies,['Name me: Hello'])
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,0)
 await h.flushNotifications();assert.equal(h.pushes.length,1)
}))
test('scheduled push applies mute, block, hidden previews and disappearing-message policy',()=>fixture(async(db,h)=>{
 const due=()=>new Date(Date.now()-1000)
 await db.conversationParticipant.update({where:{id:'p2'},data:{mutedUntil:new Date(Date.now()+3600000)}})
 await h.create({id:'muted',scheduledAt:due()});await h.dispatch(tick());await h.flushNotifications()
 assert.equal(h.pushes.length,0)
 await db.conversationParticipant.update({where:{id:'p2'},data:{mutedUntil:null}})
 await db.userBlock.create({data:{blockerId:'other',blockedId:'me'}})
 await h.create({id:'blocked',scheduledAt:due()});await h.dispatch(tick());await h.flushNotifications()
 assert.equal(h.pushes.length,0)
 await db.userBlock.deleteMany()
 await db.user.update({where:{id:'other'},data:{preferences:JSON.stringify({notifPreviews:false})}})
 await h.create({id:'private',scheduledAt:due()});await h.dispatch(tick());await h.flushNotifications()
 assert.deepEqual(h.pushes.at(-1).bodies,['New message'])
 await db.user.update({where:{id:'other'},data:{preferences:null}})
 await db.conversation.update({where:{id:'room'},data:{ttlSeconds:60}})
 await h.create({id:'expiring',scheduledAt:due()});await h.dispatch(tick());await h.flushNotifications()
 assert.deepEqual(h.pushes.at(-1).bodies,['Name me: Disappearing message'])
 assert.ok(Date.parse(h.pushes.at(-1).payload.expiresAt)>Date.now())
}))
test('socket or push failure does not change committed dispatch counts or resend messages',()=>fixture(async(db,h)=>{
 h.faults.socket=true
 await h.create({id:'socket-failure',scheduledAt:new Date(Date.now()-1000)})
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,1)
 await h.flushNotifications();assert.equal(h.pushes.length,1)
 h.faults.socket=false;h.faults.push=true
 await h.create({id:'push-failure',scheduledAt:new Date(Date.now()-1000)})
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,1)
 await h.flushNotifications();assert.equal(h.events.length,1)
 assert.equal(await db.message.count(),2)
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,0)
}))

test('scheduled notification receipts retry only unfinished channels after a lost provider or relay response',()=>fixture(async(db,h)=>{
 h.faults.socket=true
 await h.create({id:'one',scheduledAt:new Date(Date.now()-1000)})
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,1)
 assert.equal(h.pushes.length,1)
 const [pending]=await db.$queryRawUnsafe('SELECT socketDone,pushDone,completedAtMs FROM ScheduledDeliveryNotice')
 assert.equal(Boolean(pending.socketDone),false);assert.equal(Boolean(pending.pushDone),true);assert.equal(pending.completedAtMs,null)
 h.faults.socket=false
 await db.$executeRawUnsafe('UPDATE ScheduledDeliveryNotice SET nextAttemptMs=0')
 assert.equal((await(await h.dispatch(tick())).json()).dispatched,0)
 assert.equal(h.events.length,1);assert.equal(h.pushes.length,1);assert.equal(await db.message.count(),1)
 h.faults.push=true
 await h.create({id:'two',scheduledAt:new Date(Date.now()-1000)})
 await h.dispatch(tick());assert.equal(h.events.length,2)
 h.faults.push=false
 await db.$executeRawUnsafe('UPDATE ScheduledDeliveryNotice SET nextAttemptMs=0')
 await h.dispatch(tick())
 assert.equal(h.events.length,2);assert.equal(h.pushes.length,2);assert.equal(await db.message.count(),2)
 const unfinished=await db.$queryRawUnsafe('SELECT messageId FROM ScheduledDeliveryNotice WHERE completedAtMs IS NULL')
 assert.deepEqual(unfinished,[])
}))
test('scheduled notice leases exclude another dispatcher and expired messages suppress late retries',()=>fixture(async(db,h)=>{
 h.faults.socket=true;h.faults.push=true
 await h.create({scheduledAt:new Date(Date.now()-1000)})
 await h.dispatch(tick())
 await db.$executeRawUnsafe('UPDATE ScheduledDeliveryNotice SET nextAttemptMs=0')
 let enter,release
 const entered=new Promise(r=>enter=r),blocked=new Promise(r=>release=r)
 let sockets=0,pushes=0
 const first=drainScheduledNotices(db,async()=>{sockets++;enter();await blocked},async()=>{pushes++;return null})
 await entered
 assert.equal(await drainScheduledNotices(db,async()=>{sockets++},async()=>{pushes++;return null}),0)
 release();assert.equal(await first,1);assert.equal(sockets,1);assert.equal(pushes,1)
 h.faults.socket=false
 await h.create({id:'expires',scheduledAt:new Date(Date.now()-1000)})
 await h.dispatch(tick())
 await db.message.updateMany({data:{expiresAt:new Date(Date.now()-1)}})
 h.faults.push=false
 await db.$executeRawUnsafe('UPDATE ScheduledDeliveryNotice SET nextAttemptMs=0')
 await h.dispatch(tick())
 assert.equal(h.pushes.length,0)
 assert.deepEqual(await db.$queryRawUnsafe('SELECT messageId FROM ScheduledDeliveryNotice WHERE completedAtMs IS NULL'),[])
}))
test('scheduled identities reject trailing newline and completed cancellation has a machine-readable terminal outcome',()=>fixture(async(db,h)=>{
 assert.equal((await h.POSTv2(req('POST','me','synthetic-me',{clientId:'scheduled-operation-001\n'}),ctx())).status,400)
 await h.create({sentAt:new Date()})
 const result=await h.DELETE(req('DELETE'),ctx('queued'))
 assert.equal(result.status,409);assert.equal((await result.json()).code,'schedule_already_sent')
}))

test('an expired notice lease cannot authorize the next channel',()=>fixture(async(db)=>{
 let now=1_800_000_000_000,pushes=0
 await db.$transaction(tx=>enqueueScheduledNotice(tx,'lease-boundary',now))
 assert.equal(await drainScheduledNotices(db,async()=>{now+=300_001},async()=>{pushes++;return null},()=>now),0)
 assert.equal(pushes,0)
 assert.equal(await drainScheduledNotices(db,async()=>{throw Error('socket already recorded')},async()=>{pushes++;return null},()=>now),1)
 assert.equal(pushes,1)
}))
test('notice retention bounds child cleanup without deleting unfinished receipts or delivered messages',()=>fixture(async(db,h)=>{
 await h.create({scheduledAt:new Date(Date.now()-1000)})
 await h.dispatch(tick())
 const message=await db.message.findFirst()
 assert.equal((await db.scheduledMessage.findFirst()).content,'')
 assert.equal(message.content,'Hello')
 const old=Date.now()-8*86_400_000
 await db.$executeRaw`UPDATE ScheduledDeliveryNotice SET completedAtMs=${BigInt(old)}`
 await db.$transaction(async tx=>{
   for(let i=0;i<505;i++) await recordScheduledPush(tx,message.id,`device-${i}`)
   await enqueueScheduledNotice(tx,'unfinished',Date.now()+86_400_000)
   await recordScheduledPush(tx,'unfinished','keep')
 })
 await drainScheduledNotices(db,async()=>{},async()=>null)
 assert.equal((await db.$queryRaw`SELECT * FROM ScheduledPushReceipt WHERE messageId=${message.id}`).length,5)
 assert.equal((await db.$queryRaw`SELECT * FROM ScheduledDeliveryNotice WHERE messageId=${message.id}`).length,1)
 await drainScheduledNotices(db,async()=>{},async()=>null)
 assert.equal((await db.$queryRaw`SELECT * FROM ScheduledDeliveryNotice WHERE messageId=${message.id}`).length,0)
 assert.equal(await scheduledPushDelivered(db,'unfinished','keep'),true)
 assert.ok(await db.message.findUnique({where:{id:message.id}}))
}))


test('legacy no-ID scheduled retries survive restart and terminal rows cannot be recreated',()=>fixture(async(db,h)=>{
 const when=new Date(Date.now()+3600000).toISOString(), body={scheduledAt:when,content:'Legacy exact instruction'}
 const first=await(await h.POST(req('POST','me','synthetic-me',body),ctx())).json()
 assert.equal(first.replayed,false)
 await db.$disconnect();await db.$connect()
 const retry=await(await h.POST(req('POST','me','synthetic-me',body),ctx())).json()
 assert.equal(retry.item.id,first.item.id);assert.equal(retry.replayed,true)
 assert.equal(await db.scheduledMessage.count(),1)
 await h.DELETE(req('DELETE'),ctx(first.item.id))
 assert.equal((await h.POST(req('POST','me','synthetic-me',body),ctx())).status,410)
 assert.equal((await h.POSTv2(req('POST','me','synthetic-me',{...body,clientId:'fresh-other-device-0001'}),ctx())).status,410)
 assert.equal(await db.scheduledMessage.count(),1)
}))
test('cross-device and legacy simultaneous semantic duplicates admit one instruction',()=>fixture(async(db,h)=>{
 const when=new Date(Date.now()+3600000).toISOString(), body={scheduledAt:when,content:'One exact send'}
 const responses=await Promise.all([
  h.POST(req('POST','me','synthetic-me',body),ctx()),
  h.POSTv2(req('POST','me','synthetic-me',{...body,clientId:'device-two-operation-001'}),ctx()),
  h.POSTv2(req('POST','me','synthetic-me',{...body,clientId:'device-three-operation-1'}),ctx()),
 ])
 assert.equal(responses.filter(r=>r.status===201).length,1)
 for(const r of responses) assert.ok([201,409].includes(r.status))
 assert.equal(await db.scheduledMessage.count(),1)
 const changed=await h.POST(req('POST','me','synthetic-me',{...body,scheduledAt:new Date(Date.parse(when)+1000).toISOString()}),ctx())
 assert.equal(changed.status,201)
 const foreign=await h.POST(req('POST','other','synthetic-other',body),ctx())
 assert.equal(foreign.status,201)
 assert.equal(await db.scheduledMessage.count(),3)
}))
test('legacy create fails closed without receipts and adopts an existing unkeyed pending schedule',()=>fixture(async(db,h)=>{
 const when=new Date(Date.now()+3600000).toISOString()
 await h.create({id:'old-unkeyed',content:'Old body',scheduledAt:new Date(when)})
 const adopted=await(await h.POST(req('POST','me','synthetic-me',{content:'Old body',scheduledAt:when}),ctx())).json()
 assert.equal(adopted.item.id,'old-unkeyed');assert.equal(adopted.replayed,true)
 assert.equal(await db.scheduledMessage.count(),1)
 await db.$executeRawUnsafe('DROP TABLE ScheduledCreationOperation')
 assert.equal((await h.POST(req('POST'),ctx())).status,503)
 assert.equal(await db.scheduledMessage.count(),1)
}))
