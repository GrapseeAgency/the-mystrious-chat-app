// Section 11 - durable creation receipts for the economy (items 1/2/5/8):
// a lost marketplace ACK replays the SAME listing; connect/disconnect has a
// durable identity plus a monotonic per-user revision; and the money paths
// (transfer, swap, purchase) prove the SAME replay verdict after a simulated
// device restart - new request, same clientId, single debit.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { readFile } from 'node:fs/promises'
import * as market from '../../src/lib/market-contract.ts'
import * as hubApps from '../../src/lib/hub-app-operation.ts'
import * as wallet from '../../src/lib/wallet-contract.ts'
import * as section10 from '../../src/lib/section10-contracts.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

const CID = 'replay0000000000000000011'

async function routes(path, deps) {
  const source = stripTypeScriptTypes(await readFile(new URL('../../' + path, import.meta.url), 'utf8'))
    .replace(/^import [\s\S]*?from '.*'$/gm, '').replace(/^import .*$/gm, '').replace(/export /g, '')
  return new Function(...Object.keys(deps), source + '; return {GET:typeof GET==="function"?GET:null,POST:typeof POST==="function"?POST:null,DELETE:typeof DELETE==="function"?DELETE:null}')(...Object.values(deps))
}

async function fixture(tables, seed, run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-s11-replay-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of tables) await db.$executeRawUnsafe(sql)
    if (seed) await seed(db)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

const USER_TABLES = [
  'CREATE TABLE User (id TEXT PRIMARY KEY, name TEXT, username TEXT, color TEXT)',
  "INSERT INTO User VALUES ('seller','Seller','seller','emerald')",
  "INSERT INTO User VALUES ('buyer','Buyer','buyer','amber')",
  "INSERT INTO User VALUES ('other','Other','other','rose')",
]

const MARKET_TABLES = [
  ...USER_TABLES,
  'CREATE TABLE MarketListing (id TEXT PRIMARY KEY, sellerId TEXT, title TEXT, description TEXT, price INTEGER, asset TEXT, status TEXT, buyerId TEXT, createdAt DATETIME)',
  'CREATE TABLE LogEvent (id TEXT PRIMARY KEY, userId TEXT, kind TEXT, message TEXT, meta TEXT, createdAt DATETIME)',
  'CREATE TABLE MarketCreationOperation (userId TEXT, clientId TEXT, requestHash TEXT, listingId TEXT, PRIMARY KEY (userId, clientId))',
]

test('items 1/2: listing creation replays the SAME listing and refuses mismatched reuse', () => fixture(MARKET_TABLES, null, async db => {
  const route = await routes('src/app/api/hub/market/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
    requireWalletOwner: async () => null,
    createListingReceipted: market.createListingReceipted, MarketOperationError: market.MarketOperationError,
    pageLimit: section10.pageLimit,
  })
  const post = body => route.POST(new Request('https://market.test', { method: 'POST', body: JSON.stringify(body) }))
  const first = await post({ userId: 'seller', title: 'Lantern', price: 40, clientId: CID })
  assert.equal(first.status, 201)
  const created = (await first.json()).listing
  assert.equal(typeof created.id, 'string')

  // Lost-ACK retry with the SAME identity: same row, 200 replayed.
  const retry = await post({ userId: 'seller', title: 'Lantern', price: 40, clientId: CID })
  assert.equal(retry.status, 200)
  const replay = await retry.json()
  assert.equal(replay.replayed, true)
  assert.equal(replay.listing.id, created.id)
  assert.equal(await db.marketListing.count(), 1, 'no duplicate listing')

  // Same identity, different instructions: 409, nothing created.
  const clash = await post({ userId: 'seller', title: 'Kettle', price: 40, clientId: CID })
  assert.equal(clash.status, 409)
  assert.equal(await db.marketListing.count(), 1)

  // A different identity is a genuinely new listing.
  const second = await post({ userId: 'seller', title: 'Kettle', price: 12, clientId: 'replay0000000000000000012' })
  assert.equal(second.status, 201)
  assert.notEqual((await second.json()).listing.id, created.id)

  // A replay whose row was removed is an honest 410, not a silent duplicate.
  await db.marketListing.delete({ where: { id: created.id } })
  const gone = await post({ userId: 'seller', title: 'Lantern', price: 40, clientId: CID })
  assert.equal(gone.status, 410)
  assert.equal((await gone.json()).code, 'creation_gone')
  assert.equal(await db.marketListing.count(), 1)
}))

const APP_TABLES = [
  ...USER_TABLES,
  'CREATE TABLE AppInstall (id TEXT PRIMARY KEY, userId TEXT, appId TEXT, installedAt DATETIME, status TEXT)',
  'CREATE UNIQUE INDEX appinstall_uq ON AppInstall (userId, appId)',
  'CREATE TABLE AppInstallRevision (userId TEXT PRIMARY KEY, rev INTEGER DEFAULT 0)',
  'CREATE TABLE AppConnectionOperation (userId TEXT, clientId TEXT, requestHash TEXT, appId TEXT, installed BOOLEAN, PRIMARY KEY (userId, clientId))',
  'CREATE TABLE Conversation (id TEXT PRIMARY KEY, isGroup BOOLEAN, appKey TEXT, name TEXT, photo TEXT, createdAt DATETIME, updatedAt DATETIME)',
  'CREATE TABLE ConversationParticipant (id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, role TEXT, joinedAt DATETIME, lastReadAt DATETIME)',
  'CREATE UNIQUE INDEX participant_uq ON ConversationParticipant (userId, conversationId)',
]

test('item 8: connect/disconnect replays the acknowledged verdict and bumps a monotonic revision', () => fixture(APP_TABLES, null, async db => {
  const route = await routes('src/app/api/hub/apps/[appId]/install/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(),
    requireActionOwner: async () => null, MATRIX: (await import('../../src/lib/hub-catalog.ts')).MATRIX,
    appInstallSnapshot: hubApps.appInstallSnapshot, setAppInstalled: hubApps.setAppInstalled,
    hubAppErrorResponse: hubApps.hubAppErrorResponse,
  })
  const ctx = { params: Promise.resolve({ appId: '4' }) }
  const connect = body => route.POST(new Request('https://apps.test', { method: 'POST', body: JSON.stringify(body) }), ctx)
  const disconnect = body => route.DELETE(new Request('https://apps.test', { method: 'DELETE', body: JSON.stringify(body) }), ctx)

  const first = await connect({ userId: 'seller', clientId: CID })
  assert.equal(first.status, 200)
  const connected = await first.json()
  assert.equal(connected.installed, true)
  assert.equal(connected.replayed, false)
  assert.equal(connected.installsRev, 1, 'connect bumps the revision')

  // A lost-ACK retry is an acknowledgement, not a new toggle: the snapshot is
  // returned untouched (replayed) and the revision does NOT advance again.
  const retry = await connect({ userId: 'seller', clientId: CID })
  assert.equal(retry.status, 200)
  const replayed = await retry.json()
  assert.equal(replayed.replayed, true)
  assert.equal(replayed.installed, true)
  assert.equal(replayed.installsRev, 1, 'replay does not spend a revision')

  // Reused identity with different instructions: 409.
  const clash = await disconnect({ userId: 'seller', clientId: CID })
  assert.equal(clash.status, 409)

  // A fresh disconnect identity advances the revision.
  const off = await disconnect({ userId: 'seller', clientId: 'replay0000000000000000013' })
  assert.equal(off.status, 200)
  const offBody = await off.json()
  assert.equal(offBody.installed, false)
  assert.equal(offBody.installsRev, 2, 'disconnect bumps the revision')
}))

test('item 6: the batch snapshot answers for the whole catalog in ONE request', () => fixture(APP_TABLES, null, async db => {
  const batch = await routes('src/app/api/hub/apps/installs/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(),
    requireActionOwner: async () => null,
    appInstallsBatchSnapshot: hubApps.appInstallsBatchSnapshot, setAppInstalled: hubApps.setAppInstalled,
    hubAppErrorResponse: hubApps.hubAppErrorResponse,
  })
  const perApp = await routes('src/app/api/hub/apps/[appId]/install/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(),
    requireActionOwner: async () => null, MATRIX: (await import('../../src/lib/hub-catalog.ts')).MATRIX,
    appInstallSnapshot: hubApps.appInstallSnapshot, setAppInstalled: hubApps.setAppInstalled,
    hubAppErrorResponse: hubApps.hubAppErrorResponse,
  })
  await perApp.POST(new Request('https://apps.test', { method: 'POST', body: JSON.stringify({ userId: 'seller' }) }),
    { params: Promise.resolve({ appId: '4' }) })

  const page = await batch.GET(new Request('https://apps.test/installs?userId=seller'))
  assert.equal(page.status, 200)
  const body = await page.json()
  assert.equal(body.appVersion, 2)
  assert.equal(body.userId, 'seller')
  assert.equal(body.installsRev, 1)
  const four = body.apps.find(row => row.appId === '4')
  assert.equal(four.installed, true)
  assert.equal(typeof four.installedAt, 'string')
  assert.equal(body.apps.filter(row => row.installed).length, 1, 'exactly one connected app')

  const anon = await batch.GET(new Request('https://apps.test/installs'))
  assert.equal(anon.status, 400, 'batch snapshot is bound to a viewer')

  const bulk = await batch.POST(new Request('https://apps.test', { method: 'POST', body: JSON.stringify({
    userId: 'buyer',
    actions: [
      { appId: '7', installed: true, clientId: 'replay0000000000000000014' },
      { appId: '7', installed: true, clientId: 'replay0000000000000000014' },
    ],
  }) }))
  assert.equal(bulk.status, 200)
  const bulkBody = await bulk.json()
  assert.equal(bulkBody.applied.length, 2)
  assert.equal(bulkBody.applied[0].replayed, false)
  assert.equal(bulkBody.applied[1].replayed, true, 'bulk replay is an acknowledgement')
  assert.equal(await db.appInstall.count({ where: { userId: 'buyer' } }), 1, 'no duplicate install row')
}))

test('item 5: money paths replay the SAME verdict after a simulated restart', () => fixture([
  ...USER_TABLES,
  'CREATE TABLE UserWallet (userId TEXT PRIMARY KEY, coins INTEGER DEFAULT 0, gems INTEGER DEFAULT 0, streak INTEGER DEFAULT 0, lastCheckIn DATETIME)',
  'CREATE TABLE WalletLedger (id TEXT PRIMARY KEY, userId TEXT, kind TEXT, asset TEXT, amount INTEGER, note TEXT, counterpartyId TEXT, createdAt DATETIME)',
  'CREATE TABLE LogEvent (id TEXT PRIMARY KEY, userId TEXT, kind TEXT, message TEXT, meta TEXT, createdAt DATETIME)',
  'CREATE TABLE WalletOperation (userId TEXT, clientId TEXT, requestHash TEXT, result TEXT, PRIMARY KEY (userId, clientId))',
  'CREATE TABLE MarketListing (id TEXT PRIMARY KEY, sellerId TEXT, title TEXT, description TEXT, price INTEGER, asset TEXT, status TEXT, buyerId TEXT, createdAt DATETIME)',
], async db => {
  await db.userWallet.create({ data: { userId: 'buyer', coins: 500 } })
  await db.userWallet.create({ data: { userId: 'seller', coins: 0 } })
  await db.marketListing.create({ data: { id: 'l1', sellerId: 'seller', title: 'Lantern', price: 40 } })
}, async db => {
  // Restart = the SAME clientId arrives on a fresh request with no memory.
  const sent = await wallet.transferWallet(db, 'buyer', 'other', 30, '', 'Buyer', 'Other', 'restart000000000000000001')
  assert.equal(sent.coins, 470)
  const resent = await wallet.transferWallet(db, 'buyer', 'other', 30, '', 'Buyer', 'Other', 'restart000000000000000001')
  assert.equal(resent.coins, 470, 'transfer replay does not double-debit')
  assert.equal(await db.walletLedger.count(), 2, 'exactly one ledger pair')

  const swapped = await wallet.swapWallet(db, 'buyer', 'pc2gem', 200, 'restart000000000000000002')
  assert.equal(swapped.wallet.gems, 2)
  const reswapped = await wallet.swapWallet(db, 'buyer', 'pc2gem', 200, 'restart000000000000000002')
  assert.equal(reswapped.wallet.gems, 2, 'swap replay does not double-convert')

  const bought = await wallet.buyMarketListing(db, 'buyer', 'l1')
  assert.equal(bought.coins, 470 - 200 - 40, 'purchase debits once')
  const rebought = await wallet.buyMarketListing(db, 'buyer', 'l1')
  assert.equal(rebought.coins, bought.coins, 'purchase replay is the acknowledged receipt')
  const seller = await db.userWallet.findUnique({ where: { userId: 'seller' } })
  assert.equal(seller.coins, 40, 'seller credited exactly once')
}))
