import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { transferWallet, swapWallet, walletErrorResponse } from '../../src/lib/wallet-contract.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-wallet-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE UserWallet(userId TEXT PRIMARY KEY, coins INTEGER NOT NULL DEFAULT 0, gems INTEGER NOT NULL DEFAULT 0, streak INTEGER NOT NULL DEFAULT 0, lastCheckIn DATETIME)')
    await db.$executeRawUnsafe('CREATE TABLE WalletLedger(id TEXT PRIMARY KEY, userId TEXT NOT NULL, kind TEXT NOT NULL, asset TEXT NOT NULL, amount INTEGER NOT NULL, note TEXT NOT NULL DEFAULT "", counterpartyId TEXT, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    await db.$executeRawUnsafe('CREATE TABLE LogEvent(id TEXT PRIMARY KEY, userId TEXT, kind TEXT NOT NULL, message TEXT NOT NULL, meta TEXT, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    await db.userWallet.createMany({ data: [{ userId: 'a', coins: 150, gems: 2 }, { userId: 'b', coins: 10 }] })
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
const transfer = (db, amount = 100) => transferWallet(db, 'a', 'b', amount, '', 'alice', 'bob')

test('transfer conserves coins with exactly two ledger legs', () => fixture(async db => {
  await transfer(db)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'a' } })).coins, 50)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'b' } })).coins, 110)
  assert.equal(await db.walletLedger.count(), 2)
  assert.equal((await db.walletLedger.aggregate({ _sum: { amount: true } }))._sum.amount, 0)
}))
test('rejected debit returns HTTP 402, not a fake 200 wallet, and writes nothing', () => fixture(async db => {
  let response
  try { await transfer(db, 200); assert.fail('should reject') } catch (error) { response = walletErrorResponse(error) }
  assert.equal(response.status, 402)
  const body = await response.json()
  assert.ok(body.error); assert.equal(body.wallet, undefined)
  assert.equal(await db.walletLedger.count(), 0)
  assert.equal(await db.logEvent.count(), 0)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'a' } })).coins, 150)
}))
test('recipient overflow rolls the debit back too', () => fixture(async db => {
  await db.userWallet.update({ where: { userId: 'b' }, data: { coins: 2147483640 } })
  await assert.rejects(transfer(db), { status: 422 })
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'a' } })).coins, 150)
  assert.equal(await db.walletLedger.count(), 0)
}))
test('swap commits balances and both ledger legs together', () => fixture(async db => {
  const result = await swapWallet(db, 'a', 'pc2gem', 100)
  assert.equal(result.wallet.coins, 50); assert.equal(result.wallet.gems, 3)
  assert.equal(await db.walletLedger.count(), 2)
}))
test('failed swap is HTTP 402 with no balance or ledger mutation', () => fixture(async db => {
  let response
  try { await swapWallet(db, 'a', 'gem2pc', 3); assert.fail('should reject') } catch (error) { response = walletErrorResponse(error) }
  assert.equal(response.status, 402)
  assert.equal(await db.walletLedger.count(), 0)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'a' } })).gems, 2)
}))
test('unsafe, out-of-range, fractional and off-rate amounts are rejected before mutations', () => fixture(async db => {
  for (const amount of [NaN, Infinity, 1.1, 0, -1, 2147483648, Number.MAX_SAFE_INTEGER]) {
    await assert.rejects(swapWallet(db, 'a', 'gem2pc', amount), { status: 400 })
  }
  await assert.rejects(swapWallet(db, 'a', 'pc2gem', 101), { status: 400 })
  await assert.rejects(swapWallet(db, 'a', 'gem2pc', 2147483647), { status: 400 })
  assert.equal(await db.walletLedger.count(), 0)
}))
test('concurrent spends cannot overdraw or leave partial ledger legs', () => fixture(async db => {
  const results = await Promise.allSettled([transfer(db), transfer(db)])
  const fulfilled = results.filter(r => r.status === 'fulfilled').length
  assert.ok(fulfilled <= 1)
  const wallets = await db.userWallet.findMany()
  assert.equal(wallets.reduce((sum, row) => sum + row.coins, 0), 160)
  assert.ok(wallets.every(row => row.coins >= 0))
  assert.equal(await db.walletLedger.count(), fulfilled * 2)
}))
test('unexpected storage errors are not converted to success or insufficient balance', () => {
  assert.throws(() => walletErrorResponse(new Error('disk failure')), /disk failure/)
})
