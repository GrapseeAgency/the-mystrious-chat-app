import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
import * as contract from '../../src/lib/wallet-contract.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const NextResponse = { json: (body, init) => Response.json(body, init) }
async function load(path, bindings, exports) {
  const source = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8'))
    .replace(/^import .*?from ['"].*?['"];?\s*$/gm, '')
    .replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), `${stripTypeScriptTypes(source)}\nreturn {${exports}}`)(...Object.values(bindings))
}
const paths = {
  transfer: 'hub/wallet/transfer', checkin: 'hub/wallet/checkin', swap: 'hub/swap',
  buy: 'hub/market/[id]/buy', market: 'hub/market',
}
const instructions = {
  transfer: { userId: 'a', toUsername: 'bob', amount: 10 },
  checkin: { userId: 'a' }, swap: { userId: 'a', direction: 'gem2pc', amount: 1 },
  buy: { userId: 'a' }, market: { userId: 'a', title: 'New item', price: 10 },
}
function request(body, token, query = '') {
  return new Request(`https://pulse.test/api${query}`, {
    method: body ? 'POST' : 'GET',
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
}
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-legacy-wallet-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE User(id TEXT PRIMARY KEY, username TEXT UNIQUE, name TEXT, color TEXT, sessionTokenHash TEXT, legacyTokenHashes TEXT)')
    for (const [id, username] of [['a', 'alice'], ['b', 'bob']]) {
      await db.$executeRawUnsafe('INSERT INTO User VALUES (?, ?, ?, ?, ?, ?)', id, username, username, '#F2A65A', hashSessionToken(`synthetic-${id}`), '[]')
    }
    await db.$executeRawUnsafe('CREATE TABLE UserWallet(userId TEXT PRIMARY KEY, coins INTEGER NOT NULL DEFAULT 0, gems INTEGER NOT NULL DEFAULT 0, streak INTEGER NOT NULL DEFAULT 0, lastCheckIn DATETIME)')
    await db.$executeRawUnsafe('CREATE TABLE WalletLedger(id TEXT PRIMARY KEY, userId TEXT NOT NULL, kind TEXT NOT NULL, asset TEXT NOT NULL, amount INTEGER NOT NULL, note TEXT NOT NULL DEFAULT "", counterpartyId TEXT, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    await db.$executeRawUnsafe('CREATE TABLE LogEvent(id TEXT PRIMARY KEY, userId TEXT, kind TEXT NOT NULL, message TEXT NOT NULL, meta TEXT, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    await db.$executeRawUnsafe('CREATE TABLE MarketListing(id TEXT PRIMARY KEY, sellerId TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL DEFAULT "", price INTEGER NOT NULL, asset TEXT NOT NULL DEFAULT "PC", status TEXT NOT NULL DEFAULT "open", buyerId TEXT, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    await db.marketListing.create({ data: { id: 'item', sellerId: 'b', title: 'Thing', price: 100 } })
    await db.userWallet.createMany({ data: [{ userId: 'a', coins: 150, gems: 2 }, { userId: 'b', coins: 10 }] })
    const auth = await load('src/lib/wallet-authorization.ts', { db, bearerToken, tokenAccepted }, 'requireWalletOwner')
    const routes = {}
    for (const [name, path] of Object.entries(paths)) routes[name] = await load(`src/app/api/${path}/route.ts`, {
      db, NextResponse, ...auth, ...contract,
      safeJson: async req => req.json(), strField: v => typeof v === 'string' ? v.trim() : '',
    }, name === 'market' || name === 'swap' ? 'GET, POST' : 'POST')
    await run(db, routes)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
const context = { params: Promise.resolve({ id: 'item' }) }
test('all directly callable legacy monetary routes reject absent, forged and other-owner credentials without writes', () => fixture(async (db, routes) => {
  for (const [name, body] of Object.entries(instructions)) {
    for (const token of [undefined, 'forged', 'synthetic-b']) {
      const result = await routes[name].POST(request(body, token), context)
      assert.equal(result.status, token ? 403 : 401, `${name}: ${token ?? 'missing'}`)
    }
  }
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'a' } })).coins, 150)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'b' } })).coins, 10)
  assert.equal(await db.walletLedger.count(), 0)
  assert.equal(await db.logEvent.count(), 0)
  assert.equal(await db.marketListing.count(), 1)
  assert.equal((await db.marketListing.findUnique({ where: { id: 'item' } })).status, 'open')
}))
test('authenticated direct transfer and check-in retain atomic money behavior', () => fixture(async (db, routes) => {
  assert.equal((await routes.transfer.POST(request(instructions.transfer, 'synthetic-a'))).status, 200)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'a' } })).coins, 140)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'b' } })).coins, 20)
  assert.equal((await routes.checkin.POST(request(instructions.checkin, 'synthetic-a'))).status, 200)
  assert.equal((await routes.checkin.POST(request(instructions.checkin, 'synthetic-a'))).status, 409)
  assert.equal(await db.walletLedger.count(), 3)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'a' } })).coins, 165)
}))
test('authenticated direct swap and purchase preserve exact wallet effects and purchase replay', () => fixture(async (db, routes) => {
  const swap = await routes.swap.POST(request(instructions.swap, 'synthetic-a'))
  assert.equal(swap.status, 200)
  const swapped = await db.userWallet.findUnique({ where: { userId: 'a' } })
  assert.equal(swapped.gems, 1)
  const beforePurchase = swapped.coins
  for (let i = 0; i < 2; i++) assert.equal((await routes.buy.POST(request(instructions.buy, 'synthetic-a'), context)).status, 200)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'a' } })).coins, beforePurchase - 100)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'b' } })).coins, 110)
  assert.equal((await db.marketListing.findUnique({ where: { id: 'item' } })).buyerId, 'a')
}))
test('market public board stays public but sold history requires the actual seller credential', () => fixture(async (db, routes) => {
  await db.marketListing.create({ data: { id: 'sold', sellerId: 'a', buyerId: 'b', status: 'sold', title: 'Private history', price: 5 } })
  // Even an inconsistent open row must not disclose a buyer on the public board.
  await db.marketListing.update({ where: { id: 'item' }, data: { buyerId: 'a' } })
  const publicPage = await (await routes.market.GET(request())).json()
  assert.deepEqual(publicPage.listings.map(l => l.id), ['item'])
  assert.equal(publicPage.listings[0].buyer, null)
  assert.equal(publicPage.listings[0].mine, false)
  for (const token of [undefined, 'forged', 'synthetic-b']) assert.equal((await routes.market.GET(request(null, token, '?userId=a'))).status, token ? 403 : 401)
  const result = await routes.market.GET(request(null, 'synthetic-a', '?userId=a'))
  assert.match(result.headers.get('cache-control'), /private.*no-store/)
  const own = (await result.json()).listings.find(l => l.id === 'sold')
  assert.equal(own.mine, true); assert.equal(own.buyer.id, 'b')
  assert.equal((await routes.swap.GET()).status, 200)
}))
test('listing and activity log commit together, and a log failure leaves no listing to duplicate', () => fixture(async (db, routes) => {
  const accepted = await routes.market.POST(request(instructions.market, 'synthetic-a'))
  assert.equal(accepted.status, 201)
  const listing = (await accepted.json()).listing
  assert.equal((await db.marketListing.findUnique({ where: { id: listing.id } })).sellerId, 'a')
  assert.equal(await db.logEvent.count(), 1)
  await db.$executeRawUnsafe('CREATE TRIGGER reject_log BEFORE INSERT ON LogEvent BEGIN SELECT RAISE(ABORT, "journal unavailable"); END')
  await assert.rejects(routes.market.POST(request({ ...instructions.market, title: 'Must roll back' }, 'synthetic-a')))
  assert.equal(await db.marketListing.count(), 2)
  assert.equal(await db.logEvent.count(), 1)
}))
test('money routes do not coerce booleans or numeric strings into charges', () => fixture(async (db, routes) => {
  for (const name of ['transfer', 'swap', 'market']) {
    for (const invalid of [true, '1', null]) {
      const body = { ...instructions[name], [name === 'market' ? 'price' : 'amount']: invalid }
      assert.equal((await routes[name].POST(request(body, 'synthetic-a'))).status, 400, `${name}: ${invalid}`)
    }
  }
  assert.equal(await db.walletLedger.count(), 0)
  assert.equal(await db.marketListing.count(), 1)
}))
