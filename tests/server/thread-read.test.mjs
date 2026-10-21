import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { readThreadSnapshot } from '../../src/lib/message-visibility.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = new Date('2026-10-21T00:00:00Z')
const fields = { id: true, conversationId: true, parentId: true, deletedAt: true, expiresAt: true }
const root = (tx, id) => tx.message.findUnique({ where: { id }, select: fields })
const replies = (tx, where) => tx.message.findMany({ where, select: fields, orderBy: { id: 'asc' } })
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-thread-read-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT, parentId TEXT, deletedAt DATETIME, expiresAt DATETIME)')
    await db.$executeRawUnsafe('CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, UNIQUE(userId,conversationId))')
    await db.$executeRawUnsafe("INSERT INTO Message VALUES ('root','room',NULL,NULL,NULL),('reply','room','root',NULL,NULL),('foreign','other','root',NULL,NULL)")
    await db.$executeRawUnsafe("INSERT INTO ConversationParticipant VALUES ('p','viewer','room')")
    await run(db, (viewer = 'viewer', id = 'root', clock = () => now) => readThreadSnapshot(db, id, viewer, root, replies, clock))
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

test('thread snapshot authenticates membership before reading replies and stays room-scoped', () => fixture(async (db, read) => {
  assert.deepEqual((await read()).replies.map(row => row.id), ['reply'])
  await assert.rejects(read('outsider'), { status: 403 })
  await assert.rejects(read('viewer', 'missing'), { status: 404 })
  await db.$executeRawUnsafe("DELETE FROM ConversationParticipant WHERE userId='viewer'")
  let fetchedReplies = false
  await assert.rejects(readThreadSnapshot(db, 'root', 'viewer', root, async () => { fetchedReplies = true; return [] }), { status: 403 })
  assert.equal(fetchedReplies, false)
}))

test('deleted, expired or nested roots cannot serve a thread', () => fixture(async (db, read) => {
  for (const patch of ["deletedAt=0", "deletedAt=NULL,expiresAt=0", "expiresAt=NULL,parentId='other-root'"]) {
    await db.$executeRawUnsafe(`UPDATE Message SET ${patch} WHERE id='root'`)
    await assert.rejects(read(), { status: 410 })
  }
}))

test('tombstones remain in snapshots while reply and root deadlines are checked at delivery', () => fixture(async (db, read) => {
  await db.$executeRaw`UPDATE Message SET deletedAt=${now} WHERE id='reply'`
  assert.ok((await read()).replies[0].deletedAt)
  const later = new Date(now.getTime() + 1000)
  await db.$executeRaw`UPDATE Message SET expiresAt=${later} WHERE id='reply'`
  let calls = 0
  assert.equal((await read('viewer', 'root', () => calls++ === 0 ? now : later)).replies.length, 0)
  await db.$executeRaw`UPDATE Message SET expiresAt=${later} WHERE id='root'`
  calls = 0
  await assert.rejects(read('viewer', 'root', () => calls++ === 0 ? now : later), { status: 410 })
}))

test('root, membership and reply reads use the transaction client; busy errors are not access denials', async () => {
  const calls = []
  const tx = { conversationParticipant: { findUnique: async () => { calls.push('member'); return { id: 'p' } } } }
  const db = { $transaction: async (work, options) => {
    assert.equal(options.isolationLevel, 'Serializable')
    return work(tx)
  } }
  await readThreadSnapshot(db, 'root', 'viewer', async client => {
    assert.equal(client, tx); calls.push('root')
    return { id: 'root', conversationId: 'room', parentId: null, deletedAt: null, expiresAt: null }
  }, async client => { assert.equal(client, tx); calls.push('replies'); return [] })
  assert.deepEqual(calls, ['root', 'member', 'replies'])
  for (const code of ['P1008', 'P2028', 'P2034']) {
    await assert.rejects(readThreadSnapshot({ $transaction: async () => { throw { code } } }, 'root', 'viewer', root, replies), { status: 409 })
  }
})
