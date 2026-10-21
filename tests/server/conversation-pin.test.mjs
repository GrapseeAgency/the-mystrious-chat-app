import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { setConversationPin } from '../../src/lib/conversation-pin.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-conversation-pin-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE Conversation(id TEXT PRIMARY KEY)')
    await db.$executeRawUnsafe('CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, pinnedAt DATETIME, UNIQUE(userId,conversationId))')
    await db.$executeRawUnsafe("INSERT INTO Conversation VALUES ('room')")
    await db.$executeRawUnsafe("INSERT INTO ConversationParticipant VALUES ('a','alice','room',NULL),('b','bob','room',NULL)")
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
test('lost Pin response can be retried without unpinning or changing pin order', () => fixture(async db => {
  const first = await setConversationPin(db, 'room', 'alice', true)
  assert.equal(first.pinned, true)
  assert.deepEqual(await setConversationPin(db, 'room', 'alice', true), first)
  const unpinned = { ok: true, pinned: false, pinnedAt: null }
  assert.deepEqual(await setConversationPin(db, 'room', 'alice', false), unpinned)
  assert.deepEqual(await setConversationPin(db, 'room', 'alice', false), unpinned)
  assert.equal((await db.conversationParticipant.findUnique({ where: { id: 'b' }, select: { pinnedAt: true } })).pinnedAt, null)
}))
test('legacy omitted intent still toggles, while missing/nonmember requests change nothing', () => fixture(async db => {
  assert.equal((await setConversationPin(db, 'room', 'alice')).pinned, true)
  assert.equal((await setConversationPin(db, 'room', 'alice')).pinned, false)
  await assert.rejects(setConversationPin(db, 'missing', 'alice', true), { status: 404 })
  await assert.rejects(setConversationPin(db, 'room', 'outsider', true), { status: 403 })
  assert.equal((await db.conversationParticipant.findUnique({ where: { id: 'a' }, select: { pinnedAt: true } })).pinnedAt, null)
}))
test('competing duplicate Pin requests converge or explicitly retry a transaction conflict', () => fixture(async db => {
  const results = await Promise.allSettled([
    setConversationPin(db, 'room', 'alice', true), setConversationPin(db, 'room', 'alice', true),
  ])
  const completed = results.filter(r => r.status === 'fulfilled').map(r => r.value)
  assert.ok(completed.length > 0)
  for (const result of results) if (result.status === 'rejected') assert.equal(result.reason.status, 409)
  const retried = await setConversationPin(db, 'room', 'alice', true)
  for (const value of completed) assert.deepEqual(value, retried)
}))
