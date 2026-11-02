// Section 12 - automations, bots, webhooks, integrations (9 items):
//  1  durable create/delete operation identities with lost-ACK replay
//  2  keyset pagination + per-room integration revisions
//  3  exactly-once automation execution receipts per (rule, source)
//  4  notification outbox committed with the execution, drained reliably
//  5  durable webhook rate window, rule cap, bot prompt lease
//  6  canonical machine identity (serviceKey → handle → legacy name)
//  8  AI context row/char budget with revision-aware fingerprints
//  9  authorized webhook capability rotation (old URL dies at once)
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { createHash } from 'node:crypto'
import * as contracts from '../../src/lib/integration-contracts.ts'
import * as section10 from '../../src/lib/section10-contracts.ts'
import { roomControl } from '../../src/lib/room-control.ts'
import { messageDeliveryDecision } from '../../src/lib/scheduled-delivery.ts'
import { threadRootAvailable } from '../../src/lib/message-visibility.ts'
import { clipUtf16 } from '../../src/lib/text-limits.ts'
const { Prisma, PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

async function load(path, bindings, expression) {
  const text = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}

const TABLES = ['User', 'Conversation', 'ConversationParticipant', 'Automation', 'Webhook', 'Message', 'UserBlock',
  'IntegrationOperation', 'IntegrationRevision', 'AutomationExecution', 'AutomationNotifyOutbox', 'BotPromptLease']

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'section12-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const name of TABLES) {
      const columns = Prisma.dmmf.datamodel.models.find(m => m.name === name).fields
        .filter(f => f.kind !== 'object' && !f.isList).map(f => {
          const type = ['Int', 'Boolean', 'BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'Float' ? 'REAL' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
          let def = ''
          if (typeof f.default === 'string') def = ` DEFAULT '${f.default.replaceAll("'", "''")}'`
          else if (typeof f.default === 'number' || typeof f.default === 'boolean') def = ` DEFAULT ${Number(f.default)}`
          else if (f.default?.name === 'now') def = ' DEFAULT CURRENT_TIMESTAMP'
          return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : ''}${def}`
        })
      await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
    }
    for (const [id, role] of [['admin', 'admin'], ['member', 'member']]) {
      await db.user.create({ data: { id, name: id } })
      await db.conversationParticipant.create({ data: { userId: id, role, conversationId: 'room', lastReadAt: new Date('2026-01-01') } })
    }
    await db.conversation.create({ data: { id: 'room', isGroup: true } })
    await db.user.create({ data: { id: 'bot', name: 'Pulse', username: 'pulseai' } })
    await db.conversationParticipant.create({ data: { userId: 'bot', role: 'member', conversationId: 'room', lastReadAt: new Date('2026-01-01') } })

    const events = []
    const bindings = {
      db, clipUtf16, createHash, messageDeliveryDecision, threadRootAvailable, roomControl,
      NextResponse: Response, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      mapAutomation: r => r, mapMessage: r => r, MESSAGE_MAX: 2000, MESSAGE_FULL_INCLUDE: {},
      memberIdsOf: async room => (await db.conversationParticipant.findMany({ where: { conversationId: room }, orderBy: { userId: 'asc' }, select: { userId: true } })).map(r => r.userId),
      notifySocket: async (...args) => events.push(args),
      deliverLivePush: async () => {}, markAutomationNotifyDelivered: async () => {},
      requireActionOwner: async () => null, nativeBlockFromPayload: () => null,
      randomUUID: () => 'fedcba9876543210fedcba9876543210',
      AVATAR_COLORS: ['#F2A65A'], console: { error: () => {}, log: () => {} },
      ...contracts, ...section10,
    }
    await run(db, bindings, events)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

async function loadRoutes(bindings) {
  const a = await load('src/app/api/conversations/[id]/automations/route.ts', bindings, '({GET,POST})')
  const single = await load('src/app/api/automations/[id]/route.ts', bindings, '({PATCH,DELETE})')
  const w = await load('src/app/api/webhooks/route.ts', bindings, '({GET,POST})')
  const token = await load('src/app/api/webhooks/[token]/route.ts', bindings, '({POST,PATCH,DELETE})')
  return { a, single, w, token }
}

const ctx = (id = 'room', token = 'hook-token') => ({ params: Promise.resolve({ id, token }) })
const CID = 'replay0000000000000000s12'

// ── item 1 + 9: durable operation identities and rotation ───────────

test('automation create replays the SAME rule after a lost ACK and refuses mismatched reuse', () => fixture(async (db, bindings) => {
  const { a } = await loadRoutes(bindings)
  const post = extra => a.POST(new Request('https://s12.test', { method: 'POST', body: JSON.stringify({ userId: 'admin', trigger: 'Lantern', reply: 'Lit', ...extra }) }), ctx())
  const first = await post({ clientId: CID })
  assert.equal(first.status, 201); assert.equal(first.replayed ?? first.replayed, undefined)
  const body = await first.json()
  assert.equal(body.replayed, false)
  const retry = await post({ clientId: CID })
  assert.equal(retry.status, 201)
  const retried = await retry.json()
  assert.equal(retried.replayed, true)
  assert.equal(retried.automation.id, body.automation.id)
  assert.equal(await db.automation.count(), 1)
  const mismatch = await a.POST(new Request('https://s12.test', { method: 'POST', body: JSON.stringify({ userId: 'admin', trigger: 'Different', reply: 'Lit', clientId: CID }) }), ctx())
  assert.equal(mismatch.status, 409)
  // A different clientId with the SAME trigger still dedupes to 409 - the
  // replay receipt never bypasses the natural duplicate rule.
  const dup = await a.POST(new Request('https://s12.test', { method: 'POST', body: JSON.stringify({ userId: 'admin', trigger: 'lantern', reply: 'Lit', clientId: CID + 'x' }) }), ctx())
  assert.equal(dup.status, 409)
}))

test('automation delete replays the SAME verdict after the row is gone', () => fixture(async (db, bindings) => {
  const { single } = await loadRoutes(bindings)
  await db.automation.create({ data: { id: 'rule', conversationId: 'room', trigger: 'Hi', reply: 'Yo', createdById: 'admin' } })
  const del = extra => single.DELETE(new Request('https://s12.test', { method: 'DELETE', body: JSON.stringify({ userId: 'admin', ...extra }) }), ctx('rule'))
  const first = await del({ clientId: CID })
  assert.equal(first.status, 200); assert.equal((await first.json()).deleted, true)
  assert.equal(await db.automation.count(), 0)
  const retry = await del({ clientId: CID })
  assert.equal(retry.status, 200)
  const body = await retry.json()
  assert.equal(body.replayed, true); assert.equal(body.automationId, 'rule')
  // Same clientId against a different target is a mismatch, not a replay.
  const mismatch = await single.DELETE(new Request('https://s12.test', { method: 'DELETE', body: JSON.stringify({ userId: 'admin', clientId: CID }) }), ctx('other-rule'))
  assert.equal(mismatch.status, 409)
  // Replay authorization is fresh: a demoted ex-admin cannot ride the receipt.
  await db.automation.create({ data: { id: 'rule2', conversationId: 'room', trigger: 'Ho', reply: 'Yo', createdById: 'admin' } })
  await db.conversationParticipant.update({ where: { userId_conversationId: { userId: 'admin', conversationId: 'room' } }, data: { role: 'member' } })
  const demoted = await single.DELETE(new Request('https://s12.test', { method: 'DELETE', body: JSON.stringify({ userId: 'admin', clientId: CID + 'y' }) }), ctx('rule2'))
  assert.equal(demoted.status, 403)
}))

test('webhook create replays the SAME capability URL; rotate mints a fresh token once and kills the old URL', () => fixture(async (db, bindings) => {
  const { w, token } = await loadRoutes(bindings)
  const post = extra => w.POST(new Request('https://s12.test', { method: 'POST', body: JSON.stringify({ conversationId: 'room', requesterId: 'admin', name: 'CI bot', ...extra }) }), ctx())
  const first = await post({ clientId: CID })
  assert.equal(first.status, 201)
  const created = await first.json()
  assert.equal(created.replayed, false)
  const retry = await post({ clientId: CID })
  const retried = await retry.json()
  assert.equal(retried.replayed, true); assert.equal(retried.token, created.token)
  assert.equal(await db.webhook.count(), 1)
  const mismatch = await w.POST(new Request('https://s12.test', { method: 'POST', body: JSON.stringify({ conversationId: 'room', requesterId: 'admin', name: 'Renamed bot', clientId: CID }) }), ctx())
  assert.equal(mismatch.status, 409)

  // Item 9: rotation. The previously copied URL dies with the old token.
  const rotate = extra => token.PATCH(new Request('https://s12.test', { method: 'PATCH', body: JSON.stringify({ requesterId: 'admin', ...extra }) }), ctx('room', created.token))
  const rotated = await rotate({ clientId: CID })
  assert.equal(rotated.status, 200)
  const fresh = await rotated.json()
  assert.equal(fresh.replayed, false)
  assert.notEqual(fresh.token, created.token)
  assert.ok(fresh.rotatedAt)
  assert.equal(fresh.revision >= 1, true)
  // The old ingest URL is dead immediately.
  const oldIngest = await token.POST(new Request('https://s12.test', { method: 'POST', body: JSON.stringify({ content: 'hello' }) }), ctx('room', created.token))
  assert.equal(oldIngest.status, 404)
  // A lost rotation ACK replays the SAME fresh token - no second rotation.
  const retryRotate = await rotate({ clientId: CID })
  const again = await retryRotate.json()
  assert.equal(again.replayed, true); assert.equal(again.token, fresh.token)
  // The fresh URL ingests.
  const newIngest = await token.POST(new Request('https://s12.test', { method: 'POST', body: JSON.stringify({ content: 'hello' }) }), ctx('room', fresh.token))
  assert.equal(newIngest.status, 200)
}))

test('webhook delete with clientId replays the same verdict; non-admin rotation is refused', () => fixture(async (db, bindings) => {
  const { token } = await loadRoutes(bindings)
  await db.webhook.create({ data: { id: 'hook', conversationId: 'room', name: 'Bot', token: 'hook-token', createdBy: 'admin' } })
  const del = extra => token.DELETE(new Request('https://s12.test', { method: 'DELETE', body: JSON.stringify({ requesterId: 'admin', ...extra }) }), ctx())
  const first = await del({ clientId: CID })
  assert.equal(first.status, 200)
  const retry = await del({ clientId: CID })
  const body = await retry.json()
  assert.equal(body.replayed, true); assert.equal(body.deleted, true)
  assert.equal(await db.webhook.count(), 0)
  // Non-admin rotate is refused before any token mint.
  await db.webhook.create({ data: { id: 'hook2', conversationId: 'room', name: 'Bot2', token: 'hook-token2', createdBy: 'admin' } })
  const denied = await token.PATCH(new Request('https://s12.test', { method: 'PATCH', body: JSON.stringify({ requesterId: 'member' }) }), ctx('room', 'hook-token2'))
  assert.equal(denied.status, 403)
  assert.equal((await db.webhook.findUnique({ where: { id: 'hook2' } })).token, 'hook-token2')
}))

// ── item 2: pagination + management revisions ───────────────────────

test('automation lists page by (createdAt,id) keyset and carry the room revision', () => fixture(async (db, bindings) => {
  const { a, single } = await loadRoutes(bindings)
  for (let i = 0; i < 5; i += 1) {
    await db.automation.create({ data: { id: `rule-${i}`, conversationId: 'room', trigger: `t${i}`, reply: 'r', createdById: 'admin', createdAt: new Date(Date.UTC(2026, 0, i + 1)) } })
  }
  const get = query => a.GET(new Request(`https://s12.test/?userId=admin&${query}`), ctx())
  const page1 = await (await get('limit=2')).json()
  assert.equal(page1.automations.length, 2)
  assert.deepEqual(page1.automations.map(r => r.id), ['rule-4', 'rule-3'])
  assert.equal(page1.page.hasMore, true); assert.ok(page1.page.nextCursor)
  assert.equal(page1.revision, 0)
  const page2 = await (await get(`limit=2&cursor=${page1.page.nextCursor}`)).json()
  assert.deepEqual(page2.automations.map(r => r.id), ['rule-2', 'rule-1'])
  const page3 = await (await get(`limit=2&cursor=${page2.page.nextCursor}`)).json()
  assert.deepEqual(page3.automations.map(r => r.id), ['rule-0'])
  assert.equal(page3.page.hasMore, false); assert.equal(page3.page.nextCursor, null)
  const bad = await get('limit=0')
  assert.equal(bad.status, 400)
  const badCursor = await get('limit=2&cursor=garbage')
  assert.equal(badCursor.status, 400)
  // Every accepted mutation bumps the SAME room revision.
  await single.PATCH(new Request('https://s12.test', { method: 'PATCH', body: JSON.stringify({ userId: 'admin', enabled: false }) }), ctx('rule-0'))
  const after = await (await get('limit=2')).json()
  assert.equal(after.revision, 1)
  await single.DELETE(new Request('https://s12.test', { method: 'DELETE', body: JSON.stringify({ userId: 'admin' }) }), ctx('rule-1'))
  const afterDelete = await (await get('limit=2')).json()
  assert.equal(afterDelete.revision, 2)
}))

test('webhook lists page ascending and mint/create bumps the shared room revision', () => fixture(async (db, bindings) => {
  const { w } = await loadRoutes(bindings)
  for (let i = 0; i < 3; i += 1) {
    await db.webhook.create({ data: { id: `hook-${i}`, conversationId: 'room', name: `Hook ${i}`, token: `token-${i}`, createdBy: 'admin', createdAt: new Date(Date.UTC(2026, 0, i + 1)) } })
  }
  const get = query => w.GET(new Request(`https://s12.test/?conversationId=room&requesterId=admin&${query}`), ctx())
  const page1 = await (await get('limit=2')).json()
  assert.deepEqual(page1.webhooks.map(r => r.id), ['hook-0', 'hook-1'])
  assert.equal(page1.page.hasMore, true)
  assert.equal(page1.revision, 0)
  const page2 = await (await get(`limit=2&cursor=${page1.page.nextCursor}`)).json()
  assert.deepEqual(page2.webhooks.map(r => r.id), ['hook-2'])
  assert.equal(page2.page.hasMore, false)
  await w.POST(new Request('https://s12.test', { method: 'POST', body: JSON.stringify({ conversationId: 'room', requesterId: 'admin', name: 'Fourth' }) }), ctx())
  const after = await (await get('limit=2')).json()
  assert.equal(after.revision, 1)
}))

// ── items 3/4: exactly-once execution + notification outbox ─────────

async function loadDelivery(bindings) {
  return load('src/lib/automation-delivery.ts', { ...bindings, messageDeliveryDecision, threadRootAvailable },
    '({createAutomationReply,triggerMatches,markAutomationNotifyDelivered,drainAutomationNotify})')
}

async function seedExecution(db) {
  await db.automation.create({ data: { id: 'rule', conversationId: 'room', trigger: 'Hello', reply: 'Welcome', createdById: 'admin' } })
  await db.message.create({ data: { id: 'source', conversationId: 'room', senderId: 'member', content: 'Hello', kind: 'text', createdAt: new Date() } })
}

test('automation execution is exactly-once per (rule, source) and commits its notification debt atomically', () => fixture(async (db, bindings) => {
  const delivery = await loadDelivery(bindings)
  await seedExecution(db)
  const fire = () => db.$transaction(tx => delivery.createAutomationReply(tx, 'room', 'source', 'Hello'), { isolationLevel: 'Serializable' })
  const first = await fire()
  assert.ok(first); assert.equal(first.viaAutomation, true)
  assert.equal(await db.automationExecution.count(), 1)
  const receipt = await db.automationExecution.findFirst()
  assert.equal(receipt.automationId, 'rule'); assert.equal(receipt.sourceId, 'source'); assert.equal(receipt.messageId, first.id)
  // A crash-race replay of the same evaluation is a no-op.
  assert.equal(await fire(), null)
  assert.equal(await db.message.count({ where: { viaAutomation: true } }), 1)
  // The outbox row was written in the SAME commit.
  const debt = await db.automationNotifyOutbox.findFirst()
  assert.ok(debt); assert.equal(debt.state, 'pending'); assert.equal(debt.messageId, first.id)
}))

test('the outbox drain relays each debt once, retries failures, and settles vanished sources', () => fixture(async (db, bindings) => {
  const delivery = await loadDelivery(bindings)
  await seedExecution(db)
  const fire = () => db.$transaction(tx => delivery.createAutomationReply(tx, 'room', 'source', 'Hello'), { isolationLevel: 'Serializable' })
  const created = await fire()
  let clock = 1_000
  const relays = []
  const notify = async message => { relays.push(message.id) }
  assert.equal(await delivery.drainAutomationNotify(db, notify, () => clock), 1)
  assert.deepEqual(relays, [created.id])
  const debt = await db.automationNotifyOutbox.findFirst()
  assert.equal(debt.state, 'delivered'); assert.ok(debt.deliveredAt)
  // A second drain never double-relays.
  assert.equal(await delivery.drainAutomationNotify(db, notify, () => clock), 0)
  assert.equal(relays.length, 1)

  // Failed relay: row stays pending, attempts climb, next tick delivers.
  await db.automationNotifyOutbox.update({ where: { id: debt.id }, data: { state: 'pending', deliveredAt: null } })
  let failing = true
  const flaky = async () => { if (failing) throw new Error('socket down') }
  clock = 2_000
  assert.equal(await delivery.drainAutomationNotify(db, flaky, () => clock), 0)
  const attempted = await db.automationNotifyOutbox.findFirst()
  assert.equal(attempted.state, 'pending'); assert.equal(attempted.attempts, 2)
  failing = false
  assert.equal(await delivery.drainAutomationNotify(db, flaky, () => clock), 1)
  assert.equal((await db.automationNotifyOutbox.findFirst()).state, 'delivered')

  // A source deleted before delivery is settled without relaying.
  const second = await db.$transaction(tx => delivery.createAutomationReply(tx, 'room', 'source', 'Hello').catch(() => null))
  assert.equal(second, null) // exactly-once keeps this side effect free
  await db.message.update({ where: { id: created.id }, data: { deletedAt: new Date() } })
  await db.automationNotifyOutbox.updateMany({ where: { id: debt.id }, data: { state: 'pending', deliveredAt: null } })
  clock = 3_000
  assert.equal(await delivery.drainAutomationNotify(db, notify, () => clock), 1)
  assert.equal(relays.length, 1)

  // Inline fast path: marking delivered keeps the drain a no-op.
  await db.automationNotifyOutbox.updateMany({ where: { id: debt.id }, data: { state: 'pending', deliveredAt: null } })
  await delivery.markAutomationNotifyDelivered(db, created.id, new Date(4_000))
  const settled = await db.automationNotifyOutbox.findFirst()
  assert.equal(settled.state, 'delivered'); assert.equal(settled.deliveredAt.toISOString(), new Date(4_000).toISOString())
  clock = 5_000
  assert.equal(await delivery.drainAutomationNotify(db, notify, () => clock), 0)
}))

// ── item 5: distributed admission ───────────────────────────────────

test('the webhook rate window is durable on the row and resets with the minute', () => fixture(async (db) => {
  await db.webhook.create({ data: { id: 'hook', conversationId: 'room', name: 'Bot', token: 't', createdBy: 'admin' } })
  const rateWindow = async at => {
    const hook = await db.webhook.findUnique({ where: { id: 'hook' } })
    return db.$transaction(tx => contracts.webhookRateWindow(tx, hook, at))
  }
  const now = new Date(1_700_000_000_000)
  for (let i = 0; i < contracts.WEBHOOK_POSTS_PER_MINUTE; i += 1) {
    const verdict = await rateWindow(now)
    assert.equal(verdict.allowed, true)
  }
  const blocked = await rateWindow(new Date(now.getTime() + 30_000))
  assert.equal(blocked.allowed, false)
  assert.ok(blocked.retryAfter >= 1 && blocked.retryAfter <= 30)
  // A new window admits again.
  const later = await rateWindow(new Date(now.getTime() + 61_000))
  assert.equal(later.allowed, true)
  const row = await db.webhook.findUnique({ where: { id: 'hook' } })
  assert.equal(row.windowPosts, 1)
}))

test('the bot prompt lease admits one evaluation per conversation and reclaims expired leases', () => fixture(async (db) => {
  const now = new Date(1_700_000_000_000)
  const claim = at => db.$transaction(tx => contracts.claimBotPromptLease(tx, 'room', at))
  assert.equal(await claim(now), true)
  assert.equal(await claim(new Date(now.getTime() + 1000)), false)
  // Another instance sees the same refusal (durable, not in-memory).
  assert.equal(await claim(new Date(now.getTime() + 2000)), false)
  // Expired lease is re-claimable - a crashed evaluation cannot wedge the room.
  const late = new Date(now.getTime() + contracts.BOT_LEASE_MS + 5_000)
  assert.equal(await claim(late), true)
  assert.equal(await claim(new Date(late.getTime() + 1000)), false)
  await contracts.releaseBotPromptLease(db, 'room')
  assert.equal(await claim(late), true)
}))

test('rooms past the automation cap cannot grow their evaluation cost', () => fixture(async (db, bindings) => {
  const { a } = await loadRoutes(bindings)
  const cap = contracts.AUTOMATIONS_PER_ROOM_MAX
  const rows = Array.from({ length: cap }, (_, i) => ({
    id: `bulk-${i}`, conversationId: 'room', trigger: `bulk-trigger-${i}`, reply: 'r', createdById: 'admin',
  }))
  await db.automation.createMany({ data: rows })
  const full = await a.POST(new Request('https://s12.test', { method: 'POST', body: JSON.stringify({ userId: 'admin', trigger: 'overflow', reply: 'x' }) }), ctx())
  assert.equal(full.status, 409)
  assert.match((await full.json()).error, /maximum of 100 automations/)
  await db.automation.delete({ where: { id: 'bulk-0' } })
  const fits = await a.POST(new Request('https://s12.test', { method: 'POST', body: JSON.stringify({ userId: 'admin', trigger: 'fits', reply: 'x' }) }), ctx())
  assert.equal(fits.status, 201)
}))

// ── item 6: canonical machine identity ──────────────────────────────

test('machine identity resolves serviceKey first, then handle, then legacy name - and stamps forward', () => fixture(async (db) => {
  // Remove the shared fixture's machine account so this test owns the whole
  // identity space.
  await db.user.deleteMany({ where: { id: 'bot' } })
  // 1. Legacy-only deployment: a display-named bot with no handle or key.
  await db.user.create({ data: { id: 'legacy-bot', name: contracts.BOT_DISPLAY_NAME, createdAt: new Date(1) } })
  const first = await db.$transaction(tx => contracts.canonicalBotId(tx))
  assert.equal(first, 'legacy-bot')
  const stamped = await db.user.findUnique({ where: { id: 'legacy-bot' } })
  assert.equal(stamped.serviceKey, contracts.BOT_SERVICE_KEY)
  assert.equal(stamped.username, contracts.BOT_USERNAME)
  // 2. Every later lookup converges on the SAME row, whatever it looks at.
  assert.equal(await db.$transaction(tx => contracts.canonicalBotId(tx)), 'legacy-bot')
  // 3. A handle-bearing row is adopted by the canonical key when free.
  await db.user.update({ where: { id: 'legacy-bot' }, data: { serviceKey: null, username: null } })
  await db.user.create({ data: { id: 'handle-bot', name: 'Whatever', username: contracts.BOT_USERNAME } })
  assert.equal(await db.$transaction(tx => contracts.canonicalBotId(tx)), 'handle-bot')
  assert.equal((await db.user.findUnique({ where: { id: 'handle-bot' } })).serviceKey, contracts.BOT_SERVICE_KEY)
  // 4. The keyed row wins over both mutable lookups once it exists.
  await db.user.update({ where: { id: 'handle-bot' }, data: { serviceKey: null } })
  await db.user.create({ data: { id: 'keyed-bot', name: 'Renamed Machine', username: 'stale', serviceKey: contracts.BOT_SERVICE_KEY } })
  assert.equal(await db.$transaction(tx => contracts.canonicalBotId(tx)), 'keyed-bot')
}))

test('a deployment with no machine account resolves null (no silent human attribution)', () => fixture(async (db) => {
  await db.user.deleteMany({ where: { id: 'bot' } })
  assert.equal(await db.$transaction(tx => contracts.canonicalBotId(tx)), null)
}))

// ── item 8: AI context revision limits ──────────────────────────────

async function loadBotDelivery(bindings) {
  return load('src/lib/bot-delivery.ts', { ...bindings, messageDeliveryDecision, threadRootAvailable },
    '({prepareAiContext,createBotReply,botReplyContext,AI_CONTEXT_ROW_MAX,AI_CONTEXT_CHAR_BUDGET})')
}

async function seedAi(db) {
  await db.message.create({ data: { id: 'source', conversationId: 'room', senderId: 'member', content: 'Hello', kind: 'text', createdAt: new Date('2026-03-01T00:00:00Z') } })
}

test('AI context honors the row cap and the newest rows win the char budget', () => fixture(async (db, bindings) => {
  const delivery = await loadBotDelivery(bindings)
  await seedAi(db)
  // 45 rows: row cap is 40, oldest 5 must be dropped.
  for (let i = 0; i < 45; i += 1) {
    await db.message.create({ data: { id: `m-${String(i).padStart(2, '0')}`, conversationId: 'room', senderId: 'member', content: `filler ${i}`, kind: 'text', createdAt: new Date(Date.UTC(2026, 1, 1, 0, i)) } })
  }
  const capped = await db.$transaction(tx => delivery.prepareAiContext(tx, 'room', 'bot', { id: 'source', senderId: 'member', content: 'Hello' }))
  const lines = capped.transcript.split('\n')
  assert.ok(lines.length <= delivery.AI_CONTEXT_ROW_MAX, `row cap respected (${lines.length})`)
  assert.ok(capped.transcript.includes('filler 44'), 'newest filler rows survive the cap')
  assert.ok(!capped.transcript.includes('filler 0\n'), 'the oldest rows fall off first')

  // Char budget: 20 fresh 500-char rows overflow the 6000 budget.
  await db.message.deleteMany({ where: { id: { startsWith: 'm-' } } })
  for (let i = 0; i < 20; i += 1) {
    await db.message.create({ data: { id: `big-${String(i).padStart(2, '0')}`, conversationId: 'room', senderId: 'member', content: `b${i}-`.padEnd(500, 'x'), kind: 'text', createdAt: new Date(Date.UTC(2026, 2, 1, 0, i)) } })
  }
  await db.message.update({ where: { id: 'source' }, data: { createdAt: new Date('2026-03-01T01:00:00Z') } })
  const budgeted = await db.$transaction(tx => delivery.prepareAiContext(tx, 'room', 'bot', { id: 'source', senderId: 'member', content: 'Hello' }))
  assert.equal(budgeted.truncated, true, 'the char budget reports truncation')
  assert.ok(budgeted.transcript.length <= delivery.AI_CONTEXT_CHAR_BUDGET + 20, `transcript stays within budget (${budgeted.transcript.length})`)
  assert.ok(budgeted.transcript.includes('Hello'), 'the trigger source itself is never budgeted away')
}))

test('an edit revision to any included context row invalidates the fingerprint before commit', () => fixture(async (db, bindings) => {
  const delivery = await loadBotDelivery(bindings)
  await seedAi(db)
  await db.message.create({ data: { id: 'older', conversationId: 'room', senderId: 'member', content: 'original', kind: 'text', createdAt: new Date('2026-02-28T00:00:00Z') } })
  const prepared = await db.$transaction(tx => delivery.prepareAiContext(tx, 'room', 'bot', { id: 'source', senderId: 'member', content: 'Hello' }))
  // Content revision...
  await db.message.update({ where: { id: 'older' }, data: { content: 'revised' } })
  const afterContent = await db.$transaction(tx => delivery.prepareAiContext(tx, 'room', 'bot', { id: 'source', senderId: 'member', content: 'Hello' }))
  assert.notEqual(afterContent.fingerprint, prepared.fingerprint)
  // ...and a pure editedAt revision (the edit route stamps it) moves the
  // fingerprint even without a content change.
  await db.message.update({ where: { id: 'older' }, data: { editedAt: new Date('2026-03-02T00:00:00Z') } })
  const afterRevision = await db.$transaction(tx => delivery.prepareAiContext(tx, 'room', 'bot', { id: 'source', senderId: 'member', content: 'Hello' }))
  assert.notEqual(afterRevision.fingerprint, prepared.fingerprint)
}))

// ── item 7 (server half): automation origin rides the wire envelope ──

test('the automation reply message carries viaAutomation and reserved origin payload for every renderer', () => fixture(async (db, bindings, events) => {
  const delivery = await loadDelivery(bindings)
  await seedExecution(db)
  const reply = await db.$transaction(tx => delivery.createAutomationReply(tx, 'room', 'source', 'Hello'), { isolationLevel: 'Serializable' })
  assert.equal(reply.viaAutomation, true)
  const mapped = bindings.mapMessage(reply)
  assert.equal(mapped.viaAutomation, true)
  const relay = await load('src/app/api/conversations/[id]/messages/route.ts', { ...bindings, createAutomationReply: delivery.createAutomationReply }, 'maybeAutomationReply')
  await relay('room', 'source', 'Hello')
  assert.equal(events.length, 0, 'exactly-once blocks the duplicate relay for a spent source')
}))
