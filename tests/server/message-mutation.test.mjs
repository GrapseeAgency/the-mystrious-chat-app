import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { mutateMessage } from '../../src/lib/message-mutation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = new Date('2026-10-21T00:00:00Z')
const fields = { id: true, content: true, deletedAt: true, viewedAt: true, viewedBy: true, pinnedAt: true }
const read = (tx, id) => tx.message.findUniqueOrThrow({ where: { id }, select: fields })
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-mutation-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of [
      `CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT, senderId TEXT, content TEXT, parentId TEXT,
        expiresAt DATETIME, deletedAt DATETIME, editedAt DATETIME, pinnedAt DATETIME, pinnedBy TEXT,
        viewOnce BOOLEAN NOT NULL DEFAULT false, viewedAt DATETIME, viewedBy TEXT)`,
      `CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, UNIQUE(userId,conversationId))`,
      `CREATE TABLE Reaction(id TEXT PRIMARY KEY, messageId TEXT, userId TEXT, emoji TEXT, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE(messageId,userId,emoji))`,
      `INSERT INTO ConversationParticipant VALUES ('a','author','room'),('b','peer','room'),('c','other','room')`,
      `INSERT INTO Message(id,conversationId,senderId,content,viewOnce) VALUES ('root','room','author','secret',true)`,
    ]) await db.$executeRawUnsafe(sql)
    await run(db, (owner, action, id = 'root', callback = read) => mutateMessage(db, id, owner, action, callback, () => now))
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
test('departed author cannot edit/delete; tombstone replay still checks owner and membership', () => fixture(async (db, act) => {
  await act('author', { kind: 'delete' })
  assert.equal((await act('author', { kind: 'delete' })).changed, false)
  await assert.rejects(act('peer', { kind: 'delete' }), { status: 403 })
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId = 'author'`
  for (const action of [{ kind: 'delete' }, { kind: 'edit', content: 'revived' }]) {
    await assert.rejects(act('author', action), { status: 403 })
  }
}))
test('deletion unpins and cannot be undone by an edit or reaction', () => fixture(async (db, act) => {
  await db.$executeRaw`UPDATE Message SET pinnedAt = ${now}, pinnedBy = 'peer' WHERE id = 'root'`
  const removed = await act('author', { kind: 'delete' })
  assert.equal(removed.message.pinnedAt, null)
  await assert.rejects(act('author', { kind: 'edit', content: 'revived' }), { status: 410 })
  await assert.rejects(act('peer', { kind: 'react', emoji: 'heart' }), { status: 410 })
  assert.equal((await read(db, 'root')).content, 'secret') // stored history is not a public payload
}))
test('expired messages and expired/deleted/foreign/nested parents refuse mutations', () => fixture(async (db, act) => {
  await db.$executeRaw`UPDATE Message SET expiresAt = ${now} WHERE id = 'root'`
  await assert.rejects(act('peer', { kind: 'view' }), { status: 410 })
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,content,parentId) VALUES ('reply','room','author','reply','root')`
  for (const patch of ["expiresAt = NULL, deletedAt = CURRENT_TIMESTAMP", "deletedAt = NULL, conversationId = 'foreign'",
    "conversationId = 'room', parentId = 'nested'", "parentId = NULL, expiresAt = 0"]) {
    await db.$executeRawUnsafe(`UPDATE Message SET ${patch} WHERE id = 'root'`)
    await assert.rejects(act('author', { kind: 'edit', content: 'no' }, 'reply'), { status: 410 })
  }
}))
test('view-once first consumer is immutable and denied requests do not burn it', () => fixture(async (db, act) => {
  await assert.rejects(act('stranger', { kind: 'view' }), { status: 403 })
  await assert.rejects(act('author', { kind: 'view' }), { status: 400 })
  assert.equal((await read(db, 'root')).viewedAt, null)
  assert.equal((await act('peer', { kind: 'view' })).changed, true)
  const replay = await act('other', { kind: 'view' })
  assert.equal(replay.changed, false)
  assert.equal(replay.message.viewedBy, 'peer')
  assert.equal(replay.message.viewedAt.toISOString(), now.toISOString())
}))
test('reaction toggles are atomic and a failed snapshot rolls back the write', () => fixture(async (db, act) => {
  await act('peer', { kind: 'react', emoji: 'heart' })
  assert.equal(await db.reaction.count(), 1)
  await assert.rejects(act('peer', { kind: 'react', emoji: 'heart' }, 'root', async () => { throw Error('fault') }), /fault/)
  assert.equal(await db.reaction.count(), 1)
  await act('peer', { kind: 'react', emoji: 'heart' })
  assert.equal(await db.reaction.count(), 0)
}))
test('simultaneous consumers have at most one winner; busy requests cannot replace it', () => fixture(async (db, act) => {
  const attempts = await Promise.allSettled(['peer','other'].map(owner => act(owner, { kind: 'view' })))
  for (const attempt of attempts) if (attempt.status === 'rejected') assert.equal(attempt.reason.status, 409)
  assert.ok(attempts.filter(a => a.status === 'fulfilled' && a.value.changed).length <= 1)
  const retry = await act('peer', { kind: 'view' })
  const winner = retry.message.viewedBy
  assert.equal((await act('other', { kind: 'view' })).message.viewedBy, winner)
}))

test('deleted wire rows and unavailable quotes disclose no original body or attachment', async () => {
  const { redactDeletedMessage, quoteDisclosure } = await import('../../src/lib/message-disclosure.ts')
  const live = { id: 'm', deletedAt: null, content: 'secret', imagePath: 'private.jpg', payload: 'secret',
    replyTo: { content: 'quote' }, translations: [{ text: 'translated secret' }], poll: { question: 'secret' } }
  assert.equal(redactDeletedMessage(live), live)
  const dead = redactDeletedMessage({ ...live, deletedAt: now.toISOString() })
  assert.equal(dead.id, 'm')
  assert.equal(dead.content, '')
  for (const key of ['imagePath','payload','replyTo','poll','transcript','filePath','linkPreview']) assert.equal(dead[key], null)
  assert.deepEqual(dead.translations, [])
  const parent = { content: 'secret caption', deletedAt: null, expiresAt: null, viewOnce: false }
  assert.deepEqual(quoteDisclosure(parent, now), { content: 'secret caption', deleted: false })
  assert.deepEqual(quoteDisclosure({ ...parent, deletedAt: now }, now), { content: '', deleted: true })
  assert.deepEqual(quoteDisclosure({ ...parent, expiresAt: now }, now), { content: '', deleted: true })
  assert.deepEqual(quoteDisclosure({ ...parent, viewOnce: true }, now), { content: 'View-once message', deleted: false })
})
