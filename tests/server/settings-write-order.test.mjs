import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
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
    await db.conversation.create({data:{id:'room',isGroup:true}})
    await db.conversationParticipant.create({data:{userId:'owner',conversationId:'room',screenPrivacy:true}})
    await db.conversationParticipant.create({data:{userId:'other',conversationId:'room',screenPrivacy:true}})
    const notices=[]
    const privacy=await load('src/app/api/conversations/[id]/screen-privacy/route.ts', {
      db, requireActionOwner, ConversationMetadataError, patchMyScreenPrivacy, NextResponse:Response,
      safeJson:r=>r.json(), strField:v=>typeof v==='string'?v.trim():'', notifySocket:async(...args)=>notices.push(args),
    }, 'PATCH')
    const setPrivacy=(on,token='synthetic-owner',userId='owner')=>privacy(new Request('https://settings.test/api/conversations/room/screen-privacy', {
      method:'PATCH',headers:token?{authorization:`Bearer ${token}`}:{},body:JSON.stringify({userId,on}),
    }), {params:Promise.resolve({id:'room'})})
    await run({db,patch,get,setPrivacy,notices})
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
