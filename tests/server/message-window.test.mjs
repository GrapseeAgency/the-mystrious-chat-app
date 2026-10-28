import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
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
    await run({ db, page, queries: () => queries })
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
