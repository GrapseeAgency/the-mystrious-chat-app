// Section 11 - bounded pages past the legacy boundaries (items 3/6/9/11):
// the marketplace board walks past its 60-row cap, the catalog scan becomes
// one batch request, community rosters paginate with legacy alias rooms
// reconciling into the canonical app room, and game/tournament/standings
// lists grow cursor pages while every legacy no-cursor shape stays byte-
// compatible.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import * as hubApps from '../../src/lib/hub-app-operation.ts'
import * as section10 from '../../src/lib/section10-contracts.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

async function routes(path, deps) {
  const source = stripTypeScriptTypes(await readFile(new URL('../../' + path, import.meta.url), 'utf8'))
    .replace(/^import [\s\S]*?from '.*'$/gm, '').replace(/^import .*$/gm, '').replace(/export /g, '')
  return new Function(...Object.keys(deps), source + '; return {GET:typeof GET==="function"?GET:null,POST:typeof POST==="function"?POST:null}')(...Object.values(deps))
}

async function fixture(tables, seed, run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-s11-pages-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of tables) await db.$executeRawUnsafe(sql)
    if (seed) await seed(db)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

const USER_TABLES = [
  'CREATE TABLE User (id TEXT PRIMARY KEY, name TEXT, username TEXT, color TEXT, about TEXT, avatar TEXT, coverImage TEXT, statusEmoji TEXT, statusText TEXT, preferences TEXT, storyViewReceipts BOOLEAN, sessionTokenHash TEXT, legacyTokenHashes TEXT, serviceKey TEXT, createdAt DATETIME, lastSeenAt DATETIME, xp INTEGER DEFAULT 0, xpToday INTEGER DEFAULT 0, xpDay TEXT)',
  "INSERT INTO User VALUES ('me','Me','me','emerald','','','','','',null,true,null,null,null,'2026-11-01T00:00:00.000Z','2026-11-01T00:00:00.000Z',0,0,'')",
  "INSERT INTO User VALUES ('other','Other','other','amber','','','','','',null,true,null,null,null,'2026-11-01T00:00:00.000Z','2026-11-01T00:00:00.000Z',0,0,'')",
]

test('item 3: the board walks past 60 rows in cursor pages; legacy shape is unchanged', () => fixture([
  ...USER_TABLES,
  'CREATE TABLE MarketListing (id TEXT PRIMARY KEY, sellerId TEXT, title TEXT, description TEXT, price INTEGER, asset TEXT, status TEXT, buyerId TEXT, createdAt DATETIME)',
  'CREATE TABLE LogEvent (id TEXT PRIMARY KEY, userId TEXT, kind TEXT, message TEXT, meta TEXT, createdAt DATETIME)',
  'CREATE TABLE MarketCreationOperation (userId TEXT, clientId TEXT, requestHash TEXT, listingId TEXT, PRIMARY KEY (userId, clientId))',
], async db => {
  const base = new Date('2026-11-01T12:00:00Z').getTime()
  for (let i = 0; i < 75; i++) {
    await db.marketListing.create({ data: { id: `lst${String(i).padStart(3, '0')}`, sellerId: 'other',
      title: `Item ${i}`, price: i + 1, createdAt: new Date(base - i * 1000) } })
  }
}, async db => {
  const route = await routes('src/app/api/hub/market/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
    requireWalletOwner: async () => null,
    createListingReceipted: (await import('../../src/lib/market-contract.ts')).createListingReceipted,
    MarketOperationError: (await import('../../src/lib/market-contract.ts')).MarketOperationError,
    pageLimit: section10.pageLimit,
  })
  const get = query => route.GET(new Request('https://market.test' + query))

  const legacy = await get('/api/hub/market?userId=me')
  const legacyBody = await legacy.json()
  assert.equal(legacyBody.listings.length, 60, 'legacy cap unchanged')
  assert.equal('hasMore' in legacyBody, false, 'legacy shape carries no paging fields')

  // Walk all 75 rows in pages of 30; order is (status, createdAt, id) desc.
  const seen = []
  let cursor = null
  for (let guard = 0; guard < 10; guard++) {
    const page = await get('/api/hub/market?userId=me&limit=30' + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''))
    assert.equal(page.status, 200)
    const body = await page.json()
    seen.push(...body.listings.map(row => row.id))
    if (!body.hasMore) { assert.equal(body.nextCursor, null); break }
    cursor = body.nextCursor
    assert.equal(typeof cursor, 'string')
  }
  assert.equal(seen.length, 75)
  assert.equal(new Set(seen).size, 75, 'no duplicates across pages')
  const ordered = await db.marketListing.findMany({ orderBy: [{ status: 'asc' }, { createdAt: 'desc' }, { id: 'desc' }] })
  assert.deepEqual(seen, ordered.map(row => row.id), 'page walk matches the canonical board order')

  const badCursor = await get('/api/hub/market?userId=me&cursor=garbage')
  assert.equal(badCursor.status, 400)
  const badLimit = await get('/api/hub/market?userId=me&limit=0')
  assert.equal(badLimit.status, 400)
  const bigLimit = await get('/api/hub/market?userId=me&limit=101')
  assert.equal(bigLimit.status, 400)
}))

const APP_TABLES = [
  ...USER_TABLES,
  'CREATE TABLE AppInstall (id TEXT PRIMARY KEY, userId TEXT, appId TEXT, installedAt DATETIME, status TEXT)',
  'CREATE UNIQUE INDEX appinstall_uq ON AppInstall (userId, appId)',
  'CREATE TABLE AppInstallRevision (userId TEXT PRIMARY KEY, rev INTEGER DEFAULT 0)',
  'CREATE TABLE AppConnectionOperation (userId TEXT, clientId TEXT, requestHash TEXT, appId TEXT, installed BOOLEAN, PRIMARY KEY (userId, clientId))',
  'CREATE TABLE Conversation (id TEXT PRIMARY KEY, isGroup BOOLEAN, appKey TEXT, name TEXT, description TEXT, photo TEXT, inviteCode TEXT, inviteExpiresAt TEXT, metadataRev INTEGER, ttlSeconds INTEGER, broadcastMode BOOLEAN, screenPrivacy BOOLEAN, slowModeSeconds INTEGER, isSelf BOOLEAN, createdAt DATETIME, updatedAt DATETIME)',
  'CREATE TABLE ConversationParticipant (id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, role TEXT, joinedAt DATETIME, lastReadAt DATETIME, pinnedAt DATETIME, mutedUntil DATETIME, archivedAt DATETIME, handRaisedAt DATETIME, screenPrivacy BOOLEAN, manualUnread BOOLEAN, draftRevision TEXT, draft TEXT)',
  'CREATE UNIQUE INDEX participant_uq ON ConversationParticipant (userId, conversationId)',
  'CREATE TABLE MessageReadObservation (id TEXT PRIMARY KEY, participantId TEXT, messageId TEXT, observedAt DATETIME, source TEXT)',
  'CREATE TABLE Message (id TEXT PRIMARY KEY, conversationId TEXT, senderId TEXT, content TEXT, deletedAt DATETIME, kind TEXT, payload TEXT, createdAt DATETIME)',
]

async function appRoute(name, path, db, extra) {
  const base = {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(),
    requireActionOwner: async () => null, MATRIX: (await import('../../src/lib/hub-catalog.ts')).MATRIX,
    appInstallSnapshot: hubApps.appInstallSnapshot, setAppInstalled: hubApps.setAppInstalled,
    appCommunitySnapshot: hubApps.appCommunitySnapshot, joinAppCommunity: hubApps.joinAppCommunity,
    appInstallsBatchSnapshot: hubApps.appInstallsBatchSnapshot,
    hubAppErrorResponse: hubApps.hubAppErrorResponse,
    invalidateNativeReadState: () => {},
  }
  return routes(path, { ...base, ...extra })
}

test('item 9: joined-roster pagination walks every member in stable join order', () => fixture(APP_TABLES, async db => {
  await db.conversation.create({ data: { id: 'room4', isGroup: true, appKey: '4', name: 'community' } })
  const base = new Date('2026-11-01T12:00:00Z').getTime()
  for (let i = 0; i < 12; i++) {
    await db.user.create({ data: { id: `u${i}`, name: `Member ${i}` } })
    await db.conversationParticipant.create({ data: { id: `p${i}`, userId: `u${i}`, conversationId: 'room4',
      role: i === 0 ? 'admin' : 'member', joinedAt: new Date(base + i * 1000) } })
  }
}, async db => {
  const community = await appRoute('community', 'src/app/api/hub/apps/[appId]/community/route.ts', db)
  const get = query => community.GET(new Request('https://apps.test' + query), { params: Promise.resolve({ appId: '4' }) })

  const seen = []
  let cursor = null
  for (let guard = 0; guard < 10; guard++) {
    const page = await get(`/api/hub/apps/4/community?userId=u0&limit=5${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
    assert.equal(page.status, 200)
    const body = await page.json()
    assert.equal(body.joined, true)
    seen.push(...body.conversation.members.map(row => row.id))
    if (!body.rosterHasMore) { assert.equal(body.rosterNextCursor, null); break }
    cursor = body.rosterNextCursor
  }
  assert.equal(seen.length, 12)
  assert.deepEqual(seen, Array.from({ length: 12 }, (_, i) => `u${i}`), 'join order preserved across pages')
  assert.equal((await (await get('/api/hub/apps/4/community?userId=other')).json()).conversation.members.length, 0, 'nonmembers still get no roster')

  // Legacy shape (no limit/cursor) still returns the FULL roster.
  const full = await (await get('/api/hub/apps/4/community?userId=u0')).json()
  assert.equal(full.conversation.members.length, 12)
  assert.equal('rosterHasMore' in full, false)
}))

test('item 9: a legacy alias-key room is reconciled into the canonical app room', () => fixture(APP_TABLES, async db => {
  // Historic ghost: the "04" spelling predates the canonical catalog keys.
  await db.conversation.create({ data: { id: 'legacy4', isGroup: true, appKey: '04', name: 'old community' } })
  await db.conversationParticipant.create({ data: { id: 'pl1', userId: 'me', conversationId: 'legacy4', role: 'admin', joinedAt: new Date() } })
}, async db => {
  const community = await appRoute('community', 'src/app/api/hub/apps/[appId]/community/route.ts', db)
  const get = query => community.GET(new Request('https://apps.test' + query), { params: Promise.resolve({ appId: '4' }) })
  const body = await (await get('/api/hub/apps/4/community?userId=me')).json()
  assert.equal(body.conversation.id, 'legacy4', 'the historic room is adopted, not duplicated')
  assert.equal(body.memberCount, 1)
  assert.equal(await db.conversation.count(), 1, 'no second community room exists')
  const rekeyed = await db.conversation.findUnique({ where: { id: 'legacy4' } })
  assert.equal(rekeyed.appKey, '4', 'the alias key is re-keyed to the canonical id')

  // Joining reconciles onto the same adopted room.
  await appRoute('join', 'src/app/api/hub/apps/[appId]/community/route.ts', db)
    .then(route => route.POST(new Request('https://apps.test', { method: 'POST', body: JSON.stringify({ userId: 'other' }) }),
      { params: Promise.resolve({ appId: '4' }) }))
  assert.equal(await db.conversation.count(), 1, 'join does not mint a parallel room')
}))

const GAME_TABLES = [
  ...USER_TABLES,
  'CREATE TABLE Conversation (id TEXT PRIMARY KEY, isGroup BOOLEAN, appKey TEXT, name TEXT, description TEXT, photo TEXT, inviteCode TEXT, inviteExpiresAt TEXT, metadataRev INTEGER, ttlSeconds INTEGER, broadcastMode BOOLEAN, screenPrivacy BOOLEAN, slowModeSeconds INTEGER, isSelf BOOLEAN, createdAt DATETIME, updatedAt DATETIME)',
  'CREATE TABLE ConversationParticipant (id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, role TEXT, joinedAt DATETIME, lastReadAt DATETIME, pinnedAt DATETIME, mutedUntil DATETIME, archivedAt DATETIME, handRaisedAt DATETIME, screenPrivacy BOOLEAN, manualUnread BOOLEAN, draftRevision TEXT, draft TEXT)',
  'CREATE UNIQUE INDEX participant_uq ON ConversationParticipant (userId, conversationId)',
  'CREATE TABLE MessageReadObservation (id TEXT PRIMARY KEY, participantId TEXT, messageId TEXT, observedAt DATETIME, source TEXT)',
  'CREATE TABLE GameMatch (id TEXT PRIMARY KEY, conversationId TEXT, game TEXT, playerXId TEXT, playerOId TEXT, board TEXT, turn TEXT, status TEXT, winnerId TEXT, winLine TEXT, moveCount INTEGER, createdAt DATETIME, updatedAt DATETIME)',
  'CREATE TABLE Tournament (id TEXT PRIMARY KEY, conversationId TEXT, name TEXT, game TEXT, status TEXT, createdById TEXT, endsAt DATETIME, createdAt DATETIME)',
  'CREATE TABLE TournamentPlayer (id TEXT PRIMARY KEY, tournamentId TEXT, userId TEXT, points INTEGER DEFAULT 0, wins INTEGER DEFAULT 0, losses INTEGER DEFAULT 0, draws INTEGER DEFAULT 0, joinedAt DATETIME)',
]

const gameDeps = {
  requireActionOwner: async () => null,
  pageLimit: section10.pageLimit, decodeKeysetCursor: section10.decodeKeysetCursor,
  keysetBefore: section10.keysetBefore, pageVerdict: section10.pageVerdict,
  readRoomGame: (await import('../../src/lib/game-read.ts')).readRoomGame,
  gameReadErrorResponse: (await import('../../src/lib/game-read.ts')).gameReadErrorResponse,
  gameReadHeaders: (await import('../../src/lib/game-read.ts')).gameReadHeaders,
}

test('item 11: match and season lists page past their legacy caps', () => fixture([
  ...GAME_TABLES,
  'CREATE TABLE Message (id TEXT PRIMARY KEY, conversationId TEXT, senderId TEXT, kind TEXT, content TEXT, payload TEXT, deletedAt DATETIME, expiresAt DATETIME, createdAt DATETIME)',
  'CREATE TABLE MessageSendOperation (senderId TEXT, clientId TEXT, requestHash TEXT, messageId TEXT, PRIMARY KEY (senderId, clientId))',
  'CREATE TABLE RoomListRevision (conversationId TEXT, kind TEXT, rev INTEGER DEFAULT 0, PRIMARY KEY (conversationId, kind))',
  'CREATE TABLE LogEvent (id TEXT PRIMARY KEY, userId TEXT, kind TEXT, message TEXT, meta TEXT, createdAt DATETIME)',
  "INSERT INTO Conversation (id, isGroup) VALUES ('room', true)",
  "INSERT INTO ConversationParticipant (id, userId, conversationId, role) VALUES ('p1','me','room','member')",
], async db => {
  const base = new Date('2026-11-02T12:00:00Z').getTime()
  for (let i = 0; i < 30; i++) {
    await db.gameMatch.create({ data: { id: `m${String(i).padStart(3, '0')}`, conversationId: 'room', game: 'tictactoe',
      playerXId: 'me', board: '         ', turn: 'X', status: 'active', moveCount: 0, createdAt: new Date(base - i * 1000) } })
  }
  for (let i = 0; i < 8; i++) {
    await db.tournament.create({ data: { id: `t${i}`, conversationId: 'room', name: `Season ${i}`, game: 'tictactoe',
      status: 'running', createdAt: new Date(base - i * 1000) } })
  }
}, async db => {
  const games = await routes('src/app/api/games/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
    memberIdsOf: async () => ['me'], notifySocket: async () => {}, mapMessage: () => ({}),
    MESSAGE_FULL_INCLUDE: {}, deliverLivePush: async () => {},
    createRoomObject: async () => { throw new Error('not used here') },
    MessageOperationError: class extends Error {}, ...gameDeps,
  })
  const getGames = query => games.GET(new Request('https://games.test' + query))

  const legacy = await (await getGames('/api/games?conversationId=room&userId=me')).json()
  assert.equal(legacy.matches.length, 25, 'legacy match cap unchanged')
  assert.equal('hasMore' in legacy, false)

  const seen = []
  let cursor = null
  for (let guard = 0; guard < 10; guard++) {
    const body = await (await getGames('/api/games?conversationId=room&userId=me&limit=10' + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''))).json()
    seen.push(...body.matches.map(row => row.id))
    if (!body.hasMore) { assert.equal(body.nextCursor, null); break }
    cursor = body.nextCursor
  }
  assert.equal(seen.length, 30)
  assert.equal(new Set(seen).size, 30)
  assert.deepEqual(seen, Array.from({ length: 30 }, (_, i) => `m${String(i).padStart(3, '0')}`), 'newest-first across pages')

  const tournaments = await routes('src/app/api/tournaments/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
    memberIdsOf: async () => ['me'], notifySocket: async () => {}, mapMessage: () => ({}),
    MESSAGE_FULL_INCLUDE: {}, deliverLivePush: async () => {},
    createRoomObject: async () => { throw new Error('not used here') },
    MessageOperationError: class extends Error {}, ...gameDeps,
  })
  const getTours = query => tournaments.GET(new Request('https://tournaments.test' + query))
  const legacyTours = await (await getTours('/api/tournaments?conversationId=room&userId=me')).json()
  assert.equal(legacyTours.tournaments.length, 5, 'legacy season cap unchanged')
  const tourSeen = []
  let tourCursor = null
  for (let guard = 0; guard < 10; guard++) {
    const body = await (await getTours('/api/tournaments?conversationId=room&userId=me&limit=3' + (tourCursor ? `&cursor=${encodeURIComponent(tourCursor)}` : ''))).json()
    tourSeen.push(...body.tournaments.map(row => row.id))
    if (!body.hasMore) { assert.equal(body.nextCursor, null); break }
    tourCursor = body.nextCursor
  }
  assert.equal(tourSeen.length, 8)
  assert.deepEqual(tourSeen, Array.from({ length: 8 }, (_, i) => `t${i}`), 'seasons newest-first across pages')
}))

test('item 11: full standings page by points→wins→joined order with a stable cursor', () => fixture(GAME_TABLES, async db => {
  await db.conversation.create({ data: { id: 'room', isGroup: true } })
  await db.conversationParticipant.create({ data: { id: 'p1', userId: 'me', conversationId: 'room', role: 'member' } })
  await db.tournament.create({ data: { id: 't1', conversationId: 'room', name: 'Season', game: 'tictactoe', status: 'running', createdAt: new Date() } })
  const base = new Date('2026-11-03T12:00:00Z').getTime()
  for (let i = 0; i < 14; i++) {
    await db.user.create({ data: { id: `u${i}`, name: `Player ${i}` } })
    await db.tournamentPlayer.create({ data: { id: `e${i}`, tournamentId: 't1', userId: `u${i}`,
      points: Math.floor(i / 4), wins: i % 4, joinedAt: new Date(base + i * 1000) } })
  }
}, async db => {
  const detail = await routes('src/app/api/tournaments/[id]/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
    requireActionOwner: async () => null,
    finishTournament: (await import('../../src/lib/game-contract.ts')).finishTournament,
    GameOperationError: (await import('../../src/lib/game-contract.ts')).GameOperationError,
    readRoomGame: (await import('../../src/lib/game-read.ts')).readRoomGame,
    gameReadErrorResponse: (await import('../../src/lib/game-read.ts')).gameReadErrorResponse,
    gameReadHeaders: (await import('../../src/lib/game-read.ts')).gameReadHeaders,
  })
  const get = query => detail.GET(new Request('https://tournaments.test' + query), { params: Promise.resolve({ id: 't1' }) })

  const legacy = (await (await get('/api/tournaments/t1?userId=me')).json()).tournament
  assert.equal(legacy.entries.length, 14, 'legacy full standings unchanged')
  assert.equal('standingsHasMore' in legacy, false)

  const canonical = legacy.entries.map(row => row.userId)
  const seen = []
  let cursor = null
  for (let guard = 0; guard < 10; guard++) {
    const body = (await (await get('/api/tournaments/t1?userId=me&entriesLimit=4' + (cursor ? `&entriesCursor=${encodeURIComponent(cursor)}` : ''))).json()).tournament
    seen.push(...body.entries.map(row => row.userId))
    if (!body.standingsHasMore) { assert.equal(body.standingsNextCursor, null); break }
    cursor = body.standingsNextCursor
  }
  assert.equal(seen.length, 14)
  assert.deepEqual(seen, canonical, 'paged standings match the canonical sort exactly')

  const bad = await get('/api/tournaments/t1?userId=me&entriesLimit=0')
  assert.equal(bad.status, 400)
  const badCursor = await get('/api/tournaments/t1?userId=me&entriesCursor=1.2.3')
  assert.equal(badCursor.status, 400)
}))
