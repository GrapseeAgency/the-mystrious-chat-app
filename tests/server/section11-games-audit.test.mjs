// Section 11 - game lifetime, arbitration, and honest reconciliation
// (items 4/10/12/13): deleting or expiring a match carrier abandons the
// board, simultaneous moves converge to exactly one verdict, the history
// audit reports without repairing, and seller deletion settles the economy
// with an explicit receipt instead of trusting cascades.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import * as game from '../../src/lib/game-contract.ts'
import * as audit from '../../src/lib/game-state-audit.ts'
import * as lifecycle from '../../src/lib/economy-lifecycle.ts'
import * as carrier from '../../src/lib/game-carrier-lifecycle.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

async function routes(path, deps) {
  const source = stripTypeScriptTypes(await readFile(new URL('../../' + path, import.meta.url), 'utf8'))
    .replace(/^import [\s\S]*?from '.*'$/gm, '').replace(/^import .*$/gm, '').replace(/export /g, '')
  return new Function(...Object.keys(deps), source + '; return {GET:typeof GET==="function"?GET:null,POST:typeof POST==="function"?POST:null}')(...Object.values(deps))
}

async function fixture(tables, seed, run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-s11-games-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of tables) await db.$executeRawUnsafe(sql)
    if (seed) await seed(db)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

async function loadMutation() {
  const src = await readFile(new URL('../../src/lib/message-mutation.ts', import.meta.url), 'utf8')
  const { stripTypeScriptTypes: strip } = await import('node:module')
  return strip(src, { mode: 'transform' }).replace(/^import [\s\S]*?from '.*'$/gm, '').replace(/^import .*$/gm, '').replace(/export /g, '')
}

const BASE = [
  'CREATE TABLE User (id TEXT PRIMARY KEY, name TEXT, username TEXT, color TEXT, about TEXT, avatar TEXT, coverImage TEXT, statusEmoji TEXT, statusText TEXT, preferences TEXT, storyViewReceipts BOOLEAN, profileRev INTEGER DEFAULT 1, sessionTokenHash TEXT, legacyTokenHashes TEXT, serviceKey TEXT, createdAt DATETIME, lastSeenAt DATETIME, xp INTEGER DEFAULT 0, xpToday INTEGER DEFAULT 0, xpDay TEXT)',
  "INSERT INTO User VALUES ('x','X','x','emerald','','','','','',null,true,1,null,null,null,'2026-11-01T00:00:00.000Z','2026-11-01T00:00:00.000Z',0,0,'')",
  "INSERT INTO User VALUES ('o','O','o','amber','','','','','',null,true,1,null,null,null,'2026-11-01T00:00:00.000Z','2026-11-01T00:00:00.000Z',0,0,'')",
  'CREATE TABLE Conversation (id TEXT PRIMARY KEY, isGroup BOOLEAN)',
  "INSERT INTO Conversation VALUES ('room', true)",
  'CREATE TABLE ConversationParticipant (id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, role TEXT)',
  'CREATE UNIQUE INDEX participant_uq ON ConversationParticipant (userId, conversationId)',
  "INSERT INTO ConversationParticipant VALUES ('p1','x','room','member')",
  "INSERT INTO ConversationParticipant VALUES ('p2','o','room','member')",
  'CREATE TABLE GameMatch (id TEXT PRIMARY KEY, conversationId TEXT, game TEXT, playerXId TEXT, playerOId TEXT, board TEXT, turn TEXT, status TEXT, winnerId TEXT, winLine TEXT, moveCount INTEGER, createdAt DATETIME, updatedAt DATETIME)',
  'CREATE TABLE Message (id TEXT PRIMARY KEY, conversationId TEXT, senderId TEXT, content TEXT, deletedAt DATETIME, replyToId TEXT, imagePath TEXT, audioPath TEXT, durationMs INTEGER, filePath TEXT, fileName TEXT, fileSize INTEGER, editedAt DATETIME, pinnedAt DATETIME, pinnedBy TEXT, parentId TEXT, topicId TEXT, anon BOOLEAN, viaAutomation BOOLEAN, transcript TEXT, transcribedAt DATETIME, transcribedById TEXT, anonAlias TEXT, viewOnce BOOLEAN, viewedAt DATETIME, viewedBy TEXT, expiresAt DATETIME, linkUrl TEXT, kind TEXT, payload TEXT, createdAt DATETIME)',
  'CREATE TABLE Tournament (id TEXT PRIMARY KEY, conversationId TEXT, name TEXT, game TEXT, status TEXT, createdById TEXT, endsAt DATETIME, createdAt DATETIME)',
  'CREATE TABLE TournamentPlayer (id TEXT PRIMARY KEY, tournamentId TEXT, userId TEXT, points INTEGER DEFAULT 0, wins INTEGER DEFAULT 0, losses INTEGER DEFAULT 0, draws INTEGER DEFAULT 0, joinedAt DATETIME)',
  'CREATE TABLE LogEvent (id TEXT PRIMARY KEY, userId TEXT, kind TEXT, message TEXT, meta TEXT, createdAt DATETIME)',
]

test('item 10: deleting a match carrier abandons the still-active board', () => fixture([
  ...BASE,
  'CREATE TABLE KanbanCard (id TEXT PRIMARY KEY, conversationId TEXT, title TEXT, column TEXT, position INTEGER, assigneeId TEXT, createdById TEXT, sourceMessageId TEXT, sourceRevokedAt DATETIME, createdAt DATETIME, updatedAt DATETIME)',
  'CREATE TABLE RoomListRevision (conversationId TEXT, kind TEXT, rev INTEGER DEFAULT 0, PRIMARY KEY (conversationId, kind))',
], async db => {
  await db.gameMatch.create({ data: { id: 'm1', conversationId: 'room', game: 'tictactoe', playerXId: 'x', playerOId: 'o',
    board: '         ', turn: 'X', status: 'active', moveCount: 0, createdAt: new Date() } })
  await db.message.create({ data: { id: 'msg1', conversationId: 'room', senderId: 'x', kind: 'game', content: '',
    payload: JSON.stringify({ matchId: 'm1', game: 'tictactoe' }) } })
}, async db => {
  const source = await loadMutation()
  const mutateMessage = new Function('db', 'reactionId', 'matchIdsFromPayloads', 'abandonMatchesForCarriers',
    source + '; return mutateMessage')(db, emoji => emoji, carrier.matchIdsFromPayloads, carrier.abandonMatchesForCarriers)

  const result = await mutateMessage(db, 'msg1', 'x', { kind: 'delete' }, async () => ({ id: 'msg1' }))
  assert.equal(result.changed, true)
  const match = await db.gameMatch.findUnique({ where: { id: 'm1' } })
  assert.equal(match.status, 'abandoned', 'the board is abandoned with its bubble')

  // A move against the abandoned board is refused, and the record stays readable.
  try { await game.playGame(db, 'm1', 'x', 0); assert.fail('should refuse') }
  catch (error) { assert.equal(error.status, 409) }
  const readable = await db.gameMatch.findUnique({ where: { id: 'm1' } })
  assert.equal(readable.status, 'abandoned')

  // A finished match is not touched by its carrier's deletion.
  await db.gameMatch.update({ where: { id: 'm1' }, data: { status: 'x_won' } })
  await mutateMessage(db, 'msg1', 'x', { kind: 'delete' }, async () => ({ id: 'msg1' }))
  const still = await db.gameMatch.findUnique({ where: { id: 'm1' } })
  assert.equal(still.status, 'x_won', 'completed history is immutable')

  // Corrupt payloads never break the delete path.
  await db.message.create({ data: { id: 'msg2', conversationId: 'room', senderId: 'x', kind: 'game', content: '', payload: '{oops' } })
  await mutateMessage(db, 'msg2', 'x', { kind: 'delete' }, async () => ({ id: 'msg2' }))
  assert.equal(await db.message.count({ where: { deletedAt: { not: null } } }), 2)
}))

test('item 10: expiring carriers are abandoned before the disappearing purge', () => fixture(BASE, async db => {
  const expired = new Date('2026-11-04T12:00:00Z')
  await db.gameMatch.create({ data: { id: 'm1', conversationId: 'room', game: 'tictactoe', playerXId: 'x', playerOId: 'o',
    board: '         ', turn: 'X', status: 'active', moveCount: 0, createdAt: new Date() } })
  await db.message.create({ data: { id: 'msg1', conversationId: 'room', senderId: 'x', kind: 'game', content: '',
    payload: JSON.stringify({ matchId: 'm1' }), expiresAt: expired } })
  await db.message.create({ data: { id: 'msg2', conversationId: 'room', senderId: 'x', kind: 'text', content: 'keep', expiresAt: expired } })
}, async db => {
  const { abandonMatchesForExpiredCarriers } = await import('../../src/lib/game-carrier-lifecycle.ts')
  const before = new Date('2026-11-04T13:00:00Z')
  const abandoned = await abandonMatchesForExpiredCarriers(db, 'room', before)
  assert.equal(abandoned, 1)
  const match = await db.gameMatch.findUnique({ where: { id: 'm1' } })
  assert.equal(match.status, 'abandoned', 'no playable board survives its own purge')
}))

test('item 12: simultaneous moves converge to exactly one verdict and one reward', () => fixture(BASE, async db => {
  await db.tournament.create({ data: { id: 't1', conversationId: 'room', name: 'S', game: 'tictactoe', status: 'running', createdById: 'x', createdAt: new Date() } })
  await db.gameMatch.create({ data: { id: 'm1', conversationId: 'room', game: 'tictactoe', playerXId: 'x', playerOId: 'o',
    board: 'XO X     ', turn: 'O', status: 'active', moveCount: 3, createdAt: new Date() } })
}, async db => {
  // Both devices fire at once: the same turn, different squares.
  const [a, b] = await Promise.allSettled([
    game.playGame(db, 'm1', 'o', 8),
    game.playGame(db, 'm1', 'o', 7),
  ])
  const verdicts = [a, b].map(r => r.status)
  assert.deepEqual(verdicts.sort(), ['fulfilled', 'rejected'], 'exactly one move wins')
  const loser = (a.status === 'rejected' ? a : b).reason
  assert.equal(loser.status, 409, 'the loser is told to refresh, not to retry blind')
  const match = await db.gameMatch.findUnique({ where: { id: 'm1' } })
  assert.equal(match.moveCount, 4, 'the board advanced exactly once')

  // Reconnect: a fresh read returns the confirmed board (no phantom state).
  const reread = await db.gameMatch.findUnique({ where: { id: 'm1' } })
  assert.equal(reread.board, match.board)
  assert.equal(reread.status, 'active')

  // Finish + reward: X closes the diagonal [0,3,6]; XP and season score once.
  await game.playGame(db, 'm1', 'x', 6)
  const won = await db.gameMatch.findUnique({ where: { id: 'm1' } })
  assert.equal(won.status, 'x_won')
  assert.equal(await db.user.findUnique({ where: { id: 'x' } }).then(u => u.xp), 25, 'winner XP exactly once')
  assert.equal(await db.user.findUnique({ where: { id: 'o' } }).then(u => u.xp), 0)
  const standings = await db.tournamentPlayer.findMany({ where: { tournamentId: 't1' } })
  const xRow = standings.find(row => row.userId === 'x')
  const oRow = standings.find(row => row.userId === 'o')
  assert.equal(xRow.wins, 1)
  assert.equal(xRow.points, 1)
  assert.equal(oRow.losses, 1)

  // A lost finish ACK: finishing a finished season is a no-op, not a second log.
  await game.finishTournament(db, 't1', 'x')
  await game.finishTournament(db, 't1', 'x')
  assert.equal(await db.logEvent.count({ where: { kind: 'tournament' } }), 1)
}))

test('item 13: the audit REPORTS historic inconsistencies without repairing them', () => fixture(BASE, async db => {
  // A completed match whose board contradicts the verdict (historic record).
  await db.gameMatch.create({ data: { id: 'bad', conversationId: 'room', game: 'tictactoe', playerXId: 'x', playerOId: 'o',
    board: 'XO       ', turn: 'O', status: 'x_won', winnerId: 'x', winLine: null, moveCount: 2, createdAt: new Date() } })
  // An orphaned player id with no surviving user (plain string columns, no FK).
  await db.gameMatch.create({ data: { id: 'orphan', conversationId: 'room', game: 'tictactoe', playerXId: 'ghost',
    board: '         ', turn: 'X', status: 'active', moveCount: 0, createdAt: new Date() } })
  // A clean match sails through.
  await db.gameMatch.create({ data: { id: 'clean', conversationId: 'room', game: 'tictactoe', playerXId: 'x', playerOId: 'o',
    board: 'XO XO X  ', turn: 'X', status: 'x_won', winnerId: 'x', winLine: '[0,3,6]', moveCount: 5, createdAt: new Date() } })
  await db.tournament.create({ data: { id: 't1', conversationId: 'room', name: 'S', game: 'tictactoe', status: 'finished', createdAt: new Date() } })
  await db.tournamentPlayer.create({ data: { id: 'e1', tournamentId: 't1', userId: 'x', points: -1, wins: 0, joinedAt: new Date() } })
}, async db => {
  const report = await audit.auditGameHistory(db)
  assert.equal(report.matches.total, 3)
  const badReasons = report.matches.anomalies.filter(row => row.id === 'bad').map(row => row.reason)
  assert.ok(badReasons.some(reason => reason.includes('without a X win line')), 'contradictory verdict reported')
  assert.ok(badReasons.some(reason => reason.includes('without winLine')), 'missing win line reported')
  const orphanReasons = report.matches.anomalies.filter(row => row.id === 'orphan').map(row => row.reason)
  assert.ok(orphanReasons.some(reason => reason.includes('ghost no longer exists')), 'historic orphan reported')
  assert.equal(report.matches.anomalies.filter(row => row.id === 'clean').length, 0, 'clean rows stay silent')
  assert.ok(report.tournaments.anomalies.some(row => row.reason.includes('negative standings counter')), 'negative season counters reported')

  // The audit never repairs: the inconsistent rows are still there.
  assert.equal(await db.gameMatch.count(), 3)
  assert.equal((await db.tournamentPlayer.findUnique({ where: { id: 'e1' } })).points, -1)
}))

test('item 4: seller deletion settles listings and boards with an explicit receipt', () => fixture([
  ...BASE,
  'CREATE TABLE MarketListing (id TEXT PRIMARY KEY, sellerId TEXT, title TEXT, description TEXT, price INTEGER, asset TEXT, status TEXT, buyerId TEXT, createdAt DATETIME)',
  'CREATE TABLE UserWallet (userId TEXT PRIMARY KEY, coins INTEGER DEFAULT 0, gems INTEGER DEFAULT 0, streak INTEGER DEFAULT 0, lastCheckIn DATETIME)',
  'CREATE TABLE WalletLedger (id TEXT PRIMARY KEY, userId TEXT, kind TEXT, asset TEXT, amount INTEGER, note TEXT, counterpartyId TEXT, createdAt DATETIME)',
], async db => {
  await db.marketListing.create({ data: { id: 'l1', sellerId: 'x', title: 'Lantern', price: 40, status: 'open' } })
  await db.marketListing.create({ data: { id: 'l2', sellerId: 'x', title: 'Kettle', price: 12, status: 'sold', buyerId: 'o' } })
  await db.walletLedger.createMany({ data: [
    { userId: 'x', kind: 'market_sell', asset: 'PC', amount: 12, counterpartyId: 'o' },
    { userId: 'o', kind: 'market_buy', asset: 'PC', amount: -12, counterpartyId: 'x' },
  ] })
  await db.userWallet.create({ data: { userId: 'x', coins: 12 } })
}, async db => {
  const report = await lifecycle.settleDeletedUserEconomy(db, 'x')
  assert.equal(report.cancelledListings, 1, 'only the OPEN listing is cancelled')
  assert.equal(report.ledgerRows, 1)
  assert.equal(report.ledgerNetPc, 12)
  assert.equal(report.walletRows, 1)
  assert.equal((await db.marketListing.findUnique({ where: { id: 'l1' } })).status, 'cancelled')
  assert.equal((await db.marketListing.findUnique({ where: { id: 'l2' } })).status, 'sold', 'sold history is untouched')

  // Active boards the leaver plays in are abandoned (no FK cascade covers them).
  await db.gameMatch.create({ data: { id: 'm1', conversationId: 'room', game: 'tictactoe', playerXId: 'o', playerOId: 'x',
    board: 'X        ', turn: 'O', status: 'active', moveCount: 1, createdAt: new Date() } })
  const second = await lifecycle.settleDeletedUserEconomy(db, 'x')
  assert.equal(second.abandonedMatches, 1)
  assert.equal((await db.gameMatch.findUnique({ where: { id: 'm1' } })).status, 'abandoned')

  // The counterparties' own ledger legs are never rewritten.
  assert.equal((await db.walletLedger.findFirst({ where: { userId: 'o' } })).amount, -12)
}))
