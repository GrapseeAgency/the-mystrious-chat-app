import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
import { roomControl } from '../../src/lib/room-control.ts'
const {PrismaClient,Prisma}=createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path, bindings, expression) {
 const text=(await readFile(new URL(`../../${path}`,import.meta.url),'utf8')).replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm,'').replace(/^export /gm,'')
 return new Function(...Object.keys(bindings),stripTypeScriptTypes(text,{mode:'transform'})+`;return ${expression}`)(...Object.values(bindings))
}
async function fixture(run) {
 const dir=await mkdtemp(join(tmpdir(),'room-controls-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
 try {
  for(const name of ['User','Conversation','ConversationParticipant','Automation','Webhook','Message']) {
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
  const events=[],bindings={db,roomControl,requireActionOwner,NextResponse:Response,safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',mapAutomation:r=>r,
   AVATAR_COLORS:['#F2A65A'],MESSAGE_MAX:2000,MESSAGE_FULL_INCLUDE:{},mapMessage:r=>r,memberIdsOf:async()=>['me','member'],notifySocket:async(...args)=>events.push(args)}
  const a=await load('src/app/api/conversations/[id]/automations/route.ts',bindings,'({GET,POST})')
  const single=await load('src/app/api/automations/[id]/route.ts',bindings,'({PATCH,DELETE})')
  const w=await load('src/app/api/webhooks/route.ts',bindings,'({GET,POST})')
  const token=await load('src/app/api/webhooks/[token]/route.ts',bindings,'({POST,DELETE})')
  await run(db,{a,single,w,token,events,makeIngest:d=>load('src/app/api/webhooks/[token]/route.ts',{...bindings,db:d},'POST')})
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
