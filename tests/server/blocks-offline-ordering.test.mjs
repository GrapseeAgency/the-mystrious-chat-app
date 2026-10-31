// Section 8 item 6 - blocked-list offline ordering.
// Durable intents order late opposite writes by intentAt (LWW); a stale
// opposite intent is recorded but cannot regress the pair state; the same
// intent replays to the SAME receipt; large lists page by keyset cursor.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
import { peerBlock, PeerBlockError } from '../../src/lib/peer-block.ts'

const T1 = '2026-02-01T10:00:00.000Z'
const T2 = '2026-02-01T11:00:00.000Z'
const T3 = '2026-02-01T12:00:00.000Z'

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'blkord-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe(`CREATE TABLE User(id TEXT PRIMARY KEY)`)
    await db.$executeRawUnsafe(`CREATE TABLE UserBlock(id TEXT PRIMARY KEY, blockerId TEXT, blockedId TEXT,
      createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, lastIntentAt DATETIME, lastClientId TEXT, UNIQUE(blockerId, blockedId))`)
    await db.$executeRawUnsafe('CREATE TABLE PeerBlockIntent(blockerId TEXT, blockedId TEXT, clientId TEXT, desired BOOLEAN, intentAt DATETIME, PRIMARY KEY(blockerId, blockedId, clientId))')
    for (let i = 0; i < 30; i++) await db.$executeRawUnsafe('INSERT INTO User (id) VALUES (?)', 't' + String(i).padStart(2, '0'))
    for (const id of ['me', 'peer']) await db.$executeRawUnsafe('INSERT INTO User (id) VALUES (?)', id)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

test('a late opposite write with an older intent cannot regress the pair state', () => fixture(async (db) => {
  // Device B (online, newer) blocks at T2.
  const newer = await peerBlock(db, 'me', 'peer', true, { clientId: 'intent-newer-0000000001', intentAt: T2 })
  assert.equal(newer.blocked, true)
  assert.equal(newer.stale, false)
  // Device A (offline since T1) then syncs its older UNBLOCK intent.
  const late = await peerBlock(db, 'me', 'peer', false, { clientId: 'intent-older-0000000002', intentAt: T1 })
  assert.equal(late.stale, true, 'the older opposite intent must be recorded, not applied')
  assert.equal(late.blocked, true, 'pair state must not regress')
  // Device A's newer UNBLOCK (T3) wins and unblocks.
  const wins = await peerBlock(db, 'me', 'peer', false, { clientId: 'intent-older-0000000003', intentAt: T3 })
  assert.equal(wins.stale, false)
  assert.equal(wins.blocked, false)
  const intentRows = await db.$queryRawUnsafe('SELECT COUNT(*) AS n FROM PeerBlockIntent')
  assert.equal(Number(intentRows[0].n), 3, 'every submission is journaled for future ordering')
}))

test('the same durable intent replays to the same receipt without re-applying', () => fixture(async (db) => {
  const first = await peerBlock(db, 'me', 'peer', true, { clientId: 'intent-replay-000000001', intentAt: T2 })
  const row0 = await db.userBlock.findUnique({ where: { blockerId_blockedId: { blockerId: 'me', blockedId: 'peer' } } })
  const again = await peerBlock(db, 'me', 'peer', true, { clientId: 'intent-replay-000000001', intentAt: T2 })
  assert.equal(again.replayed, true)
  assert.equal(again.blocked, true)
  const row1 = await db.userBlock.findUnique({ where: { blockerId_blockedId: { blockerId: 'me', blockedId: 'peer' } } })
  assert.equal(row1.lastIntentAt?.getTime(), row0.lastIntentAt?.getTime(), 'replay must not refresh the watermark')
  // The same identity bound to a DIFFERENT intent is a hard conflict.
  await assert.rejects(peerBlock(db, 'me', 'peer', false, { clientId: 'intent-replay-000000001', intentAt: T2 }), PeerBlockError)
  await assert.rejects(peerBlock(db, 'me', 'peer', true, { clientId: 'intent-replay-000000001', intentAt: T3 }), PeerBlockError)
}))

test('legacy callers keep the immediate-apply receipt and still journal an intent', () => fixture(async (db) => {
  const receipt = await peerBlock(db, 'me', 'peer', true)
  assert.deepEqual(receipt, { ok: true, blockReceiptVersion: 1, actorId: 'me', targetId: 'peer', blocked: true },
    'the historical receipt shape must not change for legacy callers')
  const intents = await db.$queryRawUnsafe('SELECT clientId, desired FROM PeerBlockIntent')
  assert.equal(intents.length, 1)
  assert.match(intents[0].clientId, /^legacy-/)
  // A later offline intent born BEFORE the legacy write arrives late: stale.
  const offline = await peerBlock(db, 'me', 'peer', false, { clientId: 'intent-late-00000000001', intentAt: '2026-01-01T00:00:00.000Z' })
  assert.equal(offline.stale, true)
  assert.equal(offline.blocked, true)
}))

test('block state reads stay honest and malformed intents are refused up front', () => fixture(async (db) => {
  await assert.rejects(peerBlock(db, 'me', 'peer', true, { clientId: 'short', intentAt: T1 }), e => e.status === 400)
  await assert.rejects(peerBlock(db, 'me', 'peer', true, { clientId: 'intent-no-stamp-0000001' }), e => e.status === 400)
  await assert.rejects(peerBlock(db, 'me', 'peer', true, { clientId: 'intent-bad-stamp-00001', intentAt: 'not-a-date' }), e => e.status === 400)
  const state = await peerBlock(db, 'me', 'peer', null)
  assert.deepEqual(state, { ok: true, blockReceiptVersion: 1, actorId: 'me', targetId: 'peer', blocked: false })
}))

test('large block lists page by keyset cursor with stable newest-first order', () => fixture(async (db) => {
  for (let i = 0; i < 25; i++) {
    await db.userBlock.create({ data: { id: `blk${String(i).padStart(2, '0')}`, blockerId: 'me', blockedId: `t${String(i).padStart(2, '0')}` } })
  }
  const page1 = await db.userBlock.findMany({ where: { blockerId: 'me' }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take: 11 })
  const hasMore1 = page1.length > 10
  assert.equal(hasMore1, true)
  const rows1 = page1.slice(0, 10)
  // Cursor anchors on (createdAt, id); tied timestamps split by id desc.
  const anchor = rows1[9]
  const page2 = await db.userBlock.findMany({
    where: { blockerId: 'me', OR: [
      { createdAt: { lt: anchor.createdAt } },
      { createdAt: anchor.createdAt, id: { lt: anchor.id } },
    ] },
    orderBy: [{ createdAt: 'desc' }, { id: 'desc' }], take: 11 })
  const ids = [...rows1.map(r => r.id), ...page2.map(r => r.id)]
  assert.equal(new Set(ids).size, 21)
  // Both pages joined reproduce the total order without overlap.
  const ordered = await db.userBlock.findMany({ where: { blockerId: 'me' }, orderBy: [{ createdAt: 'desc' }, { id: 'desc' }] })
  assert.deepEqual(ids, ordered.slice(0, 21).map(r => r.id))
}))
