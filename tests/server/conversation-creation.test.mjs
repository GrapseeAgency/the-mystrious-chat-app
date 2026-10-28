import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { createConversationOnce } from '../../src/lib/conversation-creation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const key = 'creation-operation-0001'
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-creation-'))
  const datasourceUrl = `file:${join(dir, 'test.sqlite')}`
  const db = new PrismaClient({ datasourceUrl })
  try {
    for (const sql of [
      `CREATE TABLE Conversation(id TEXT PRIMARY KEY, isGroup BOOLEAN NOT NULL DEFAULT false, name TEXT,
        description TEXT NOT NULL DEFAULT '', photo TEXT, inviteCode TEXT, ttlSeconds INTEGER NOT NULL DEFAULT 0,
        broadcastMode BOOLEAN NOT NULL DEFAULT false, screenPrivacy BOOLEAN NOT NULL DEFAULT false,
        slowModeSeconds INTEGER NOT NULL DEFAULT 0, isSelf BOOLEAN NOT NULL DEFAULT false, appKey TEXT,
        createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updatedAt DATETIME NOT NULL)`,
      `CREATE TABLE ConversationParticipant(draftRevision TEXT NOT NULL DEFAULT 0, id TEXT PRIMARY KEY, userId TEXT NOT NULL, conversationId TEXT NOT NULL,
        role TEXT NOT NULL DEFAULT 'member', joinedAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        lastReadAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, pinnedAt DATETIME, mutedUntil DATETIME, archivedAt DATETIME,
        handRaisedAt DATETIME, screenPrivacy BOOLEAN NOT NULL DEFAULT false, manualUnread BOOLEAN NOT NULL DEFAULT false,
        draft TEXT, UNIQUE(userId,conversationId))`,
      'CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT, content TEXT)',
      await readFile(new URL('../../prisma/patches/20261022_conversation_creation_operations.sql', import.meta.url), 'utf8'),
    ]) await db.$executeRawUnsafe(sql)
    await run(db, datasourceUrl)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
const create = (db, owner = 'owner', id = key, kind = 'channel', name = 'Autumn') =>
  createConversationOnce(db, owner, id, kind, [name], async tx => {
    const room = await tx.conversation.create({ data: { name, isGroup: true, broadcastMode: kind === 'channel',
      participants: { create: { userId: owner, role: 'admin' } } }, select: { id: true } })
    await tx.$executeRaw`INSERT INTO Message VALUES (${room.id}, ${room.id}, 'Channel created')`
    return room
  })

test('lost creation ACK replays after reconnect without another room or initial post', () => fixture(async (db, datasourceUrl) => {
  const first = await create(db)
  const reopened = new PrismaClient({ datasourceUrl })
  try {
    const retry = await create(reopened)
    assert.equal(retry.id, first.id); assert.equal(retry.replayed, true); assert.equal(retry.clientId, key)
    assert.equal(await db.conversation.count(), 1); assert.equal(await db.message.count(), 1)
  } finally { await reopened.$disconnect() }
}))
test('creation identities bind kind/instructions and owner, not just display name', () => fixture(async db => {
  const first = await create(db)
  await assert.rejects(create(db, 'owner', key, 'channel', 'Changed'), { status: 409 })
  await assert.rejects(create(db, 'owner', key, 'group'), { status: 409 })
  assert.notEqual((await create(db, 'another-owner')).id, first.id)
  assert.notEqual((await create(db, 'owner', 'deliberate-new-operation')).id, first.id)
  assert.equal(await db.conversation.count(), 3)
}))
test('replay preserves later metadata, refuses departed creator, and never resurrects deletion', () => fixture(async db => {
  const first = await create(db)
  await db.conversation.update({ where: { id: first.id }, data: { name: 'Renamed' } })
  assert.equal((await create(db)).id, first.id)
  assert.equal((await db.conversation.findUnique({ where: { id: first.id }, select: { name: true } })).name, 'Renamed')
  await db.conversationParticipant.deleteMany({ where: { conversationId: first.id } })
  await assert.rejects(create(db), { status: 403 })
  await db.conversation.delete({ where: { id: first.id } })
  await assert.rejects(create(db), { status: 410, code: 'creation_gone', clientId: key })
  assert.equal(await db.conversation.count(), 0)
  assert.equal((await db.$queryRaw`SELECT * FROM ConversationCreationOperation`).length, 1)
}))
test('receipt failure rolls back room, membership and initial post together', () => fixture(async db => {
  await db.$executeRawUnsafe("CREATE TRIGGER reject_receipt BEFORE INSERT ON ConversationCreationOperation BEGIN SELECT RAISE(ABORT, 'disk refused'); END")
  await assert.rejects(create(db))
  assert.equal(await db.conversation.count(), 0); assert.equal(await db.conversationParticipant.count(), 0)
  assert.equal(await db.message.count(), 0)
  await db.$executeRawUnsafe('DROP TRIGGER reject_receipt')
  assert.equal((await create(db)).replayed, false)
}))
test('simultaneous identical submissions converge or explicitly request the same retry', () => fixture(async db => {
  const results = await Promise.allSettled([create(db), create(db)])
  const success = results.filter(r => r.status === 'fulfilled')
  assert.ok(success.length)
  for (const result of results) if (result.status === 'rejected') assert.equal(result.reason.status, 409)
  const retry = await create(db)
  for (const result of success) assert.equal(result.value.id, retry.id)
  assert.equal(await db.conversation.count(), 1); assert.equal(await db.message.count(), 1)
}))
test('missing receipt schema fails before mutation and malformed IDs never dispatch', () => fixture(async db => {
  for (const id of ['', null, 12, 'bad/id']) await assert.rejects(create(db, 'owner', id), { status: 400 })
  await db.$executeRawUnsafe('DROP TABLE ConversationCreationOperation')
  await assert.rejects(create(db), { status: 503 })
  assert.equal(await db.conversation.count(), 0)
  const legacy = () => createConversationOnce(db, 'owner', undefined, 'group', [], tx => tx.conversation.create({
    data: { isGroup: true }, select: { id: true } }))
  assert.notEqual((await legacy()).id, (await legacy()).id)
}))


test('versioned entry points require an identity and forward the unconsumed authenticated request', async () => {
  for (const resource of ['channels', 'conversations']) {
    const source = stripTypeScriptTypes(await readFile(new URL(`../../src/app/api/${resource}/create-v2/route.ts`, import.meta.url), 'utf8'))
      .replace(/^import .*$/gm, '').replace(/export /g, '')
    let dispatched = 0
    const post = new Function('NextResponse', 'create', 'safeJson', source + '; return POST')(
      { json: Response.json }, async request => {
        dispatched++
        assert.equal(request.headers.get('Authorization'), 'Bearer synthetic-owner')
        assert.equal((await request.json()).clientId, key)
        return Response.json({ ok: true })
      }, async request => { try { return await request.json() } catch { return {} } })
    for (const clientId of [undefined, null, '', 'short', 42, []]) {
      const result = await post(new Request('https://creation.test', { method: 'POST', body: JSON.stringify({ clientId }) }))
      assert.equal(result.status, 400)
    }
    if (resource === 'conversations') for (const isGroup of [undefined, false, 'true', 1]) {
      const result = await post(new Request('https://creation.test', { method: 'POST', body: JSON.stringify({ clientId: key, isGroup }) }))
      assert.equal(result.status, 400)
    }
    assert.equal(dispatched, 0)
    const response = await post(new Request('https://creation.test', { method: 'POST',
      headers: { Authorization: 'Bearer synthetic-owner' }, body: JSON.stringify({ clientId: key, isGroup: true }) }))
    assert.equal(response.status, 200); assert.equal(dispatched, 1)
  }
})
