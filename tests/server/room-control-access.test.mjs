import { clipUtf16 } from '../../src/lib/text-limits.ts'
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
import { roomControl } from '../../src/lib/room-control.ts'
import { messageDeliveryDecision } from '../../src/lib/scheduled-delivery.ts'
import { threadRootAvailable } from '../../src/lib/message-visibility.ts'
const {PrismaClient,Prisma}=createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path, bindings, expression) {
 const text=(await readFile(new URL(`../../${path}`,import.meta.url),'utf8')).replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm,'').replace(/^export /gm,'')
 return new Function(...Object.keys(bindings),stripTypeScriptTypes(text,{mode:'transform'})+`;return ${expression}`)(...Object.values(bindings))
}
async function fixture(run) {
 const dir=await mkdtemp(join(tmpdir(),'room-controls-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
 try {
  for(const name of ['User','Conversation','ConversationParticipant','Automation','Webhook','Message','UserBlock']) {
   const columns=Prisma.dmmf.datamodel.models.find(m=>m.name===name).fields.filter(f=>f.kind!=='object'&&!f.isList).map(f=>{
    const type=['Int','Boolean','BigInt'].includes(f.type)?'INTEGER':f.type==='Float'?'REAL':f.type==='DateTime'?'DATETIME':'TEXT'
    let def=''
    if(typeof f.default==='string')def=` DEFAULT '${f.default.replaceAll("'","''")}'`
    else if(typeof f.default==='number'||typeof f.default==='boolean')def=` DEFAULT ${Number(f.default)}`
    else if(f.default?.name==='now')def=' DEFAULT CURRENT_TIMESTAMP'
    return `"${f.name}" ${type}${f.isId?' PRIMARY KEY':''}${def}`
   })
   await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
  }
  for(const id of ['me','member','outsider']) await db.user.create({data:{id,name:id,sessionTokenHash:hashSessionToken(`synthetic-${id}`),legacyTokenHashes:'[]'}})
  await db.conversation.create({data:{id:'room',isGroup:true}})
  for(const [userId,role]of [['me','admin'],['member','member']]) await db.conversationParticipant.create({data:{userId,role,conversationId:'room',lastReadAt:new Date('2026-01-01')}})
  await db.automation.create({data:{id:'rule',conversationId:'room',trigger:'Hello',reply:'Welcome',createdById:'me'}})
  await db.webhook.create({data:{id:'hook',conversationId:'room',name:'Build bot',token:'synthetic-hook',createdBy:'me'}})
  const requireActionOwner=await load('src/lib/wallet-authorization.ts',{db,bearerToken,tokenAccepted},'requireActionOwner')
  const events=[],bindings={db,clipUtf16,messageDeliveryDecision,roomControl,requireActionOwner,NextResponse:Response,safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',mapAutomation:r=>r,
   AVATAR_COLORS:['#F2A65A'],MESSAGE_MAX:2000,MESSAGE_FULL_INCLUDE:{},mapMessage:r=>r,memberIdsOf:async()=>['me','member'],notifySocket:async(...args)=>events.push(args)}
  const {createAutomationReply,triggerMatches}=await load('src/lib/automation-delivery.ts',{...bindings,messageDeliveryDecision,threadRootAvailable},'({createAutomationReply,triggerMatches})')
  const a=await load('src/app/api/conversations/[id]/automations/route.ts',bindings,'({GET,POST})')
  const single=await load('src/app/api/automations/[id]/route.ts',bindings,'({PATCH,DELETE})')
  const w=await load('src/app/api/webhooks/route.ts',bindings,'({GET,POST})')
  const token=await load('src/app/api/webhooks/[token]/route.ts',bindings,'({POST,DELETE})')
  await run(db,{a,single,w,token,events,triggerMatches,fire:(content='Hello',now=new Date())=>db.$transaction(tx=>createAutomationReply(tx,'room','source',content,now),{isolationLevel:'Serializable'}), makeReply:(overrides={})=>load('src/app/api/conversations/[id]/messages/route.ts',{...bindings,createAutomationReply,nativeBlockFromPayload:()=>null,console:{error:()=>{}},...overrides},'maybeAutomationReply'),makeIngest:(d,overrides={})=>load('src/app/api/webhooks/[token]/route.ts',{...bindings,db:d,...overrides},'POST')})
 }finally{await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
const ctx=(id='room')=>({params:Promise.resolve({id,token:'synthetic-hook'})})
const req=(method,owner='me',token='synthetic-me',extra={})=>new Request(`https://control.test/?conversationId=room&userId=${owner}&requesterId=${owner}`,{method,headers:token?{Authorization:`Bearer ${token}`}:{},...(method==='GET'?{}:{body:JSON.stringify({userId:owner,requesterId:owner,conversationId:'room',trigger:'New trigger',reply:'Reply',enabled:false,name:'Build bot',content:'Build completed',...extra})})})
test('all seven automation/webhook management handlers bind credentials before read or write',()=>fixture(async(db,h)=>{
 for(const [handler,method,id]of [[h.a.GET,'GET','room'],[h.a.POST,'POST','room'],[h.single.PATCH,'PATCH','rule'],[h.single.DELETE,'DELETE','rule'],[h.w.GET,'GET','room'],[h.w.POST,'POST','room'],[h.token.DELETE,'DELETE','room']]) {
  assert.equal((await handler(req(method,'me',null),ctx(id))).status,401)
  assert.equal((await handler(req(method,'me','synthetic-member'),ctx(id))).status,403)
 }
 assert.equal(await db.automation.count(),1);assert.equal(await db.webhook.count(),1)
 assert.equal((await db.automation.findUnique({where:{id:'rule'}})).enabled,true)
}))
test('members can read automation rules but cannot read webhook credentials, mint hooks or administer either control',()=>fixture(async(db,h)=>{
 assert.equal((await h.a.GET(req('GET','member','synthetic-member'),ctx())).status,200)
 for(const [handler,method,id]of [[h.a.POST,'POST','room'],[h.single.PATCH,'PATCH','rule'],[h.single.DELETE,'DELETE','rule'],[h.w.GET,'GET','room'],[h.w.POST,'POST','room'],[h.token.DELETE,'DELETE','room']]) {
  assert.equal((await handler(req(method,'member','synthetic-member'),ctx(id))).status,403)
 }
 const page=await h.w.GET(req('GET'),ctx());assert.equal(page.status,200);assert.match(page.headers.get('cache-control'),/private.*no-store/);assert.equal(page.headers.get('vary'),'Authorization')
 assert.equal((await page.json()).webhooks[0].token,'synthetic-hook')
}))
test('demotion removes creator authority and malformed boolean patches cannot partially apply',()=>fixture(async(db,h)=>{
 assert.equal((await h.single.PATCH(req('PATCH','me','synthetic-me',{enabled:'false',trigger:'Renamed'}),ctx('rule'))).status,400)
 assert.equal((await db.automation.findUnique({where:{id:'rule'}})).trigger,'Hello')
 await db.conversationParticipant.updateMany({where:{userId:'me'},data:{role:'member'}})
 for(const [handler,method,id]of [[h.single.PATCH,'PATCH','rule'],[h.single.DELETE,'DELETE','rule'],[h.w.GET,'GET','room'],[h.w.POST,'POST','room'],[h.token.DELETE,'DELETE','room']]) assert.equal((await handler(req(method),ctx(id))).status,403)
 assert.equal(await db.automation.count(),1);assert.equal(await db.webhook.count(),1)
}))
test('concurrent case-insensitive duplicate creation cannot commit duplicate triggers',()=>fixture(async(db,h)=>{
 const replies=await Promise.all([h.a.POST(req('POST','me','synthetic-me',{trigger:'Ping'}),ctx()),h.a.POST(req('POST','me','synthetic-me',{trigger:'PING'}),ctx())])
 assert.ok(replies.every(r=>[201,409].includes(r.status)))
 const rows=await db.automation.findMany();assert.ok(rows.filter(r=>r.trigger.toLowerCase()==='ping').length<=1)
 const once=await h.a.POST(req('POST','me','synthetic-me',{trigger:'Ping'}),ctx());assert.ok([201,409].includes(once.status))
 assert.equal((await h.a.POST(req('POST','me','synthetic-me',{trigger:'pINg'}),ctx())).status,409)
 assert.equal((await h.single.PATCH(req('PATCH','me','synthetic-me',{trigger:'PING'}),ctx('rule'))).status,409)
}))
test('exact deletion receipts and failed writes preserve unrelated controls',()=>fixture(async(db,h)=>{
 await db.$executeRawUnsafe("CREATE TRIGGER refuse_control BEFORE DELETE ON Automation BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
 await assert.rejects(h.single.DELETE(req('DELETE'),ctx('rule')));assert.equal(await db.automation.count(),1)
 await db.$executeRawUnsafe('DROP TRIGGER refuse_control')
 assert.deepEqual(await(await h.single.DELETE(req('DELETE'),ctx('rule'))).json(),{ok:true,deleted:true,automationId:'rule',userId:'me'})
 assert.deepEqual(await(await h.token.DELETE(req('DELETE'),ctx())).json(),{ok:true,deleted:true,token:'synthetic-hook',userId:'me'})
 assert.equal(await db.conversation.count(),1)
}))
test('public capability ingest rechecks revocation before writing and notifies its represented sender',()=>fixture(async(db,h)=>{
 const first=await h.token.POST(req('POST','',null),ctx());assert.equal(first.status,200);assert.equal(await db.message.count(),1)
 assert.deepEqual(h.events[0][1],['me','member'])
 assert.equal((await db.conversationParticipant.findFirst({where:{userId:'me'}})).lastReadAt.toISOString(),'2026-01-01T00:00:00.000Z')
 const racing=new Proxy(db,{get(target,key){if(key==='$transaction')return async(action,options)=>{await db.webhook.delete({where:{id:'hook'}});return db.$transaction(action,options)};const value=target[key];return typeof value==='function'?value.bind(target):value}})
 const ingest=await h.makeIngest(racing)
 assert.equal((await ingest(req('POST','',null),ctx())).status,404);assert.equal(await db.message.count(),1);assert.equal(h.events.length,1)
}))
async function source(db,extra={}) {
 return db.message.create({data:{id:'source',conversationId:'room',senderId:'member',content:'Hello',kind:'text',...extra}})
}
test('automation execution rechecks disabled/deleted rules and departed creators against the write snapshot',()=>fixture(async(db,h)=>{
 await source(db)
 await db.automation.update({where:{id:'rule'},data:{enabled:false}})
 assert.equal(await h.fire(),null)
 await db.automation.update({where:{id:'rule'},data:{enabled:true}})
 await db.conversationParticipant.deleteMany({where:{userId:'me'}})
 assert.equal(await h.fire(),null)
 await db.conversationParticipant.create({data:{userId:'me',conversationId:'room',role:'admin'}})
 await db.automation.delete({where:{id:'rule'}})
 assert.equal(await h.fire(),null)
 assert.equal(await db.message.count(),1)
}))
test('automations respect current broadcast, block and slow-mode posting restrictions',()=>fixture(async(db,h)=>{
 await source(db)
 await db.conversationParticipant.updateMany({where:{userId:'me'},data:{role:'member'}})
 await db.conversation.update({where:{id:'room'},data:{broadcastMode:true}})
 assert.equal(await h.fire(),null)
 await db.conversation.update({where:{id:'room'},data:{isGroup:false,broadcastMode:false}})
 await db.userBlock.create({data:{blockerId:'member',blockedId:'me'}})
 assert.equal(await h.fire(),null)
 await db.userBlock.deleteMany()
 await db.conversation.update({where:{id:'room'},data:{isGroup:true,slowModeSeconds:60}})
 const now=new Date();await db.message.create({data:{id:'recent',conversationId:'room',senderId:'me',content:'Recent',createdAt:now}})
 assert.equal(await h.fire('Hello',now),null)
 assert.equal((await db.automation.findUnique({where:{id:'rule'}})).hits,0)
}))
test('deleted, expired, edited, machine-authored or inaccessible thread sources never trigger replies',()=>fixture(async(db,h)=>{
 await source(db)
 for(const patch of [{deletedAt:new Date()},{expiresAt:new Date(0)},{content:'Edited'},{viaAutomation:true},{parentId:'missing'}]) {
  await db.message.update({where:{id:'source'},data:patch});assert.equal(await h.fire(),null)
  await db.message.update({where:{id:'source'},data:{deletedAt:null,expiresAt:null,content:'Hello',viaAutomation:false,parentId:null}})
 }
 await db.conversationParticipant.deleteMany({where:{userId:'member'}})
 assert.equal(await h.fire(),null)
 assert.equal(await db.message.count(),1)
}))
test('reply, hit count and unarchive roll back together; committed counters survive notification failure',()=>fixture(async(db,h)=>{
 await source(db)
 await db.conversationParticipant.updateMany({where:{userId:'member'},data:{archivedAt:new Date(0)}})
 await db.$executeRawUnsafe("CREATE TRIGGER refuse_hit BEFORE UPDATE ON Automation BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
 await assert.rejects(h.fire());assert.equal(await db.message.count(),1)
 assert.equal((await db.automation.findUnique({where:{id:'rule'}})).hits,0)
 assert.notEqual((await db.conversationParticipant.findFirst({where:{userId:'member'}})).archivedAt,null)
 await db.$executeRawUnsafe('DROP TRIGGER refuse_hit')
 const reply=await h.makeReply({notifySocket:async()=>{throw Error('Notification unavailable')}})
 await reply('room','source','Hello')
 assert.equal(await db.message.count(),2)
 assert.equal((await db.automation.findUnique({where:{id:'rule'}})).hits,1)
 assert.equal((await db.conversationParticipant.findFirst({where:{userId:'member'}})).archivedAt,null)
 assert.equal((await db.conversationParticipant.findFirst({where:{userId:'member'}})).lastReadAt.toISOString(),'2026-01-01T00:00:00.000Z')
}))
test('thread automations remain in their thread and expire no later than its root',()=>fixture(async(db,h)=>{
 const now=new Date(),deadline=new Date(now.getTime()+10_000)
 await db.message.create({data:{id:'root',conversationId:'room',senderId:'member',content:'Topic root',expiresAt:deadline}})
 await source(db,{parentId:'root',topicId:'topic'})
 await db.conversation.update({where:{id:'room'},data:{ttlSeconds:60}})
 const message=await h.fire('Hello',now)
 assert.equal(message.parentId,'root');assert.equal(message.topicId,'topic');assert.equal(message.expiresAt.toISOString(),deadline.toISOString())
 const reply=await h.makeReply();await reply('room','source','Hello')
 assert.deepEqual(h.events[0][1],['me','member'])
}))
test('Unicode keyword boundaries do not fire inside Bengali or combining-mark words',()=>fixture(async(db,h)=>{
 for(const [trigger,body,expected] of [
  ['দাম','দাম কত?',true],['দাম','বদাম',false],['দাম','দামগুলো',false],
  ['price','price_list',false],['price','what is the PRICE?',true],['price','prices',false],
  ['café','cafe\u0301!',true],['cafe','cafe\u0301',false],['C++','C++?',true],['a.b','axb',false],
  ['দাম','দাম\u200d',false],['','anything',false],
 ]) assert.equal(h.triggerMatches(trigger,body),expected,`${trigger}:${body}`)
}))
test('webhook capability cannot bypass block, announcement or machine slow-mode policies',()=>fixture(async(db,h)=>{
 await db.conversation.update({where:{id:'room'},data:{isGroup:false}})
 await db.userBlock.create({data:{blockerId:'member',blockedId:'me'}})
 assert.equal((await h.token.POST(req('POST','',null),ctx())).status,403)
 await db.userBlock.deleteMany()
 await db.conversationParticipant.updateMany({where:{userId:'me'},data:{role:'member'}})
 await db.conversation.update({where:{id:'room'},data:{isGroup:true,broadcastMode:true}})
 assert.equal((await h.token.POST(req('POST','',null),ctx())).status,403)
 await db.conversation.update({where:{id:'room'},data:{broadcastMode:false,slowModeSeconds:60}})
 assert.equal((await h.token.POST(req('POST','',null),ctx())).status,200)
 const message=await db.message.findFirst();assert.equal(message.viaAutomation,true)
 const wait=await h.token.POST(req('POST','',null),ctx());assert.equal(wait.status,429);assert.ok(Number(wait.headers.get('retry-after'))>0)
 assert.equal(await db.message.count(),1)
}))
test('normal sends reject reserved origin flags instead of minting integration metadata',()=>fixture(async(db,h)=>{
 const POST=await load('src/app/api/conversations/[id]/messages/route.ts',{
  db,NextResponse:Response,safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',MESSAGE_MAX:2000,MESSAGE_FULL_INCLUDE:{},
  requireActionOwner:async()=>null,
  messageOperation:()=>null,replayMessageOperation:async()=>null,MessageOperationError:class extends Error {},
 },'POST')
 for(const key of ['bot','webhook','webhookName','webhookColor']) {
  const request=new Request('https://controls.test',{method:'POST',body:JSON.stringify({senderId:'me',content:'Hello',payload:{[key]:true}})})
  assert.equal((await POST(request,ctx())).status,400)
 }
 assert.equal(await db.message.count(),0)
}))
test('webhook committed acceptance survives notification outage without encouraging a transport retry',()=>fixture(async(db,h)=>{
 const ingest=await h.makeIngest(db,{notifySocket:async()=>{throw Error('Synthetic outage')},console:{error:()=>{}}})
 const response=await ingest(req('POST','',null),ctx())
 assert.equal(response.status,200)
 const receipt=await response.json();assert.equal(receipt.success,true)
 assert.ok(await db.message.findUnique({where:{id:receipt.messageId}}))
 assert.equal(await db.message.count(),1)
}))
test('webhook override name remains valid Unicode when its display limit splits an emoji',()=>fixture(async(db,h)=>{
 const response=await h.token.POST(req('POST','',null,{username:'X'+'😀'.repeat(20)}),ctx())
 assert.equal(response.status,200)
 const {webhookName}=JSON.parse((await db.message.findFirst()).payload)
 assert.ok(webhookName.isWellFormed());assert.ok(webhookName.length<=40)
 assert.equal(webhookName,'X'+'😀'.repeat(19))
}))
