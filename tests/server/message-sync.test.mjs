import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { messageSyncWhere, messagesBefore, encodeMessageSyncCursor, decodeMessageSyncCursor,
  MESSAGE_ORDER_ASC, MESSAGE_ORDER_DESC } from '../../src/lib/message-sync.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const at = '2026-10-19T00:00:00.000Z'
const start = { v: 1, room: 'room', topic: null, from: at, upper: { at, id: 'm449' } }
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-sync-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE Message (id TEXT PRIMARY KEY, conversationId TEXT, topicId TEXT, createdAt DATETIME, content TEXT, deletedAt DATETIME)')
    await db.$transaction(async tx => {
      for (let i = 0; i < 450; i++) {
        const id = `m${String(i).padStart(3, '0')}`
        await tx.$executeRaw`INSERT INTO Message (id,conversationId,createdAt,content) VALUES (${id}, 'room', ${new Date(at)}, 'original')`
      }
    })
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
const select = { id: true, createdAt: true, content: true, deletedAt: true }

test('450 timestamp-tied messages reconcile across bounded ascending pages without gaps', () => fixture(async db => {
  let cursor = start
  const ids = []
  while (true) {
    const rows = await db.message.findMany({ where: messageSyncWhere(cursor), orderBy: MESSAGE_ORDER_ASC, take: 101, select })
    const page = rows.slice(0, 100)
    ids.push(...page.map(m => m.id))
    if (rows.length <= 100) break
    const last = page.at(-1)
    cursor = decodeMessageSyncCursor(encodeMessageSyncCursor({ ...cursor, after: { at: last.createdAt.toISOString(), id: last.id } }), 'room', null)
    // This arrival is after the captured upper position. It belongs to a subsequent sync.
    await db.$executeRaw`INSERT OR IGNORE INTO Message (id,conversationId,createdAt,content) VALUES ('zzz', 'room', ${new Date(at)}, 'later')`
  }
  assert.equal(ids.length, 450)
  assert.equal(new Set(ids).size, 450)
  assert.equal(ids[0], 'm000')
  assert.equal(ids.at(-1), 'm449')
}))
test('cached-range reconciliation returns edits and tombstones, not only new timestamps', () => fixture(async db => {
  await db.$executeRaw`UPDATE Message SET content='edited' WHERE id='m010'`
  await db.$executeRaw`UPDATE Message SET deletedAt=${new Date(at)} WHERE id='m020'`
  const rows = await db.message.findMany({ where: messageSyncWhere(start), orderBy: MESSAGE_ORDER_ASC, select })
  assert.equal(rows.find(m => m.id === 'm010').content, 'edited')
  assert.ok(rows.find(m => m.id === 'm020').deletedAt)
}))
test('backward history cursor preserves earlier rows with the same timestamp', () => fixture(async db => {
  const page = await db.message.findMany({ where: { conversationId: 'room', ...messagesBefore(new Date(at), 'm100') }, orderBy: MESSAGE_ORDER_DESC, take: 40, select })
  assert.equal(page.length, 40)
  assert.equal(page[0].id, 'm099')
  assert.equal(page.at(-1).id, 'm060')
}))
test('cursor is scoped to its room/topic and malformed input is rejected', () => {
  const encoded = encodeMessageSyncCursor(start)
  assert.deepEqual(decodeMessageSyncCursor(encoded, 'room', null), start)
  assert.throws(() => decodeMessageSyncCursor(encoded, 'other', null))
  assert.throws(() => decodeMessageSyncCursor(encoded, 'room', 'other-topic'))
  assert.throws(() => decodeMessageSyncCursor('bad!', 'room', null))
  assert.throws(() => decodeMessageSyncCursor('x'.repeat(4097), 'room', null))
})
