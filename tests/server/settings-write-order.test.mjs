import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
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
    for (const name of ['User', 'Conversation', 'ConversationParticipant']) {
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
    for (const id of ['owner','other']) await db.user.create({ data: {
      id, name:id, color:'amber', preferences: '{"readReceipts":true,"density":"cozy"}',
      sessionTokenHash:hashSessionToken(`synthetic-${id}`), legacyTokenHashes:'[]',
    } })
    const requireActionOwner = await load('src/lib/wallet-authorization.ts', {db,bearerToken,tokenAccepted}, 'requireActionOwner')
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
    const roomDeps={...metadata,db,requireActionOwner,NextResponse:Response,GROUP_NAME_MAX:80,
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
    await run({db,patch,get,setPrivacy,notices,roomAction,setPersonal})
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
