import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { readExportPage } from '../../src/lib/history-export.ts'
import { readMessageWindow, MessageWindowError } from '../../src/lib/message-window.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'message-window-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(directory, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,UNIQUE(userId,conversationId))')
    await db.$executeRawUnsafe('CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,parentId TEXT,createdAt DATETIME,deletedAt DATETIME,expiresAt DATETIME)')
    await db.$executeRawUnsafe('CREATE INDEX message_room_position ON Message(conversationId,createdAt,id)')
    await db.$executeRawUnsafe("INSERT INTO ConversationParticipant VALUES ('member','reader','room')")
    await db.$executeRawUnsafe(`INSERT INTO Message(id,conversationId,createdAt) VALUES ${Array.from({length: 6000}, (_, i) => `('m-${String(i).padStart(6, '0')}','room',1000)`).join(',')}`)
    const select = { id: true, conversationId: true, parentId: true, createdAt: true, deletedAt: true, expiresAt: true,
      parent: { select: { conversationId: true, parentId: true, deletedAt: true, expiresAt: true } } }
    let queries = 0
    const read = (tx, where, order, take) => { queries++; return tx.message.findMany({ where, orderBy: [{createdAt:order},{id:order}], take, select }) }
    const page = (options = {}, owner = 'reader', clock) => readMessageWindow(db, 'room', owner, options, read, clock)
    const exportPage = (cursor = null, owner = 'reader', clock) => readExportPage(db, 'room', owner, cursor,
      (tx, where, take) => tx.message.findMany({where,take,orderBy:[{createdAt:'asc'},{id:'asc'}],select}), clock)
    await run({ db, page, exportPage, queries: () => queries })
  } finally { await db.$disconnect(); await rm(directory, { recursive: true, force: true }) }
}

test('an arbitrarily old uncached equal-time anchor uses a bounded indexed neighborhood, not fourteen scans', () => fixture(async f => {
  const result = await f.page({ anchorId:'m-000010', limit:40 })
  assert.equal(result.messageWindowVersion,1)
  assert.equal(result.anchorId,'m-000010')
  assert.equal(result.messages.length,40)
  assert.ok(result.messages.some(m=>m.id==='m-000010'))
  assert.equal(result.hasOlder,false); assert.equal(result.hasNewer,true)
  assert.equal(f.queries(),3)
}))

test('both window directions traverse timestamp ties after a boundary is removed', () => fixture(async f => {
  const initial = await f.page({anchorId:'m-003000',limit:20})
  await f.db.message.deleteMany({where:{id:initial.olderCursor.id}})
  const older = await f.page({before:initial.olderCursor,limit:20})
  assert.equal(older.messages.length,20)
  assert.ok(older.messages.every(m=>m.id<initial.olderCursor.id))
  const newer = await f.page({after:initial.newerCursor,limit:20})
  assert.ok(newer.messages.every(m=>m.id>initial.newerCursor.id))
  assert.equal(newer.hasOlder,true)
}))

test('thread anchors return the exact reply neighborhood and validate canonical root lifetime and room', () => fixture(async f => {
  await f.db.$executeRawUnsafe("INSERT INTO Message(id,conversationId,parentId,createdAt) VALUES ('reply','room','m-000001',2000),('foreign-root','foreign',NULL,1),('bad-reply','room','foreign-root',3000)")
  const result = await f.page({anchorId:'reply'})
  assert.equal(result.threadId,'m-000001'); assert.equal(result.parent.id,'m-000001')
  assert.deepEqual(result.messages.map(m=>m.id),['reply'])
  await assert.rejects(f.page({anchorId:'bad-reply'}), e=>e instanceof MessageWindowError && e.status===410)
  await f.db.message.updateMany({where:{id:'m-000001'},data:{deletedAt:new Date()}})
  await assert.rejects(f.page({anchorId:'reply'}),e=>e.status===410)
}))

test('unavailable, foreign, expired and unauthorized targets never produce a successful window', () => fixture(async f => {
  await assert.rejects(f.page({anchorId:'m-000001'},'outsider'),e=>e.status===403)
  await assert.rejects(f.page({anchorId:'missing'}),e=>e.status===404)
  await f.db.message.updateMany({where:{id:'m-000001'},data:{expiresAt:new Date(0)}})
  await assert.rejects(f.page({anchorId:'m-000001'}),e=>e.status===410)
  for (const options of [{limit:0},{limit:201},{anchorId:'a',after:{id:'b',createdAt:new Date().toISOString()}},{before:{id:'',createdAt:'bad'}}]) {
    await assert.rejects(f.page(options),e=>e.status===400)
  }
  await f.db.conversationParticipant.deleteMany()
  await assert.rejects(f.page(),e=>e.status===403)
}))


test('export traverses a fixed upper boundary in bounded pages and rechecks membership on every page', () => fixture(async f => {
  let cursor = null, calls = 0, count = 0
  do {
    const result = await f.exportPage(cursor)
    assert.equal(result.exportVersion,1)
    assert.ok(result.messages.length<=200)
    count += result.messages.length; calls++
    cursor = result.nextSyncCursor
    if (calls===1) await f.db.$executeRawUnsafe("INSERT INTO Message(id,conversationId,createdAt) VALUES ('new-arrival','room',2000)")
    assert.equal(result.hasMore,cursor!==null)
  } while (cursor)
  assert.equal(count,6000); assert.equal(calls,30)
  const first = await f.exportPage()
  await f.db.conversationParticipant.deleteMany()
  await assert.rejects(f.exportPage(first.nextSyncCursor),e=>e.status===403)
}))

test('export scan positions survive a fully expired page without publishing inaccessible replies', () => fixture(async f => {
  await f.db.$executeRawUnsafe("UPDATE Message SET expiresAt=1 WHERE id<'m-000200'")
  await f.db.$executeRawUnsafe("INSERT INTO Message(id,conversationId,parentId,createdAt) VALUES ('hidden','room','m-000000',0)")
  let time=0
  const page=await f.exportPage(null,'reader',()=>new Date(time++===0?0:2))
  assert.equal(page.hasMore,true)
  assert.ok(page.exportPosition)
  assert.deepEqual(page.messages,[])
  const next=await f.exportPage(page.nextSyncCursor)
  assert.ok(next.messages.length>0)
  assert.ok(next.messages.every(m=>m.id!=='hidden'))
}))

test('sixteen thousand replies are traversed as bounded windows, without a full-thread ceiling', () => fixture(async f => {
  await f.db.$executeRawUnsafe("INSERT INTO Message(id,conversationId,createdAt) VALUES ('root','room',0)")
  await f.db.$executeRawUnsafe("UPDATE Message SET parentId='root' WHERE id != 'root'")
  await f.db.$executeRawUnsafe(`INSERT INTO Message(id,conversationId,parentId,createdAt) VALUES ${Array.from({length:10000},(_,i)=>`('m-${String(i+6000).padStart(6,'0')}','room','root',1000)`).join(',')}`)
  const ids = new Set(); let before
  do {
    const page = await f.page({threadId:'root',before,limit:60})
    assert.ok(page.messages.length<=60)
    assert.equal(page.parent.id,'root')
    for(const row of page.messages) { assert.ok(!ids.has(row.id));ids.add(row.id) }
    before=page.hasOlder?page.olderCursor:null
  } while(before)
  assert.equal(ids.size,16000)
  const exact=await f.page({threadId:'root',anchorId:'m-000001',limit:60})
  assert.ok(exact.messages.some(row=>row.id==='m-000001'))
}))

test('center reconciliation includes same-time backfills and edits even after the center is deleted', () => fixture(async f => {
  const center={id:'m-003000',createdAt:new Date(1000).toISOString()}
  const original=await f.page({center,limit:60})
  assert.ok(original.messages.some(m=>m.id===center.id))
  await f.db.message.deleteMany({where:{id:center.id}})
  await f.db.message.updateMany({where:{id:'m-003001'},data:{deletedAt:new Date()}})
  await f.db.$executeRawUnsafe("INSERT INTO Message(id,conversationId,createdAt) VALUES ('m-003000a','room',1000)")
  const refreshed=await f.page({center,limit:60})
  assert.equal(refreshed.messages.length,60)
  assert.ok(refreshed.messages.some(m=>m.id==='m-003000a'))
  assert.ok(refreshed.messages.find(m=>m.id==='m-003001').deletedAt)
  assert.ok(!refreshed.messages.some(m=>m.id===center.id))
  assert.equal(refreshed.hasOlder,true);assert.equal(refreshed.hasNewer,true)
  await assert.rejects(f.page({center:{id:'a',createdAt:'invalid'}}),e=>e.status===400)
}))

test('missing replies do not authorize denying the thread; bound root and membership refusals do', () => fixture(async f => {
  await assert.rejects(f.page({threadId:'m-000001',anchorId:'missing'}),e=>e.status===404 && e.code==='message_missing')
  assert.equal((await f.page({threadId:'m-000001'})).parent.id,'m-000001')
  await assert.rejects(f.page({threadId:'m-000001'},'outsider'),e=>e.status===403 && e.code==='thread_access')
  await f.db.message.updateMany({where:{id:'m-000001'},data:{deletedAt:new Date()}})
  await assert.rejects(f.page({threadId:'m-000001',anchorId:'missing'}),e=>e.status===410 && e.code==='thread_gone')
}))

test('an empty direction keeps a usable opposite cursor', () => fixture(async f => {
  const early={id:'m-000000',createdAt:new Date(1000).toISOString()}
  const empty=await f.page({before:early})
  assert.equal(empty.messages.length,0)
  assert.equal(empty.hasNewer,true)
  assert.deepEqual(empty.newerCursor,early)
  assert.ok((await f.page({after:empty.newerCursor})).messages.length>0)
}))
