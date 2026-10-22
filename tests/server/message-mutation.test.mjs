import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { mutateMessage } from '../../src/lib/message-mutation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = new Date('2026-10-21T00:00:00Z')
const fields = { id: true, content: true, deletedAt: true, viewedAt: true, viewedBy: true, pinnedAt: true }
const read = (tx, id) => tx.message.findUniqueOrThrow({ where: { id }, select: fields })
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-mutation-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of [
      `CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT, senderId TEXT, content TEXT, parentId TEXT,
        expiresAt DATETIME, deletedAt DATETIME, editedAt DATETIME, pinnedAt DATETIME, pinnedBy TEXT,
        viewOnce BOOLEAN NOT NULL DEFAULT false, viewedAt DATETIME, viewedBy TEXT)`,
      `CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, UNIQUE(userId,conversationId))`,
      `CREATE TABLE Reaction(id TEXT PRIMARY KEY, messageId TEXT, userId TEXT, emoji TEXT, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        UNIQUE(messageId,userId,emoji))`,
      `INSERT INTO ConversationParticipant VALUES ('a','author','room'),('b','peer','room'),('c','other','room')`,
      `INSERT INTO Message(id,conversationId,senderId,content,viewOnce) VALUES ('root','room','author','secret',true)`,
    ]) await db.$executeRawUnsafe(sql)
    await run(db, (owner, action, id = 'root', callback = read) => mutateMessage(db, id, owner, action, callback, () => now))
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
test('departed author cannot edit/delete; tombstone replay still checks owner and membership', () => fixture(async (db, act) => {
  await act('author', { kind: 'delete' })
  assert.equal((await act('author', { kind: 'delete' })).changed, false)
  await assert.rejects(act('peer', { kind: 'delete' }), { status: 403 })
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId = 'author'`
  for (const action of [{ kind: 'delete' }, { kind: 'edit', content: 'revived' }]) {
    await assert.rejects(act('author', action), { status: 403 })
  }
}))
test('deletion unpins and cannot be undone by an edit or reaction', () => fixture(async (db, act) => {
  await db.$executeRaw`UPDATE Message SET pinnedAt = ${now}, pinnedBy = 'peer' WHERE id = 'root'`
  const removed = await act('author', { kind: 'delete' })
  assert.equal(removed.message.pinnedAt, null)
  await assert.rejects(act('author', { kind: 'edit', content: 'revived' }), { status: 410 })
  await assert.rejects(act('peer', { kind: 'react', emoji: 'heart' }), { status: 410 })
  assert.equal((await read(db, 'root')).content, 'secret') // stored history is not a public payload
}))
test('expired messages and expired/deleted/foreign/nested parents refuse mutations', () => fixture(async (db, act) => {
  await db.$executeRaw`UPDATE Message SET expiresAt = ${now} WHERE id = 'root'`
  await assert.rejects(act('peer', { kind: 'view' }), { status: 410 })
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,content,parentId) VALUES ('reply','room','author','reply','root')`
  for (const patch of ["expiresAt = NULL, deletedAt = CURRENT_TIMESTAMP", "deletedAt = NULL, conversationId = 'foreign'",
    "conversationId = 'room', parentId = 'nested'", "parentId = NULL, expiresAt = 0"]) {
    await db.$executeRawUnsafe(`UPDATE Message SET ${patch} WHERE id = 'root'`)
    await assert.rejects(act('author', { kind: 'edit', content: 'no' }, 'reply'), { status: 410 })
  }
}))
test('view-once first consumer is immutable and denied requests do not burn it', () => fixture(async (db, act) => {
  await assert.rejects(act('stranger', { kind: 'view' }), { status: 403 })
  await assert.rejects(act('author', { kind: 'view' }), { status: 400 })
  assert.equal((await read(db, 'root')).viewedAt, null)
  assert.equal((await act('peer', { kind: 'view' })).changed, true)
  const replay = await act('other', { kind: 'view' })
  assert.equal(replay.changed, false)
  assert.equal(replay.message.viewedBy, 'peer')
  assert.equal(replay.message.viewedAt.toISOString(), now.toISOString())
}))
test('reaction toggles are atomic and a failed snapshot rolls back the write', () => fixture(async (db, act) => {
  await act('peer', { kind: 'react', emoji: 'heart' })
  assert.equal(await db.reaction.count(), 1)
  await assert.rejects(act('peer', { kind: 'react', emoji: 'heart' }, 'root', async () => { throw Error('fault') }), /fault/)
  assert.equal(await db.reaction.count(), 1)
  await act('peer', { kind: 'react', emoji: 'heart' })
  assert.equal(await db.reaction.count(), 0)
}))
test('simultaneous consumers have at most one winner; busy requests cannot replace it', () => fixture(async (db, act) => {
  const attempts = await Promise.allSettled(['peer','other'].map(owner => act(owner, { kind: 'view' })))
  for (const attempt of attempts) if (attempt.status === 'rejected') assert.equal(attempt.reason.status, 409)
  assert.ok(attempts.filter(a => a.status === 'fulfilled' && a.value.changed).length <= 1)
  const retry = await act('peer', { kind: 'view' })
  const winner = retry.message.viewedBy
  assert.equal((await act('other', { kind: 'view' })).message.viewedBy, winner)
}))

test('deleted wire rows and unavailable quotes disclose no original body or attachment', async () => {
  const { redactDeletedMessage, quoteDisclosure } = await import('../../src/lib/message-disclosure.ts')
  const live = { id: 'm', deletedAt: null, content: 'secret', imagePath: 'private.jpg', payload: 'secret',
    replyTo: { content: 'quote' }, translations: [{ text: 'translated secret' }], poll: { question: 'secret' } }
  assert.equal(redactDeletedMessage(live), live)
  const dead = redactDeletedMessage({ ...live, deletedAt: now.toISOString() })
  assert.equal(dead.id, 'm')
  assert.equal(dead.content, '')
  for (const key of ['imagePath','payload','replyTo','poll','transcript','filePath','linkPreview']) assert.equal(dead[key], null)
  assert.deepEqual(dead.translations, [])
  const parent = { content: 'secret caption', deletedAt: null, expiresAt: null, viewOnce: false }
  assert.deepEqual(quoteDisclosure(parent, now), { content: 'secret caption', deleted: false })
  assert.deepEqual(quoteDisclosure({ ...parent, deletedAt: now }, now), { content: '', deleted: true })
  assert.deepEqual(quoteDisclosure({ ...parent, expiresAt: now }, now), { content: '', deleted: true })
  assert.deepEqual(quoteDisclosure({ ...parent, viewOnce: true }, now), { content: 'View-once message', deleted: false })
})

test('explicit reaction add/remove can be replayed after a lost acknowledgement without toggling', () => fixture(async (db, act) => {
  for (const present of [true, false]) {
    assert.equal((await act('peer', { kind: 'react', emoji: 'heart', present })).changed, true)
    for (let retry = 0; retry < 3; retry++) {
      assert.equal((await act('peer', { kind: 'react', emoji: 'heart', present })).changed, false)
      assert.equal(await db.reaction.count(), present ? 1 : 0)
    }
  }
}))
test('explicit reaction state changes only that owner and emoji and rolls back a failed acknowledgement', () => fixture(async (db, act) => {
  await act('peer', { kind: 'react', emoji: 'heart', present: true })
  await act('other', { kind: 'react', emoji: 'heart', present: true })
  await act('peer', { kind: 'react', emoji: 'laugh', present: true })
  await assert.rejects(act('peer', { kind: 'react', emoji: 'heart', present: false }, 'root', async () => { throw Error('fault') }), /fault/)
  assert.equal(await db.reaction.count(), 3)
  await act('peer', { kind: 'react', emoji: 'heart', present: false })
  assert.equal(await db.reaction.count(), 2)
  assert.equal(await db.reaction.count({ where: { userId: 'other', emoji: 'heart' } }), 1)
  assert.equal(await db.reaction.count({ where: { userId: 'peer', emoji: 'laugh' } }), 1)
}))
test('even an unchanged reaction retry checks current membership and message lifetime', () => fixture(async (db, act) => {
  await act('peer', { kind: 'react', emoji: 'heart', present: true })
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId = 'peer'`
  await assert.rejects(act('peer', { kind: 'react', emoji: 'heart', present: true }), { status: 403 })
  await act('author', { kind: 'delete' })
  await assert.rejects(act('other', { kind: 'react', emoji: 'heart', present: false }), { status: 410 })
}))
test('reaction state route refuses missing/nonboolean state and never invokes a legacy toggle', async () => {
  const { readFile } = await import('node:fs/promises')
  const { stripTypeScriptTypes } = await import('node:module')
  const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/messages/[id]/react-state/route.ts', import.meta.url), 'utf8'))
    .replace(/^import .*$/gm, '').replace(/export /g, '')
  const calls = [], fanouts = []
  const dependencies = {
    NextResponse: { json: Response.json }, db: {},
    mutateMessage: async (_, id, owner, action) => {
      calls.push({ id, owner, action }); return { message: { id }, conversationId: 'room', changed: action.present }
    }, MessageMutationError: Error, mapMessage: m => m, memberIdsOf: async () => ['peer'],
    notifySocket: async (...args) => { fanouts.push(args) }, MESSAGE_FULL_INCLUDE: {}, REACTION_EMOJIS: ['heart'],
    safeJson: req => req.json(), strField: value => typeof value === 'string' ? value.trim() : '',
    bearerToken: req => req.headers.get('Authorization'), requireActionOwner: async () => Response.json({}, { status: 403 }),
  }
  const post = new Function(...Object.keys(dependencies), source + '; return POST')(...Object.values(dependencies))
  const request = (present, authorization) => post(new Request('https://reaction.test', { method: 'POST',
    headers: authorization ? { Authorization: authorization } : {}, body: JSON.stringify({ userId: 'peer', emoji: 'heart', present }) }), { params: Promise.resolve({ id: 'root' }) })
  for (const present of [undefined, null, 'true', 1, [], {}]) assert.equal((await request(present)).status, 400)
  assert.equal((await request(true, 'Bearer wrong-owner')).status, 403)
  assert.equal(calls.length, 0)
  for (const present of [true, false]) {
    const response = await request(present)
    assert.equal(response.status, 200)
    assert.deepEqual((await response.json()).reaction, { messageId: 'root', emoji: 'heart', present })
    assert.deepEqual(calls.at(-1), { id: 'root', owner: 'peer', action: { kind: 'react', emoji: 'heart', present } })
  }
  assert.equal(fanouts.length, 1)
})

test('explicit reaction state removes legacy aliases as one owned logical reaction', () => fixture(async (db, act) => {
  for (const [userId, emoji] of [['peer', '\u2764'], ['peer', 'heart'], ['peer', '\u2665\uFE0F'], ['other', '\u2764'], ['peer', 'fire']]) {
    await db.reaction.create({ data: { messageId: 'root', userId, emoji } })
  }
  await act('peer', { kind: 'react', emoji: 'heart', present: false })
  assert.deepEqual((await db.reaction.findMany({ where: { userId: 'peer' } })).map(r => r.emoji), ['fire'])
  assert.equal(await db.reaction.count({ where: { userId: 'other' } }), 1)
  assert.equal((await act('peer', { kind: 'react', emoji: 'heart', present: false })).changed, false)
}))
test('adding a legacy reaction converges to one canonical row and failed normalization rolls back', () => fixture(async (db, act) => {
  for (const emoji of ['\u{1F525}', '\u{1F525}\uFE0F']) await db.reaction.create({ data: { messageId: 'root', userId: 'peer', emoji } })
  const before = await db.reaction.findMany()
  await assert.rejects(act('peer', { kind: 'react', emoji: 'fire', present: true }, 'root', async () => { throw Error('fault') }), /fault/)
  assert.deepEqual(await db.reaction.findMany(), before)
  await act('peer', { kind: 'react', emoji: 'fire', present: true })
  const rows = await db.reaction.findMany()
  assert.equal(rows.length, 1); assert.equal(rows[0].emoji, 'fire')
  assert.equal((await act('peer', { kind: 'react', emoji: 'fire', present: true })).changed, false)
  await act('peer', { kind: 'react', emoji: 'fire' }) // legacy toggle still toggles the whole displayed group
  assert.equal(await db.reaction.count(), 0)
}))

test('pin validates membership and message/thread lifetime in its write transaction', () => fixture(async (db, act) => {
  await assert.rejects(act('stranger', { kind: 'pin' }), { status: 403 })
  await db.$executeRaw`UPDATE Message SET expiresAt = ${now} WHERE id = 'root'`
  await assert.rejects(act('peer', { kind: 'pin' }), { status: 410 })
  await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,content,parentId) VALUES ('reply','room','author','reply','root')`
  await assert.rejects(act('peer', { kind: 'pin' }, 'reply'), { status: 410 })
  await db.$executeRaw`UPDATE Message SET expiresAt = NULL, deletedAt = ${now} WHERE id = 'root'`
  await assert.rejects(act('peer', { kind: 'pin' }), { status: 410 })
  await assert.rejects(act('peer', { kind: 'pin' }, 'reply'), { status: 410 })
}))
test('pin snapshot failures roll back; explicit removal can clean expired/deleted pins and replay safely', () => fixture(async (db, act) => {
  await assert.rejects(act('peer', { kind: 'pin' }, 'root', async () => { throw Error('snapshot fault') }), /snapshot fault/)
  assert.equal((await read(db, 'root')).pinnedAt, null)
  assert.equal((await act('peer', { kind: 'pin' })).message.pinnedAt.toISOString(), now.toISOString())
  await db.$executeRaw`UPDATE Message SET deletedAt = ${now}, expiresAt = ${now} WHERE id = 'root'`
  assert.equal((await act('peer', { kind: 'pin', present: false })).message.pinnedAt, null)
  assert.equal((await act('peer', { kind: 'pin', present: false })).changed, false)
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId = 'peer'`
  await assert.rejects(act('peer', { kind: 'pin', present: false }), { status: 403 })
}))
test('pin and delete cannot commit a pinned tombstone even when requests overlap', () => fixture(async (db, act) => {
  const results = await Promise.allSettled([act('peer', { kind: 'pin' }), act('author', { kind: 'delete' })])
  for (const result of results) if (result.status === 'rejected') assert.ok([409, 410].includes(result.reason.status))
  await act('author', { kind: 'delete' })
  await assert.rejects(act('peer', { kind: 'pin' }), { status: 410 })
  assert.equal((await read(db, 'root')).pinnedAt, null)
}))

test('explicit pin retries retain the first stamp and pinner instead of unpinning or stealing attribution', () => fixture(async (db, act) => {
  assert.equal((await act('peer', { kind: 'pin', present: true })).changed, true)
  const first = await db.message.findUniqueOrThrow({ where: { id: 'root' }, select: { pinnedAt: true, pinnedBy: true } })
  for (const owner of ['peer', 'peer', 'other']) {
    assert.equal((await act(owner, { kind: 'pin', present: true })).changed, false)
    assert.deepEqual(await db.message.findUniqueOrThrow({ where: { id: 'root' }, select: { pinnedAt: true, pinnedBy: true } }), first)
  }
  assert.equal((await act('peer', { kind: 'pin', present: false })).changed, true)
  assert.equal((await act('peer', { kind: 'pin', present: false })).changed, false)
}))

test('versioned pin route requires boolean state and returns the explicit action receipt', async () => {
  const { readFile } = await import('node:fs/promises')
  const { stripTypeScriptTypes } = await import('node:module')
  const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/messages/[id]/pin-state/route.ts', import.meta.url), 'utf8'))
    .replace(/^import .*$/gm, '').replace(/export /g, '')
  const calls = [], fanouts = []
  const dependencies = {
    NextResponse: { json: Response.json }, db: {},
    mutateMessage: async (_, id, owner, action) => {
      calls.push({ id, owner, action }); return { message: { id }, conversationId: 'room', changed: true }
    }, MessageMutationError: Error, mapMessage: m => m, memberIdsOf: async () => ['peer', 'other'],
    notifySocket: async (...args) => { fanouts.push(args) }, MESSAGE_FULL_INCLUDE: {},
    safeJson: req => req.json(), strField: value => typeof value === 'string' ? value.trim() : '',
    requireActionOwner: async () => Response.json({}, { status: 403 }),
  }
  const post = new Function(...Object.keys(dependencies), source + '; return POST')(...Object.values(dependencies))
  const request = (pinned, authorization) => post(new Request('https://pin.test', { method: 'POST',
    headers: authorization ? { Authorization: authorization } : {}, body: JSON.stringify({ userId: 'peer', pinned }) }), { params: Promise.resolve({ id: 'root' }) })
  for (const pinned of [undefined, null, 'true', 1, [], {}]) assert.equal((await request(pinned)).status, 400)
  assert.equal((await request(true, 'Bearer wrong-owner')).status, 403)
  assert.equal(calls.length, 0)
  for (const pinned of [true, false]) {
    const response = await request(pinned)
    assert.equal(response.status, 200)
    assert.deepEqual((await response.json()).pin, { messageId: 'root', pinned })
    assert.deepEqual(calls.at(-1), { id: 'root', owner: 'peer', action: { kind: 'pin', present: pinned } })
    assert.deepEqual(fanouts.at(-1)[1], ['peer', 'other']) // actor devices too
  }
})
