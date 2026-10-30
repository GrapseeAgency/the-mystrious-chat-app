import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import crypto from 'node:crypto'
import { deliverMessageNotification } from '../../src/lib/message-notification.ts'
import { pushDeliveryWindow, shouldRetirePushToken, apnsInvalidatedAt } from '../../src/lib/push/delivery-policy.ts'
import { apnsOrigin } from '../../src/lib/push/apns-http2.ts'
const { Prisma, PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = 2_000_000_000_000

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'live-push-outbox-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    // Registry + patches (revision/origin/apnsEnvironment + LivePush tables).
    await db.$executeRawUnsafe('CREATE TABLE PushToken(id TEXT PRIMARY KEY,userId TEXT,platform TEXT,token TEXT UNIQUE,endpoint TEXT,createdAt DATETIME,updatedAt DATETIME)')
    await db.$executeRawUnsafe(await readFile(new URL('../../prisma/patches/20261026_native_read_push.sql', import.meta.url), 'utf8'))
    for (const statement of (await readFile(new URL('../../prisma/patches/20261031_push_registry_revision.sql', import.meta.url), 'utf8'))
      .replace(/^--.*$/gm, '').split(';').map(s => s.trim()).filter(Boolean)) await db.$executeRawUnsafe(statement)
    // Planner tables (DMMF-driven, same pattern as message-notification tests).
    for (const name of ['Message', 'User', 'Conversation', 'ConversationParticipant', 'UserBlock']) {
      const model = Prisma.dmmf.datamodel.models.find(m => m.name === name)
      const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
        const type = ['Int', 'Boolean', 'BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'Float' ? 'REAL' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
        const def = typeof f.default === 'string' ? ` DEFAULT '${f.default.replaceAll("'", "''")}'` :
          typeof f.default === 'number' || typeof f.default === 'boolean' ? ` DEFAULT ${Number(f.default)}` : f.default?.name === 'now' ? ' DEFAULT CURRENT_TIMESTAMP' : ''
        return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : ''}${def}`
      })
      await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
    }
    const seed = async () => {
      await db.conversation.create({ data: { id: 'room', name: 'Group', isGroup: true } })
      for (const id of ['author', 'peer']) {
        await db.user.create({ data: { id, username: id, name: id, preferences: '{}' } })
        await db.conversationParticipant.create({ data: { userId: id, conversationId: 'room' } })
      }
      await db.message.create({ data: { id: 'message', conversationId: 'room', senderId: 'author', content: 'Hello there', createdAt: new Date(now - 1000) } })
    }
    // Evaluate the real transport source (mock providers) and the real
    // live-push-outbox source (bound to the transport fanout + real planner).
    const requests = []
    let clockNow = now
    // Per-token FCM response handler: default healthy.
    let androidHandler = token => ({ status: 200, body: {} })
    const fetch = async (url, options) => {
      if (url.includes('/online')) return new Response(JSON.stringify({ onlineUserIds: [] }))
      const payload = JSON.parse(options.body)
      requests.push({ platform: 'android', payload })
      const response = androidHandler(payload.message.token)
      return new Response(JSON.stringify(response.body), { status: response.status })
    }
    const transportSource = (await readFile(new URL('../../src/lib/push/transport.ts', import.meta.url), 'utf8'))
      .replace(/^import .*$/gm, '').replace(/^export /gm, '')
    const transportBindings = { db, crypto, fetch, pushDeliveryWindow: p => pushDeliveryWindow(p, clockNow), shouldRetirePushToken, apnsInvalidatedAt, apnsOrigin,
      postApns: async () => ({ status: 200, body: null }), process: { env: { CRON_SECRET: 'synthetic-relay-key' } }, console: { log() {}, warn() {} } }
    const transport = new Function(...Object.keys(transportBindings), stripTypeScriptTypes(transportSource, { mode: 'transform' }) + `;
      fcmBearer=async()=>"test-token";fcmCredentials=()=>({projectId:"test-project"});apnsBearer=async()=>"test-token";
      webPushSender=async()=>null;
      return {fanoutPush};`)(...Object.values(transportBindings))
    const outboxSource = (await readFile(new URL('../../src/lib/live-push-outbox.ts', import.meta.url), 'utf8'))
      .replace(/^import .*$/gm, '').replace(/^export /gm, '')
    const outboxBindings = { db, randomUUID: crypto.randomUUID, deliverMessageNotification, fanoutPush: transport.fanoutPush, console: { log() {}, warn() {}, error() {} } }
    const outbox = new Function(...Object.keys(outboxBindings), stripTypeScriptTypes(outboxSource, { mode: 'transform' }) + `;return {deliverLivePush,drainLivePushOutbox,enqueueLivePushOutbox,livePushDelivered,recordLivePush};`)(...Object.values(outboxBindings))
    await run({ db, seed, requests, outbox, clock: () => clockNow,
      setAndroidHandler: handler => { androidHandler = handler },
      advance: ms => { clockNow += ms },
      seedToken: async (id, userId = 'peer', platform = 'android') =>
        db.pushToken.create({ data: { id, userId, platform, token: `token-${id}`, createdAt: new Date(now - 10000), updatedAt: new Date(now - 1000) } }) })
  } finally {
    await db.$disconnect()
    await rm(dir, { recursive: true, force: true })
  }
}

test('a fully successful inline fanout never enqueues the outbox', () => fixture(async h => {
  await h.seed()
  await h.seedToken('peer-phone')
  await h.outbox.deliverLivePush(h.db, 'message', h.clock)
  assert.equal(h.requests.length, 1)
  assert.equal(await h.db.livePushOutbox.count(), 0)
}))

test('a failed inline report enqueues durably and the drain retries to completion', () => fixture(async h => {
  await h.seed()
  await h.seedToken('peer-phone')
  h.setAndroidHandler(() => ({ status: 500, body: {} }))
  await h.outbox.deliverLivePush(h.db, 'message', h.clock)
  assert.equal(h.requests.length, 1)
  assert.equal(await h.db.livePushOutbox.count(), 1)
  // Provider recovers: the drain re-plans with CURRENT policy and delivers.
  h.setAndroidHandler(() => ({ status: 200, body: {} }))
  h.advance(2000)
  await h.outbox.drainLivePushOutbox(h.db, h.clock)
  assert.equal(h.requests.length, 2)
  assert.equal(await h.db.livePushReceipt.count(), 1)
  // The completed row never retries again.
  h.advance(2000)
  await h.outbox.drainLivePushOutbox(h.db, h.clock)
  assert.equal(h.requests.length, 2)
  const rows = await h.db.livePushOutbox.findMany()
  assert.equal(rows.length, 1)
  assert.notEqual(rows[0].completedAtMs, null)
}))

test('a mute landing between the failed attempt and the drain is honored at retry', () => fixture(async h => {
  await h.seed()
  await h.seedToken('peer-phone')
  h.setAndroidHandler(() => ({ status: 500, body: {} }))
  await h.outbox.deliverLivePush(h.db, 'message', h.clock)
  assert.equal(await h.db.livePushOutbox.count(), 1)
  // The recipient mutes the room AFTER the failed inline attempt.
  await h.db.conversationParticipant.updateMany({ where: { userId: 'peer' }, data: { mutedUntil: new Date(now + 3_600_000) } })
  h.setAndroidHandler(() => ({ status: 200, body: {} }))
  h.advance(2000)
  await h.outbox.drainLivePushOutbox(h.db, h.clock)
  assert.equal(h.requests.length, 1, 'muted recipient is not retried')
  const rows = await h.db.livePushOutbox.findMany()
  assert.notEqual(rows[0].completedAtMs, null, 'mute-honoring drain completes the row')
}))

test('receipts make partial-failure retries idempotent per device', () => fixture(async h => {
  await h.seed()
  await h.seedToken('phone-a')
  await h.seedToken('phone-b')
  // Device A succeeds inline; device B fails → the report is failed → enqueue.
  h.setAndroidHandler(token => token === 'token-phone-b' ? { status: 500, body: {} } : { status: 200, body: {} })
  await h.outbox.deliverLivePush(h.db, 'message', h.clock)
  assert.equal(h.requests.length, 2)
  assert.equal(await h.db.livePushReceipt.count(), 1, 'the delivered binding has a receipt')
  assert.equal(await h.db.livePushOutbox.count(), 1)
  // B recovers: the drain retries ONLY B (A's receipt proves delivery).
  h.setAndroidHandler(() => ({ status: 200, body: {} }))
  h.advance(2000)
  await h.outbox.drainLivePushOutbox(h.db, h.clock)
  assert.equal(h.requests.length, 3)
  assert.equal(h.requests.at(-1).payload.message.token, 'token-phone-b')
  assert.equal(await h.db.livePushReceipt.count(), 2)
  const rows = await h.db.livePushOutbox.findMany()
  assert.notEqual(rows[0].completedAtMs, null)
}))

test('a stale outbox row older than a day completes without delivery', () => fixture(async h => {
  await h.seed()
  await h.seedToken('peer-phone')
  h.setAndroidHandler(() => ({ status: 500, body: {} }))
  await h.outbox.deliverLivePush(h.db, 'message', h.clock)
  h.setAndroidHandler(() => ({ status: 200, body: {} }))
  h.advance(2 * 86_400_000)
  await h.outbox.drainLivePushOutbox(h.db, h.clock)
  assert.equal(h.requests.length, 1, 'stale retry does not re-deliver')
  const rows = await h.db.livePushOutbox.findMany()
  assert.notEqual(rows[0].completedAtMs, null)
}))
