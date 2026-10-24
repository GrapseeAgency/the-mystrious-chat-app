import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { readThreadPage } from '../../src/lib/thread-page.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = new Date('2026-10-22T00:00:00Z')
const fields = { id: true, conversationId: true, parentId: true, deletedAt: true, expiresAt: true, createdAt: true }
const root = (tx, id) => tx.message.findUnique({ where: { id }, select: fields })
const replies = (tx, where, take) => {
  assert.ok(take <= 201)
  return tx.message.findMany({ where, take, select: fields, orderBy: [{ createdAt: 'asc' }, { id: 'asc' }] })
}
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-thread-page-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT, parentId TEXT, deletedAt DATETIME, expiresAt DATETIME, createdAt DATETIME NOT NULL)')
    await db.$executeRawUnsafe('CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, UNIQUE(userId,conversationId))')
    await db.$executeRaw`INSERT INTO Message(id,conversationId,createdAt) VALUES ('root','room',${now}),('other-root','room',${now})`
    await db.$executeRaw`INSERT INTO ConversationParticipant VALUES ('p','viewer','room')`
    for (let i = 0; i < 31; i++) await db.$executeRaw`INSERT INTO Message(id,conversationId,parentId,createdAt) VALUES (${`m${String(i).padStart(3, '0')}`},'room','root',${now})`
    await db.$executeRaw`INSERT INTO Message(id,conversationId,parentId,createdAt) VALUES ('foreign','other','root',${now})`
    const read = (cursor = null, limit = 2, rootId = 'root', clock = () => now) => readThreadPage(db, rootId, 'viewer', limit, cursor, root, replies, clock)
    await run(db, read)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
test('bounded thread pages cover timestamp ties beyond fourteen pages and exclude foreign-room replies', () => fixture(async (db, read) => {
  const ids = []; let cursor = null, pages = 0
  do {
    const page = await read(cursor); pages++
    assert.equal(page.threadPageVersion, 1); assert.ok(page.replies.length <= 2)
    assert.equal(page.hasMore, page.nextCursor !== null)
    ids.push(...page.replies.map(r => r.id)); cursor = page.nextCursor
  } while (cursor)
  assert.equal(pages, 16); assert.equal(ids.length, 31); assert.equal(new Set(ids).size, 31)
  assert.equal(ids.at(-1), 'm030')
}))
test('thread upper boundary does not chase new arrivals and a removed cursor row cannot stall paging', () => fixture(async (db, read) => {
  const first = await read(null, 20)
  await db.$executeRaw`DELETE FROM Message WHERE id='m019'`
  await db.$executeRaw`INSERT INTO Message(id,conversationId,parentId,createdAt) VALUES ('new','room','root',${new Date(now.getTime() + 1000)})`
  const second = await read(first.nextCursor, 20)
  assert.deepEqual(second.replies.map(r => r.id), Array.from({ length: 11 }, (_, i) => `m${i + 20}`.replace('m', 'm0')))
  assert.equal(second.hasMore, false)
}))
test('every continuation rechecks membership, root lifetime and cursor scope', () => fixture(async (db, read) => {
  const first = await read()
  await assert.rejects(read(first.nextCursor, 2, 'other-root'), { status: 400 })
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId='viewer'`
  await assert.rejects(read(first.nextCursor), { status: 403 })
  await db.$executeRaw`INSERT INTO ConversationParticipant VALUES ('p','viewer','room')`
  await db.$executeRaw`UPDATE Message SET deletedAt=${now} WHERE id='root'`
  await assert.rejects(read(first.nextCursor), { status: 410 })
}))
test('expiring a whole page still returns a progressing raw cursor, not false exhaustion', () => fixture(async (db, read) => {
  const later = new Date(now.getTime() + 1000)
  await db.$executeRaw`UPDATE Message SET expiresAt=${later} WHERE id IN ('m000','m001')`
  let calls = 0
  const page = await read(null, 2, 'root', () => calls++ === 0 ? now : later)
  assert.equal(page.replies.length, 0); assert.equal(page.hasMore, true); assert.ok(page.nextCursor)
  assert.equal((await read(page.nextCursor)).replies[0].id, 'm002')
}))
test('bad limits and cursor shapes fail explicitly while an actually empty thread completes', () => fixture(async (db, read) => {
  for (const limit of [0, -1, 201, 1.5, NaN]) await assert.rejects(read(null, limit), { status: 400 })
  for (const cursor of ['', 'garbage', 'a'.repeat(4097)]) await assert.rejects(read(cursor), { status: 400 })
  await db.$executeRaw`DELETE FROM Message WHERE parentId='root'`
  const empty = await read()
  assert.deepEqual(empty.replies, []); assert.equal(empty.hasMore, false); assert.equal(empty.nextCursor, null)
}))

test('saved selection excludes dead ancestors, private one-time rows, and unavailable memberships', () => fixture(async (db) => {
  const { savedMessagesWhere, visibleInboxPreview } = await import('../../src/lib/message-visibility.ts')
  await db.$executeRawUnsafe('ALTER TABLE Message ADD COLUMN viewOnce BOOLEAN NOT NULL DEFAULT false')
  await db.$executeRawUnsafe('CREATE TABLE Conversation(id TEXT PRIMARY KEY)')
  await db.$executeRawUnsafe('CREATE TABLE SavedMessage(id TEXT PRIMARY KEY,userId TEXT,messageId TEXT,savedAt DATETIME)')
  await db.$executeRaw`INSERT INTO Conversation VALUES ('room'),('other')`
  await db.$executeRaw`INSERT INTO SavedMessage VALUES ('s','viewer','m000',${now})`
  const list = () => db.savedMessage.findMany({ where: savedMessagesWhere('viewer', now), select: { message: { select: {
    ...fields, parent: { select: fields },
  } } } })
  assert.equal((await list()).length, 1)
  for (const patch of ['deletedAt=0', 'deletedAt=NULL,expiresAt=0', "expiresAt=NULL,parentId='other-root'"]) {
    await db.$executeRawUnsafe(`UPDATE Message SET ${patch} WHERE id='root'`)
    assert.equal((await list()).length, 0)
  }
  await db.$executeRaw`UPDATE Message SET parentId=NULL,conversationId='other' WHERE id='root'`
  const foreign = await list()
  assert.equal(visibleInboxPreview(foreign[0].message, 'room', now), null)
  await db.$executeRaw`UPDATE Message SET conversationId='room' WHERE id='root'`
  await db.$executeRaw`UPDATE Message SET viewOnce=true WHERE id='m000'`
  assert.equal((await list()).length, 0)
  await db.$executeRaw`UPDATE Message SET viewOnce=false WHERE id='m000'`
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId='viewer'`
  assert.equal((await list()).length, 0)
}))
