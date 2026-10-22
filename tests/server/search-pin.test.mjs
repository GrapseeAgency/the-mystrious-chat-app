import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { visibleSearchHit } from '../../src/lib/search-visibility.ts'
import { removeMessagePin } from '../../src/lib/pin-operation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = new Date('2026-10-21T00:00:00Z')
const hit = { id: 'hit', content: 'Autumn message', senderId: 'private-id',
  sender: { id: 'private-id', name: 'Real name', username: 'private', color: 'red', avatar: 'private.png' } }

test('retained search excludes expired, deleted, invalid-expiry and one-time content', () => {
  for (const fields of [{ expiresAt: now.toISOString() }, { deletedAt: now.toISOString() },
    { expiresAt: 'broken' }, { viewOnce: true }]) assert.equal(visibleSearchHit({ ...hit, ...fields }, 'autumn', now), null)
  assert.equal(visibleSearchHit(hit, 'unmatched', now), null)
  assert.equal(visibleSearchHit(hit, ' ', now), null)
  assert.equal(visibleSearchHit(hit, 'AUTUMN', now), hit)
  assert.ok(visibleSearchHit({ ...hit, content: '', fileName: 'AUTUMN.pdf' }, 'autumn', now))
})
test('anonymous search rows do not carry their real author identity into native chrome', () => {
  const result = visibleSearchHit({ ...hit, anon: true, anonAlias: 'Autumn Fox' }, 'autumn', now)
  assert.equal(result.senderId, '')
  assert.deepEqual(result.sender, { id: '', name: 'Autumn Fox', username: null, color: 'orange', avatar: null })
  assert.equal(visibleSearchHit({ ...hit, anon: true }, 'autumn', now).sender.name, 'Anonymous')
  assert.equal(hit.sender.id, 'private-id') // Do not mutate the shared serialized row.
})
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-pin-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT, pinnedAt DATETIME, pinnedBy TEXT, senderId TEXT, deletedAt DATETIME, expiresAt DATETIME, parentId TEXT, viewOnce BOOLEAN NOT NULL DEFAULT false, viewedAt DATETIME)')
    await db.$executeRawUnsafe('CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, UNIQUE(userId,conversationId))')
    await db.$executeRaw`INSERT INTO Message(id,conversationId,pinnedAt,pinnedBy,senderId) VALUES ('pin','room',${now},'member','member')`
    await db.$executeRaw`INSERT INTO ConversationParticipant VALUES ('p','member','room')`
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
test('retrying explicit Unpin stays unpinned after a lost response', () => fixture(async db => {
  assert.equal(await removeMessagePin(db, 'member', 'pin'), 'room')
  assert.equal(await removeMessagePin(db, 'member', 'pin'), 'room')
  assert.deepEqual(await db.message.findUnique({ where: { id: 'pin' }, select: { pinnedAt: true, pinnedBy: true } }), { pinnedAt: null, pinnedBy: null })
}))
test('Unpin refuses nonmembers without changing the message', () => fixture(async db => {
  await assert.rejects(removeMessagePin(db, 'outsider', 'pin'), { status: 403 })
  await assert.rejects(removeMessagePin(db, 'member', 'absent'), { status: 404 })
  assert.equal((await db.message.findUnique({ where: { id: 'pin' }, select: { pinnedAt: true } })).pinnedAt.getTime(), now.getTime())
}))
