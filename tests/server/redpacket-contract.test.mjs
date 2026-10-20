import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { claimRedPacket, packetSlices, refundRedPacket } from '../../src/lib/redpacket-contract.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'packet-contract-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'fixture.sqlite')}` })
  try {
    for (const sql of [
      'CREATE TABLE User(id TEXT PRIMARY KEY, name TEXT)',
      'CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT)',
      'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, UNIQUE(userId, conversationId))',
      'CREATE TABLE RedPacket(id TEXT PRIMARY KEY, messageId TEXT UNIQUE, senderId TEXT, total INTEGER, count INTEGER, grabbed INTEGER DEFAULT 0, slices TEXT, note TEXT DEFAULT "", expiresAt DATETIME, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
      'CREATE TABLE RedPacketGrab(id TEXT PRIMARY KEY, packetId TEXT, userId TEXT, amount INTEGER, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE(packetId, userId))',
      'CREATE TABLE UserWallet(userId TEXT PRIMARY KEY, coins INTEGER DEFAULT 0, gems INTEGER DEFAULT 0, streak INTEGER DEFAULT 0, lastCheckIn DATETIME)',
      'CREATE TABLE WalletLedger(id TEXT PRIMARY KEY, userId TEXT, kind TEXT, asset TEXT, amount INTEGER, note TEXT, counterpartyId TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
      'CREATE TABLE LogEvent(id TEXT PRIMARY KEY, userId TEXT, kind TEXT, message TEXT, meta TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)',
      `INSERT INTO User VALUES ('sender', 'Sender')`,
      `INSERT INTO Message VALUES ('m', 'room')`,
      `INSERT INTO ConversationParticipant VALUES ('pa', 'a', 'room'), ('pb', 'b', 'room')`,
    ]) await db.$executeRawUnsafe(sql)
    await db.redPacket.create({ data: { id: 'packet', messageId: 'm', senderId: 'sender', total: 10, count: 2, slices: '[3,7]', expiresAt: new Date('2040-01-01') } })
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
test('corrupt slices fail as a whole, never filter and shift payout indices', () => {
  for (const value of ['[null,3,7]', '[3,"7"]', '[3,8]', '[0,10]', '[3.5,6.5]', 'broken']) {
    assert.throws(() => packetSlices(value, 2, 10), { status: 500 })
  }
  assert.deepEqual(packetSlices('[3,7]', 2, 10), [3,7])
})
test('claim retry replays its receipt even after expiry and exhaustion', () => fixture(async db => {
  const first = await claimRedPacket(db, 'a', 'packet')
  assert.equal(first.amount, 3); assert.equal(first.alreadyGrabbed, false)
  await claimRedPacket(db, 'b', 'packet')
  const retry = await claimRedPacket(db, 'a', 'packet', () => new Date('2041-01-01'))
  assert.equal(retry.amount, 3); assert.equal(retry.alreadyGrabbed, true)
  assert.equal(await db.redPacketGrab.count(), 2)
  assert.equal(await db.walletLedger.count(), 2)
  assert.equal((await db.userWallet.aggregate({ _sum: { coins: true } }))._sum.coins, 10)
}))
test('corruption cannot consume cursor, receipt, wallet or ledger', () => fixture(async db => {
  await db.redPacket.update({ where: { id: 'packet' }, data: { slices: '[null,3,7]' } })
  await assert.rejects(claimRedPacket(db, 'a', 'packet'), { status: 500 })
  assert.equal((await db.redPacket.findUnique({ where: { id: 'packet' } })).grabbed, 0)
  assert.equal(await db.redPacketGrab.count(), 0); assert.equal(await db.userWallet.count(), 0)
}))
test('overflow rolls back the claim and leaves its payout available', () => fixture(async db => {
  await db.userWallet.create({ data: { userId: 'a', coins: 2147483646 } })
  await assert.rejects(claimRedPacket(db, 'a', 'packet'), { status: 422 })
  assert.equal((await db.redPacket.findUnique({ where: { id: 'packet' } })).grabbed, 0)
  assert.equal(await db.redPacketGrab.count(), 0); assert.equal(await db.walletLedger.count(), 0)
}))
test('nonmember cannot claim and a new claimant cannot collect after expiry', () => fixture(async db => {
  await assert.rejects(claimRedPacket(db, 'outsider', 'packet'), { status: 403 })
  await assert.rejects(claimRedPacket(db, 'a', 'packet', () => new Date('2040-01-01')), { status: 400 })
  assert.equal(await db.redPacketGrab.count(), 0)
}))
test('concurrent same-user claims and retry award only one slice', () => fixture(async db => {
  await Promise.allSettled([claimRedPacket(db, 'a', 'packet'), claimRedPacket(db, 'a', 'packet')])
  await claimRedPacket(db, 'a', 'packet')
  assert.equal(await db.redPacketGrab.count(), 1)
  assert.equal(await db.walletLedger.count(), 1)
  assert.equal((await db.userWallet.findUnique({ where: { userId: 'a' } })).coins, 3)
}))

const expired = () => new Date('2040-01-01')
test('refund pays only unclaimed escrow once and claim receipts remain replayable', () => fixture(async db => {
  await claimRedPacket(db, 'a', 'packet')
  assert.equal(await refundRedPacket(db, 'packet', expired), 7)
  assert.equal(await refundRedPacket(db, 'packet', expired), 0)
  assert.equal((await claimRedPacket(db, 'a', 'packet', expired)).alreadyGrabbed, true)
  assert.equal((await db.userWallet.aggregate({ _sum: { coins: true } }))._sum.coins, 10)
  assert.equal(await db.walletLedger.count(), 2)
}))
test('corrupt refund data cannot silently close and burn unclaimed funds', () => fixture(async db => {
  await db.redPacket.update({ where: { id: 'packet' }, data: { slices: 'invalid' } })
  await assert.rejects(refundRedPacket(db, 'packet', expired), { status: 500 })
  assert.equal((await db.redPacket.findUnique({ where: { id: 'packet' } })).grabbed, 0)
  assert.equal(await db.walletLedger.count(), 0)
}))
test('refund overflow leaves settlement pending and a later retry can recover', () => fixture(async db => {
  await db.userWallet.create({ data: { userId: 'sender', coins: 2147483640 } })
  await assert.rejects(refundRedPacket(db, 'packet', expired), { status: 422 })
  assert.equal((await db.redPacket.findUnique({ where: { id: 'packet' } })).grabbed, 0)
  await db.userWallet.update({ where: { userId: 'sender' }, data: { coins: 0 } })
  assert.equal(await refundRedPacket(db, 'packet', expired), 10)
}))
test('mismatched historic payouts cannot cause a new claim or refund to mint money', () => fixture(async db => {
  await claimRedPacket(db, 'a', 'packet')
  await db.redPacketGrab.updateMany({ where: { userId: 'a' }, data: { amount: 9 } })
  await assert.rejects(claimRedPacket(db, 'b', 'packet'), { status: 500 })
  await assert.rejects(refundRedPacket(db, 'packet', expired), { status: 500 })
  assert.equal((await db.redPacket.findUnique({ where: { id: 'packet' } })).grabbed, 1)
}))
test('expiry settlement racing a final claim conserves the entire escrow', () => fixture(async db => {
  await Promise.allSettled([
    claimRedPacket(db, 'a', 'packet', () => new Date('2039-12-31T23:59:59Z')),
    refundRedPacket(db, 'packet', expired),
  ])
  await refundRedPacket(db, 'packet', expired)
  assert.equal((await db.userWallet.aggregate({ _sum: { coins: true } }))._sum.coins, 10)
  assert.equal((await db.redPacket.findUnique({ where: { id: 'packet' } })).grabbed, 2)
  assert.equal((await db.walletLedger.aggregate({ _sum: { amount: true } }))._sum.amount, 10)
}))
