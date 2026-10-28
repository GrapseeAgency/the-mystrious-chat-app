import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
import { DirectConversationError, openSelfConversation } from '../../src/lib/direct-conversation.ts'
import * as reminders from '../../src/lib/reminder-contract.ts'
import * as reads from '../../src/lib/read-receipt.ts'
import * as sync from '../../src/lib/message-sync.ts'
import * as feedPagination from '../../src/lib/feed-pagination.ts'
import * as visibility from '../../src/lib/message-visibility.ts'
import { visibleSearchHit } from '../../src/lib/search-visibility.ts'
import { clipUtf16 } from '../../src/lib/text-limits.ts'
import { deriveSafetyNumber } from '../../src/lib/safety.ts'
import { peerBlock, PeerBlockError } from '../../src/lib/peer-block.ts'
import * as personalSettings from '../../src/lib/conversation-personal-settings.ts'
import { setConversationPin, ConversationPinError } from '../../src/lib/conversation-pin.ts'
import * as metadata from '../../src/lib/conversation-metadata.ts'
import { ConversationMetadataError, patchMyScreenPrivacy } from '../../src/lib/conversation-metadata.ts'
import { privacyPreferenceSnapshot } from '../../src/lib/privacy-preferences.ts'
const { PrismaClient, Prisma } = createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path, bindings, expression) {
  const text = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}
async function fixture(run, database = db => db) {
  const directory = await mkdtemp(join(tmpdir(), 'settings-order-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(directory, 'test.sqlite')}` })
  try {
    for (const name of ['User', 'Conversation', 'ConversationParticipant', 'ReadIntentState', 'UserBlock', 'AccountReport', 'UserVerification', 'Message', 'SavedMessage', 'Reminder']) {
      const model = Prisma.dmmf.datamodel.models.find(m => m.name === name)
      const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
        const type = ['Int','Boolean','BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'Float' ? 'REAL' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
        let def = ''
        if (typeof f.default === 'string') def = ` DEFAULT '${f.default.replaceAll("'", "''")}'`
        else if (typeof f.default === 'number' || typeof f.default === 'boolean') def = ` DEFAULT ${Number(f.default)}`
        else if (f.default?.name === 'now') def = ' DEFAULT CURRENT_TIMESTAMP'
        return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : ''}${def}`
      })
      await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
    }
    const readSQL = await readFile(new URL('../../prisma/patches/20261026_exact_read_observations.sql', import.meta.url), 'utf8')
    await db.$transaction(async tx => {
      for (const statement of readSQL.replace(/^--.*$/gm, '').split(';').map(s => s.trim()).filter(s => s && !['BEGIN IMMEDIATE','COMMIT'].includes(s))) await tx.$executeRawUnsafe(statement)
    })
    await db.$executeRawUnsafe('CREATE UNIQUE INDEX "UserBlock_pair" ON "UserBlock" ("blockerId", "blockedId")')
    await db.$executeRawUnsafe('CREATE UNIQUE INDEX "AccountReport_pair_reason" ON "AccountReport" ("reporterId", "reportedId", "reason")')
    await db.$executeRawUnsafe('CREATE UNIQUE INDEX "UserVerification_pair" ON "UserVerification" ("ownerId", "peerId")')
    for (const id of ['owner','other']) await db.user.create({ data: {
      id, name:id, color:'amber', preferences: '{"readReceipts":true,"density":"cozy"}',
      sessionTokenHash:hashSessionToken(`synthetic-${id}`), legacyTokenHashes:'[]',
    } })
    const {requireActionOwner,requireActionActor} = await load('src/lib/wallet-authorization.ts', {db,bearerToken,tokenAccepted,hashSessionToken}, '({requireActionOwner,requireActionActor})')
    const mergePrefs = await load('src/lib/prefs-defaults.ts', {sanitizeConvThemeMap:()=>{throw Error('Unexpected theme branch')}}, 'mergePrefs')
    const route = await load('src/app/api/settings/route.ts', { db:database(db), requireActionOwner, mergePrefs,
      privacyPreferenceSnapshot, NextResponse:{json:Response.json} }, '({GET,PATCH})')
    const patch = (preferences,token='synthetic-owner',userId='owner') => route.PATCH(new Request('https://settings.test', {
      method:'PATCH', headers:token ? {authorization:`Bearer ${token}`} : {}, body:JSON.stringify({userId,preferences}),
    }))
    const get = (token='synthetic-owner',userId='owner') => route.GET(new Request(`https://settings.test?userId=${userId}`, {
      headers:token ? {authorization:`Bearer ${token}`} : {},
    }))
    await db.conversation.create({data:{id:'room',isGroup:true,screenPrivacy:true}})
    await db.conversationParticipant.create({data:{userId:'owner',conversationId:'room',screenPrivacy:true,role:'admin'}})
    await db.conversationParticipant.create({data:{userId:'other',conversationId:'room',screenPrivacy:true}})
    const notices=[]
    const privacy=await load('src/app/api/conversations/[id]/screen-privacy/route.ts', {
      db, requireActionOwner, ConversationMetadataError, patchMyScreenPrivacy, NextResponse:Response,
      safeJson:r=>r.json(), strField:v=>typeof v==='string'?v.trim():'', notifySocket:async(...args)=>notices.push(args),
    }, 'PATCH')
    const setPrivacy=(on,token='synthetic-owner',userId='owner')=>privacy(new Request('https://settings.test/api/conversations/room/screen-privacy', {
      method:'PATCH',headers:token?{authorization:`Bearer ${token}`}:{},body:JSON.stringify({userId,on}),
    }), {params:Promise.resolve({id:'room'})})
    const readHints = []
    const roomDeps={...metadata,db,requireActionOwner,NextResponse:Response,GROUP_NAME_MAX:80,
      invalidateNativeReadState:(...args)=>readHints.push(args),
      CONVERSATION_FULL_INCLUDE:{participants:true},
      buildConversationDetail:async row=>({id:row.id,screenPrivacy:row.screenPrivacy,ttlSeconds:row.ttlSeconds}),
      safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',notifySocket:async(...args)=>notices.push(args),
    }
    const details=await load('src/app/api/conversations/[id]/route.ts',roomDeps,'({GET,PATCH})')
    const ttl=await load('src/app/api/conversations/[id]/disappearing/route.ts',roomDeps,'PATCH')
    const slow=await load('src/app/api/conversations/[id]/slow-mode/route.ts',roomDeps,'PATCH')
    const roomAction=(kind,body={},token='synthetic-owner',actor='owner')=>{
      const read=kind==='detail'
      const handler=read?details.GET:kind==='metadata'?details.PATCH:kind==='ttl'?ttl:slow
      return handler(new Request(`https://settings.test/api/conversations/room?userId=${actor}`,{
        method:read?'GET':'PATCH',headers:token?{authorization:`Bearer ${token}`}:{},
        ...(read?{}:{body:JSON.stringify({userId:actor,requesterId:actor,...body})}),
      }),{params:Promise.resolve({id:'room'})})
    }
    const personalRoutes={}
    for(const kind of ['mute','archive','pin']) personalRoutes[kind]=await load(`src/app/api/conversations/[id]/${kind}/route.ts`,
      {...roomDeps,...personalSettings,setConversationPin,ConversationPinError},'PATCH')
    const setPersonal=(kind,body,token='synthetic-owner',userId='owner')=>personalRoutes[kind](new Request(`https://settings.test/api/conversations/room/${kind}`,{
      method:'PATCH',headers:token?{authorization:`Bearer ${token}`}:{},body:JSON.stringify({userId,...body}),
    }),{params:Promise.resolve({id:'room'})})
    const blockDeps = {db,requireActionOwner,peerBlock,PeerBlockError,NextResponse:Response,
      safeJson:r=>r.json(),strField:v=>typeof v==='string'?v.trim():'',notifySocket:async(...args)=>notices.push(args)}
    const pairRoutes=await load('src/app/api/users/[id]/block/route.ts',blockDeps,'({GET,POST,DELETE})')
    const listRoute=await load('src/app/api/users/[id]/blocks/route.ts',blockDeps,'GET')
    const peer=(method,token='synthetic-owner',actor='owner',target='other')=>pairRoutes[method](new Request(`https://settings.test?userId=${actor}`,{
      method,headers:token?{authorization:`Bearer ${token}`}:{},...(method==='POST'?{body:JSON.stringify({userId:actor})}:{})
    }),{params:Promise.resolve({id:target})})
    const blocks=(token='synthetic-owner',actor='owner',target='owner')=>listRoute(new Request(`https://settings.test?userId=${actor}`,{
      headers:token?{authorization:`Bearer ${token}`}:{},
    }),{params:Promise.resolve({id:target})})
    const reportRoutes=await load('src/app/api/users/[id]/report/route.ts',blockDeps,'({GET,POST})')
    const report=(method,body={},token='synthetic-owner',actor='owner',target='other')=>reportRoutes[method](new Request(`https://settings.test?userId=${actor}`,{
      method,headers:token?{authorization:`Bearer ${token}`}:{},...(method==='POST'?{body:JSON.stringify({userId:actor,reason:'spam',...body})}:{})
    }),{params:Promise.resolve({id:target})})
    const safetyRoutes=await load('src/app/api/users/[id]/safety/route.ts',{...blockDeps,deriveSafetyNumber},'({GET,POST,DELETE})')
    const safety=(method,token='synthetic-owner',actor='owner',target='other')=>safetyRoutes[method](new Request(`https://settings.test?userId=${actor}`,{
      method,headers:token?{authorization:`Bearer ${token}`}:{},...(method==='POST'?{body:JSON.stringify({userId:actor})}:{})
    }),{params:Promise.resolve({id:target})})
    const notebookRoute = await load('src/app/api/conversations/self/route.ts', {
      ...blockDeps, DirectConversationError, openSelfConversation, CONVERSATION_FULL_INCLUDE:{participants:true},
      buildConversationSummary:async row=>({id:row.id,isSelf:row.isSelf,isGroup:row.isGroup,members:row.participants.map(p=>({id:p.userId}))}),
    }, 'POST')
    const notebook=(token='synthetic-owner',userId='owner')=>notebookRoute(new Request('https://settings.test/api/conversations/self',{
      method:'POST',headers:token?{authorization:`Bearer ${token}`}:{},body:JSON.stringify({userId}),
    }))
    const reminderDeps={...blockDeps,...reminders,clipUtf16,parseIsoDate:value=>typeof value==='string' && Number.isFinite(Date.parse(value))?new Date(value):null}
    const reminderList=await load('src/app/api/reminders/route.ts',reminderDeps,'({GET,POST})')
    const reminderItem=await load('src/app/api/reminders/[id]/route.ts',reminderDeps,'({PATCH,DELETE})')
    const reminder=(method,body={},token='synthetic-owner',userId='owner',id='private-reminder')=>
      (reminderList[method]??reminderItem[method])(new Request(`https://settings.test/api/reminders?userId=${userId}`,{
        method,headers:token?{authorization:`Bearer ${token}`}:{},...(method==='GET'?{}:{body:JSON.stringify({userId,...body})}),
      }),{params:Promise.resolve({id})})
    const conversationList = await load('src/app/api/conversations/route.ts', {
      ...blockDeps, CONVERSATION_FULL_INCLUDE:{participants:true},
      buildConversationSummary:async row=>({id:row.id,updatedAt:row.updatedAt.toISOString(),pinnedAt:null}),
    }, '({GET,POST})')
    const legacyRead=await load('src/app/api/conversations/[id]/read/route.ts',{
      ...blockDeps,...reads,memberIdsOf:async()=>['owner','other'],invalidateNativeReadState:()=>{},
    },'POST')
    const messagesRoute=await load('src/app/api/conversations/[id]/messages/route.ts',{
      ...blockDeps,...sync,requireActionActor,parseIsoDate:reminderDeps.parseIsoDate,
      MESSAGES_DEFAULT_LIMIT:200,MESSAGES_MAX_LIMIT:100,MESSAGE_FULL_INCLUDE:{},mapMessage:row=>({id:row.id,content:row.content}),
    },'({GET,POST})')
    const chat=(kind,token='synthetic-owner',body={},query='')=>{
      const reading=kind==='list'||kind==='history'
      const handler=kind==='list'?conversationList.GET:kind==='create'?conversationList.POST:kind==='read'?legacyRead:reading?messagesRoute.GET:messagesRoute.POST
      return handler(new Request(`https://settings.test/api?userId=owner${query}`,{
        method:reading?'GET':'POST',headers:token?{authorization:`Bearer ${token}`}:{},
        ...(reading?{}:{body:JSON.stringify({userId:'owner',creatorId:'owner',senderId:'owner',...body})}),
      }),{params:Promise.resolve({id:'room'})})
    }
    const draftRoute=await load('src/app/api/conversations/[id]/draft/route.ts',blockDeps,'({GET,PATCH})')
    const draft=(method,text,token='synthetic-owner',userId='owner')=>draftRoute[method](new Request(`https://settings.test/api/conversations/room/draft?userId=${userId}`,{
      method,headers:token?{authorization:`Bearer ${token}`}:{},...(method==='GET'?{}:{body:JSON.stringify({userId,draft:text})}),
    }),{params:Promise.resolve({id:'room'})})
    const feeds={}
    for(const kind of ['search','mentions']) feeds[kind]=await load(`src/app/api/${kind}/route.ts`,{
      ...blockDeps,...visibility,...feedPagination,clipUtf16,visibleSearchHit,MESSAGE_FULL_INCLUDE:{sender:true},
      mapMessage:row=>({...row,createdAt:row.createdAt.toISOString(),expiresAt:row.expiresAt?.toISOString()??null}),
    },'GET')
    const feed=(kind,token='synthetic-owner',q='private',cursor=null)=>feeds[kind](new Request(`https://settings.test/api/${kind}?userId=owner&q=${encodeURIComponent(q)}${cursor?'&cursor='+encodeURIComponent(cursor):''}`,{
      headers:token?{authorization:`Bearer ${token}`}:{},
    }))
    const savedRoute=await load('src/app/api/users/[id]/saved/route.ts',{
      ...blockDeps,...visibility,...feedPagination,MESSAGE_FULL_INCLUDE:{sender:true},
      mapMessage:row=>({...row,createdAt:row.createdAt.toISOString(),expiresAt:row.expiresAt?.toISOString()??null}),
    },'GET')
    const saved=(cursor=null,token='synthetic-owner')=>savedRoute(new Request(`https://settings.test/api/users/owner/saved${cursor?'?cursor='+encodeURIComponent(cursor):''}`,{
      headers:token?{authorization:`Bearer ${token}`}:{},
    }),{params:Promise.resolve({id:'owner'})})
    const readRoutes={}
    for (const [kind,method] of [['read-intent','GET'],['read-state','POST'],['mark-unread','PATCH']]) {
      readRoutes[kind]=await load(`src/app/api/conversations/[id]/${kind}/route.ts`,{
        ...blockDeps,...reads,memberIdsOf:async()=>['owner','other'],invalidateNativeReadState:()=>{},
      },method)
    }
    const attention=(kind,body={},token='synthetic-owner',userId='owner')=>readRoutes[kind](new Request(
      `https://settings.test/api/conversations/room/${kind}?userId=${userId}`,{
        method:kind==='read-intent'?'GET':kind==='read-state'?'POST':'PATCH',
        headers:token?{authorization:`Bearer ${token}`}:{},
        ...(kind==='read-intent'?{}:{body:JSON.stringify({userId,...body})}),
      }),{params:Promise.resolve({id:'room'})})
    await run({db,patch,get,setPrivacy,notices,readHints,roomAction,setPersonal,peer,blocks,report,safety,notebook,reminder,chat,draft,feed,saved,attention})
  } finally { await db.$disconnect(); await rm(directory,{recursive:true,force:true}) }
}

test('settings read/write credentials belong to the target, not merely some user', () => fixture(async({db,patch,get})=>{
  for(const token of [null,'synthetic-other','invalid']) {
    const expected = token ? 403 : 401
    assert.equal((await patch({readReceipts:false},token)).status,expected)
    assert.equal((await get(token)).status,expected)
  }
  assert.equal((await patch({readReceipts:false},'synthetic-owner','other')).status,403)
  assert.equal(JSON.parse((await db.user.findUnique({where:{id:'owner'}})).preferences).readReceipts,true)
  const response = await get(); assert.equal(response.status,200)
  assert.equal(response.headers.get('cache-control'),'private, no-store')
}))

test('simultaneous settings merges preserve both independent changes', async()=>{
  let arrivals=0,release
  const barrier = new Promise(resolve=>{release=resolve})
  await fixture(async({db,patch})=>{
    const responses = await Promise.all([patch({readReceipts:false}),patch({density:'compact'})])
    assert.deepEqual(responses.map(r=>r.status),[200,200])
    const stored = JSON.parse((await db.user.findUnique({where:{id:'owner'}})).preferences)
    assert.equal(stored.readReceipts,false); assert.equal(stored.density,'compact')
  },db=>({user:{
    findUnique:async args=>{
      const snapshot=await db.user.findUnique(args)
      if(++arrivals<=2){if(arrivals===2)release();await barrier}
      return snapshot
    },
    updateMany:args=>db.user.updateMany(args),
  }}))
})

test('exhausted contention returns a retryable conflict instead of a fake receipt',()=>fixture(async({db,patch})=>{
  const response=await patch({readReceipts:false})
  assert.equal(response.status,409);assert.equal((await response.json()).code,'settings_conflict')
  assert.equal(response.headers.get('retry-after'),'1')
  assert.equal(JSON.parse((await db.user.findUnique({where:{id:'owner'}})).preferences).readReceipts,true)
},db=>({user:{findUnique:args=>db.user.findUnique(args),updateMany:async()=>({count:0})}})))

test('malformed privacy cannot mutate and repair does not silently opt in',()=>fixture(async({db,patch,get})=>{
  await db.user.update({where:{id:'owner'},data:{preferences:'{broken'}})
  assert.equal((await patch({readReceipts:'true'})).status,400)
  assert.equal((await patch({density:'compact'})).status,200)
  const preferences=(await (await get()).json()).preferences
  for(const key of ['readReceipts','lastSeenVisible','typingVisible','notifPreviews']) assert.equal(preferences[key],false)
}))


test('personal room privacy requires its owner credential and repeated desired-state writes are safe',()=>fixture(async({db,setPrivacy,notices})=>{
  for(const token of [null,'invalid','synthetic-other']) {
    assert.equal((await setPrivacy(false,token)).status,token?403:401)
  }
  assert.equal(notices.length,0)
  assert.equal((await setPrivacy('false')).status,400)
  for(let retry=0;retry<2;retry++) {
    const response=await setPrivacy(false)
    assert.equal(response.status,200)
    assert.deepEqual(await response.json(),{ok:true,screenPrivacy:false})
  }
  const rows=await db.conversationParticipant.findMany({where:{conversationId:'room'}})
  assert.equal(rows.find(r=>r.userId==='owner').screenPrivacy,false)
  assert.equal(rows.find(r=>r.userId==='other').screenPrivacy,true)
  assert.ok(notices.every(([,recipients])=>JSON.stringify(recipients)==='["owner"]'))
  await db.conversationParticipant.deleteMany({where:{userId:'owner'}})
  assert.equal((await setPrivacy(true)).status,403)
}))


test('room detail and shared controls require the current participant credential even when the header is absent',()=>fixture(async({db,roomAction,notices})=>{
  const requests=[['detail',{}],['metadata',{screenPrivacy:false}],['ttl',{ttlSeconds:86400}],['slow',{seconds:5}]]
  for(const [kind,body] of requests) for(const token of [null,'invalid','synthetic-other']) {
    assert.equal((await roomAction(kind,body,token)).status,token?403:401)
  }
  let stored=await db.conversation.findUnique({where:{id:'room'}})
  assert.equal(stored.screenPrivacy,true);assert.equal(stored.ttlSeconds,0);assert.equal(stored.slowModeSeconds,0)
  assert.equal(notices.length,0)
  for(const [kind,body] of requests) {
    const response=await roomAction(kind,body)
    assert.equal(response.status,200)
    if(kind==='detail')assert.equal(response.headers.get('cache-control'),'private, no-store')
  }
  stored=await db.conversation.findUnique({where:{id:'room'}})
  assert.equal(stored.screenPrivacy,false);assert.equal(stored.ttlSeconds,86400);assert.equal(stored.slowModeSeconds,5)
  await db.conversationParticipant.deleteMany({where:{userId:'owner'}})
  assert.equal((await roomAction('detail')).status,404)
  assert.equal((await roomAction('metadata',{screenPrivacy:true})).status,403)
}))


test('personal conversation flags require owner credentials and return owned timestamp receipts',()=>fixture(async({db,setPersonal,notices})=>{
  for(const [kind,body] of [['mute',{until:'8h'}],['archive',{archived:true}],['pin',{pinned:true}]]) {
    for(const token of [null,'invalid','synthetic-other']) assert.equal((await setPersonal(kind,body,token)).status,token?403:401)
  }
  assert.equal(notices.length,0)
  const mute=await (await setPersonal('mute',{until:'8h'})).json()
  assert.equal(mute.conversationPreferenceVersion,1);assert.equal(mute.conversationId,'room');assert.equal(mute.userId,'owner')
  assert.equal(mute.mutePreset,'8h')
  const row=await db.conversationParticipant.findFirst({where:{userId:'owner'}})
  assert.equal(row.mutedUntil.toISOString(),mute.mutedUntil)
  const archive=await (await setPersonal('archive',{archived:true})).json()
  assert.deepEqual(await (await setPersonal('archive',{archived:true})).json(),archive)
  const pin=await (await setPersonal('pin',{pinned:true})).json()
  assert.deepEqual(await (await setPersonal('pin',{pinned:true})).json(),pin)
  for(const [kind,body,field] of [['mute',{until:null},'mutedUntil'],['archive',{archived:false},'archivedAt'],['pin',{pinned:false},'pinnedAt']]) {
    const response=await setPersonal(kind,body);assert.equal(response.status,200)
    assert.equal((await response.json())[field],null)
  }
  assert.ok(notices.every(([,recipients])=>JSON.stringify(recipients)==='["owner"]'))
  const other=await db.conversationParticipant.findFirst({where:{userId:'other'}})
  assert.equal(other.mutedUntil,null);assert.equal(other.archivedAt,null);assert.equal(other.pinnedAt,null)
  await db.conversationParticipant.deleteMany({where:{userId:'owner'}})
  for(const [kind,body] of [['mute',{until:'8h'}],['archive',{archived:true}],['pin',{pinned:true}]]) {
    assert.equal((await setPersonal(kind,body)).status,403)
  }
}))
test('missing or inherited mute presets cannot silently unmute or crash the route',()=>fixture(async({db,setPersonal})=>{
  await setPersonal('mute',{until:'1w'})
  const before=(await db.conversationParticipant.findFirst({where:{userId:'owner'}})).mutedUntil.toISOString()
  for(const until of [undefined,true,0,[],{},'constructor','__proto__','bad']) {
    assert.equal((await setPersonal('mute',{until})).status,400)
    assert.equal((await db.conversationParticipant.findFirst({where:{userId:'owner'}})).mutedUntil.toISOString(),before)
  }
  assert.equal((await setPersonal('archive',{archived:'false'})).status,400)
  assert.equal((await setPersonal('mute',{until:'off'})).status,200)
  assert.equal((await db.conversationParticipant.findFirst({where:{userId:'owner'}})).mutedUntil,null)
}))
test('personal mute and archive contention are retryable and do not return invented receipts',async()=>{
  const db={$transaction:async()=>{throw {code:'P2034'}}}
  await assert.rejects(personalSettings.setConversationMute(db,'room','owner','8h'),{status:409})
  await assert.rejects(personalSettings.setConversationArchive(db,'room','owner',true),{status:409})
})


test('peer block and private list require owner credentials, not matching public IDs', () => fixture(async({db,peer,blocks})=>{
  for(const token of [null,'synthetic-other','invalid']) {
    for(const method of ['GET','POST','DELETE']) assert.equal((await peer(method,token)).status,token?403:401)
    assert.equal((await blocks(token)).status,token?403:401)
  }
  assert.equal((await blocks('synthetic-other','other','owner')).status,403)
  assert.equal(await db.userBlock.count(),0)
}))

test('pair receipts confirm desired state; block/unblock are repeatable and actor-private', () => fixture(async({db,peer,blocks,notices})=>{
  await db.conversation.update({where:{id:'room'},data:{isGroup:false}})
  for(const [method,expected] of [['GET',false],['POST',true],['POST',true],['GET',true],['DELETE',false],['DELETE',false]]) {
    const response=await peer(method)
    assert.equal(response.status,200)
    assert.equal(response.headers.get('cache-control'),'private, no-store')
    assert.deepEqual(await response.json(),{ok:true,blockReceiptVersion:1,actorId:'owner',targetId:'other',blocked:expected})
    assert.equal(await db.userBlock.count(),expected?1:0)
    const list=await blocks(); assert.equal(list.headers.get('cache-control'),'private, no-store')
    const body=await list.json();assert.equal(body.blockListVersion,1);assert.equal(body.userId,'owner')
    assert.deepEqual(body.blocks.map(row=>row.id),expected?['other']:[])
    if(expected) assert.ok(Number.isFinite(Date.parse(body.blocks[0].blockedAt)))
  }
  assert.equal(notices.length,4)
  for(const [event,recipients,payload] of notices) {
    assert.equal(event,'conversation:updated');assert.deepEqual(recipients,['owner'])
    assert.deepEqual(payload.recipientIds,['owner'])
  }
  assert.deepEqual((await (await blocks('synthetic-other','other','other')).json()).blocks,[])
}))

test('self or missing peer mutations cannot yield successful block receipts', () => fixture(async({db,peer})=>{
  for(const method of ['POST','DELETE']) assert.equal((await peer(method,'synthetic-owner','owner','owner')).status,400)
  for(const method of ['GET','POST','DELETE']) assert.equal((await peer(method,'synthetic-owner','owner','missing')).status,404)
  assert.equal(await db.userBlock.count(),0)
}))


test('private report reads and writes cannot impersonate a public reporter ID',()=>fixture(async({db,report,notices})=>{
  for(const token of [null,'invalid','synthetic-other']) for(const method of ['GET','POST'])
    assert.equal((await report(method,{},token)).status,token?403:401)
  assert.equal(await db.accountReport.count(),0);assert.deepEqual(notices,[])
  assert.equal((await report('POST',{},'synthetic-owner','owner','owner')).status,400)
  assert.equal((await report('POST',{},'synthetic-owner','owner','missing')).status,404)
  assert.equal((await report('GET',{},'synthetic-owner','owner','missing')).status,404)
}))

test('report receipts confirm exact evidence, repeated reason updates one row and history stays private',()=>fixture(async({db,report,notices})=>{
  for(const [details,updated] of [[' initial evidence ',false],['new evidence 😀',true]]) {
    const response=await report('POST',{details});assert.equal(response.status,updated?200:201)
    assert.equal(response.headers.get('cache-control'),'private, no-store')
    const receipt=await response.json();assert.ok(Number.isFinite(Date.parse(receipt.reportedAt)))
    assert.deepEqual({...receipt,reportedAt:null},{ok:true,reportReceiptVersion:1,reporterId:'owner',reportedId:'other',
      reason:'spam',details,reported:true,updated,reportedAt:null})
    assert.equal(await db.accountReport.count(),1)
    assert.equal((await db.accountReport.findFirst()).details,details)
  }
  const response=await report('GET');assert.equal(response.headers.get('cache-control'),'private, no-store')
  const history=await response.json();assert.equal(history.reportHistoryVersion,1);assert.equal(history.reporterId,'owner')
  assert.equal(history.reportedId,'other');assert.deepEqual(history.reasons.map(row=>row.reason),['spam'])
  assert.ok(Number.isFinite(Date.parse(history.reasons[0].at)))
  const other=await (await report('GET',{},'synthetic-other','other','owner')).json();assert.deepEqual(other.reasons,[])
  assert.deepEqual(notices,[])
}))

test('report details enforce UTF16 limit without silently truncating or coercing evidence',()=>fixture(async({db,report})=>{
  const details='😀'.repeat(250)
  assert.equal((await report('POST',{details})).status,201)
  for(const bad of [details+'x', '😀'.repeat(251),123,{},[]]) assert.equal((await report('POST',{details:bad})).status,400)
  assert.equal((await db.accountReport.findFirst()).details,details)
  assert.equal((await report('POST',{reason:'constructor'})).status,400)
  assert.equal(await db.accountReport.count(),1)
}))


test('account comparison marks require the owner credential and remain directional',()=>fixture(async({db,safety})=>{
  for(const token of [null,'synthetic-other','invalid']) for(const method of ['GET','POST','DELETE'])
    assert.equal((await safety(method,token)).status,token?403:401)
  for(const [method,verified] of [['GET',false],['POST',true],['POST',true],['GET',true],['DELETE',false],['DELETE',false]]) {
    const response=await safety(method);assert.equal(response.status,200)
    assert.equal(response.headers.get('cache-control'),'private, no-store')
    const receipt=await response.json()
    assert.deepEqual({...receipt,verifiedAt:null},{ok:true,safetyReceiptVersion:1,ownerId:'owner',peerId:'other',
      safetyNumber:deriveSafetyNumber('owner','other'),verified,verifiedAt:null})
    assert.match(receipt.safetyNumber,/^[0-9]{5}( [0-9]{5}){11}$/)
    if(verified) assert.ok(Number.isFinite(Date.parse(receipt.verifiedAt)))
    else assert.equal(receipt.verifiedAt,null)
    const other=await (await safety('GET','synthetic-other','other','owner')).json()
    assert.equal(other.verified,false);assert.equal(other.safetyNumber,receipt.safetyNumber)
    assert.equal(await db.userVerification.count(),verified?1:0)
  }
  assert.equal((await safety('POST','synthetic-owner','owner','owner')).status,400)
  for(const method of ['GET','POST','DELETE']) assert.equal((await safety(method,'synthetic-owner','owner','missing')).status,404)
}))


test('private notebook cannot be opened or created by matching public IDs without the owner credential',()=>fixture(async({db,notebook})=>{
  for(const token of [null,'invalid','synthetic-other']) assert.equal((await notebook(token)).status,token?403:401)
  assert.equal(await db.conversation.count({where:{isSelf:true}}),0)
  const first=await notebook(); assert.equal(first.status,201)
  assert.equal(first.headers.get('cache-control'),'private, no-store')
  const receipt=await first.json();assert.equal(receipt.notebookReceiptVersion,1);assert.equal(receipt.userId,'owner')
  assert.equal(receipt.conversation.isSelf,true);assert.equal(receipt.conversation.isGroup,false)
  assert.deepEqual(receipt.conversation.members,[{id:'owner'}])
  for(const token of [null,'synthetic-other']) assert.equal((await notebook(token)).status,token?403:401)
  const retry=await notebook();assert.equal(retry.status,200);assert.deepEqual(await retry.json(),receipt)
  assert.equal(await db.conversation.count({where:{isSelf:true}}),1)
  const other=await notebook('synthetic-other','other');assert.equal(other.status,201)
  const otherBody=await other.json();assert.notEqual(otherBody.conversation.id,receipt.conversation.id)
  assert.deepEqual(otherBody.conversation.members,[{id:'other'}])
}))


test('reminder list create resolve and delete require the exact credential before accessing private rows',()=>fixture(async({db,reminder})=>{
  const remindAt=new Date(Date.now()+60_000)
  await db.reminder.create({data:{id:'private-reminder',userId:'owner',conversationId:'room',note:'private note',remindAt}})
  for(const method of ['GET','POST','PATCH','DELETE']) {
    for(const token of [null,'invalid','synthetic-other']) {
      const response=await reminder(method,{conversationId:'room',note:'injected',remindAt:remindAt.toISOString()},token)
      assert.equal(response.status,token?403:401,method)
      assert.equal(JSON.stringify(await response.json()).includes('private note'),false)
    }
  }
  assert.equal(await db.reminder.count(),1)
  assert.equal((await db.reminder.findUnique({where:{id:'private-reminder'}})).firedAt,null)
  const own=await reminder('GET');assert.equal(own.status,200);assert.equal(own.headers.get('cache-control'),'private, no-store')
  assert.equal((await own.json()).items[0].note,'private note')
  const created=await reminder('POST',{conversationId:'room',note:'new note',remindAt:remindAt.toISOString()})
  assert.equal(created.status,201)
  const resolved=await reminder('PATCH');assert.equal(resolved.status,200)
  assert.equal((await resolved.json()).ok,true)
  assert.equal((await reminder('DELETE')).status,200)
  assert.equal((await reminder('DELETE')).status,200)
}))

test('reminder context stops revealing expired or inaccessible anchors without deleting the owners own notes',()=>fixture(async({db,reminder})=>{
  const remindAt=new Date(Date.now()+60_000)
  await db.conversation.update({where:{id:'room'},data:{name:'Private room'}})
  await db.message.create({data:{id:'anchor',conversationId:'room',senderId:'other',content:'a'.repeat(119)+'🌿tail'}})
  await db.reminder.create({data:{id:'private-reminder',userId:'owner',conversationId:'room',messageId:'anchor',note:'my retained note',remindAt}})
  const row=async()=>(await (await reminder('GET')).json()).items[0]
  assert.equal((await row()).snippet,'a'.repeat(119))
  await db.message.update({where:{id:'anchor'},data:{expiresAt:new Date(0)}})
  assert.equal((await row()).snippet,null)
  assert.equal((await reminder('POST',{conversationId:'room',messageId:'anchor',remindAt:remindAt.toISOString()})).status,400)
  await db.message.update({where:{id:'anchor'},data:{expiresAt:null,deletedAt:new Date()}})
  assert.equal((await row()).snippet,null)
  await db.message.update({where:{id:'anchor'},data:{deletedAt:null}})
  await db.conversationParticipant.deleteMany({where:{conversationId:'room',userId:'owner'}})
  const revoked=await row();assert.equal(revoked.snippet,null);assert.equal(revoked.conversation.name,'Chat');assert.equal(revoked.note,'my retained note')
}))

test('reminder thread anchors need a live canonical root in the same conversation',()=>fixture(async({db,reminder})=>{
  const remindAt=new Date(Date.now()+60_000).toISOString()
  await db.message.create({data:{id:'root',conversationId:'room',senderId:'other',content:'root',expiresAt:new Date(0)}})
  await db.message.create({data:{id:'reply',conversationId:'room',senderId:'other',content:'private reply',parentId:'root'}})
  const create=()=>reminder('POST',{conversationId:'room',messageId:'reply',note:'my note',remindAt})
  assert.equal((await create()).status,400)
  await db.message.update({where:{id:'root'},data:{expiresAt:null}})
  assert.equal((await create()).status,201)
  let list=await (await reminder('GET')).json();assert.equal(list.items[0].snippet,'private reply')
  await db.message.update({where:{id:'root'},data:{deletedAt:new Date()}})
  list=await (await reminder('GET')).json();assert.equal(list.items[0].snippet,null)
}))


test('conversation list creation send and legacy mark-all cannot be impersonated with public IDs',()=>fixture(async({db,chat,notices})=>{
  await db.conversationParticipant.updateMany({where:{userId:'owner'},data:{manualUnread:true,lastReadAt:new Date(0)}})
  await db.message.create({data:{id:'visible',conversationId:'room',senderId:'other',content:'private chat content'}})
  for(const kind of ['list','create','send','read']) for(const token of [null,'invalid','synthetic-other']) {
    const response=await chat(kind,token,{memberIds:['other'],content:'impersonated'})
    assert.equal(response.status,token?403:401,kind)
    assert.equal(JSON.stringify(await response.json()).includes('private chat content'),false)
  }
  assert.equal(await db.message.count(),1);assert.equal(await db.conversation.count(),1)
  assert.equal((await db.conversationParticipant.findFirst({where:{userId:'owner'}})).manualUnread,true)
  assert.equal(notices.length,0)
  assert.equal((await chat('list')).status,200)
  assert.equal((await chat('read')).status,200)
  assert.equal((await db.conversationParticipant.findFirst({where:{userId:'owner'}})).manualUnread,true)
}))

test('history search and sync require credential-derived room membership before even the expiry purge',()=>fixture(async({db,chat})=>{
  await db.message.create({data:{id:'visible',conversationId:'room',senderId:'other',content:'private chat content'}})
  await db.message.create({data:{id:'expired',conversationId:'room',senderId:'other',content:'expired',expiresAt:new Date(0)}})
  await db.conversationParticipant.deleteMany({where:{userId:'other'}})
  for(const query of ['', '&q=private', '&syncFrom=2026-01-01T00:00:00Z']) {
    for(const token of [null,'invalid','synthetic-other']) {
      assert.equal((await chat('history',token,{},query)).status,token?403:401)
      assert.equal(await db.message.count(),2)
    }
  }
  const allowed=await chat('history');assert.equal(allowed.status,200);assert.equal(allowed.headers.get('cache-control'),'private, no-store')
  assert.deepEqual((await allowed.json()).messages,[{id:'visible',content:'private chat content'}])
  assert.equal(await db.message.count(),1)
  await db.user.update({where:{id:'owner'},data:{sessionTokenHash:hashSessionToken('rotated'),legacyTokenHashes:JSON.stringify([hashSessionToken('synthetic-owner')])}})
  assert.equal((await chat('history')).status,200)
  await db.user.update({where:{id:'owner'},data:{legacyTokenHashes:'[]'}})
  assert.equal((await chat('history')).status,403)
  await db.user.update({where:{id:'owner'},data:{sessionTokenHash:hashSessionToken('synthetic-owner')}})
  await db.user.update({where:{id:'other'},data:{sessionTokenHash:hashSessionToken('synthetic-owner')}})
  assert.equal((await chat('history')).status,403,'ambiguous credential must fail closed')
}))


test('drafts require the exact owner and an oversize write never clips or changes the previous draft',()=>fixture(async({db,draft})=>{
  await db.conversationParticipant.updateMany({where:{userId:'owner'},data:{draft:'private unsent text'}})
  for(const method of ['GET','PATCH']) for(const token of [null,'invalid','synthetic-other']) {
    const response=await draft(method,'injected',token)
    assert.equal(response.status,token?403:401)
    assert.equal(JSON.stringify(await response.json()).includes('private unsent text'),false)
  }
  for(const text of ['x'.repeat(2001),'a'.repeat(1999)+'🌿','🌿'.repeat(1001),null,5]) {
    assert.equal((await draft('PATCH',text)).status,400)
    assert.equal((await db.conversationParticipant.findFirst({where:{userId:'owner'}})).draft,'private unsent text')
  }
  for(const text of ['🌿'.repeat(1000),'  spaces stay  ','','']) {
    const written=await draft('PATCH',text);assert.equal(written.status,200)
    assert.equal(written.headers.get('cache-control'),'private, no-store')
    assert.deepEqual(await written.json(),{ok:true,draftReceiptVersion:1,conversationId:'room',userId:'owner',draft:text})
    const fetched=await draft('GET');assert.equal(fetched.headers.get('cache-control'),'private, no-store')
    assert.deepEqual(await fetched.json(),{draft:text||null})
  }
  await db.conversationParticipant.deleteMany({where:{userId:'owner'}})
  assert.equal((await draft('PATCH','lost membership')).status,403)
}))


test('private search and mentions exclude unauthorized readers, expired roots and anonymous identity disclosure',()=>fixture(async({db,feed})=>{
  const future=new Date(Date.now()+60_000)
  const add=(id,extra={})=>db.message.create({data:{id,conversationId:'room',senderId:'other',content:'@owner private content',...extra}})
  await add('live');await add('expired',{expiresAt:new Date(0)});await add('deleted',{deletedAt:new Date()})
  await add('once',{viewOnce:true});await add('dead-root',{content:'root',expiresAt:new Date(0)})
  await add('orphan',{parentId:'dead-root'});await add('live-root',{content:'root',expiresAt:future})
  await add('reply',{parentId:'live-root'});await add('anonymous',{anon:true,anonAlias:'Autumn Fox'})
  for(const kind of ['search','mentions']) {
    for(const token of [null,'invalid','synthetic-other']) assert.equal((await feed(kind,token)).status,token?403:401)
    const response=await feed(kind);assert.equal(response.status,200);assert.equal(response.headers.get('cache-control'),'private, no-store')
    const page=await response.json(), rows=page.messages??page.items
    assert.deepEqual(rows.map(r=>r.id??r.messageId).sort(),['anonymous','live','reply'])
    const anonymous=rows.find(r=>(r.id??r.messageId)==='anonymous'), author=anonymous.sender??anonymous.author
    assert.equal(author.id,'');assert.equal(author.name,'Autumn Fox');assert.equal(author.avatar,null)
    assert.equal(rows.find(r=>(r.id??r.messageId)==='reply').expiresAt,future.toISOString())
  }
  assert.equal((await feed('search','synthetic-owner','a'.repeat(65))).status,400)
  await db.conversationParticipant.deleteMany({where:{userId:'owner'}})
  assert.deepEqual((await (await feed('search')).json()).messages,[])
  assert.deepEqual((await (await feed('mentions')).json()).items,[])
}))

test('mention snippets do not split Unicode at the display boundary',()=>fixture(async({db,feed})=>{
  const content='@owner '+'x'.repeat(152)+'🌿tail'
  await db.message.create({data:{id:'unicode',conversationId:'room',senderId:'other',content}})
  const row=(await (await feed('mentions')).json()).items[0]
  assert.equal(row.snippet,'@owner '+'x'.repeat(152));assert.ok(row.snippet.isWellFormed())
}))


test('read intent routes require owner authority and retain manual choice against delayed and forged writes',()=>fixture(async({db,attention})=>{
  await db.conversationParticipant.updateMany({where:{userId:'owner'},data:{lastReadAt:new Date(0)}})
  await db.message.create({data:{id:'anchor',conversationId:'room',senderId:'other',content:'visible'}})
  for(const kind of ['read-intent','read-state','mark-unread']) for(const token of [null,'invalid','synthetic-other']) {
    const response=await attention(kind,{mode:'visible',messageId:'anchor',on:true,readIntent:'forged'},token)
    assert.equal(response.status,token?403:401)
  }
  const snapshot=await attention('read-intent')
  assert.equal(snapshot.headers.get('cache-control'),'private, no-store')
  const {readIntent:old}=await snapshot.json()
  assert.equal((await attention('mark-unread',{on:true})).status,400)
  assert.equal((await attention('mark-unread',{on:true,readIntent:old})).status,200)
  const visible=await attention('read-state',{mode:'visible',messageId:'anchor',readIntent:old})
  assert.equal(visible.status,200)
  assert.equal((await visible.json()).readIntent,old)
  assert.equal((await db.conversationParticipant.findFirst({where:{userId:'owner'}})).manualUnread,true)
  assert.equal((await attention('read-state',{mode:'all',readIntent:old})).status,409)
  assert.equal((await attention('mark-unread',{on:false,readIntent:old})).status,409)
  const {readIntent:fresh}=await (await attention('read-intent')).json()
  assert.equal((await attention('read-state',{mode:'all',readIntent:fresh})).status,200)
  assert.equal((await db.conversationParticipant.findFirst({where:{userId:'owner'}})).manualUnread,false)
  await db.conversationParticipant.deleteMany({where:{userId:'owner'}})
  assert.equal((await attention('read-intent')).status,403)
}))


test('archive badge invalidations reach only the committed owner, never failed or foreign writes',()=>fixture(async({setPersonal,readHints})=>{
  assert.equal((await setPersonal('archive',{archived:true},'synthetic-other')).status,403)
  assert.equal((await setPersonal('archive',{archived:'yes'})).status,400)
  assert.deepEqual(readHints,[])
  for(const archived of [true,true,false]) {
    assert.equal((await setPersonal('archive',{archived})).status,200)
    assert.deepEqual(readHints.pop(),['owner','room'])
  }
  assert.deepEqual(readHints,[])
}))

test('actual mentions and search routes expose all old matches across scan windows and timestamp ties',()=>fixture(async({db,feed})=>{
  await db.message.createMany({data:Array.from({length:1250},(_,i)=>({id:`noise-${String(i).padStart(5,'0')}`,conversationId:'room',senderId:'other',content:'@someone unrelated',createdAt:new Date(2000)}))})
  await db.message.createMany({data:Array.from({length:260},(_,i)=>({id:`old-${String(i).padStart(5,'0')}`,conversationId:'room',senderId:'other',content:'@owner private historical mention',createdAt:new Date(1000)}))})
  for(const kind of ['search','mentions']) {
    let cursor=null, ids=[]
    do {
      const response=await feed(kind,'synthetic-owner','private',cursor);assert.equal(response.status,200)
      const page=await response.json();assert.equal(page.feedPageVersion,1)
      ids.push(...(page.items??page.messages).map(row=>row.messageId??row.id));cursor=page.nextCursor
      assert.equal(page.hasMore,cursor!==null)
    }while(cursor)
    assert.equal(ids.length,260);assert.equal(new Set(ids).size,260)
  }
  const first=await(await feed('mentions')).json();assert.ok(first.nextCursor)
  await db.conversationParticipant.deleteMany({where:{userId:'owner'}})
  const revoked=await(await feed('mentions','synthetic-owner','private',first.nextCursor)).json()
  assert.deepEqual(revoked.items,[]);assert.equal(revoked.hasMore,false)
}))

test('saved route requires identity without an optional-header bypass and pages past 100 tied saves',()=>fixture(async({db,saved})=>{
  for(const token of [null,'invalid','synthetic-other']) assert.equal((await saved(null,token)).status,token?403:401)
  await db.message.createMany({data:Array.from({length:230},(_,i)=>({id:`saved-${String(i).padStart(5,'0')}`,conversationId:'room',senderId:'other',content:'saved body'}))})
  await db.savedMessage.createMany({data:Array.from({length:230},(_,i)=>({id:`marker-${i}`,userId:'owner',messageId:`saved-${String(i).padStart(5,'0')}`,savedAt:new Date(1000)}))})
  let cursor=null, ids=[]
  do {
    const response=await saved(cursor);assert.equal(response.status,200);assert.equal(response.headers.get('cache-control'),'private, no-store')
    const page=await response.json();assert.equal(page.feedPageVersion,1);assert.ok(page.items.length<=100)
    ids.push(...page.items.map(row=>row.message.id));cursor=page.nextCursor
  }while(cursor)
  assert.equal(ids.length,230);assert.equal(new Set(ids).size,230)
}))
