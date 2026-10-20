import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { advanceReadReceipt, receiptRecipients } from '../../src/lib/read-receipt.ts'
import { openDirectConversation } from '../../src/lib/direct-conversation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const t = n => new Date(`2026-10-20T00:00:${String(n).padStart(2, '0')}.000Z`)
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-chat-behavior-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of [
      `CREATE TABLE Conversation(id TEXT PRIMARY KEY, isGroup BOOLEAN NOT NULL DEFAULT false, name TEXT,
        description TEXT NOT NULL DEFAULT '', photo TEXT, inviteCode TEXT, ttlSeconds INTEGER NOT NULL DEFAULT 0,
        broadcastMode BOOLEAN NOT NULL DEFAULT false, screenPrivacy BOOLEAN NOT NULL DEFAULT false,
        slowModeSeconds INTEGER NOT NULL DEFAULT 0, isSelf BOOLEAN NOT NULL DEFAULT false, appKey TEXT,
        createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME NOT NULL)`,
      `CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT NOT NULL, conversationId TEXT NOT NULL,
        role TEXT NOT NULL DEFAULT 'member', joinedAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        lastReadAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, pinnedAt DATETIME, mutedUntil DATETIME, archivedAt DATETIME,
        handRaisedAt DATETIME, screenPrivacy BOOLEAN NOT NULL DEFAULT false, manualUnread BOOLEAN NOT NULL DEFAULT false,
        draft TEXT, UNIQUE(userId,conversationId))`,
      `CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT, createdAt DATETIME)`,
      `CREATE TABLE UserBlock(id TEXT PRIMARY KEY, blockerId TEXT, blockedId TEXT)`,
    ]) await db.$executeRawUnsafe(sql)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
async function seedRead(db) {
  await db.$executeRaw`INSERT INTO ConversationParticipant(id,userId,conversationId,lastReadAt,manualUnread) VALUES ('p','reader','room',${t(0)},true)`
  await db.$executeRaw`INSERT INTO Message VALUES ('displayed','room',${t(10)}),('not-displayed','room',${t(20)}),('foreign','other',${t(30)})`
}
test('reading a displayed row leaves later, undisplayed arrival unread', () => fixture(async db => {
  await seedRead(db)
  assert.equal((await advanceReadReceipt(db, 'room', 'reader', 'displayed')).toISOString(), t(10).toISOString())
  assert.equal(await db.message.count({ where: { conversationId: 'room', createdAt: { gt: t(10) } } }), 1)
  assert.equal((await db.conversationParticipant.findUnique({ where: { id: 'p' } })).manualUnread, false)
}))
test('late older-device acknowledgement cannot regress read progress', () => fixture(async db => {
  await seedRead(db)
  await advanceReadReceipt(db, 'room', 'reader', 'not-displayed')
  assert.equal((await advanceReadReceipt(db, 'room', 'reader', 'displayed')).toISOString(), t(20).toISOString())
}))
test('unknown or foreign message cannot clear unread, nonmember cannot acknowledge', () => fixture(async db => {
  await seedRead(db)
  for (const id of ['missing','foreign']) await assert.rejects(advanceReadReceipt(db, 'room', 'reader', id), { status: 404 })
  await assert.rejects(advanceReadReceipt(db, 'room', 'outsider', 'displayed'), { status: 403 })
  const row = await db.conversationParticipant.findUnique({ where: { id: 'p' } })
  assert.equal(row.manualUnread, true)
  assert.equal(row.lastReadAt.toISOString(), t(0).toISOString())
}))
test('explicit mark-all uses the latest server row, not wall-clock time', () => fixture(async db => {
  await seedRead(db)
  assert.equal((await advanceReadReceipt(db, 'room', 'reader')).toISOString(), t(20).toISOString())
}))
test('receipt privacy still synchronizes the reader own devices', () => {
  assert.deepEqual(receiptRecipients('reader', ['reader','peer'], '{"readReceipts":false}'), ['reader'])
  assert.deepEqual(receiptRecipients('reader', ['reader','peer'], null), ['reader','peer'])
  assert.deepEqual(receiptRecipients('reader', ['peer'], '{broken'), ['reader','peer'])
})
test('reversed member order and response-loss retry open the same DM', () => fixture(async db => {
  const first = await openDirectConversation(db, ['a','b'])
  const second = await openDirectConversation(db, ['b','a'])
  assert.equal(first.created, true)
  assert.equal(second.created, false)
  assert.equal(first.id, second.id)
  assert.equal(await db.conversation.count(), 1)
}))
test('simultaneous contact taps converge without duplicate DM threads', () => fixture(async db => {
  const attempts = await Promise.allSettled([openDirectConversation(db, ['a','b']), openDirectConversation(db, ['b','a'])])
  const completed = attempts.filter(a => a.status === 'fulfilled').map(a => a.value.id)
  assert.ok(completed.length > 0)
  for (const attempt of attempts) if (attempt.status === 'rejected') assert.equal(attempt.reason.status, 409)
  assert.equal((await openDirectConversation(db, ['a','b'])).id, completed[0])
  assert.equal(await db.conversation.count(), 1)
}))
test('either-direction block forbids a new DM but preserves existing history', () => fixture(async db => {
  await db.$executeRaw`INSERT INTO UserBlock VALUES ('block','b','a')`
  await assert.rejects(openDirectConversation(db, ['a','b']), { status: 403 })
  assert.equal(await db.conversation.count(), 0)
  await db.$executeRaw`DELETE FROM UserBlock`
  const first = await openDirectConversation(db, ['a','b'])
  await db.$executeRaw`INSERT INTO UserBlock VALUES ('block','a','b')`
  assert.equal((await openDirectConversation(db, ['b','a'])).id, first.id)
}))
