import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { reminderPage, resolveReminder, deleteReminder } from '../../src/lib/reminder-contract.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const time = new Date('2026-10-20T00:00:00Z')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-reminders-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe('CREATE TABLE Reminder(id TEXT PRIMARY KEY, userId TEXT NOT NULL, conversationId TEXT NOT NULL, messageId TEXT, note TEXT NOT NULL DEFAULT "", remindAt DATETIME NOT NULL, firedAt DATETIME, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)')
    await db.$transaction(async tx => {
      for (let i = 0; i < 250; i++) await tx.reminder.create({ data: { id: `r${String(i).padStart(3, '0')}`, userId: 'me', conversationId: 'room', remindAt: time, firedAt: i < 150 ? time : null } })
    })
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
test('250 same-time reminders page without gaps; old history cannot hide upcoming rows', () => fixture(async db => {
  let cursor = null; const ids = []
  do { const page = await reminderPage(db, 'me', false, cursor); ids.push(...page.rows.map(r => r.id)); cursor = page.nextCursor } while (cursor)
  assert.equal(ids.length, 250); assert.equal(new Set(ids).size, 250)
  assert.equal(ids.at(-1), 'r249')
}))
test('due filter excludes fired and future reminders', () => fixture(async db => {
  const page = await reminderPage(db, 'me', true, null, time)
  assert.equal(page.rows.length, 100)
  assert.equal(page.rows[0].id, 'r150')
  assert.equal((await reminderPage(db, 'me', true, null, new Date(time.getTime() - 1))).rows.length, 0)
}))
test('deleted boundary row does not invalidate the continuation', () => fixture(async db => {
  const page = await reminderPage(db, 'me', false, null)
  await deleteReminder(db, page.rows.at(-1).id, 'me')
  const next = await reminderPage(db, 'me', false, page.nextCursor)
  assert.equal(next.rows[0].id, 'r100')
}))
test('cursor is bound to its owner and filter and malformed cursors are rejected', () => fixture(async db => {
  const first = await reminderPage(db, 'me', false, null)
  await assert.rejects(reminderPage(db, 'other', false, first.nextCursor), { status: 400 })
  await assert.rejects(reminderPage(db, 'me', true, first.nextCursor), { status: 400 })
  await assert.rejects(reminderPage(db, 'me', false, 'not-json'), { status: 400 })
}))
test('repeated resolution preserves the original firedAt', () => fixture(async db => {
  const first = await resolveReminder(db, 'r200', 'me')
  assert.deepEqual(await resolveReminder(db, 'r200', 'me'), first)
  assert.equal((await db.reminder.findUnique({ where: { id: 'r200' } })).firedAt.toISOString(), first.firedAt)
}))
test('lost DELETE acknowledgement is retryable and cannot delete another owners row', () => fixture(async db => {
  await assert.rejects(deleteReminder(db, 'r200', 'other'), { status: 403 })
  assert.deepEqual(await deleteReminder(db, 'r200', 'me'), { ok: true })
  assert.deepEqual(await deleteReminder(db, 'r200', 'me'), { ok: true })
  assert.equal(await db.reminder.count(), 249)
}))
test('wrong owner and absent rows cannot be resolved', () => fixture(async db => {
  await assert.rejects(resolveReminder(db, 'r200', 'other'), { status: 403 })
  await assert.rejects(resolveReminder(db, 'absent', 'me'), { status: 404 })
  assert.equal((await db.reminder.findUnique({ where: { id: 'r200' } })).firedAt, null)
}))
