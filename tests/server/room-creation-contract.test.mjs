import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { createRoomObject } from '../../src/lib/message-operation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'room-creation-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'fixture.sqlite')}` })
  try {
    for (const sql of [
      'CREATE TABLE Conversation(id TEXT PRIMARY KEY, ttlSeconds INTEGER DEFAULT 0, updatedAt DATETIME)',
      'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, lastReadAt DATETIME, archivedAt DATETIME, UNIQUE(userId,conversationId))',
      'CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT, senderId TEXT, content TEXT, kind TEXT, payload TEXT, createdAt DATETIME, expiresAt DATETIME, deletedAt DATETIME, anon BOOLEAN DEFAULT 0, viaAutomation BOOLEAN DEFAULT 0, viewOnce BOOLEAN DEFAULT 0)',
      'CREATE TABLE GameMatch(id TEXT PRIMARY KEY, conversationId TEXT, game TEXT DEFAULT "tictactoe", playerXId TEXT, playerOId TEXT, board TEXT DEFAULT "         ", turn TEXT DEFAULT "X", status TEXT DEFAULT "active", winnerId TEXT, winLine TEXT, moveCount INTEGER DEFAULT 0, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
      'CREATE TABLE Tournament(id TEXT PRIMARY KEY, conversationId TEXT, name TEXT, game TEXT DEFAULT "tictactoe", status TEXT DEFAULT "running", createdById TEXT, endsAt DATETIME, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
      'CREATE TABLE TournamentPlayer(id TEXT PRIMARY KEY, tournamentId TEXT, userId TEXT, points INTEGER DEFAULT 0, wins INTEGER DEFAULT 0, losses INTEGER DEFAULT 0, draws INTEGER DEFAULT 0, joinedAt DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE(tournamentId,userId))',
      'CREATE TABLE LogEvent(id TEXT PRIMARY KEY, userId TEXT, kind TEXT, message TEXT, meta TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
      `INSERT INTO Conversation VALUES ('room',60,'2020-01-01T00:00:00.000Z'),('other',0,'2020-01-01T00:00:00.000Z')`,
      `INSERT INTO ConversationParticipant VALUES ('px','x','room',NULL,NULL),('po','o','room',NULL,'2020-01-01T00:00:00.000Z'),('px2','x','other',NULL,NULL)`,
    ]) await db.$executeRawUnsafe(sql)
    const patch = await readFile(new URL('../../prisma/patches/20261019_message_send_operation.sql', import.meta.url), 'utf8')
    for (const sql of patch.split(';').filter(s => s.trim())) await db.$executeRawUnsafe(sql)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
const game = { kind: 'game', userId: 'x', conversationId: 'room', clientId: 'creation-operation-01' }
const season = { ...game, kind: 'tournament', name: 'Cup' }

test('lost game response recovers the same challenge after O joins and play starts', () => fixture(async db => {
  const first = await createRoomObject(db, game)
  await db.gameMatch.updateMany({ where: { id: first.resourceId }, data: { playerOId: 'o', board: 'X        ', moveCount: 1, turn: 'O' } })
  await db.$executeRawUnsafe(`UPDATE ConversationParticipant SET lastReadAt='2001-01-01T00:00:00.000Z',archivedAt='2001-01-01T00:00:00.000Z'`)
  const replay = await createRoomObject(db, game)
  assert.equal(replay.resourceId, first.resourceId); assert.equal(replay.messageId, first.messageId)
  assert.equal(replay.replayed, true); assert.equal(replay.clientId, game.clientId)
  assert.equal(await db.gameMatch.count(), 1); assert.equal(await db.message.count(), 1)
  const rows = await db.$queryRaw`SELECT lastReadAt, archivedAt FROM ConversationParticipant WHERE id='po'`
  assert.equal(new Date(rows[0].archivedAt).getUTCFullYear(), 2001)
  assert.equal(new Date(rows[0].lastReadAt).getUTCFullYear(), 2001)
}))
test('receipt recovery does not require the invited opponent to still be a member', () => fixture(async db => {
  const request = { ...game, opponentId: 'o' }
  const first = await createRoomObject(db, request)
  await db.conversationParticipant.deleteMany({ where: { id: 'po' } })
  assert.equal((await createRoomObject(db, request)).resourceId, first.resourceId)
  await assert.rejects(createRoomObject(db, { ...request, clientId: 'new-creation-operation' }), { status: 400 })
  await db.conversationParticipant.deleteMany({ where: { id: 'px' } })
  await assert.rejects(createRoomObject(db, request), { status: 403 })
}))
test('season replay after finish does not reset standings, rejoin or log again', () => fixture(async db => {
  const first = await createRoomObject(db, season)
  await db.tournament.updateMany({ where: { id: first.resourceId }, data: { status: 'finished' } })
  await db.tournamentPlayer.updateMany({ data: { points: 7, wins: 7 } })
  const replay = await createRoomObject(db, season)
  assert.equal(replay.resourceId, first.resourceId); assert.equal(replay.replayed, true)
  assert.equal(await db.tournament.count(), 1); assert.equal(await db.tournamentPlayer.count(), 1)
  assert.equal((await db.tournamentPlayer.findFirst()).points, 7); assert.equal(await db.logEvent.count(), 1)
  const message = await db.message.findFirst({ select: { createdAt: true, expiresAt: true } })
  assert.equal(message.expiresAt.getTime() - message.createdAt.getTime(), 60_000)
}))
test('changed opponent, room, kind or season name cannot reuse a creation receipt', () => fixture(async db => {
  await createRoomObject(db, game)
  for (const changed of [{ ...game, opponentId: 'o' }, { ...game, conversationId: 'other' }, season]) {
    await assert.rejects(createRoomObject(db, changed), { status: 409 })
  }
  await createRoomObject(db, { ...season, clientId: 'season-operation-02' })
  await assert.rejects(createRoomObject(db, { ...season, clientId: 'season-operation-02', name: 'Different' }), { status: 409 })
}))
test('separate acknowledged instructions may intentionally create identical new challenges', () => fixture(async db => {
  const first = await createRoomObject(db, game)
  const next = await createRoomObject(db, { ...game, clientId: 'creation-operation-02' })
  assert.notEqual(first.resourceId, next.resourceId)
  assert.equal(await db.gameMatch.count(), 2)
}))
test('racing retries create one match and one carrier', () => fixture(async db => {
  const replies = await Promise.all([createRoomObject(db, game), createRoomObject(db, game)])
  assert.equal(replies[0].resourceId, replies[1].resourceId)
  assert.equal(await db.gameMatch.count(), 1); assert.equal(await db.message.count(), 1)
}))
test('failed receipt insertion rolls back the season, entry, carrier, audit log and read/archive effects', () => fixture(async db => {
  await db.$executeRawUnsafe(`CREATE TRIGGER reject_receipt BEFORE INSERT ON MessageSendOperation BEGIN SELECT RAISE(ABORT, 'receipt unavailable'); END`)
  await assert.rejects(createRoomObject(db, season))
  assert.equal(await db.tournament.count(), 0); assert.equal(await db.tournamentPlayer.count(), 0)
  assert.equal(await db.message.count(), 0); assert.equal(await db.logEvent.count(), 0)
  assert.equal((await db.$queryRaw`SELECT lastReadAt FROM ConversationParticipant WHERE id='px'`)[0].lastReadAt, null)
  assert.notEqual((await db.$queryRaw`SELECT archivedAt FROM ConversationParticipant WHERE id='po'`)[0].archivedAt, null)
  await db.$executeRawUnsafe('DROP TRIGGER reject_receipt')
  await createRoomObject(db, season)
  assert.equal(await db.tournament.count(), 1)
}))
test('expired, soft-deleted and removed carriers never authorize a replacement creation', () => fixture(async db => {
  const first = await createRoomObject(db, game)
  await db.message.updateMany({ where: { id: first.messageId }, data: { expiresAt: new Date('2000-01-01') } })
  await assert.rejects(createRoomObject(db, game), { status: 410, code: 'creation_gone', clientId: game.clientId })
  await db.message.updateMany({ where: { id: first.messageId }, data: { expiresAt: null, deletedAt: new Date() } })
  await assert.rejects(createRoomObject(db, game), { status: 410, code: 'creation_gone', clientId: game.clientId })
  await db.message.deleteMany()
  await assert.rejects(createRoomObject(db, game), { status: 410, code: 'creation_gone', clientId: game.clientId })
  assert.equal(await db.gameMatch.count(), 1)
}))

test('missing receipt storage fails before creating any object', () => fixture(async db => {
  await db.$executeRawUnsafe('DROP TABLE MessageSendOperation')
  await assert.rejects(createRoomObject(db, game), { status: 503 })
  assert.equal(await db.gameMatch.count(), 0); assert.equal(await db.message.count(), 0)
}))

test('a background creation send never pretends the sender read the conversation', () => fixture(async db => {
  await db.$executeRaw`UPDATE ConversationParticipant SET lastReadAt=${new Date('2001-01-01T00:00:00.000Z')} WHERE id='px'`
  await createRoomObject(db, game)
  const row = (await db.$queryRaw`SELECT lastReadAt FROM ConversationParticipant WHERE id='px'`)[0]
  assert.equal(row.lastReadAt.getUTCFullYear(), 2001)
}))
