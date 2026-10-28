import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import * as operations from '../../src/lib/message-operation.ts'
import { messageDeliveryDecision } from '../../src/lib/scheduled-delivery.ts'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
const { Prisma, PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path, bindings, expression) {
  const source = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8')).replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(source, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}
async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'poll-outbox-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(directory, 'db.sqlite')}` })
  try {
    for (const name of ['User', 'Conversation', 'ConversationParticipant', 'Message', 'UserBlock', 'Poll', 'PollOption', 'PollVote']) {
      const model = Prisma.dmmf.datamodel.models.find(m => m.name === name)
      const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
        const type = ['Int', 'Boolean', 'BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
        const def = typeof f.default === 'string' ? ` DEFAULT '${f.default.replaceAll("'", "''")}'` : typeof f.default === 'number' || typeof f.default === 'boolean' ? ` DEFAULT ${Number(f.default)}` : f.default?.name === 'now' ? ' DEFAULT CURRENT_TIMESTAMP' : ''
        return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : f.isUnique ? ' UNIQUE' : ''}${def}`
      })
      await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
    }
    for (const sql of (await readFile(new URL('../../prisma/patches/20261019_message_send_operation.sql', import.meta.url), 'utf8')).split(';').filter(x => x.trim())) await db.$executeRawUnsafe(sql)
    await db.user.create({ data: { id: 'me', name: 'Me', sessionTokenHash: hashSessionToken('synthetic-me'), legacyTokenHashes: '[]' } })
    await db.conversation.create({ data: { id: 'room', isGroup: true, ttlSeconds: 3600 } })
    await db.conversationParticipant.create({ data: { userId: 'me', conversationId: 'room', role: 'member' } })
    const requireActionOwner = await load('src/lib/wallet-authorization.ts', { db, bearerToken, tokenAccepted }, 'requireActionOwner')
    const events = []
    const bindings = { db, ...operations, messageDeliveryDecision, requireActionOwner, NextResponse: Response,
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '', MESSAGE_MAX: 2000,
      MESSAGE_FULL_INCLUDE: { poll: { include: { options: { orderBy: { position: 'asc' } } } } },
      mapMessage: r => r, memberIdsOf: async () => ['me', 'peer'], notifySocket: async (...args) => events.push(args),
      nativeBlockFromPayload: () => null, deliverMessageNotification: async () => false, fanoutPush: async () => {},
      dayKey: date => date.toISOString().slice(0, 10), XP_DAILY_CAP: 0, console: { error: () => {} } }
    const POST = await load('src/app/api/conversations/[id]/messages/route.ts', bindings, 'POST')
    const legacy = await load('src/app/api/conversations/[id]/poll/route.ts', { ...bindings, createMessage: POST }, 'POST')
    const send = (extra = {}, token = 'synthetic-me', old = false) => {
      const body = { senderId: 'me', clientId: 'poll-operation-000001', kind: 'poll', content: 'Choose', payload: { question: 'Choose', options: ['One', 'Two'] }, ...extra }
      return (old ? legacy : POST)(new Request('https://poll.test/api', { method: 'POST', headers: token ? { authorization: `Bearer ${token}` } : {}, body: JSON.stringify(old ? { ...body, question: body.content, options: body.payload.options } : body) }), { params: Promise.resolve({ id: 'room' }) })
    }
    await run({ db, send, events })
  } finally { await db.$disconnect(); await rm(directory, { recursive: true, force: true }) }
}
test('poll replay across legacy and outbox routes retains one message, poll, options and receipt', () => fixture(async ({ db, send, events }) => {
  const first = await send(); assert.equal(first.status, 201)
  const accepted = (await first.json()).message
  assert.equal(accepted.poll.options.length, 2); assert.ok(accepted.expiresAt)
  const retry = await send({}, 'synthetic-me', true); assert.equal(retry.status, 200)
  assert.equal((await retry.json()).message.id, accepted.id)
  assert.equal(await db.message.count(), 1); assert.equal(await db.poll.count(), 1); assert.equal(await db.pollOption.count(), 2)
  assert.equal(events.length, 1)
  assert.equal((await send({ payload: { question: 'Choose', options: ['One', 'Three'] } })).status, 409)
  assert.equal(await db.poll.count(), 1)
}))
test('poll creation enforces credentials, announcement policy, slow mode, bounds and terminal identity', () => fixture(async ({ db, send }) => {
  assert.equal((await send({}, null)).status, 401)
  await db.conversation.update({ where: { id: 'room' }, data: { broadcastMode: true } })
  assert.equal((await send()).status, 403)
  await db.conversation.update({ where: { id: 'room' }, data: { broadcastMode: false, slowModeSeconds: 300 } })
  assert.equal((await send({ payload: { question: 'Choose', options: ['only one'] } })).status, 400)
  assert.equal((await send({ payload: { question: 'wrong question', options: ['One', 'Two'] } })).status, 400)
  assert.equal((await send()).status, 201)
  assert.equal((await send({ clientId: 'poll-operation-000002' })).status, 429)
  await db.message.deleteMany()
  assert.equal((await send()).status, 410)
  assert.equal(await db.message.count(), 0)
}))
