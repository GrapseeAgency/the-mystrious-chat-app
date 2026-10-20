import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { commitBoardStrokes, clearBoard } from '../../src/lib/whiteboard-operation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-board-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE WhiteboardStroke(id TEXT PRIMARY KEY, conversationId TEXT NOT NULL, userId TEXT NOT NULL, color TEXT NOT NULL, width REAL NOT NULL, points TEXT NOT NULL, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    await db.$executeRawUnsafe('CREATE TABLE LogEvent(id TEXT PRIMARY KEY, userId TEXT, kind TEXT NOT NULL, message TEXT NOT NULL, meta TEXT, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    const patch = await readFile(new URL('../../prisma/patches/20261019_whiteboard_operations.sql', import.meta.url), 'utf8')
    for (let i = 0; i < 2; i++) for (const sql of patch.split(';').filter(x => x.trim())) await db.$executeRawUnsafe(sql)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
const stroke = (clientId = 'drawing-operation-001', changes = {}) => ({ clientId, baseResetAt: 0, color: '#abcdef', width: 3, points: '[[0,0],[1,1]]', ...changes })
const send = (db, strokes = [stroke()], room = 'room', user = 'alice') => commitBoardStrokes(db, room, user, strokes)

test('lost ACK replay preserves the original ID without duplicating the row', () => fixture(async db => {
  const original = await send(db)
  const retry = await send(db)
  assert.deepEqual(retry.ids, original.ids)
  assert.equal(retry.created, 0)
  assert.equal(await db.whiteboardStroke.count(), 1)
}))
test('identical geometry is distinct intent and is isolated per sender', () => fixture(async db => {
  await send(db, [stroke(), stroke('drawing-operation-002')])
  await send(db, [stroke()], 'room', 'bob')
  assert.equal(await db.whiteboardStroke.count(), 3)
}))
test('changing content or room under an existing operation key conflicts', () => fixture(async db => {
  await send(db)
  await assert.rejects(send(db, [stroke(undefined, { width: 4 })]), { status: 409 })
  await assert.rejects(send(db, [stroke()], 'other-room'), { status: 409 })
  assert.equal(await db.whiteboardStroke.count(), 1)
}))
test('Undo tombstone acknowledges retry without resurrecting the stroke', () => fixture(async db => {
  const first = await send(db)
  await db.whiteboardStroke.deleteMany()
  assert.deepEqual((await send(db)).ids, first.ids)
  assert.equal(await db.whiteboardStroke.count(), 0)
}))
test('Clear fences unseen old uploads and retains delivered operation receipts', () => fixture(async db => {
  const first = await send(db)
  const cleared = await clearBoard(db, 'room', 'alice')
  await assert.rejects(send(db, [stroke('drawing-operation-002')]), { status: 409 })
  assert.deepEqual((await send(db)).ids, first.ids)
  assert.equal(await db.whiteboardStroke.count(), 0)
  await send(db, [stroke('drawing-operation-003', { baseResetAt: cleared.at })])
  assert.equal(await db.whiteboardStroke.count(), 1)
}))
test('failed mixed batch rolls back new strokes and receipts together', () => fixture(async db => {
  await send(db)
  await assert.rejects(send(db, [stroke('drawing-operation-002'), stroke(undefined, { width: 9 })]), { status: 409 })
  assert.equal(await db.whiteboardStroke.count(), 1)
  assert.equal((await db.$queryRaw`SELECT * FROM WhiteboardStrokeOperation`).length, 1)
}))
test('concurrent retries converge without duplicate commits', () => fixture(async db => {
  const results = await Promise.allSettled(Array.from({ length: 4 }, () => send(db)))
  const settled = await send(db)
  for (const r of results) if (r.status === 'fulfilled') assert.deepEqual(r.value.ids, settled.ids)
  assert.equal(await db.whiteboardStroke.count(), 1)
  assert.equal((await db.$queryRaw`SELECT * FROM WhiteboardStrokeOperation`).length, 1)
}))
test('repeated Clears always advance the epoch and return the stored marker', () => fixture(async db => {
  const first = await clearBoard(db, 'room', 'alice')
  const second = await clearBoard(db, 'room', 'alice')
  assert.ok(second.at > first.at)
  const marker = await db.logEvent.findFirst({ orderBy: { createdAt: 'desc' } })
  assert.equal(marker.createdAt.getTime(), second.at)
}))
test('new operations require a valid epoch; legacy sends remain accepted', () => fixture(async db => {
  await assert.rejects(send(db, [stroke(undefined, { baseResetAt: undefined })]), { status: 400 })
  await assert.rejects(send(db, [stroke('short')]), { status: 400 })
  await send(db, [stroke(undefined, { clientId: undefined, baseResetAt: undefined })])
  assert.equal(await db.whiteboardStroke.count(), 1)
}))
