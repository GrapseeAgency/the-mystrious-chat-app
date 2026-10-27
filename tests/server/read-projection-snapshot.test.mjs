import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { randomInt } from 'node:crypto'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { privacyPreferenceEnabled } from '../../src/lib/privacy-preferences.ts'
import { normalizedReactionGroups } from '../../src/lib/reaction-contract.ts'
import { redactDeletedMessage, quoteSnippet } from '../../src/lib/message-disclosure.ts'
import * as visibility from '../../src/lib/message-visibility.ts'
import { captureReadIntent, setManualUnread, advanceReadReceipt } from '../../src/lib/read-receipt.ts'
import { createConversationOnce, ConversationCreationError } from '../../src/lib/conversation-creation.ts'
import { openDirectConversation, openSelfConversation, DirectConversationError } from '../../src/lib/direct-conversation.ts'
import { channelIdentity, normalizeChannelPhoto } from '../../src/lib/channel-contract.ts'
const { PrismaClient, Prisma } = createRequire(import.meta.url)('../../prisma/generated-client')

async function load(file, bindings, expression) {
  const source = (await readFile(new URL(`../../${file}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '')
    .replace(/^export \{[^}]+\} from [^\n]+$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(source, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}
function barrier() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
async function fixture(run) {
  const directory = await mkdtemp(path.join(tmpdir(), 'read-projection-'))
  const datasourceUrl = `file:${path.join(directory, 'test.sqlite')}`
  const db = new PrismaClient({ datasourceUrl }), writer = new PrismaClient({ datasourceUrl })
  try {
    // Real generated Prisma delegates and SQL, including every relation used by
    // the production serializer. No substitute unread/count implementation.
    for (const model of Prisma.dmmf.datamodel.models) {
      const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
        const type = ['Int', 'Boolean', 'BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'Float' ? 'REAL' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
        let def = ''
        if (typeof f.default === 'string') def = ` DEFAULT '${f.default.replaceAll("'", "''")}'`
        else if (typeof f.default === 'number' || typeof f.default === 'boolean') def = ` DEFAULT ${Number(f.default)}`
        else if (f.default?.name === 'now') def = ' DEFAULT CURRENT_TIMESTAMP'
        return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : f.isUnique ? ' UNIQUE' : ''}${def}`
      })
      if (model.primaryKey) columns.push(`PRIMARY KEY (${model.primaryKey.fields.map(f => `"${f}"`).join(',')})`)
      for (const fields of model.uniqueFields) columns.push(`UNIQUE (${fields.map(f => `"${f}"`).join(',')})`)
      await db.$executeRawUnsafe(`CREATE TABLE "${model.name}" (${columns.join(',')})`)
    }
    await db.$executeRawUnsafe('CREATE TABLE IF NOT EXISTS ConversationCreationOperation(userId TEXT, clientId TEXT, requestHash TEXT, conversationId TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(userId,clientId))')
    for (const id of ['reader', 'peer', 'third']) await db.user.create({ data: { id, name: id, color: 'amber' } })
    await db.conversation.create({ data: { id: 'room', participants: { create: ['reader', 'peer'].map(userId => ({
      id: `${userId}-membership`, userId, joinedAt: new Date(0), lastReadAt: new Date(0),
    })) } } })
    await db.message.create({ data: { id: 'message', conversationId: 'room', senderId: 'peer', content: 'Hello', createdAt: new Date(1000) } })
    const intent = (await captureReadIntent(db, 'room', 'reader')).readIntent
    let afterRows = async () => {}, failTransaction = null
    const scopedDB = {
      $transaction: (work, options) => {
        if (failTransaction) throw failTransaction
        assert.equal(options.isolationLevel, 'Serializable')
        return db.$transaction(tx => work(new Proxy(tx, { get(target, key) {
          if (key === 'conversation') return new Proxy(target.conversation, { get(delegate, operation) {
            if (operation === 'findMany') return async args => {
              assert.deepEqual(args.where, { participants: { some: { userId: 'reader' } } })
              const rows = await delegate.findMany(args)
              await afterRows()
              return rows
            }
            return delegate[operation]
          } })
          return target[key]
        } })), options)
      },
    }
    // Fail if any route/serializer query escapes the authorized transaction.
    const guardedDB = new Proxy(scopedDB, { get(target, key) {
      assert.equal(key, '$transaction', `Projection escaped its snapshot through ${String(key)}`)
      return target[key]
    } })
    const serializers = await load('src/lib/serializers.ts', {
      path, randomInt, db: guardedDB, ...visibility, privacyPreferenceEnabled,
      normalizedReactionGroups, redactDeletedMessage, quoteSnippet,
    }, '({buildConversationSummary,buildConversationDetail,CONVERSATION_FULL_INCLUDE})')
    const deps = { ...serializers, db: guardedDB, NextResponse: Response,
      requireActionOwner: async (_, userId) => userId === 'reader' ? null : Response.json({}, { status: 403 }) }
    const list = await load('src/app/api/conversations/route.ts', deps, 'GET')
    const detail = await load('src/app/api/conversations/[id]/route.ts', deps, 'GET')
    const inbox = () => list(new Request('https://pulse.test/api/conversations?userId=reader'))
    const room = () => detail(new Request('https://pulse.test/api/conversations/room?userId=reader'), { params: Promise.resolve({ id: 'room' }) })
    await run({ db, writer, intent, serializers, inbox, room,
      pause: hook => { afterRows = hook }, fail: error => { failTransaction = error } })
  } finally {
    await Promise.all([db.$disconnect(), writer.$disconnect()])
    await rm(directory, { recursive: true, force: true })
  }
}

test('real inbox serializer does not combine an old manual marker with a later read aggregate', () => fixture(async f => {
  const oldRows = await f.db.conversation.findFirst({ where: { id: 'room' }, include: f.serializers.CONVERSATION_FULL_INCLUDE })
  const initial = (await (await f.inbox()).json()).conversations[0]
  assert.equal(initial.myManualUnread, false); assert.equal(initial.unreadCount, 1)
  // Delayed progress belongs to the OLD intention and cannot clear the NEW dot.
  await setManualUnread(f.writer, 'room', 'reader', true, f.intent)
  await advanceReadReceipt(f.writer, 'room', 'reader', 'message', f.intent)
  // Reproduce the old split-read failure with actual SQL: this tuple never
  // existed in the database, and would incorrectly publish a zero badge.
  const split = await f.serializers.buildConversationSummary(oldRows, 'reader', f.db)
  assert.equal(split.myManualUnread, false); assert.equal(split.unreadCount, 0)
  const response = await f.inbox()
  assert.equal(response.headers.get('cache-control'), 'private, no-store')
  const current = (await response.json()).conversations[0]
  assert.equal(current.myManualUnread, true); assert.equal(current.unreadCount, 0)
  assert.notEqual(current.members.find(m => m.id === 'reader').readIntent, f.intent)
  const detail = (await (await f.room()).json()).conversation
  assert.equal(detail.myManualUnread, true)
  assert.equal(detail.members.find(m => m.id === 'reader').readIntent, current.members.find(m => m.id === 'reader').readIntent)
  await f.db.conversationParticipant.delete({ where: { id: 'reader-membership' } })
  assert.deepEqual((await (await f.inbox()).json()).conversations, [])
  assert.equal((await f.room()).status, 404)
}))

test('a second database connection cannot split an in-flight inbox projection', () => fixture(async f => {
  const selected = barrier(), resume = barrier()
  f.pause(async () => { selected.resolve(); await resume.promise })
  const response = f.inbox()
  await Promise.race([selected.promise, response.then(() => { throw new Error('Inbox completed without its snapshot barrier') })])
  const mutation = (async () => {
    await setManualUnread(f.writer, 'room', 'reader', true, f.intent)
    await advanceReadReceipt(f.writer, 'room', 'reader', 'message', f.intent)
  })()
  // Give the competing connection a chance to attempt its write while the
  // response has metadata but has not executed the unread SQL yet.
  await new Promise(done => setTimeout(done, 20))
  resume.resolve()
  const before = (await (await response).json()).conversations[0]
  assert.equal(before.myManualUnread, false); assert.equal(before.unreadCount, 1)
  await mutation
  const after = (await (await f.inbox()).json()).conversations[0]
  assert.equal(after.myManualUnread, true); assert.equal(after.unreadCount, 0)
}))

test('a failed snapshot cannot be converted into a successful empty inbox or a missing room', () => fixture(async f => {
  const failure = Object.assign(new Error('snapshot busy'), { code: 'P2028' })
  f.fail(failure)
  await assert.rejects(f.inbox, error => error === failure)
  await assert.rejects(f.room, error => error === failure)
}))


test('direct, group, notebook and channel responses serialize inside their authorized read transaction', () => fixture(async f => {
  let active = false, projected = 0
  const db = new Proxy(f.db, { get(target, key) {
    if (key === '$transaction') return (work, options) => target.$transaction(async tx => {
      assert.equal(active, false)
      active = true
      try { return await work(tx) } finally { active = false }
    }, options)
    return target[key]
  } })
  const bindings = { db, NextResponse: Response, ...f.serializers,
    buildConversationSummary: async (row, viewer, tx) => {
      assert.equal(active, true)
      assert.equal(tx.$transaction, undefined, 'No global client may escape the read snapshot')
      const result = await f.serializers.buildConversationSummary(row, viewer, tx)
      assert.equal(active, true)
      projected++
      return result
    },
    createConversationOnce, ConversationCreationError, openDirectConversation, openSelfConversation, DirectConversationError,
    channelIdentity, normalizeChannelPhoto, GROUP_NAME_MAX: 48,
    requireActionOwner: async (_, owner) => { assert.equal(owner, 'reader'); return null },
    bearerToken: () => 'synthetic-reader', safeJson: req => req.json(),
    strField: value => typeof value === 'string' ? value.trim() : '',
    notifySocket: async () => { assert.equal(active, false, 'Network fanout must not hold a database transaction') },
  }
  for (const [file, body] of [
    ['conversations', { creatorId: 'reader', memberIds: ['peer'] }],
    ['conversations', { creatorId: 'reader', memberIds: ['peer', 'third'], isGroup: true, name: 'Three', clientId: 'group-snapshot-create' }],
    ['conversations/self', { userId: 'reader' }],
    ['channels', { userId: 'reader', name: 'Channel', clientId: 'channel-snapshot-create' }],
  ]) {
    const post = await load(`src/app/api/${file}/route.ts`, bindings, 'POST')
    const response = await post(new Request(`https://pulse.test/api/${file}`, { method: 'POST', body: JSON.stringify(body) }))
    assert.ok([200, 201].includes(response.status), `${file}: ${response.status}`)
    const result = await response.json()
    assert.ok(result.conversation.id)
    assert.ok(result.conversation.members.some(m => m.id === 'reader'))
    assert.equal(typeof result.conversation.myManualUnread, 'boolean')
    assert.equal(typeof result.conversation.unreadCount, 'number')
  }
  assert.equal(projected, 4)
}))
