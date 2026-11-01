// Section 10 items 3/7/9/12/13b/c - cross-device revision ordering for events
// and Kanban boards, copied task-title revocation, reminder source-snapshot
// integrity, and scheduled packet settlement (healthy refunds + corrupt
// historical payout quarantine).
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

const now = new Date('2026-10-22T12:00:00Z')
const ms = base => base.getTime()

async function fixture(tables, seed, run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-s10-rev-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of tables) await db.$executeRawUnsafe(sql)
    if (seed) await seed(db)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

const BASE = [
  'CREATE TABLE User(id TEXT PRIMARY KEY, name TEXT, xp INTEGER NOT NULL DEFAULT 0)',
  'CREATE TABLE Conversation(id TEXT PRIMARY KEY, isGroup BOOLEAN, metadataRev INTEGER DEFAULT 0)',
  'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,role TEXT,UNIQUE(userId,conversationId))',
  'CREATE TABLE RoomListRevision(conversationId TEXT,kind TEXT,rev INTEGER DEFAULT 0,PRIMARY KEY(conversationId,kind))',
  'CREATE TABLE LogEvent(id TEXT PRIMARY KEY,userId TEXT,kind TEXT,message TEXT,meta TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
  "INSERT INTO User VALUES ('me','Me',0),('peer','Peer',0)",
  'INSERT INTO Conversation (id,isGroup) VALUES (\'room\',true)',
  "INSERT INTO ConversationParticipant VALUES ('a','me','room','member'),('b','peer','room','member')",
]

test('items 3+7: competing cross-device mutations land on distinct revisions; replays do not spend', async () => {
  await fixture([
    ...BASE,
    'CREATE TABLE GroupEvent(id TEXT PRIMARY KEY,conversationId TEXT,title TEXT,description TEXT NOT NULL DEFAULT "",location TEXT NOT NULL DEFAULT "",startsAt DATETIME,createdById TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
    'CREATE TABLE EventRsvp(id TEXT PRIMARY KEY,eventId TEXT REFERENCES GroupEvent(id) ON DELETE CASCADE,userId TEXT,status TEXT,checkedInAt DATETIME,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(eventId,userId))',
  ], async db => {
    await db.$executeRawUnsafe(`INSERT INTO GroupEvent (id,conversationId,title,createdById,startsAt,createdAt) VALUES ('event','room','Meet','me',${ms(now)},${ms(now)})`)
  }, async db => {
    const { mutateEvent } = await import('../../src/lib/event-operation.ts')
    const act = (action, user = 'me') => mutateEvent(db, 'event', user, action, () => now)

    // Five competing RSVPs from two devices: five strictly increasing revs.
    const revs = []
    for (const [user, status] of [['me', 'going'], ['peer', 'maybe'], ['me', 'no'], ['peer', 'going'], ['me', 'maybe']]) {
      revs.push((await act({ kind: 'rsvp', status }, user)).eventsRev)
    }
    assert.deepEqual(revs, [1, 2, 3, 4, 5])

    // The read side carries the SAME counter, so devices can order applies.
    const current = await db.$queryRaw`SELECT rev FROM RoomListRevision WHERE conversationId='room' AND kind='events'`
    assert.equal(current[0].rev, 5)

    // A repeated check-in replay (already stamped) must NOT spend a revision.
    await act({ kind: 'rsvp', status: 'maybe' }, 'peer') // rev 6
    const before = await db.$queryRaw`SELECT rev FROM RoomListRevision WHERE conversationId='room' AND kind='events'`
    const receipt = await act({ kind: 'checkin' }, 'peer') // first stamp: 7
    assert.equal(receipt.eventsRev, 7)
    assert.equal(receipt.xpAwarded, true)
    const replay = await act({ kind: 'checkin' }, 'peer') // already stamped: read-only
    assert.equal(replay.eventsRev, 7, 'no-op replay does not advance the counter')
    assert.equal(replay.xpAwarded, false)
    const after = await db.$queryRaw`SELECT rev FROM RoomListRevision WHERE conversationId='room' AND kind='events'`
    assert.equal(after[0].rev, 7)

    // Deletion advances the same counter (eventsRev, item 3).
    const deleted = await act({ kind: 'delete' }, 'me') // creator deletes
    assert.equal(deleted.ok, true)
    assert.equal(deleted.eventsRev, 8)
  })
})

test('item 7: board revision moves with every accepted card mutation', () => fixture([
  ...BASE,
  'CREATE TABLE KanbanCard(id TEXT PRIMARY KEY,conversationId TEXT,title TEXT,column TEXT,position INTEGER,assigneeId TEXT,createdById TEXT,sourceMessageId TEXT,sourceRevokedAt DATETIME,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updatedAt DATETIME NOT NULL)',
  'CREATE TABLE KanbanCardOperation(userId TEXT,clientId TEXT,requestHash TEXT,cardId TEXT,PRIMARY KEY(userId,clientId))',
], null, async db => {
  const { kanbanOperation } = await import('../../src/lib/kanban-operation.ts')
  const op = (action, user = 'me') => kanbanOperation(db, user, action, () => now)

  const created = await op({ kind: 'create', conversationId: 'room', input: { title: 'A' } })
  assert.equal(created.boardRev, 1)
  const moved = await op({ kind: 'update', cardId: created.card.id, input: { column: 'doing', position: 0 } })
  assert.equal(moved.boardRev, 2)
  const renamed = await op({ kind: 'update', cardId: created.card.id, input: { title: 'A2' } })
  assert.equal(renamed.boardRev, 3)
  const listed = await op({ kind: 'list', conversationId: 'room' })
  assert.equal(listed.boardRev, 3, 'reads carry the current revision')
  const removed = await op({ kind: 'delete', cardId: created.card.id })
  assert.equal(removed.boardRev, 4)
}))

test('item 9: derived card titles revoke when their source message becomes unavailable', () => fixture([
  ...BASE,
  'CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,senderId TEXT,content TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,deletedAt DATETIME,viewOnce BOOLEAN NOT NULL DEFAULT false,viewedAt DATETIME,viewedBy TEXT,pinnedAt DATETIME,pinnedBy TEXT,expiresAt DATETIME,parentId TEXT)',
  'CREATE TABLE KanbanCard(id TEXT PRIMARY KEY,conversationId TEXT,title TEXT,column TEXT,position INTEGER,assigneeId TEXT,createdById TEXT,sourceMessageId TEXT,sourceRevokedAt DATETIME,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updatedAt DATETIME NOT NULL)',
  'CREATE TABLE KanbanCardOperation(userId TEXT,clientId TEXT,requestHash TEXT,cardId TEXT,PRIMARY KEY(userId,clientId))',
], async db => {
  await db.$executeRawUnsafe(`INSERT INTO Message (id,conversationId,senderId,content,createdAt) VALUES ('src','room','peer','Ship the mast',${ms(now)})`)
  const { kanbanOperation } = await import('../../src/lib/kanban-operation.ts')
  const card = (await kanbanOperation(db, 'me', { kind: 'create', conversationId: 'room', input: { messageId: 'src' } }, () => now)).card
  assert.equal(card.title, 'Ship the mast')
  assert.equal(card.sourceRevoked, false)
}, async db => {
  const { kanbanOperation } = await import('../../src/lib/kanban-operation.ts')
  const { mutateMessage } = await import('../../src/lib/message-mutation.ts')
  const revBefore = (await db.$queryRaw`SELECT rev FROM RoomListRevision WHERE conversationId='room' AND kind='kanban'`)[0]?.rev ?? 0

  // The sender deletes the source message: the derivation is stamped and
  // the board revision advances so every retained viewer re-reads it.
  await mutateMessage(db, 'src', 'peer', { kind: 'delete' }, async () => ({ id: 'src', conversationId: 'room' }))
  const stamped = await db.$queryRaw`SELECT sourceRevokedAt FROM KanbanCard WHERE sourceMessageId='src'`
  assert.ok(stamped[0].sourceRevokedAt, 'revocation stamp persisted')
  const revAfter = (await db.$queryRaw`SELECT rev FROM RoomListRevision WHERE conversationId='room' AND kind='kanban'`)[0].rev
  assert.ok(revAfter > revBefore, 'revocation advances the board revision')

  const listed = await kanbanOperation(db, 'me', { kind: 'list', conversationId: 'room' }, () => now)
  assert.equal(listed.cards[0].sourceRevoked, true, 'live annotation flags the card')
  assert.equal(listed.cards[0].title, 'Ship the mast', 'the title is retained for auditability')

  // A card with no source is never flagged.
  const plain = (await kanbanOperation(db, 'me', { kind: 'create', conversationId: 'room', input: { title: 'Plain' } }, () => now)).card
  assert.equal(plain.sourceRevoked, false)
  assert.equal(plain.sourceAvailable, null)
}))

test('item 12: reminder snapshots expose source drift without hiding the owner note', () => fixture([
  'CREATE TABLE User(id TEXT PRIMARY KEY, name TEXT)',
  'CREATE TABLE Conversation(id TEXT PRIMARY KEY, name TEXT, isGroup BOOLEAN, isSelf BOOLEAN)',
  'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,UNIQUE(userId,conversationId))',
  'CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,senderId TEXT,content TEXT,kind TEXT,payload TEXT,parentId TEXT,replyToId TEXT,topicId TEXT,deletedAt DATETIME,expiresAt DATETIME,imagePath TEXT,audioPath TEXT,durationMs INTEGER,filePath TEXT,fileName TEXT,fileSize INTEGER,editedAt DATETIME,pinnedAt DATETIME,pinnedBy TEXT,anon BOOLEAN DEFAULT false,viaAutomation BOOLEAN DEFAULT false,transcript TEXT,transcribedAt DATETIME,transcribedById TEXT,anonAlias TEXT,viewOnce BOOLEAN DEFAULT false,viewedAt DATETIME,viewedBy TEXT,linkUrl TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
  'CREATE TABLE Reminder(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,messageId TEXT,note TEXT,remindAt DATETIME,firedAt DATETIME,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,sourceHash TEXT)',
  'CREATE TABLE ReminderCreationOperation(userId TEXT,clientId TEXT,requestHash TEXT,reminderId TEXT,PRIMARY KEY(userId,clientId))',
  "INSERT INTO User VALUES ('me','Me')",
  "INSERT INTO Conversation VALUES ('room','Room',true,false)",
  "INSERT INTO ConversationParticipant VALUES ('p','me','room')",
  "INSERT INTO Message (id,conversationId,senderId,content,kind) VALUES ('anchor','room','peer','Original words','text')",
], null, async db => {
  const { createHash } = await import('node:crypto')
  const { readFile } = await import('node:fs/promises')
  const { stripTypeScriptTypes } = await import('node:module')
  const { reminderAnchor, reminderPage, ReminderContractError } = await import('../../src/lib/reminder-contract.ts')
  const section10 = await import('../../src/lib/section10-contracts.ts')
  const stripped = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/reminders/route.ts', import.meta.url), 'utf8'))
    .replace(/^import [\s\S]*?from '.*'$/gm, '').replace(/^import .*$/gm, '').replace(/export /g, '')
  const deps = { db, NextResponse: { json: Response.json }, safeJson: r => r.json(),
    strField: v => typeof v === 'string' ? v.trim() : '', requireActionOwner: async () => null,
    clipUtf16: (v, n) => v.slice(0, n), ...section10, reminderAnchor, reminderPage, ReminderContractError, createHash,

    parseIsoDate: v => typeof v === 'string' && Number.isFinite(Date.parse(v)) ? new Date(v) : null }
  const route = new Function(...Object.keys(deps), stripped + '; return {GET,POST}')(...Object.values(deps))
  const post = body => route.POST(new Request('https://reminders.test', { method: 'POST', body: JSON.stringify(body) }))
  const get = () => route.GET(new Request('https://reminders.test?userId=me'))

  const created = await post({ userId: 'me', conversationId: 'room', messageId: 'anchor', note: 'context', remindAt: new Date(Date.now() + 3600_000).toISOString() })
  assert.equal(created.status, 201)
  const id = (await created.json()).item.id

  // Snapshot matches the live anchor.
  let item = (await (await get()).json()).items[0]
  assert.equal(item.sourceAvailable, true)
  assert.equal(item.sourceChanged, false)
  assert.equal(item.snippet, 'Original words')

  // The source is edited after the read snapshot: available, but changed.
  await db.message.update({ where: { id: 'anchor' }, data: { content: 'Edited words' } })
  item = (await (await get()).json()).items[0]
  assert.equal(item.sourceAvailable, true)
  assert.equal(item.sourceChanged, true)

  // Deleted source: unavailable; the note is retained.
  await db.message.update({ where: { id: 'anchor' }, data: { deletedAt: new Date() } })
  item = (await (await get()).json()).items[0]
  assert.equal(item.sourceAvailable, false)
  assert.equal(item.note, 'context')

  // Legacy rows without a hash never pretend to know drift.
  await db.$executeRawUnsafe(`UPDATE Reminder SET sourceHash = NULL, messageId = NULL WHERE id = '${id}'`)
  item = (await (await get()).json()).items[0]
  assert.equal(item.sourceAvailable, null)
  assert.equal(item.sourceChanged, false)
  assert.equal(createHash('sha256').update('x').digest('hex').length, 64)
}))

test('item 13b/c: scheduled settlement refunds expired escrow and quarantines corrupt payouts exactly once', () => fixture([
  'CREATE TABLE User(id TEXT PRIMARY KEY, name TEXT)',
  'CREATE TABLE UserWallet(userId TEXT PRIMARY KEY,coins INTEGER NOT NULL DEFAULT 0,gems INTEGER NOT NULL DEFAULT 0,streak INTEGER NOT NULL DEFAULT 0,lastCheckIn DATETIME)',
  'CREATE TABLE WalletLedger(id TEXT PRIMARY KEY,userId TEXT,kind TEXT,asset TEXT,amount INTEGER,note TEXT,counterpartyId TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
  'CREATE TABLE Conversation(id TEXT PRIMARY KEY)',
  'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,UNIQUE(userId,conversationId))',
  'CREATE TABLE RedPacket(id TEXT PRIMARY KEY, messageId TEXT UNIQUE, senderId TEXT, total INTEGER, count INTEGER, grabbed INTEGER DEFAULT 0, slices TEXT, note TEXT DEFAULT "", expiresAt DATETIME, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, reconciledAt DATETIME, corrupt BOOLEAN DEFAULT false)',
  'CREATE TABLE RedPacketGrab(id TEXT PRIMARY KEY,packetId TEXT,userId TEXT,amount INTEGER,createdAt DATETIME DEFAULT CURRENT_TIMESTAMP,UNIQUE(packetId,userId))',
  'CREATE TABLE LogEvent(id TEXT PRIMARY KEY,userId TEXT,kind TEXT,message TEXT,meta TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
  'CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT)',
  "INSERT INTO User VALUES ('sender','Sender'),('grabber','Grabber'),('peer','Peer')",
  "INSERT INTO UserWallet (userId,coins) VALUES ('sender',0),('grabber',0)",
  "INSERT INTO Conversation VALUES ('room')",
  "INSERT INTO ConversationParticipant VALUES ('a','sender','room'),('b','grabber','room')",
  "INSERT INTO Message VALUES ('m1','room')",
  "INSERT INTO Message VALUES ('m2','room')",
], async db => {
  const expired = new Date(Date.now() - 1000)
  // Healthy packet: 40 total / 4 grabs, 2 slices paid out (15+15), 10 left.
  await db.redPacket.create({ data: { id: 'healthy', messageId: 'm1', senderId: 'sender', total: 40, count: 4, grabbed: 2, slices: JSON.stringify([15, 15, 5, 5]), expiresAt: expired } })
  await db.redPacketGrab.create({ data: { packetId: 'healthy', userId: 'grabber', amount: 15 } })
  await db.redPacketGrab.create({ data: { packetId: 'healthy', userId: 'peer', amount: 15 } })
  await db.userWallet.update({ where: { userId: 'grabber' }, data: { coins: { increment: 30 } } })
  // Corrupt packet: slices JSON does not sum to total - the historical wound.
  await db.redPacket.create({ data: { id: 'corrupt', messageId: 'm2', senderId: 'sender', total: 50, count: 3, grabbed: 1, slices: JSON.stringify([10, 10, 10]), expiresAt: expired } })
  await db.redPacketGrab.create({ data: { packetId: 'corrupt', userId: 'grabber', amount: 10 } })
  await db.userWallet.update({ where: { userId: 'grabber' }, data: { coins: { increment: 10 } } })
}, async db => {
  const { reconcileExpiredPackets } = await import('../../src/lib/redpacket-contract.ts')

  const settled = await reconcileExpiredPackets(db)
  assert.equal(settled, 2, 'both expired packets settled in one sweep')

  // Healthy: the unclaimed remainder (10) went back to the sender.
  const sender = await db.userWallet.findUnique({ where: { userId: 'sender' } })
  assert.equal(sender.coins, 60, '10 remainder + 50 corrupt escrow')
  const healthy = await db.redPacket.findUnique({ where: { id: 'healthy' } })
  assert.equal(healthy.grabbed, 4, 'cursor closed')
  assert.ok(healthy.reconciledAt)
  assert.equal(healthy.corrupt, false)

  // Corrupt: quarantined and settled at FULL escrow, grab kept.
  const corrupt = await db.redPacket.findUnique({ where: { id: 'corrupt' } })
  assert.equal(corrupt.corrupt, true)
  assert.ok(corrupt.reconciledAt)
  const ledgers = await db.walletLedger.findMany({ where: { kind: 'redpacket_refund' } })
  assert.equal(ledgers.reduce((sum, l) => sum + l.amount, 0), 60)

  // The second sweep is a no-op: never a double refund.
  assert.equal(await reconcileExpiredPackets(db), 0)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'sender' } })).coins, 60)
}))