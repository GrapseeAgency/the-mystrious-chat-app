import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { playGame, joinGame, finishTournament, joinTournament } from '../../src/lib/game-contract.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'game-contract-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'fixture.sqlite')}` })
  try {
    for (const sql of [
      'CREATE TABLE User(id TEXT PRIMARY KEY, xp INTEGER DEFAULT 0)',
      'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, role TEXT DEFAULT "member", UNIQUE(userId, conversationId))',
      'CREATE TABLE GameMatch(id TEXT PRIMARY KEY, conversationId TEXT, game TEXT DEFAULT "tictactoe", playerXId TEXT, playerOId TEXT, board TEXT DEFAULT "         ", turn TEXT DEFAULT "X", status TEXT DEFAULT "active", winnerId TEXT, winLine TEXT, moveCount INTEGER DEFAULT 0, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
      'CREATE TABLE Tournament(id TEXT PRIMARY KEY, conversationId TEXT, name TEXT, game TEXT DEFAULT "tictactoe", status TEXT DEFAULT "running", createdById TEXT, endsAt DATETIME, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
      'CREATE TABLE TournamentPlayer(id TEXT PRIMARY KEY, tournamentId TEXT, userId TEXT, points INTEGER DEFAULT 0, wins INTEGER DEFAULT 0, losses INTEGER DEFAULT 0, draws INTEGER DEFAULT 0, joinedAt DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE(tournamentId, userId))',
      'CREATE TABLE LogEvent(id TEXT PRIMARY KEY, userId TEXT, kind TEXT, message TEXT, meta TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
      `INSERT INTO User VALUES ('x',0),('o',0),('new',0)`,
      `INSERT INTO ConversationParticipant(id,userId,conversationId) VALUES ('px','x','room'),('po','o','room'),('pn','new','room')`,
    ]) await db.$executeRawUnsafe(sql)
    await db.gameMatch.create({ data: { id: 'match', conversationId: 'room', playerXId: 'x', playerOId: 'o', board: 'XX OO    ', moveCount: 4 } })
    await db.tournament.create({ data: { id: 'season', conversationId: 'room', name: 'Cup', createdById: 'x' } })
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
const board = db => db.gameMatch.findUnique({ where: { id: 'match' } })
const xp = async (db, id) => (await db.user.findUnique({ where: { id }, select: { xp: true } })).xp
const entry = (db, userId) => db.tournamentPlayer.findUnique({ where: { tournamentId_userId: { tournamentId: 'season', userId } } })

test('winning verdict updates board, XP, both standings and log exactly once across a lost ACK', () => fixture(async db => {
  assert.equal((await playGame(db, 'match', 'x', 2)).status, 'x_won')
  assert.equal((await playGame(db, 'match', 'x', 2)).status, 'x_won')
  assert.equal(await xp(db, 'x'), 25); assert.equal(await xp(db, 'o'), 0)
  assert.equal((await entry(db, 'x')).wins, 1); assert.equal((await entry(db, 'x')).points, 1)
  assert.equal((await entry(db, 'o')).losses, 1); assert.equal(await db.logEvent.count(), 1)
  await assert.rejects(playGame(db, 'match', 'x', 8), { status: 409 })
}))
test('failure halfway through standings rolls back board, XP and both inserted players; retry remains usable', () => fixture(async db => {
  await db.$executeRawUnsafe(`CREATE TRIGGER reject_score BEFORE UPDATE OF losses ON TournamentPlayer BEGIN SELECT RAISE(ABORT, 'score unavailable'); END`)
  await assert.rejects(playGame(db, 'match', 'x', 2))
  assert.equal((await board(db)).board, 'XX OO    '); assert.equal((await board(db)).status, 'active')
  assert.equal(await xp(db, 'x'), 0); assert.equal(await db.tournamentPlayer.count(), 0); assert.equal(await db.logEvent.count(), 0)
  await db.$executeRawUnsafe('DROP TRIGGER reject_score')
  assert.equal((await playGame(db, 'match', 'x', 2)).status, 'x_won')
  assert.equal(await xp(db, 'x'), 25)
}))
test('draw awards both players once and rollback protects first player when second credit overflows', () => fixture(async db => {
  await db.gameMatch.update({ where: { id: 'match' }, data: { board: 'XOXXOOOX ', moveCount: 8 } })
  await db.$executeRawUnsafe(`UPDATE User SET xp=2147483647 WHERE id='o'`)
  await assert.rejects(playGame(db, 'match', 'x', 8), { status: 409 })
  assert.equal(await xp(db, 'x'), 0); assert.equal((await board(db)).moveCount, 8)
  await db.$executeRawUnsafe(`UPDATE User SET xp=0 WHERE id='o'`)
  await playGame(db, 'match', 'x', 8); await playGame(db, 'match', 'x', 8)
  assert.equal(await xp(db, 'x'), 10); assert.equal(await xp(db, 'o'), 10)
  assert.equal((await entry(db, 'x')).draws, 1); assert.equal((await entry(db, 'o')).draws, 1)
  assert.equal(await db.logEvent.count(), 2)
}))
test('counter overflow cannot leave a finished board or an awarded winner', () => fixture(async db => {
  await db.tournamentPlayer.create({ data: { tournamentId: 'season', userId: 'o', losses: 2147483647 } })
  await assert.rejects(playGame(db, 'match', 'x', 2), { status: 409 })
  assert.equal((await board(db)).status, 'active'); assert.equal(await xp(db, 'x'), 0)
  assert.equal(await entry(db, 'x'), null); assert.equal((await entry(db, 'o')).losses, 2147483647)
}))
test('open challenge cannot be played before an opponent joins and join can be replayed', () => fixture(async db => {
  await db.gameMatch.update({ where: { id: 'match' }, data: { board: '         ', moveCount: 0, playerOId: null } })
  await assert.rejects(playGame(db, 'match', 'x', 0), { status: 409 })
  assert.equal((await joinGame(db, 'match', 'o')).playerOId, 'o')
  assert.equal((await joinGame(db, 'match', 'o')).playerOId, 'o')
  await assert.rejects(joinGame(db, 'match', 'new'), { status: 409 })
  assert.equal((await playGame(db, 'match', 'x', 0)).moveCount, 1)
  await playGame(db, 'match', 'o', 1)
  assert.equal((await playGame(db, 'match', 'x', 0)).moveCount, 2) // earlier square remains the receipt
  await db.gameMatch.update({ where: { id: 'match' }, data: { status: 'abandoned' } })
  assert.equal((await joinGame(db, 'match', 'o')).playerOId, 'o')
}))
test('bad cells and corrupt boards cannot be coerced, padded or scored', () => fixture(async db => {
  for (const cell of [null, false, '', '0', -1, 9, 0.5, NaN]) await assert.rejects(playGame(db, 'match', 'x', cell), { status: 400 })
  for (const data of [{ board: 'XX OO' }, { board: 'Z  OO    ' }, { moveCount: 3 }, { turn: 'O' }, { board: 'XXXOO    ', moveCount: 5 }]) {
    await db.gameMatch.update({ where: { id: 'match' }, data: { board: 'XX OO    ', moveCount: 4, turn: 'X', ...data } })
    await assert.rejects(playGame(db, 'match', 'x', 8), { status: 409 })
  }
  assert.equal(await db.logEvent.count(), 0); assert.equal(await xp(db, 'x'), 0)
}))
test('racing finish moves award one result, never two', () => fixture(async db => {
  const results = await Promise.allSettled([playGame(db, 'match', 'x', 2), playGame(db, 'match', 'x', 2)])
  assert.ok(results.some(r => r.status === 'fulfilled'))
  for (const result of results) if (result.status === 'rejected') assert.equal(result.reason.status, 409)
  await playGame(db, 'match', 'x', 2)
  assert.equal(await xp(db, 'x'), 25); assert.equal((await entry(db, 'x')).wins, 1)
  assert.equal(await db.logEvent.count(), 1)
}))
test('season finish is atomic, replayable and does not rewrite its end time or duplicate logs', () => fixture(async db => {
  await db.$executeRawUnsafe(`CREATE TRIGGER reject_log BEFORE INSERT ON LogEvent BEGIN SELECT RAISE(ABORT, 'log unavailable'); END`)
  await assert.rejects(finishTournament(db, 'season', 'x'))
  assert.equal((await db.tournament.findUnique({ where: { id: 'season' } })).status, 'running')
  await db.$executeRawUnsafe('DROP TRIGGER reject_log')
  await finishTournament(db, 'season', 'x')
  const first = await db.tournament.findUnique({ where: { id: 'season' } })
  await finishTournament(db, 'season', 'x')
  assert.equal((await db.tournament.findUnique({ where: { id: 'season' } })).endsAt.getTime(), first.endsAt.getTime())
  assert.equal(await db.logEvent.count(), 1)
  await playGame(db, 'match', 'x', 2)
  assert.equal(await xp(db, 'x'), 25); assert.equal(await db.tournamentPlayer.count(), 0)
}))
test('season join receipt survives closure; new and unauthorized entrants cannot change standings', () => fixture(async db => {
  const first = await joinTournament(db, 'season', 'o')
  await finishTournament(db, 'season', 'x')
  assert.equal((await joinTournament(db, 'season', 'o')).id, first.id)
  await assert.rejects(joinTournament(db, 'season', 'new'), { status: 400 })
  await assert.rejects(joinTournament(db, 'season', 'outsider'), { status: 403 })
  await assert.rejects(finishTournament(db, 'season', 'o'), { status: 403 })
  await db.conversationParticipant.deleteMany({ where: { id: 'px' } })
  await assert.rejects(finishTournament(db, 'season', 'x'), { status: 403 })
  await assert.rejects(playGame(db, 'match', 'x', 2), { status: 403 })
  assert.equal(await db.tournamentPlayer.count(), 1)
}))
