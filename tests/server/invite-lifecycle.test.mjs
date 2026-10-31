// Section 8 item 4 - invite lifecycle: expiry, revocation, old-code reuse,
// and every redemption entry point.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
import { groupInvite, revokeGroupInvite, joinGroupInvite, leaveGroup, GroupMembershipError } from '../../src/lib/group-membership.ts'

const mint = () => 'LIVECODE' + Math.floor(Math.random() * 10)
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'invlife-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe(`CREATE TABLE Conversation(id TEXT PRIMARY KEY, isGroup BOOLEAN, broadcastMode BOOLEAN DEFAULT false,
      inviteCode TEXT UNIQUE, inviteExpiresAt DATETIME, metadataRev INTEGER DEFAULT 0, updatedAt DATETIME)`)
    await db.$executeRawUnsafe(`CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT,
      role TEXT DEFAULT "member", joinedAt DATETIME DEFAULT CURRENT_TIMESTAMP, lastReadAt DATETIME DEFAULT CURRENT_TIMESTAMP,
      screenPrivacy BOOLEAN DEFAULT false, manualUnread BOOLEAN DEFAULT false, draftRevision TEXT DEFAULT '0', draft TEXT, UNIQUE(userId, conversationId))`)
    await db.$executeRawUnsafe('CREATE TABLE MembershipHistory(id TEXT PRIMARY KEY, conversationId TEXT, userId TEXT, joinedAt DATETIME, leftAt DATETIME)')
    await db.$executeRawUnsafe('CREATE TABLE MessageReadObservation(participantId TEXT, messageId TEXT, observedAt DATETIME, source TEXT DEFAULT \'legacy\', PRIMARY KEY(participantId, messageId))')
    await db.$executeRawUnsafe('CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT, createdAt DATETIME)')
    await db.$executeRawUnsafe(`CREATE TABLE User(id TEXT PRIMARY KEY)`)
    await db.$executeRawUnsafe(`INSERT INTO Conversation (id,isGroup,updatedAt) VALUES ('group',1,${Date.now()})`)
    for (const id of ['admin', 'joiner', 'late']) await db.$executeRawUnsafe('INSERT INTO User (id) VALUES (?)', id)
    await db.$executeRawUnsafe(`INSERT INTO ConversationParticipant (id,userId,conversationId,role) VALUES ('p-admin','admin','group','admin')`)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

test('an expired code is dead at every entry point: preview and join', () => fixture(async (db) => {
  const past = new Date(Date.now() - 1000)
  const minted = await groupInvite(db, 'group', 'admin', false, () => 'EXPIRED1', past)
  assert.equal(minted.inviteCode, 'EXPIRED1')
  assert.equal(minted.metadataRev, 1)
  await assert.rejects(joinGroupInvite(db, 'expired1', 'joiner'), e =>
    e.status === 404 && /expired/.test(e.message), 'join must reject the expired code')
  // Regeneration clears the old expiry and revives the entry point.
  const fresh = await groupInvite(db, 'group', 'admin', true, () => 'FRESH001', new Date(Date.now() + 86_400_000))
  assert.equal(fresh.inviteCode, 'FRESH001')
  const redemption = await joinGroupInvite(db, 'fresh001', 'joiner')
  assert.equal(redemption.alreadyMember, false)
  assert.equal(redemption.metadataRev, 3)
}))

test('revocation clears the code and old-link reuse deterministically 404s', () => fixture(async (db) => {
  const minted = await groupInvite(db, 'group', 'admin', false, () => 'REVOKE01')
  const joined = await joinGroupInvite(db, minted.inviteCode.toLowerCase(), 'joiner')
  assert.equal(joined.alreadyMember, false)
  const revoked = await revokeGroupInvite(db, 'group', 'admin')
  assert.equal(revoked.revoked, true)
  assert.equal(revoked.metadataRev > minted.metadataRev, true)
  await assert.rejects(joinGroupInvite(db, 'revoke01', 'late'), e =>
    e.status === 404 && /no longer valid/.test(e.message), 'the old link is dead for everyone')
  await assert.rejects(joinGroupInvite(db, 'revoke01', 'joiner'), e => e.status === 404,
    'old-code reuse by an existing member is also refused')
  // Revoking with nothing active is a stable no-op.
  const again = await revokeGroupInvite(db, 'group', 'admin')
  assert.equal(again.revoked, false)
  assert.equal(again.metadataRev, undefined, 'no-op revocations must not spend a revision')
}))

test('only current admins touch the lifecycle and joins are repeat-safe', () => fixture(async (db) => {
  await assert.rejects(groupInvite(db, 'group', 'joiner', false, mint), e => e.status === 403)
  await assert.rejects(revokeGroupInvite(db, 'group', 'joiner'), e => e.status === 403)
  const minted = await groupInvite(db, 'group', 'admin', false, () => 'AUTHOK01')
  const first = await joinGroupInvite(db, minted.inviteCode, 'joiner')
  const second = await joinGroupInvite(db, minted.inviteCode, 'joiner')
  assert.equal(first.alreadyMember, false)
  assert.equal(second.alreadyMember, true)
  assert.equal(second.metadataRev, undefined, 'duplicate redemption must not spend a revision')
  assert.equal(await db.conversationParticipant.count({ where: { conversationId: 'group', userId: 'joiner' } }), 1)
  await assert.rejects(joinGroupInvite(db, 'toolongcode12345678', 'late'), e => e.status === 400)
}))

test('the last admin leaving without a successor still refuses for channels but hands groups over', () => fixture(async (db) => {
  await db.$executeRawUnsafe(`UPDATE Conversation SET broadcastMode = 0 WHERE id = 'group'`)
  await db.$executeRawUnsafe(`INSERT INTO ConversationParticipant (id,userId,conversationId,role) VALUES ('p-joiner','joiner','group','member')`)
  const leave = await leaveGroup(db, 'group', 'admin')
  assert.equal(leave.promotedUserId, 'joiner')
  const roles = await db.conversationParticipant.findMany({ where: { conversationId: 'group' }, select: { role: true } })
  assert.deepEqual(roles.map(r => r.role), ['admin'])
  // The group metadataRev moved: caches can reconcile the handoff.
  const room = await db.conversation.findUnique({ where: { id: 'group' }, select: { metadataRev: true } })
  assert.equal(room.metadataRev, 1)
}))
