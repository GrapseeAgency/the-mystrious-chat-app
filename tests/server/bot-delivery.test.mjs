import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp,rm,readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire,stripTypeScriptTypes } from 'node:module'
import { createHash } from 'node:crypto'
import { messageDeliveryDecision } from '../../src/lib/scheduled-delivery.ts'
import { threadRootAvailable } from '../../src/lib/message-visibility.ts'
const {Prisma,PrismaClient}=createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path,bindings,expression) {
 const text=(await readFile(new URL(`../../${path}`,import.meta.url),'utf8')).replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm,'').replace(/^export /gm,'')
 return new Function(...Object.keys(bindings),stripTypeScriptTypes(text,{mode:'transform'})+`;return ${expression}`)(...Object.values(bindings))
}
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return{resolve,promise}}
async function fixture(run) {
 const dir=await mkdtemp(join(tmpdir(),'bot-delivery-'));const db=new PrismaClient({datasourceUrl:`file:${join(dir,'test.sqlite')}`})
 try {
  for(const name of ['User','Conversation','ConversationParticipant','Message','UserBlock','Poll','PollOption','PollVote','UserWallet']) {
   const columns=Prisma.dmmf.datamodel.models.find(m=>m.name===name).fields.filter(f=>f.kind!=='object'&&!f.isList).map(f=>{
    const type=['Int','Boolean','BigInt'].includes(f.type)?'INTEGER':f.type==='Float'?'REAL':f.type==='DateTime'?'DATETIME':'TEXT'
    const def=typeof f.default==='string'?` DEFAULT '${f.default.replaceAll("'","''")}'`:typeof f.default==='number'||typeof f.default==='boolean'?` DEFAULT ${Number(f.default)}`:f.default?.name==='now'?' DEFAULT CURRENT_TIMESTAMP':''
    return `"${f.name}" ${type}${f.isId?' PRIMARY KEY':''}${def}`
   });await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
  }
  for(const [id,name,username]of [['bot','Pulse AI','pulseai'],['human','Private Real Name','human']])await db.user.create({data:{id,name,username}})
  await db.conversation.create({data:{id:'room',isGroup:false}})
  for(const userId of ['bot','human'])await db.conversationParticipant.create({data:{userId,conversationId:'room',role:'member',lastReadAt:new Date('2026-01-01')}})
  const trigger={id:'source',senderId:'human',content:'Hello'}
  await db.message.create({data:{...trigger,conversationId:'room',createdAt:new Date('2026-10-23T10:00:00Z')}})
  const events=[],typing=[]
  const bindings={db,MESSAGE_MAX:2000,MESSAGE_FULL_INCLUDE:{},createHash,messageDeliveryDecision,threadRootAvailable,mapMessage:r=>r,
   memberIdsOf:async room=>(await db.conversationParticipant.findMany({where:{conversationId:room},orderBy:{userId:'asc'},select:{userId:true}})).map(r=>r.userId),
   notifySocket:async(...args)=>events.push(args),fetch:async(_,options)=>{typing.push(JSON.parse(options.body));return new Response('{}')},console:{error:()=>{}}}
  const delivery=await load('src/lib/bot-delivery.ts',bindings,'({botReplyContext,createBotReply,prepareAiContext})')
  const deps={...bindings,...delivery}
  const tx=fn=>db.$transaction(fn,{isolationLevel:'Serializable'})
  await run(db,{trigger,events,typing,
   prepare:()=>tx(t=>delivery.prepareAiContext(t,'room','bot',trigger)),
   create:(data={content:'Reply'})=>tx(t=>delivery.createBotReply(t,'room','bot',trigger,data)),
   ai:overrides=>load('src/lib/ai-bot.ts',{...deps,ZAI:{create:async()=>({chat:{completions:{create:async()=>({choices:[{message:{content:'Answer'}}]})}}})},...overrides},'({runReply,inFlight})'),
   commands:()=>load('src/lib/bot-engine.ts',deps,'maybeBotReply')})
 }finally{await db.$disconnect();await rm(dir,{recursive:true,force:true})}
}
test('bot reply gates recheck source, author, block, broadcast and slow mode',()=>fixture(async(db,h)=>{
 for(const patch of [{deletedAt:new Date()},{expiresAt:new Date(0)},{viewOnce:true},{content:'Edited'},{viaAutomation:true}]) {
  await db.message.update({where:{id:'source'},data:patch});assert.equal(await h.create(),null)
  await db.message.update({where:{id:'source'},data:{deletedAt:null,expiresAt:null,viewOnce:false,content:'Hello',viaAutomation:false}})
 }
 await db.userBlock.create({data:{blockerId:'human',blockedId:'bot'}});assert.equal(await h.create(),null);await db.userBlock.deleteMany()
 await db.conversation.update({where:{id:'room'},data:{isGroup:true,broadcastMode:true}});assert.equal(await h.create(),null)
 await db.conversation.update({where:{id:'room'},data:{broadcastMode:false,slowModeSeconds:60}})
 await db.message.create({data:{id:'recent',conversationId:'room',senderId:'bot',content:'Earlier',viaAutomation:true,createdAt:new Date()}});assert.equal(await h.create(),null)
 await db.message.delete({where:{id:'recent'}})
 await db.conversationParticipant.deleteMany({where:{userId:'bot'}});assert.equal(await h.create(),null)
 assert.equal(await db.message.count(),1)
}))
test('AI transcript excludes deleted expired view-once future and unrelated-thread content and masks incognito identity',()=>fixture(async(db,h)=>{
 const past=new Date('2026-10-23T09:00:00Z')
 for(const [id,extra]of [['gone',{deletedAt:new Date()}],['expired',{expiresAt:new Date(0)}],['once',{viewOnce:true}],['thread',{parentId:'another-root'}],['future',{createdAt:new Date('2099-01-01')}],['visible',{}]]) {
  await db.message.create({data:{id,conversationId:'room',senderId:'human',content:`text-${id}`,createdAt:past,...extra}})
 }
 await db.message.updateMany({where:{senderId:'human'},data:{anon:true,anonAlias:'Ember'}})
 const context=await h.prepare();assert.ok(context.transcript.includes('Ember: Hello'));assert.ok(context.transcript.includes('text-visible'))
 assert.ok(!context.transcript.includes('Private Real Name'))
 for(const word of ['gone','expired','once','thread','future'])assert.ok(!context.transcript.includes(`text-${word}`))
 await db.message.update({where:{id:'visible'},data:{content:'Changed context'}})
 assert.notEqual((await h.prepare()).fingerprint,context.fingerprint)
}))
test('bot poll has poll kind, respects thread/source TTL, and rollback leaves no partial poll',()=>fixture(async(db,h)=>{
 const deadline=new Date(Date.now()+60_000)
 await db.message.create({data:{id:'root',conversationId:'room',senderId:'human',content:'Root',expiresAt:deadline}})
 await db.message.update({where:{id:'source'},data:{parentId:'root',topicId:'topic'}})
 const data={content:'',poll:{question:'Play?',options:['Yes','No']}}
 await db.$executeRawUnsafe("CREATE TRIGGER reject_order BEFORE UPDATE ON Conversation BEGIN SELECT RAISE(ABORT,'synthetic'); END")
 await assert.rejects(h.create(data));assert.equal(await db.poll.count(),0);assert.equal(await db.pollOption.count(),0);assert.equal(await db.message.count(),2)
 await db.$executeRawUnsafe('DROP TRIGGER reject_order')
 const reply=await h.create(data)
 assert.equal(reply.viaAutomation,true);assert.equal(reply.kind,'poll');assert.equal(reply.parentId,'root');assert.equal(reply.topicId,'topic');assert.equal(reply.expiresAt.toISOString(),deadline.toISOString())
 assert.equal(await db.poll.count(),1);assert.equal(await db.pollOption.count(),2)
 assert.equal((await db.conversationParticipant.findFirst({where:{userId:'human'}})).lastReadAt.toISOString(),'2026-01-01T00:00:00.000Z')
 await db.message.update({where:{id:'root'},data:{deletedAt:new Date()}});assert.equal(await h.create(),null)
}))
test('late AI answer is discarded after context edit or bot removal; pre-await claim prevents duplicate generation',()=>fixture(async(db,h)=>{
 const entered=deferred(),release=deferred();let calls=0
 const ai=await h.ai({ZAI:{create:async()=>({chat:{completions:{create:async()=>{calls++;entered.resolve();await release.promise;return{choices:[{message:{content:'Late answer'}}]}}}}})}})
 const first=ai.runReply('room',h.trigger);await entered.promise
 await ai.runReply('room',h.trigger);assert.equal(calls,1)
 await db.message.update({where:{id:'source'},data:{content:'Edited'}});release.resolve();await first
 assert.equal(await db.message.count(),1);assert.equal(ai.inFlight.size,0)
 assert.ok(h.typing.every(event=>event.userId==='bot'))
 const gone=await h.ai({ZAI:{create:async()=>({chat:{completions:{create:async()=>{await db.conversationParticipant.deleteMany({where:{userId:'bot'}});return{choices:[{message:{content:'Removed bot answer'}}]}}}}})}})
 await db.message.update({where:{id:'source'},data:{content:'Hello'}});await gone.runReply('room',h.trigger)
 assert.equal(await db.message.count(),1);assert.equal(h.events.length,0)
}))
test('AI timeout releases the room; successful answer is source-bound and relayed to current members including bot',()=>fixture(async(db,h)=>{
 const hanging=await h.ai({ZAI:{create:async()=>new Promise(()=>{})},setTimeout:fn=>setTimeout(fn,1)})
 await assert.rejects(hanging.runReply('room',h.trigger),/timed out/);assert.equal(hanging.inFlight.size,0)
 const ai=await h.ai();await ai.runReply('room',h.trigger)
 assert.equal(await db.message.count(),2);assert.deepEqual(h.events[0][1],['bot','human'])
}))
test('real command entry creates renderable polls and wallet read does not create a wallet',()=>fixture(async(db,h)=>{
 const commands=await h.commands()
 await db.message.update({where:{id:'source'},data:{content:'/poll Play? | Yes | No'}})
 await commands('room',{...h.trigger,content:'/poll Play? | Yes | No'})
 assert.equal((await db.message.findFirst({where:{senderId:'bot'}})).kind,'poll')
 await db.message.create({data:{id:'wallet-source',conversationId:'room',senderId:'human',content:'/wallet'}})
 await commands('room',{id:'wallet-source',senderId:'human',content:'/wallet'})
 assert.equal(await db.userWallet.count(),0)
 assert.equal(h.events.length,2)
 assert.ok(h.events.every(e=>e[1].includes('bot')))
}))
