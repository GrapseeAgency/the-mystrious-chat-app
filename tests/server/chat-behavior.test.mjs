import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { canJoinConversationCall } from '../../src/lib/group-call-access.ts'
import { advanceReadReceipt, receiptRecipients, setManualUnread, captureReadIntent } from '../../src/lib/read-receipt.ts'
import { inboxMessagesWhere, visibleInboxPreview, threadRepliesWhere, removeSavedMessage, unreadSnapshot as exactUnreadSnapshot, unreadMessages, unexpiredMessages, threadRootAvailable } from '../../src/lib/message-visibility.ts'
import { scheduledDeliveryDecision } from '../../src/lib/scheduled-delivery.ts'
import { openDirectConversation, openSelfConversation } from '../../src/lib/direct-conversation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const t = n => new Date(`2026-10-20T00:00:${String(n).padStart(2, '0')}.000Z`)
// Existing expiry assertions deliberately compare the counts projection. Identity
// assertions below exercise the anchor returned by the very same SQL snapshot.
async function unreadSnapshot(...args) {
  const { firstUnreadMessageId, ...counts } = await exactUnreadSnapshot(...args)
  return counts
}
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
      await readFile(new URL('../../prisma/patches/20261026_read_intent_state.sql', import.meta.url), 'utf8'),
      `CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT, createdAt DATETIME, senderId TEXT,
        expiresAt DATETIME, deletedAt DATETIME, parentId TEXT, topicId TEXT, viaAutomation BOOLEAN NOT NULL DEFAULT false)`,
      `CREATE TABLE UserBlock(id TEXT PRIMARY KEY, blockerId TEXT, blockedId TEXT)`,
    ]) await db.$executeRawUnsafe(sql)
    await exactMigration(db)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
async function exactMigration(db) {
  const sql = await readFile(new URL('../../prisma/patches/20261026_exact_read_observations.sql', import.meta.url), 'utf8')
  await db.$transaction(async tx => {
    for (const statement of sql.replace(/^--.*$/gm, '').split(';').map(s => s.trim()).filter(s => s && !['BEGIN IMMEDIATE','COMMIT'].includes(s))) await tx.$executeRawUnsafe(statement)
  })
}
async function seedUnreadMember(db, room = 'room', viewer = 'reader') {
  await db.$executeRaw`INSERT INTO ConversationParticipant(id,userId,conversationId,joinedAt,lastReadAt)
    VALUES ('unread-member',${viewer},${room},${new Date(t(0).getTime()+1)},${t(0)})`
}
async function seedRead(db) {
  await db.$executeRaw`INSERT INTO ConversationParticipant(id,userId,conversationId,joinedAt,lastReadAt,manualUnread) VALUES ('p','reader','room',${t(0)},${t(0)},true)`
  await db.$executeRaw`INSERT INTO Message(id,conversationId,createdAt) VALUES ('displayed','room',${t(10)}),('not-displayed','room',${t(20)}),('foreign','other',${t(30)})`
}
test('reading a displayed row leaves later, undisplayed arrival unread', () => fixture(async db => {
  await seedRead(db)
  assert.equal((await advanceReadReceipt(db, 'room', 'reader', 'displayed', (await captureReadIntent(db, 'room', 'reader')).readIntent)).toISOString(), t(10).toISOString())
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
test('unavailable displayed anchors never advance or clear manual unread', () => fixture(async db => {
  await seedRead(db)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,createdAt,parentId) VALUES
    ('reply','room',${t(30)},'displayed'),('foreign-parent','room',${t(30)},'foreign'),
    ('nested','room',${t(30)},'reply'),('orphan','room',${t(30)},'missing')`
  for (const id of ['foreign-parent', 'nested', 'orphan']) {
    await assert.rejects(advanceReadReceipt(db, 'room', 'reader', id), { status: 404 })
  }
  for (const patch of ["deletedAt=0", "deletedAt=NULL,expiresAt=0"]) {
    await db.$executeRawUnsafe(`UPDATE Message SET ${patch} WHERE id='displayed'`)
    for (const id of ['displayed', 'reply']) await assert.rejects(advanceReadReceipt(db, 'room', 'reader', id), { status: 404 })
  }
  const unread = await db.conversationParticipant.findUnique({ where: { id: 'p' } })
  assert.equal(unread.manualUnread, true)
  assert.equal(unread.lastReadAt.toISOString(), t(0).toISOString())
  await db.$executeRawUnsafe("UPDATE Message SET deletedAt=NULL,expiresAt=NULL WHERE id='displayed'")
  await db.$executeRawUnsafe("UPDATE Message SET expiresAt=0 WHERE id='reply'")
  await assert.rejects(advanceReadReceipt(db, 'room', 'reader', 'reply'), { status: 404 })
  await db.$executeRawUnsafe("UPDATE Message SET expiresAt=NULL WHERE id='reply'")
  assert.equal((await advanceReadReceipt(db, 'room', 'reader', 'reply')).toISOString(), t(30).toISOString())
}))
test('explicit mark-all uses the latest server row, not wall-clock time', () => fixture(async db => {
  await seedRead(db)
  assert.equal((await advanceReadReceipt(db, 'room', 'reader')).toISOString(), t(20).toISOString())
}))
test('receipt privacy still synchronizes the reader own devices', () => {
  assert.deepEqual(receiptRecipients('reader', ['reader','peer'], '{"readReceipts":false}'), ['reader'])
  assert.deepEqual(receiptRecipients('reader', ['reader','peer'], null), ['reader','peer'])
  assert.deepEqual(receiptRecipients('reader', ['peer'], '{broken'), ['reader'])
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

test('expired deleted and own messages cannot leave unread ghosts before purge', () => fixture(async db => {
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt,expiresAt,deletedAt) VALUES
    ('alive','room','peer',${t(10)},NULL,NULL),
    ('expired','room','peer',${t(15)},${t(20)},NULL),
    ('deleted','room','peer',${t(16)},NULL,${t(19)}),
    ('own','room','reader',${t(17)},NULL,NULL)`
  assert.equal(await db.message.count({ where: unreadMessages('room', 'reader', t(0), t(20)) }), 1)
  assert.equal(await db.message.count({ where: unreadMessages('room', 'reader', t(0), t(19)) }), 2)
  const preview = await db.message.findFirst({ where: { conversationId: 'room', ...unexpiredMessages(t(20)) }, orderBy: { createdAt: 'desc' }, select: { id: true } })
  assert.notEqual(preview.id, 'expired')
}))
async function scheduledFixture(db, group = true) {
  await db.$executeRaw`INSERT INTO Conversation(id,isGroup,broadcastMode,ttlSeconds,slowModeSeconds,updatedAt) VALUES ('room',${group},false,60,10,${t(0)})`
  await db.$executeRaw`INSERT INTO ConversationParticipant(id,userId,conversationId,role,lastReadAt) VALUES ('sender','sender','room','member',${t(0)}),('peer','peer','room','member',${t(0)})`
}
test('scheduled dispatch rechecks membership and announcement role', () => fixture(async db => {
  await scheduledFixture(db)
  await db.conversation.update({ where: { id: 'room' }, data: { broadcastMode: true } })
  assert.deepEqual(await scheduledDeliveryDecision(db, 'room', 'sender', t(20)), { state: 'cancel', reason: 'announcement_only' })
  await db.conversationParticipant.update({ where: { id: 'sender' }, data: { role: 'admin' } })
  assert.equal((await scheduledDeliveryDecision(db, 'room', 'sender', t(20))).state, 'send')
  await db.conversationParticipant.delete({ where: { id: 'sender' } })
  assert.deepEqual(await scheduledDeliveryDecision(db, 'room', 'sender', t(20)), { state: 'cancel', reason: 'not_member' })
}))
test('scheduled dispatch obeys slow mode and current disappearing-message TTL', () => fixture(async db => {
  await scheduledFixture(db)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt,deletedAt) VALUES ('last','room','sender',${t(10)},${t(12)})`
  assert.deepEqual(await scheduledDeliveryDecision(db, 'room', 'sender', t(15)), { state: 'wait', retryAfter: 5 })
  const ready = await scheduledDeliveryDecision(db, 'room', 'sender', t(20))
  assert.equal(ready.state, 'send')
  assert.equal(ready.expiresAt.getTime() - t(20).getTime(), 60_000)
  await db.message.update({ where: { id: 'last' }, data: { viaAutomation: true }, select: { id: true } })
  assert.equal((await scheduledDeliveryDecision(db, 'room', 'sender', t(15))).state, 'send')
  assert.equal((await db.$queryRaw`SELECT lastReadAt FROM ConversationParticipant WHERE id='sender'`)[0].lastReadAt.getTime(), t(0).getTime())
}))
test('a DM blocked after scheduling is refused at delivery time in either direction', () => fixture(async db => {
  await scheduledFixture(db, false)
  for (const [a,b] of [['sender','peer'],['peer','sender']]) {
    await db.$executeRaw`DELETE FROM UserBlock`
    await db.$executeRaw`INSERT INTO UserBlock VALUES ('block',${a},${b})`
    assert.deepEqual(await scheduledDeliveryDecision(db, 'room', 'sender', t(20)), { state: 'cancel', reason: 'blocked' })
  }
}))

test('thread roots are unavailable at expiry, after deletion, across rooms, or when nested', () => {
  const root = { conversationId: 'room', parentId: null, deletedAt: null, expiresAt: t(20) }
  assert.equal(threadRootAvailable(root, 'room', t(19)), true)
  assert.equal(threadRootAvailable(root, 'room', t(20)), false)
  assert.equal(threadRootAvailable(root, 'other', t(19)), false)
  assert.equal(threadRootAvailable({ ...root, parentId: 'another' }, 'room', t(19)), false)
  assert.equal(threadRootAvailable({ ...root, deletedAt: t(18) }, 'room', t(19)), false)
  assert.equal(threadRootAvailable(null, 'room', t(19)), false)
})

test('concurrent transactional sends cannot both bypass room slow mode', () => fixture(async db => {
  await scheduledFixture(db)
  const send = id => db.$transaction(async tx => {
    const decision = await scheduledDeliveryDecision(tx, 'room', 'sender', t(20))
    if (decision.state !== 'send') return decision
    await tx.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt,expiresAt) VALUES (${id},'room','sender',${t(20)},${decision.expiresAt})`
    return decision
  })
  const results = await Promise.allSettled([send('first'), send('second')])
  assert.equal(await db.message.count(), 1)
  assert.equal(results.filter(r => r.status === 'fulfilled' && r.value.state === 'send').length, 1)
  for (const result of results) {
    if (result.status === 'fulfilled' && result.value.state === 'wait') assert.equal(result.value.retryAfter, 10)
    if (result.status === 'rejected') assert.ok(['P2034', 'P2028', 'P1008'].includes(result.reason.code), String(result.reason))
  }
}))


test('offline unread schedule counts only live incoming unread rows and keeps permanent unread', () => fixture(async db => {
  await seedUnreadMember(db)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt,expiresAt,deletedAt) VALUES
    ('permanent','room','peer',${t(10)},NULL,NULL),
    ('soon-a','room','peer',${t(11)},${t(30)},NULL),
    ('soon-b','room','peer',${t(12)},${t(30)},NULL),
    ('later','room','peer',${t(13)},${t(40)},NULL),
    ('read','room','peer',${t(0)},${t(40)},NULL),
    ('own','room','reader',${t(14)},${t(40)},NULL),
    ('expired','room','peer',${t(15)},${t(20)},NULL),
    ('deleted','room','peer',${t(16)},${t(40)},${t(19)}),
    ('other','elsewhere','peer',${t(17)},${t(40)},NULL)`
  assert.deepEqual(await unreadSnapshot(db, 'room', 'reader', t(0), t(20)), {
    unreadCount: 4, unreadExpirations: [{ expiresAt: t(30).toISOString(), count: 2 }, { expiresAt: t(40).toISOString(), count: 1 }],
  })
  assert.deepEqual(await unreadSnapshot(db, 'room', 'reader', t(0), t(30)), {
    unreadCount: 2, unreadExpirations: [{ expiresAt: t(40).toISOString(), count: 1 }],
  })
  assert.deepEqual(await unreadSnapshot(db, 'room', 'reader', t(0), t(40)), { unreadCount: 1, unreadExpirations: [] })
}))


test('explicit unsave retries stay removed, affect only the owner, and need no surviving message', () => fixture(async db => {
  await db.$executeRawUnsafe('CREATE TABLE SavedMessage(id TEXT PRIMARY KEY, userId TEXT, messageId TEXT, savedAt DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE(userId,messageId))')
  await db.$executeRaw`INSERT INTO SavedMessage(id,userId,messageId) VALUES ('a','alice','gone'),('b','bob','gone')`
  assert.deepEqual(await removeSavedMessage(db, 'alice', 'gone'), { saved: false })
  assert.deepEqual(await removeSavedMessage(db, 'alice', 'gone'), { saved: false })
  assert.deepEqual(await removeSavedMessage(db, 'alice', 'never-saved'), { saved: false })
  assert.equal(await db.savedMessage.count({ where: { userId: 'alice' } }), 0)
  assert.equal(await db.savedMessage.count({ where: { userId: 'bob' } }), 1)
}))


test('thread reader excludes foreign-room and expired replies even with a matching parent link', () => fixture(async db => {
  await db.$executeRaw`INSERT INTO Message(id,conversationId,parentId,createdAt,expiresAt) VALUES
    ('live','room','root',${t(0)},NULL),
    ('foreign','other','root',${t(0)},NULL),
    ('expired','room','root',${t(0)},${t(20)}),
    ('different-thread','room','other-root',${t(0)},NULL)`
  const rows = await db.message.findMany({ where: threadRepliesWhere('root', 'room', t(20)), select: { id: true } })
  assert.deepEqual(rows.map(row => row.id), ['live'])
}))


test('unread replies require a live same-room canonical root, including at its expiry boundary', () => fixture(async db => {
  await seedUnreadMember(db)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt,expiresAt,deletedAt,parentId) VALUES
    ('root','room','reader',${t(0)},${t(30)},NULL,NULL),
    ('reply','room','peer',${t(10)},NULL,NULL,'root'),
    ('deleted-root','room','reader',${t(0)},NULL,${t(1)},NULL),
    ('deleted-root-reply','room','peer',${t(10)},NULL,NULL,'deleted-root'),
    ('expired-root','room','reader',${t(0)},${t(20)},NULL,NULL),
    ('expired-root-reply','room','peer',${t(10)},NULL,NULL,'expired-root'),
    ('foreign-root','other','reader',${t(0)},NULL,NULL,NULL),
    ('foreign-root-reply','room','peer',${t(10)},NULL,NULL,'foreign-root'),
    ('nested-reply','room','peer',${t(10)},NULL,NULL,'reply'),
    ('orphan','room','peer',${t(10)},NULL,NULL,'missing')`
  assert.equal(await db.message.count({ where: unreadMessages('room', 'reader', t(0), t(20)) }), 1)
  assert.deepEqual(await unreadSnapshot(db, 'room', 'reader', t(0), t(20)), {
    unreadCount: 1, unreadExpirations: [{ expiresAt: t(30).toISOString(), count: 1 }],
  })
  assert.deepEqual(await unreadSnapshot(db, 'room', 'reader', t(0), t(30)), { unreadCount: 0, unreadExpirations: [] })
  await db.$executeRaw`UPDATE Message SET deletedAt=${t(21)} WHERE id='root'`
  assert.deepEqual(await unreadSnapshot(db, 'room', 'reader', t(0), t(22)), { unreadCount: 0, unreadExpirations: [] })
}))

test('offline unread buckets use the earlier root/reply deadline without double counting', () => fixture(async db => {
  await seedUnreadMember(db)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt,expiresAt,parentId) VALUES
    ('root','room','peer',${t(1)},${t(30)},NULL),
    ('early','room','peer',${t(2)},${t(25)},'root'),
    ('late','room','peer',${t(3)},${t(40)},'root'),
    ('same','room','peer',${t(4)},${t(30)},'root'),
    ('no-deadline','room','peer',${t(5)},NULL,'root'),
    ('own','room','reader',${t(6)},NULL,'root'),
    ('permanent-root','room','reader',${t(0)},NULL,NULL),
    ('permanent','room','peer',${t(7)},NULL,'permanent-root'),
    ('child-only','room','peer',${t(8)},${t(40)},'permanent-root')`
  const initial = await unreadSnapshot(db, 'room', 'reader', t(0), t(20))
  assert.deepEqual(initial, { unreadCount: 7, unreadExpirations: [
    { expiresAt: t(25).toISOString(), count: 1 }, { expiresAt: t(30).toISOString(), count: 4 },
    { expiresAt: t(40).toISOString(), count: 1 },
  ] })
  for (const now of [t(25), t(30), t(40)]) {
    const expired = initial.unreadExpirations.filter(bucket => new Date(bucket.expiresAt) <= now)
      .reduce((n, bucket) => n + bucket.count, 0)
    const refreshed = await unreadSnapshot(db, 'room', 'reader', t(0), now)
    assert.equal(initial.unreadCount - expired, refreshed.unreadCount)
    assert.equal(await db.message.count({ where: unreadMessages('room', 'reader', t(0), now) }), refreshed.unreadCount)
  }
}))

test('unread aggregation binds room and viewer values rather than interpolating SQL', () => fixture(async db => {
  const room = "room' OR 1=1 --", viewer = "reader' --"
  await seedUnreadMember(db, room, viewer)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES
    ('mine',${room},${viewer},${t(5)}),('incoming',${room},'peer',${t(6)}),('elsewhere','other','peer',${t(7)})`
  assert.deepEqual(await unreadSnapshot(db, room, viewer, t(0), t(20)), { unreadCount: 1, unreadExpirations: [] })
}))


test('inbox preview skips hidden-root replies and carries the earlier lifetime without changing stored rows', () => fixture(async db => {
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt,expiresAt,deletedAt,parentId) VALUES
    ('root','room','reader',${t(0)},${t(30)},NULL,NULL),
    ('reply','room','peer',${t(10)},${t(40)},NULL,'root'),
    ('deleted-root','room','reader',${t(0)},NULL,${t(1)},NULL),
    ('hidden','room','peer',${t(20)},NULL,NULL,'deleted-root'),
    ('orphan','room','peer',${t(21)},NULL,NULL,'missing'),
    ('fallback','room','peer',${t(5)},NULL,NULL,NULL)`
  const fields = { id: true, conversationId: true, parentId: true, expiresAt: true, deletedAt: true }
  async function preview(at) {
    const row = await db.message.findFirst({ where: inboxMessagesWhere('room', at),
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], select: { ...fields, parent: { select: fields } } })
    return { raw: row, projected: visibleInboxPreview(row, 'room', at) }
  }
  const initial = await preview(t(22))
  assert.equal(initial.projected.id, 'reply')
  assert.equal(initial.projected.expiresAt.toISOString(), t(30).toISOString())
  assert.equal(initial.raw.expiresAt.toISOString(), t(40).toISOString())
  assert.equal((await preview(t(30))).projected.id, 'fallback')
  assert.equal(visibleInboxPreview(initial.raw, 'room', t(30)), null)
  assert.equal(visibleInboxPreview(initial.raw, 'other', t(22)), null)
  assert.equal(visibleInboxPreview({ ...initial.raw, parent: null }, 'room', t(22)), null)
  assert.equal(visibleInboxPreview({ ...initial.raw, parent: { ...initial.raw.parent, parentId: 'nested' } }, 'room', t(22)), null)
}))


test('simultaneous Note to Self requests converge without creating a one-member DM', () => fixture(async db => {
  await db.$executeRawUnsafe("CREATE TABLE User(id TEXT PRIMARY KEY)")
  await db.$executeRawUnsafe("INSERT INTO User VALUES ('me'),('other')")
  const attempts = await Promise.allSettled([openSelfConversation(db, 'me'), openSelfConversation(db, 'me')])
  const successful = attempts.filter(result => result.status === 'fulfilled').map(result => result.value)
  assert.ok(successful.length > 0)
  for (const result of attempts) if (result.status === 'rejected') assert.equal(result.reason.status, 409)
  const reopened = await openSelfConversation(db, 'me')
  for (const result of successful) assert.equal(result.id, reopened.id)
  assert.equal(reopened.created, false)
  assert.equal(await db.conversation.count(), 1)
  const row = await db.conversation.findUnique({ where: { id: reopened.id },
    select: { isSelf: true, isGroup: true, participants: { select: { userId: true } } } })
  assert.deepEqual(row, { isSelf: true, isGroup: false, participants: [{ userId: 'me' }] })
  assert.notEqual((await openSelfConversation(db, 'other')).id, reopened.id)
  await assert.rejects(openSelfConversation(db, 'missing'), { status: 404 })
  assert.equal(await db.conversation.count(), 2)
}))

test('a malformed shared self-flagged chat is never selected as a private notebook', () => fixture(async db => {
  await db.$executeRawUnsafe("CREATE TABLE User(id TEXT PRIMARY KEY)")
  await db.$executeRawUnsafe("INSERT INTO User VALUES ('me'),('other')")
  await db.$executeRaw`INSERT INTO Conversation(id,isSelf,isGroup,updatedAt) VALUES ('shared',true,false,${t(0)})`
  await db.$executeRawUnsafe("INSERT INTO ConversationParticipant(id,userId,conversationId) VALUES ('me-shared','me','shared'),('other-shared','other','shared')")
  const notebook = await openSelfConversation(db, 'me')
  assert.notEqual(notebook.id, 'shared')
  assert.equal(notebook.created, true)
  assert.equal((await openSelfConversation(db, 'me')).id, notebook.id)
  const direct = await openDirectConversation(db, ['me', 'other'])
  assert.notEqual(direct.id, 'shared')
  assert.notEqual(direct.id, notebook.id)
}))


test('read transaction contention is retryable, not an access refusal', async () => {
  for (const code of ['P1008', 'P2028', 'P2034']) {
    const db = { $transaction: async () => { throw Object.assign(new Error('busy'), { code }) } }
    await assert.rejects(advanceReadReceipt(db, 'room', 'reader', 'displayed'), { status: 409 })
  }
  const failure = new Error('unexpected persistence failure')
  await assert.rejects(advanceReadReceipt({ $transaction: async () => { throw failure } }, 'room', 'reader', 'displayed'), error => error === failure)
})

test('represented integration sends are incoming for unread without counting human echoes', () => fixture(async db => {
  await seedUnreadMember(db)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt,viaAutomation,expiresAt)
    VALUES ('human','room','reader',${t(10)},false,NULL),
           ('machine','room','reader',${t(11)},true,${t(30)}),
           ('other','room','peer',${t(12)},false,NULL),
           ('dead-machine','room','reader',${t(13)},true,${t(19)})`
  assert.equal(await db.message.count({ where: unreadMessages('room','reader',t(0),t(20)) }), 2)
  assert.deepEqual(await unreadSnapshot(db,'room','reader',t(0),t(20)), {
    unreadCount: 2, unreadExpirations: [{ expiresAt: t(30).toISOString(), count: 1 }],
  })
  await db.$executeRaw`INSERT INTO MessageReadObservation(participantId,messageId,observedAt) VALUES ('unread-member','machine',${t(11)})`
  assert.deepEqual(await unreadSnapshot(db,'room','reader',t(11),t(20)), { unreadCount: 1, unreadExpirations: [] })
  assert.equal(await db.message.count({ where: unreadMessages('room','reader',t(0),t(30)) }), 1)
  await db.$executeRaw`UPDATE Message SET deletedAt = ${t(20)} WHERE id = 'machine'`
  assert.deepEqual(await unreadSnapshot(db,'room','reader',t(0),t(20)), { unreadCount: 1, unreadExpirations: [] })
}))

test('an older displayed receipt preserves a newer manual marker; deliberate mark-all still clears it', () => fixture(async db => {
  await seedRead(db)
  await db.$executeRaw`INSERT INTO Conversation(id,updatedAt) VALUES ('room',${t(0)})`
  await advanceReadReceipt(db, 'room', 'reader', 'not-displayed')
  const receipt = await setManualUnread(db, 'room', 'reader', true)
  assert.deepEqual(receipt, { ok: true, unreadReceiptVersion: 2, readIntent: undefined, conversationId: 'room', userId: 'reader', manualUnread: true })
  assert.equal((await advanceReadReceipt(db, 'room', 'reader', 'displayed')).toISOString(), t(20).toISOString())
  let row = await db.conversationParticipant.findUnique({ where: { id: 'p' } })
  assert.equal(row.manualUnread, true)
  // Deleting the latest row must not prevent an explicit mark-all from clearing the marker.
  await db.message.delete({ where: { id: 'not-displayed' }, select: { id: true } })
  await advanceReadReceipt(db, 'room', 'reader', undefined, (await captureReadIntent(db, 'room', 'reader')).readIntent)
  row = await db.conversationParticipant.findUnique({ where: { id: 'p' } })
  assert.equal(row.manualUnread, false)
  assert.equal(row.lastReadAt.toISOString(), t(20).toISOString())
}))
test('manual unread commits only the member marker and returns the exact desired state', () => fixture(async db => {
  await seedRead(db)
  await db.$executeRaw`INSERT INTO Conversation(id,updatedAt) VALUES ('room',${t(0)})`
  for (const on of [false, true, true, false]) {
    assert.equal((await setManualUnread(db, 'room', 'reader', on)).manualUnread, on)
    const row = await db.conversationParticipant.findUnique({ where: { id: 'p' } })
    assert.equal(row.manualUnread, on)
    assert.equal(row.lastReadAt.toISOString(), t(0).toISOString())
  }
  await assert.rejects(setManualUnread(db, 'missing', 'reader', true), { status: 404 })
  await assert.rejects(setManualUnread(db, 'room', 'outsider', true), { status: 403 })
  await db.conversationParticipant.delete({ where: { id: 'p' } })
  await assert.rejects(setManualUnread(db, 'room', 'reader', true), { status: 403 })
  assert.equal(await db.conversationParticipant.count(), 0)
}))

test('call access requires current conversation membership, not just an existing user or orphaned row', () => fixture(async db => {
  await seedRead(db)
  assert.equal(await canJoinConversationCall(db, 'room', 'reader'), false)
  await db.$executeRaw`INSERT INTO Conversation(id,updatedAt) VALUES ('room',${t(0)})`
  assert.equal(await canJoinConversationCall(db, 'room', 'reader'), true)
  assert.equal(await canJoinConversationCall(db, 'room', 'outsider'), false)
  assert.equal(await canJoinConversationCall(db, 'other', 'reader'), false)
  await db.conversationParticipant.delete({ where: { id: 'p' } })
  assert.equal(await canJoinConversationCall(db, 'room', 'reader'), false)
}))


test('manual unread fences equal/newer delayed visible receipts and lost-ACK retries', () => fixture(async db => {
  await seedRead(db)
  await db.$executeRaw`INSERT INTO Conversation(id,updatedAt) VALUES ('room',${t(0)})`
  const original = (await captureReadIntent(db, 'room', 'reader')).readIntent
  await advanceReadReceipt(db, 'room', 'reader', 'displayed', original) // response lost
  await setManualUnread(db, 'room', 'reader', true, original)
  for (const anchor of ['displayed', 'not-displayed', 'displayed']) {
    await advanceReadReceipt(db, 'room', 'reader', anchor, original)
    assert.equal((await db.conversationParticipant.findUnique({ where: { id: 'p' } })).manualUnread, true)
  }
  const fresh = (await captureReadIntent(db, 'room', 'reader')).readIntent
  assert.notEqual(fresh, original)
  await advanceReadReceipt(db, 'room', 'reader', 'not-displayed', fresh)
  assert.equal((await db.conversationParticipant.findUnique({ where: { id: 'p' } })).manualUnread, false)
}))
test('stale explicit all/off/on cannot overwrite newer manual intent, including ABA', () => fixture(async db => {
  await seedRead(db)
  await db.$executeRaw`INSERT INTO Conversation(id,updatedAt) VALUES ('room',${t(0)})`
  const original = (await captureReadIntent(db, 'room', 'reader')).readIntent
  await setManualUnread(db, 'room', 'reader', false, original)
  const afterOff = (await captureReadIntent(db, 'room', 'reader')).readIntent
  await setManualUnread(db, 'room', 'reader', true, afterOff)
  for (const token of [original, afterOff]) {
    await assert.rejects(advanceReadReceipt(db, 'room', 'reader', undefined, token), {status:409})
    for (const on of [true,false]) await assert.rejects(setManualUnread(db, 'room', 'reader', on, token), {status:409})
  }
  const row = await db.conversationParticipant.findUnique({ where: { id:'p' } })
  assert.equal(row.manualUnread,true); assert.equal(row.lastReadAt.toISOString(),t(0).toISOString())
  await advanceReadReceipt(db, 'room', 'reader', undefined, (await captureReadIntent(db, 'room', 'reader')).readIntent)
  assert.equal((await db.conversationParticipant.findUnique({where:{id:'p'}})).manualUnread,false)
}))
test('legacy unfenced reads cannot erase manual unread; removed membership cannot capture intent', () => fixture(async db => {
  await seedRead(db)
  for (const anchor of ['displayed', 'not-displayed', undefined]) {
    await advanceReadReceipt(db, 'room', 'reader', anchor)
    assert.equal((await db.conversationParticipant.findUnique({where:{id:'p'}})).manualUnread,true)
  }
  await assert.rejects(captureReadIntent(db,'room','outsider'),{status:403})
  const old = (await captureReadIntent(db,'room','reader')).readIntent
  await db.conversationParticipant.delete({where:{id:'p'}})
  await db.conversationParticipant.create({data:{id:'replacement',userId:'reader',conversationId:'room',manualUnread:true,lastReadAt:t(0)}})
  const fresh = (await captureReadIntent(db,'room','reader')).readIntent
  assert.notEqual(old,fresh)
  await assert.rejects(advanceReadReceipt(db,'room','reader','not-displayed',old), {status:410})
  assert.equal((await db.conversationParticipant.findUnique({where:{id:'replacement'}})).manualUnread,true)
}))


test('read-intent migration is additive and rerunnable; membership removal retires its fence', () => fixture(async db => {
  await seedRead(db)
  const first = await captureReadIntent(db,'room','reader')
  await db.$executeRawUnsafe(await readFile(new URL('../../prisma/patches/20261026_read_intent_state.sql', import.meta.url),'utf8'))
  assert.equal((await captureReadIntent(db,'room','reader')).readIntent,first.readIntent)
  assert.equal((await db.conversationParticipant.findUnique({where:{id:'p'}})).manualUnread,true)
  await db.conversationParticipant.delete({where:{id:'p'}})
  assert.equal(await db.readIntentState.count(),0)
  await db.conversationParticipant.create({data:{id:'p',userId:'reader',conversationId:'room',manualUnread:true,lastReadAt:t(0)}})
  assert.notEqual((await captureReadIntent(db,'room','reader')).readIntent,first.readIntent)
}))


test('equal timestamps and unseen older gaps require independent observations', () => fixture(async db => {
  await seedUnreadMember(db)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES
    ('gap','room','peer',${t(5)}),('shown','room','peer',${t(10)}),('tie','room','peer',${t(10)})`
  const intent = (await captureReadIntent(db,'room','reader')).readIntent
  await advanceReadReceipt(db,'room','reader','shown',intent)
  assert.equal((await unreadSnapshot(db,'room','reader',t(59),t(20))).unreadCount,2)
  assert.deepEqual((await db.messageReadObservation.findMany()).map(r => r.messageId),['shown'])
  await advanceReadReceipt(db,'room','reader','gap',intent)
  assert.equal((await unreadSnapshot(db,'room','reader',t(59),t(20))).unreadCount,1)
  await advanceReadReceipt(db,'room','reader','tie',intent)
  assert.equal((await unreadSnapshot(db,'room','reader',t(0),t(20))).unreadCount,0)
}))

test('mark-all snapshots identities; a subsequent equal-time arrival remains unread', () => fixture(async db => {
  await seedUnreadMember(db)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES ('first','room','peer',${t(10)})`
  const intent = (await captureReadIntent(db,'room','reader')).readIntent
  await advanceReadReceipt(db,'room','reader',undefined,intent)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES ('late','room','peer',${t(10)})`
  assert.equal((await unreadSnapshot(db,'room','reader',t(10),t(20))).unreadCount,1)
  assert.deepEqual((await db.messageReadObservation.findMany()).map(r=>r.messageId),['first'])
}))

test('thread and topic observations do not consume sibling scopes', () => fixture(async db => {
  await seedUnreadMember(db)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt,parentId,topicId) VALUES
    ('root-a','room','peer',${t(5)},NULL,'topic-a'),('root-b','room','peer',${t(5)},NULL,'topic-b'),
    ('a','room','peer',${t(10)},'root-a',NULL),('b','room','peer',${t(10)},'root-b',NULL)`
  await advanceReadReceipt(db,'room','reader','a', (await captureReadIntent(db,'room','reader')).readIntent)
  const count = async scope => (await unreadSnapshot(db,'room','reader',t(0),t(20),scope)).unreadCount
  assert.equal(await count({threadId:'root-a'}),0)
  assert.equal(await count({threadId:'root-b'}),1)
  assert.equal(await count({topicId:'topic-a'}),1)
  assert.equal(await count({topicId:'topic-b'}),2)
  assert.equal(await count(),3)
}))

test('offline membership proof cannot clear a manual marker or survive leave/rejoin', () => fixture(async db => {
  await seedRead(db)
  await advanceReadReceipt(db,'room','reader','displayed','offline.p')
  assert.equal((await db.conversationParticipant.findUnique({where:{id:'p'}})).manualUnread,true)
  assert.equal(await db.messageReadObservation.count(),1)
  await db.conversationParticipant.delete({where:{id:'p'}})
  assert.equal(await db.messageReadObservation.count(),0)
  await db.conversationParticipant.create({data:{id:'rejoin',userId:'reader',conversationId:'room',joinedAt:t(15),lastReadAt:t(15)}})
  await assert.rejects(advanceReadReceipt(db,'room','reader','not-displayed','offline.p'), {status:410})
  await db.$executeRaw`UPDATE Message SET senderId='peer' WHERE conversationId='room'`
  assert.equal((await unreadSnapshot(db,'room','reader',t(0),t(30))).unreadCount,1)
  assert.equal(await db.messageReadObservation.count(),0)
}))

test('new members exclude prejoin history from badges without fabricating Seen; join-time ties remain unread', () => fixture(async db => {
  await seedUnreadMember(db)
  await db.conversationParticipant.update({where:{id:'unread-member'},data:{joinedAt:t(10)}})
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES
    ('history','room','peer',${t(5)}),('at-join','room','peer',${t(10)}),('new','room','peer',${t(15)})`
  assert.equal((await unreadSnapshot(db,'room','reader',t(59),t(20))).unreadCount,2)
  assert.equal(await db.messageReadObservation.count(),0)
}))

test('migration preserves old badge exclusions once, never converts later timestamp gaps to Seen', () => fixture(async db => {
  await db.readLedgerMigration.deleteMany()
  await seedUnreadMember(db)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES
    ('old','room','peer',${t(5)}),('gap','room','peer',${t(10)})`
  await db.conversationParticipant.update({where:{id:'unread-member'},data:{lastReadAt:t(5)}})
  await exactMigration(db)
  const before = await db.messageReadObservation.findMany()
  assert.equal(before.length,1); assert.equal(before[0].messageId,'old'); assert.equal(before[0].source,'legacy')
  await db.conversationParticipant.update({where:{id:'unread-member'},data:{lastReadAt:t(20)}})
  await exactMigration(db)
  assert.deepEqual(await db.messageReadObservation.findMany(),before)
  assert.equal((await unreadSnapshot(db,'room','reader',t(20),t(30))).unreadCount,1)
}))

test('unread anchor is an exact identity, including after reading later equal-time rows', () => fixture(async db => {
  await seedUnreadMember(db)
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES
    ('a','room','peer',${t(10)}),('b','room','peer',${t(10)})`
  await advanceReadReceipt(db,'room','reader','b',(await captureReadIntent(db,'room','reader')).readIntent)
  assert.equal((await exactUnreadSnapshot(db,'room','reader',t(59),t(20))).firstUnreadMessageId,'a')
}))

test('unfenced legacy entrypoints cannot fabricate exact Seen or consume unseen gaps', () => fixture(async db => {
  await seedRead(db)
  await advanceReadReceipt(db,'room','reader','displayed')
  await advanceReadReceipt(db,'room','reader')
  assert.equal(await db.messageReadObservation.count(),0)
  assert.equal((await db.conversationParticipant.findUnique({where:{id:'p'}})).manualUnread,true)
}))

test('fresh authorized reading of an older row clears a known manual reminder without inferring its gaps', () => fixture(async db => {
  await seedRead(db)
  await db.$executeRaw`INSERT INTO Conversation(id,updatedAt) VALUES ('room',${t(0)})`
  const old = (await captureReadIntent(db,'room','reader')).readIntent
  await advanceReadReceipt(db,'room','reader','not-displayed',old)
  await setManualUnread(db,'room','reader',true,old)
  await advanceReadReceipt(db,'room','reader','displayed',old)
  assert.equal((await db.conversationParticipant.findUnique({where:{id:'p'}})).manualUnread,true)
  const fresh = (await captureReadIntent(db,'room','reader')).readIntent
  await advanceReadReceipt(db,'room','reader','displayed',fresh)
  assert.equal((await db.conversationParticipant.findUnique({where:{id:'p'}})).manualUnread,false)
}))
