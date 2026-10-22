import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function route(db, requireActionOwner) {
  const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/hub/logs/route.ts', import.meta.url), 'utf8'))
    .replace(/^import .*$/gm, '').replace(/export /g, '')
  return new Function('db','NextResponse','requireActionOwner',source+';return GET')(db,{json:Response.json},requireActionOwner)
}
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-private-activity-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir,'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE User(id TEXT PRIMARY KEY,name TEXT,username TEXT,color TEXT)')
    await db.$executeRawUnsafe('CREATE TABLE LogEvent(id TEXT PRIMARY KEY,userId TEXT,kind TEXT,message TEXT,meta TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    await db.$executeRawUnsafe("INSERT INTO User VALUES ('me','Me','me','#fff'),('other','Other','other','#000')")
    for (const [id,userId,kind,message,meta] of [
      ['a','me','transfer','my transfer','{"to":"recipient"}'],
      ['b','other','message','anonymous message posted','{"conversationId":"private-room","messageId":"secret-message"}'],
      ['c','other','message','converted secret message text into a task',null],
      ['d','me','whiteboard-clear','private-room',null],
      ['e',null,'system','orphaned account activity',null],
      ['f','me','message','my own converted task',null],
      ['g','me','future-internal-kind','private bookkeeping',null],
    ]) await db.logEvent.create({data:{id,userId,kind,message,meta}})
    const get = await route(db, async (req,userId) => req.headers.get('authorization') === 'Bearer '+userId ? null : Response.json({}, {status:403}))
    await run(db,(userId='me',extra='',token=userId) => get(new Request('https://activity.test?userId='+userId+extra,{headers:{authorization:'Bearer '+token}})))
  } finally { await db.$disconnect(); await rm(dir,{recursive:true,force:true}) }
}
test('private activity query never returns another actor, anonymous attribution, orphan or internal reset ledger', () => fixture(async (db,get) => {
  const response = await get()
  assert.equal(response.status,200); assert.equal(response.headers.get('cache-control'),'private, no-store')
  assert.equal(response.headers.get('vary'),'Authorization')
  const page = await response.json()
  assert.equal(page.activityVersion,1); assert.equal(page.userId,'me')
  assert.deepEqual(page.logs.map(x=>x.id).sort(),['a','f'])
  assert.ok(page.logs.every(x=>x.user.id==='me'))
  const serialized = JSON.stringify(page)
  for (const secret of ['anonymous message posted','secret-message','private-room','private bookkeeping','other']) assert.equal(serialized.includes(secret),false)
  // Reading the terminal must not destroy the private operation journal.
  assert.equal(await db.logEvent.count(),7)
}))
test('kind filter is always intersected with owner and cannot expose internal journals', () => fixture(async (_,get) => {
  const page = await (await get('me','&kind=message')).json()
  assert.deepEqual(page.logs.map(x=>x.id),['f'])
  assert.equal((await get('me','&kind=whiteboard-clear')).status,400)
  assert.equal((await get('other','','me')).status,403)
  assert.equal((await get('')).status,400)
  assert.equal((await (await get('me','&limit=1')).json()).logs.length,1)
}))
test('missing, expired or wrong-owner credentials stop before any activity query', async () => {
  let reads = 0
  const get = await route({logEvent:{findMany:async()=>{reads++;return[]}}},async()=>Response.json({}, {status:401}))
  assert.equal((await get(new Request('https://activity.test?userId=me'))).status,401)
  assert.equal((await get(new Request('https://activity.test?userId=other',{headers:{authorization:'Bearer stale'}}))).status,401)
  assert.equal(reads,0)
})
