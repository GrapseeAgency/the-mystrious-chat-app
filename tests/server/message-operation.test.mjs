import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { messageOperation, commitMessageOperation, replayMessageOperation } from '../../src/lib/message-operation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-operation-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE TestMessage(id TEXT PRIMARY KEY, content TEXT NOT NULL)')
    const patch = await readFile(new URL('../../prisma/patches/20261019_message_send_operation.sql', import.meta.url), 'utf8')
    for (const statement of patch.split(';').filter(s => s.trim())) await db.$executeRawUnsafe(statement)
    let n = 0
    const find = async id => (await db.$queryRaw`SELECT * FROM TestMessage WHERE id = ${id}`)[0] ?? null
    const create = async tx => {
      const row = { id: `server-${++n}`, content: 'hello' }
      await tx.$executeRaw`INSERT INTO TestMessage VALUES (${row.id}, ${row.content})`
      return row
    }
    await run({ db, find, create })
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
const op = (key = 'operation-00000001', changes = {}) => messageOperation('sender-a', 'room-a', { content: 'hello', clientId: key, ...changes })

test('lost response replay returns original row, distinct keys preserve intentional identical sends', () => fixture(async ({ db, find, create }) => {
  const first = await commitMessageOperation(db, op(), create, find)
  const retry = await commitMessageOperation(db, op(), create, find)
  const second = await commitMessageOperation(db, op('operation-00000002'), create, find)
  assert.equal(retry.replayed, true)
  assert.equal(retry.message.id, first.message.id)
  assert.notEqual(second.message.id, first.message.id)
  assert.equal((await db.$queryRaw`SELECT * FROM TestMessage`).length, 2)
}))
test('same key with changed content/room is a conflict', () => fixture(async ({ db, find, create }) => {
  await commitMessageOperation(db, op(), create, find)
  await assert.rejects(commitMessageOperation(db, op(undefined, { content: 'changed' }), create, find), { status: 409 })
  await assert.rejects(replayMessageOperation(db, { ...op(), conversationId: 'room-b' }, find), { status: 409 })
}))
test('receipt survives deletion, never resurrects expired delivery', () => fixture(async ({ db, find, create }) => {
  await commitMessageOperation(db, op(), create, find)
  await db.$executeRaw`DELETE FROM TestMessage`
  await assert.rejects(commitMessageOperation(db, op(), create, find), { status: 410 })
  assert.equal((await db.$queryRaw`SELECT * FROM TestMessage`).length, 0)
}))
test('failed transaction commits neither message nor receipt', () => fixture(async ({ db, find, create }) => {
  await assert.rejects(commitMessageOperation(db, op(), async tx => { await create(tx); throw Error('fault') }, find), /fault/)
  assert.equal((await db.$queryRaw`SELECT * FROM TestMessage`).length, 0)
  assert.equal((await db.$queryRaw`SELECT * FROM MessageSendOperation`).length, 0)
}))
test('concurrent retries commit at most one row and converge on retry', () => fixture(async ({ db, find, create }) => {
  await Promise.allSettled(Array.from({ length: 5 }, () => commitMessageOperation(db, op(), create, find)))
  const replay = await commitMessageOperation(db, op(), create, find)
  assert.equal(replay.replayed, true)
  assert.equal((await db.$queryRaw`SELECT * FROM TestMessage`).length, 1)
  assert.equal((await db.$queryRaw`SELECT * FROM MessageSendOperation`).length, 1)
}))
test('keys are per sender; reordered JSON and absent defaults have identical fingerprints', () => {
  assert.notEqual(op().senderId, messageOperation('sender-b', 'room-a', { clientId: 'operation-00000001', content: 'hello' }).senderId)
  assert.equal(op(undefined, { payload: { a: 1, b: 2 } }).requestHash, op(undefined, { payload: { b: 2, a: 1 }, kind: 'text', anon: false, replyToId: null }).requestHash)
  assert.throws(() => op('short'), { status: 400 })
  assert.equal(messageOperation('a', 'b', { content: 'legacy' }), null)
})
