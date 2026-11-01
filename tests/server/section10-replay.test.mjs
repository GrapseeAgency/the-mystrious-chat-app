// Section 10 - creation replay receipts across all six domains (items 1, 4,
// 6, 11, 13): a lost create ACK replays the SAME clientId into the SAME
// stored row; a reused identity with different instructions is a 409; a
// replay after the row was deleted is an honest 410; a packet replay never
// double-debits the wallet.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import * as section10 from '../../src/lib/section10-contracts.ts'
import { createHash } from 'node:crypto'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

const now = new Date('2026-10-25T12:00:00Z')
const CID = 'replay0000000000000000001'

async function routes(path, deps) {
  const source = stripTypeScriptTypes(await readFile(new URL('../../' + path, import.meta.url), 'utf8'))
    .replace(/^import [\s\S]*?from '.*'$/gm, '').replace(/^import .*$/gm, '').replace(/export /g, '')
  return new Function(...Object.keys(deps), source + '; return {GET:typeof GET==="function"?GET:null,POST:typeof POST==="function"?POST:null}')(...Object.values(deps))
}

async function fixture(tables, seed, run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-s10-replay-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of tables) await db.$executeRawUnsafe(sql)
    if (seed) await seed(db)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

const BASE = [
  'CREATE TABLE User(id TEXT PRIMARY KEY, name TEXT)',
  'CREATE TABLE Conversation(id TEXT PRIMARY KEY, isGroup BOOLEAN, metadataRev INTEGER DEFAULT 0)',
  'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,role TEXT,UNIQUE(userId,conversationId))',
  'CREATE TABLE RoomListRevision(conversationId TEXT,kind TEXT,rev INTEGER DEFAULT 0,PRIMARY KEY(conversationId,kind))',
  "INSERT INTO User VALUES ('me','Me')",
  'INSERT INTO Conversation (id,isGroup) VALUES (\'room\',true)',
  "INSERT INTO ConversationParticipant VALUES ('p','me','room','member')",
]

test('item 1: event creation replays the SAME event and refuses mismatched reuse', () => fixture([
  ...BASE,
  'CREATE TABLE GroupEvent(id TEXT PRIMARY KEY,conversationId TEXT,title TEXT,description TEXT,location TEXT,startsAt DATETIME,createdById TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
  'CREATE TABLE EventRsvp(id TEXT PRIMARY KEY,eventId TEXT REFERENCES GroupEvent(id) ON DELETE CASCADE,userId TEXT,status TEXT,checkedInAt DATETIME,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(eventId,userId))',
  'CREATE TABLE EventCreationOperation(userId TEXT,clientId TEXT,requestHash TEXT,eventId TEXT,PRIMARY KEY(userId,clientId))',
], null, async db => {
  const route = await routes('src/app/api/conversations/[id]/events/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(),
    strField: v => typeof v === 'string' ? v.trim() : '', requireActionOwner: async () => null, ...section10,
  })
  const ctx = { params: Promise.resolve({ id: 'room' }) }
  const post = body => route.POST(new Request('https://events.test', { method: 'POST', body: JSON.stringify(body) }), ctx)
  const first = await post({ userId: 'me', title: 'Standup', startsAt: now.toISOString(), clientId: CID })
  assert.equal(first.status, 201)
  const firstBody = await first.json()
  const created = firstBody.event
  assert.equal(typeof firstBody.eventsRev, 'number')

  // Lost-ACK retry with the SAME identity: same row, 200 replayed.
  const retry = await post({ userId: 'me', title: 'Standup', startsAt: now.toISOString(), clientId: CID })
  assert.equal(retry.status, 200)
  const replay = await retry.json()
  assert.equal(replay.replayed, true)
  assert.equal(replay.event.id, created.id)
  assert.equal(await db.groupEvent.count(), 1, 'no duplicate event')

  // Same identity, different instructions: 409, and nothing is created.
  const clash = await post({ userId: 'me', title: 'Retro', startsAt: now.toISOString(), clientId: CID })
  assert.equal(clash.status, 409)
  assert.equal(await db.groupEvent.count(), 1)

  // A different identity creates a genuinely new event.
  const second = await post({ userId: 'me', title: 'Retro', startsAt: now.toISOString(), clientId: 'replay0000000000000000002' })
  assert.equal(second.status, 201)
  assert.notEqual((await second.json()).event.id, created.id)
}))

test('item 4: personal-task creation replays the SAME task; update/delete receipts are retry-safe', () => fixture([
  'CREATE TABLE User(id TEXT PRIMARY KEY)',
  'CREATE TABLE HubTask(id TEXT PRIMARY KEY,ownerId TEXT,title TEXT,status TEXT NOT NULL DEFAULT "todo",createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updatedAt DATETIME NOT NULL)',
  'CREATE TABLE TaskCreationOperation(userId TEXT,clientId TEXT,requestHash TEXT,taskId TEXT,PRIMARY KEY(userId,clientId))',
  "INSERT INTO User VALUES ('me')",
], null, async db => {
  const load = async (rel, extra = {}) => {
    const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/hub/' + rel + '/route.ts', import.meta.url), 'utf8'))
      .replace(/^import [\s\S]*?from '.*'$/gm, '').replace(/^import .*$/gm, '').replace(/export /g, '')
    const deps = { db, NextResponse: { json: Response.json }, safeJson: r => r.json(),
      strField: v => typeof v === 'string' ? v.trim() : '',
      requireActionOwner: async () => null, ...section10, ...extra }
    return new Function(...Object.keys(deps), source + '; return {GET:typeof GET==="function"?GET:null,POST:typeof POST==="function"?POST:null,PATCH:typeof PATCH==="function"?PATCH:null,DELETE:typeof DELETE==="function"?DELETE:null}')(...Object.values(deps))
  }
  const list = await load('tasks')
  const item = await load('tasks/[id]')
  const ctx = id => ({ params: Promise.resolve({ id }) })

  const first = await list.POST(new Request('https://hub.test', { method: 'POST', body: JSON.stringify({ userId: 'me', title: 'Ship', clientId: CID }) }))
  assert.equal(first.status, 201)
  const firstBody = await first.json()
  const task = firstBody.task
  assert.equal(firstBody.replayed, false)

  const retry = await list.POST(new Request('https://hub.test', { method: 'POST', body: JSON.stringify({ userId: 'me', title: 'Ship', clientId: CID }) }))
  assert.equal(retry.status, 200)
  const again = await retry.json()
  assert.equal(again.replayed, true)
  assert.equal(again.task.id, task.id)
  assert.equal(await db.hubTask.count(), 1, 'no duplicate task')

  const clash = await list.POST(new Request('https://hub.test', { method: 'POST', body: JSON.stringify({ userId: 'me', title: 'Different', clientId: CID }) }))
  assert.equal(clash.status, 409)

  // A lost update ACK replays onto the CURRENT row (a newer edit wins).
  await item.PATCH(new Request('https://hub.test', { method: 'PATCH', body: JSON.stringify({ userId: 'me', title: 'Renamed' }) }), ctx(task.id))
  const updateRetry = await item.PATCH(new Request('https://hub.test', { method: 'PATCH', body: JSON.stringify({ userId: 'me', title: 'Ship', clientId: 'update000000000000000001' }) }), ctx(task.id))
  assert.equal(updateRetry.status, 200)
  assert.equal((await updateRetry.json()).replayed, false)

  // Delete + replay: the first delete confirms, the retry replays ok.
  const del = await item.DELETE(new Request('https://hub.test?userId=me&clientId=delete00000000000000001'), ctx(task.id))
  assert.equal((await del.json()).replayed, false)
  const delRetry = await item.DELETE(new Request('https://hub.test?userId=me&clientId=delete00000000000000001'), ctx(task.id))
  assert.equal((await delRetry.json()).replayed, true)
  assert.equal(await db.hubTask.count(), 0)
}))

test('item 6: kanban card creation replays the SAME card through the board receipt', () => fixture([
  ...BASE,
  'CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,content TEXT,deletedAt DATETIME,viewOnce BOOLEAN NOT NULL DEFAULT false,expiresAt DATETIME,parentId TEXT)',
  'CREATE TABLE KanbanCard(id TEXT PRIMARY KEY,conversationId TEXT,title TEXT,column TEXT,position INTEGER,assigneeId TEXT,createdById TEXT,sourceMessageId TEXT,sourceRevokedAt TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updatedAt DATETIME NOT NULL)',
  'CREATE TABLE LogEvent(id TEXT PRIMARY KEY,userId TEXT,kind TEXT,message TEXT,meta TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
  'CREATE TABLE KanbanCardOperation(userId TEXT,clientId TEXT,requestHash TEXT,cardId TEXT,PRIMARY KEY(userId,clientId))',
], null, async db => {
  const { kanbanOperation, KanbanError } = await import('../../src/lib/kanban-operation.ts')
  const create = input => kanbanOperation(db, 'me', { kind: 'create', conversationId: 'room', input }, () => now)
  const first = await create({ title: 'Write tests', clientId: CID })
  assert.equal(first.replayed, false)
  const retry = await create({ title: 'Write tests', clientId: CID })
  assert.equal(retry.replayed, true)
  assert.equal(retry.card.id, first.card.id)
  assert.equal(await db.kanbanCard.count(), 1, 'no duplicate card')
  await assert.rejects(() => create({ title: 'Other card', clientId: CID }),
    error => error instanceof KanbanError && error.status === 409)
  // The room revision only moved once for the committed creation.
  const revRow = await db.$queryRaw`SELECT rev FROM RoomListRevision WHERE conversationId='room' AND kind='kanban'`
  assert.equal(revRow[0].rev, 1)
}))

test('item 11: reminder creation replays the SAME reminder with its snapshot intact', () => fixture([
  'CREATE TABLE User(id TEXT PRIMARY KEY, name TEXT)',
  'CREATE TABLE Conversation(id TEXT PRIMARY KEY, name TEXT, isGroup BOOLEAN, isSelf BOOLEAN)',
  'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,UNIQUE(userId,conversationId))',
  'CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,senderId TEXT,content TEXT,kind TEXT,parentId TEXT,deletedAt DATETIME,expiresAt DATETIME)',
  'CREATE TABLE Reminder(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,messageId TEXT,note TEXT,remindAt DATETIME,firedAt DATETIME,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,sourceHash TEXT)',
  'CREATE TABLE ReminderCreationOperation(userId TEXT,clientId TEXT,requestHash TEXT,reminderId TEXT,PRIMARY KEY(userId,clientId))',
  "INSERT INTO User VALUES ('me','Me')",
  "INSERT INTO Conversation VALUES ('room','Room',true,false)",
  "INSERT INTO ConversationParticipant VALUES ('p','me','room')",
  "INSERT INTO Message VALUES ('anchor','room','peer','Anchor text','text',null,null,null)",
], null, async db => {
  const { reminderAnchor } = await import('../../src/lib/reminder-contract.ts')
  const { createHash } = await import('node:crypto')
  const route = await routes('src/app/api/reminders/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(),
    strField: v => typeof v === 'string' ? v.trim() : '', requireActionOwner: async () => null,
    clipUtf16: (v, n) => v.slice(0, n), ...section10, reminderAnchor, createHash,
    ReminderContractError: class extends Error {}, reminderPage: async () => ({ rows: [], nextCursor: null }),
    parseIsoDate: v => typeof v === 'string' && Number.isFinite(Date.parse(v)) ? new Date(v) : null,
  })
  const remindAt = new Date(Date.now() + 3600_000).toISOString() // the route validates against the real clock
  const post = body => route.POST(new Request('https://reminders.test', { method: 'POST', body: JSON.stringify(body) }))
  const first = await post({ userId: 'me', conversationId: 'room', messageId: 'anchor', note: 'n', remindAt, clientId: CID })
  assert.equal(first.status, 201)
  const created = (await first.json()).item
  const retry = await post({ userId: 'me', conversationId: 'room', messageId: 'anchor', note: 'n', remindAt, clientId: CID })
  assert.equal(retry.status, 200)
  const replayed = await retry.json()
  assert.equal(replayed.replayed, true)
  assert.equal(replayed.item.id, created.id)
  assert.equal(await db.reminder.count(), 1)
  // The creation snapshotted the anchor text (item 12's snapshot exists).
  const row = await db.reminder.findUnique({ where: { id: created.id } })
  assert.equal(row.sourceHash, createHash('sha256').update('Anchor text').digest('hex'))
  const clash = await post({ userId: 'me', conversationId: 'room', messageId: 'anchor', note: 'different', remindAt, clientId: CID })
  assert.equal(clash.status, 409)
}))

test('item 13a: packet creation replay never double-debits and returns the SAME packet', () => fixture([
  'CREATE TABLE User(id TEXT PRIMARY KEY, name TEXT)',
  'CREATE TABLE UserWallet(userId TEXT PRIMARY KEY,coins INTEGER NOT NULL DEFAULT 0,gems INTEGER NOT NULL DEFAULT 0,streak INTEGER NOT NULL DEFAULT 0,lastCheckIn DATETIME)',
  'CREATE TABLE WalletLedger(id TEXT PRIMARY KEY,userId TEXT,kind TEXT,asset TEXT,amount INTEGER,note TEXT,counterpartyId TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
  'CREATE TABLE Conversation(id TEXT PRIMARY KEY, isGroup BOOLEAN, name TEXT, description TEXT, photo TEXT, inviteCode TEXT, inviteExpiresAt DATETIME, metadataRev INTEGER DEFAULT 0, ttlSeconds INTEGER, broadcastMode BOOLEAN DEFAULT false, screenPrivacy BOOLEAN DEFAULT false, slowModeSeconds INTEGER DEFAULT 0, isSelf BOOLEAN DEFAULT false, appKey TEXT, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME NOT NULL)',
  'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,role TEXT,archivedAt DATETIME,UNIQUE(userId,conversationId))',
  'CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,senderId TEXT,content TEXT,kind TEXT,payload TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,deletedAt DATETIME,expiresAt DATETIME,anon BOOLEAN DEFAULT 0,viaAutomation BOOLEAN DEFAULT false,parentId TEXT,replyToId TEXT,topicId TEXT,durationMs INTEGER,fileName TEXT,fileSize INTEGER,transcript TEXT,transcribedAt DATETIME,transcribedById TEXT,anonAlias TEXT,viewOnce BOOLEAN DEFAULT false,viewedAt DATETIME,viewedBy TEXT,pinnedAt DATETIME,pinnedBy TEXT,editedAt DATETIME,imagePath TEXT,audioPath TEXT,filePath TEXT,linkUrl TEXT,linkTitle TEXT,linkDescription TEXT,linkImage TEXT,pollId TEXT,boardId TEXT,threadReplyCount INTEGER DEFAULT 0,lastReplyAt DATETIME,forwardedFromId TEXT,updatedAt DATETIME)',
  'CREATE TABLE RedPacket(id TEXT PRIMARY KEY, messageId TEXT UNIQUE, senderId TEXT, total INTEGER, count INTEGER, grabbed INTEGER DEFAULT 0, slices TEXT, note TEXT DEFAULT "", expiresAt DATETIME, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, reconciledAt DATETIME, corrupt BOOLEAN DEFAULT false)',
  'CREATE TABLE RedPacketCreationOperation(userId TEXT,clientId TEXT,requestHash TEXT,packetId TEXT,PRIMARY KEY(userId,clientId))',
  'CREATE TABLE LogEvent(id TEXT PRIMARY KEY,userId TEXT,kind TEXT,message TEXT,meta TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
  "INSERT INTO User VALUES ('me','Me')",
  "INSERT INTO UserWallet(userId,coins) VALUES ('me',1000)",
  "INSERT INTO Conversation (id,isGroup,name,description,ttlSeconds,broadcastMode,screenPrivacy,slowModeSeconds,isSelf,updatedAt) VALUES ('room',1,'Room','',0,0,0,0,0,CURRENT_TIMESTAMP)",
  "INSERT INTO ConversationParticipant (id,userId,conversationId) VALUES ('p','me','room')",
  ], null, async db => {
  const route = await routes('src/app/api/redpackets/route.ts', {
    db, NextResponse: class { static json(...args) { return Response.json(...args) } }, safeJson: r => r.json(),
    strField: v => typeof v === 'string' ? v.trim() : '',
    deliverLivePush: async () => {}, reconcileExpiredPackets: async () => 0,
    memberIdsOf: async () => ['me', 'peer'],
    notifySocket: async () => {},
    MESSAGE_FULL_INCLUDE: {},
    mapMessage: row => ({ id: row.id, content: row.content, payload: row.payload }),
    ...section10,
  })
  const post = body => route.POST(new Request('https://packets.test', { method: 'POST', body: JSON.stringify(body) }))
  const body = { userId: 'me', conversationId: 'room', total: 100, count: 5, note: 'go', clientId: CID }
  const first = await post(body)
  assert.equal(first.status, 201)
  const created = await first.json()
  assert.equal(created.replayed, false)
  const walletAfterSend = (await db.userWallet.findUnique({ where: { userId: 'me' } })).coins
  assert.equal(walletAfterSend, 900)

  const retry = await post(body)
  assert.equal(retry.status, 200)
  const replay = await retry.json()
  assert.equal(replay.replayed, true)
  assert.equal(replay.packet.id, created.packet.id)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'me' } })).coins, 900, 'no second debit')
  assert.equal(await db.redPacket.count(), 1)
  assert.equal((await db.walletLedger.findMany({ where: { kind: 'redpacket_send' } })).length, 1, 'one ledger entry')

  const clash = await post({ ...body, total: 500 })
  assert.equal(clash.status, 409)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'me' } })).coins, 900)
}))
