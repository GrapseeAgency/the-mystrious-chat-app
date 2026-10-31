// Section 8 item 1 - cross-device room metadata revisions:
// competing writes take STRICTLY increasing metadataRev values (SQLite
// serializable order IS the total order) and every ACK carries the winning
// revision so devices can discard stale applications.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
import * as metadata from '../../src/lib/conversation-metadata.ts'
import { setGroupRole, removeGroupMember, GroupMembershipError } from '../../src/lib/group-membership.ts'

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'roomrev-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe(`CREATE TABLE Conversation(id TEXT PRIMARY KEY, isGroup BOOLEAN, name TEXT, photo TEXT,
      broadcastMode BOOLEAN DEFAULT false, screenPrivacy BOOLEAN DEFAULT false, ttlSeconds INTEGER DEFAULT 0,
      slowModeSeconds INTEGER DEFAULT 0, metadataRev INTEGER DEFAULT 0, updatedAt DATETIME)`)
    await db.$executeRawUnsafe('CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, role TEXT DEFAULT "member", joinedAt DATETIME, UNIQUE(userId,conversationId))')
    await db.$executeRawUnsafe(`INSERT INTO Conversation (id,isGroup,updatedAt) VALUES ('room',1,${Date.now()})`)
    await db.$executeRawUnsafe(`INSERT INTO ConversationParticipant (id,userId,conversationId,role) VALUES
      ('pa','alice','room','admin'),('pb','bob','room','member'),('pc','carol','room','member')`)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

test('competing metadata writers receive distinct strictly increasing revisions', () => fixture(async (db) => {
  const patches = [
    { name: 'Alpha' }, { name: 'Bravo' }, { ttlSeconds: 86400 }, { name: 'Charlie' }, { slowModeSeconds: 5 },
  ]
  // Honest client behavior: 409 contention retries the SAME patch until it
  // lands. Serialized SQLite must eventually give each write its own slot.
  const acks = await Promise.all(patches.map(async patch => {
    for (let attempt = 0; ; attempt++) {
      try { return await patchConversation(db, patch) }
      catch (e) { if (e.status === 409 && attempt < 60) { await new Promise(r => setTimeout(r, Math.min(50, 2 * attempt))); continue } ; throw e }
    }
  }))
  const revs = acks.map(a => a.metadataRev).sort((x, y) => x - y)
  assert.deepEqual(revs, [1, 2, 3, 4, 5], 'five competing writes must occupy five total-order slots')
  const final = await db.conversation.findUnique({ where: { id: 'room' }, select: { metadataRev: true, name: true } })
  assert.equal(final.metadataRev, 5)
  // The room's stored name is exactly one of the competing writes (no mix).
  assert.ok(['Alpha', 'Bravo', 'Charlie'].includes(final.name))
  // A subsequent single write lands on the next slot deterministically.
  const later = await patchConversation(db, { name: 'Delta' })
  assert.equal(later.metadataRev, 6)
  assert.equal((await db.conversation.findUnique({ where: { id: 'room' }, select: { name: true } })).name, 'Delta')
}))

async function patchConversation(db, patch) {
  const actor = patch.ttlSeconds !== undefined ? 'carol' : 'alice'
  return metadata.patchConversationMetadata(db, 'room', actor, patch)
}

test('membership mutations share the same counter and hand the revision to caches', () => fixture(async (db) => {
  const promote = await setGroupRole(db, 'room', 'alice', 'carol', 'admin')
  assert.equal(promote.metadataRev, 1)
  const demote = await setGroupRole(db, 'room', 'alice', 'carol', 'member')
  assert.equal(demote.metadataRev, 2)
  const noChange = await setGroupRole(db, 'room', 'alice', 'carol', 'member')
  assert.equal(noChange.metadataRev, undefined, 'no-op role writes must not spend a revision')
  const removal = await removeGroupMember(db, 'room', 'alice', 'carol')
  assert.equal(removal.metadataRev, 3)
  assert.deepEqual(removal.recipients, ['alice', 'bob'])
  // Owner handoff path bumps the counter too (leave of last admin promotes successor).
  await db.$executeRawUnsafe(`INSERT INTO ConversationParticipant (id,userId,conversationId,role) VALUES ('pd','erin','room','member')`)
  const leave = await import('../../src/lib/group-membership.ts').then(m => m.leaveGroup(db, 'room', 'alice'))
  assert.equal(leave.promotedUserId, 'bob')
  assert.equal(leave.metadataRev, 4)
  const room = await db.conversation.findUnique({ where: { id: 'room' }, select: { metadataRev: true } })
  assert.equal(room.metadataRev, 4)
}))

test('stale ACKs from lost deliveries are detectable by revision comparison', () => fixture(async (db) => {
  const first = await patchConversation(db, { name: 'One' })
  const second = await patchConversation(db, { name: 'Two' })
  assert.ok(second.metadataRev > first.metadataRev)
  // A device that cached first.metadataRev knows its state is outdated.
  const current = await db.conversation.findUnique({ where: { id: 'room' }, select: { metadataRev: true } })
  assert.ok(current.metadataRev > first.metadataRev)
}))

test('metadata authority still gates the revision: non-admins and strangers cannot spend slots', () => fixture(async (db) => {
  await assert.rejects(metadata.patchConversationMetadata(db, 'room', 'carol', { name: 'Nope' }), e => e.status === 403)
  await assert.rejects(metadata.patchConversationMetadata(db, 'room', 'zoe', { ttlSeconds: 604800 }), e => e.status === 403)
  const room = await db.conversation.findUnique({ where: { id: 'room' }, select: { metadataRev: true } })
  assert.equal(room.metadataRev, 0, 'refused writes must not consume revision slots')
  await assert.rejects(setGroupRole(db, 'room', 'carol', 'alice', 'member'), GroupMembershipError)
}))
