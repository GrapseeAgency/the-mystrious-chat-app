import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { mutateEvent, EventOperationError } from '../../src/lib/event-operation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = new Date('2026-10-22T12:00:00Z')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-events-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of [
      'PRAGMA foreign_keys=ON',
      'CREATE TABLE User(id TEXT PRIMARY KEY,name TEXT,xp INTEGER NOT NULL DEFAULT 0)',
      'CREATE TABLE Conversation(id TEXT PRIMARY KEY)',
      'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,role TEXT,UNIQUE(userId,conversationId))',
      'CREATE TABLE GroupEvent(id TEXT PRIMARY KEY,conversationId TEXT,title TEXT,description TEXT,location TEXT,startsAt DATETIME,createdById TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
      'CREATE TABLE EventRsvp(id TEXT PRIMARY KEY,eventId TEXT REFERENCES GroupEvent(id) ON DELETE CASCADE,userId TEXT,status TEXT,checkedInAt DATETIME,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(eventId,userId))',
      "INSERT INTO User(id,name) VALUES ('me','Member'),('creator','Creator'),('admin','Admin')",
      "INSERT INTO Conversation VALUES ('room')",
      "INSERT INTO ConversationParticipant VALUES ('p','me','room','member'),('q','creator','room','member'),('a','admin','room','admin')",
    ]) await db.$executeRawUnsafe(sql)
    await db.groupEvent.create({ data: { id: 'event', conversationId: 'room', title: 'Meet', startsAt: now, createdById: 'creator' } })
    await run(db, (action, user = 'me', clock = () => now) => mutateEvent(db, 'event', user, action, clock))
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
const xp = async db => (await db.user.findUnique({ where: { id: 'me' }, select: { xp: true } })).xp
const row = db => db.eventRsvp.findUnique({ where: { eventId_userId: { eventId: 'event', userId: 'me' } } })
test('RSVP is explicit replay-safe and returns its own canonical tally', () => fixture(async (db, act) => {
  for (const status of ['going', 'going', 'maybe', 'no']) {
    const result = await act({ kind: 'rsvp', status })
    assert.equal(result.eventReceiptVersion, 1); assert.equal(result.eventId, 'event'); assert.equal(result.userId, 'me')
    assert.equal(result.rsvp.status, status); assert.equal(result.counts[status], 1)
    assert.equal(Object.values(result.counts).reduce((a,b) => a+b), 1)
  }
  assert.equal(await db.eventRsvp.count(), 1)
  await assert.rejects(act({ kind: 'rsvp', status: 'unknown' }), { status: 400 })
}))
test('old RSVPs never authorize former members to attend, revote or delete', () => fixture(async (db, act) => {
  await act({ kind: 'rsvp', status: 'going' })
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId='me'`
  for (const action of [{ kind: 'checkin' }, { kind: 'rsvp', status: 'maybe' }, { kind: 'delete' }]) {
    await assert.rejects(act(action), { status: 403 })
  }
  assert.equal(await xp(db), 0); assert.equal((await row(db)).checkedInAt, null)
}))
test('attendance and XP commit once; lost-response replay outside window never reawards', () => fixture(async (db, act) => {
  await act({ kind: 'rsvp', status: 'going' })
  const first = await act({ kind: 'checkin' })
  assert.equal(first.xpAwarded, true); assert.equal(first.alreadyCheckedIn, false); assert.equal(first.checkedInCount, 1)
  await act({ kind: 'rsvp', status: 'no' })
  const again = await act({ kind: 'checkin' }, 'me', () => new Date(now.getTime() + 86400000))
  assert.equal(again.xpAwarded, false); assert.equal(again.alreadyCheckedIn, true)
  assert.equal(again.checkIn.checkedInAt.getTime(), first.checkIn.checkedInAt.getTime())
  assert.equal(await xp(db), 15)
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId='me'`
  await assert.rejects(act({ kind: 'checkin' }), { status: 403 })
}))
test('check-in requires eligible RSVP and honors both inclusive window boundaries', () => fixture(async (db, act) => {
  await assert.rejects(act({ kind: 'checkin' }), { status: 400 })
  await act({ kind: 'rsvp', status: 'no' }); await assert.rejects(act({ kind: 'checkin' }), { status: 400 })
  await act({ kind: 'rsvp', status: 'maybe' })
  for (const delta of [-900001, 7200001]) await assert.rejects(act({ kind: 'checkin' }, 'me', () => new Date(now.getTime()+delta)), { status: 409 })
  for (const delta of [-900000, 7200000]) {
    assert.equal((await act({ kind: 'checkin' }, 'me', () => new Date(now.getTime()+delta))).xpAwarded, true)
    await db.eventRsvp.updateMany({ data: { checkedInAt: null } }) // reset synthetic fixture only
  }
}))
test('XP failure rolls attendance back, so retry can still earn the promised bonus', () => fixture(async (db, act) => {
  await act({ kind: 'rsvp', status: 'going' })
  await db.$executeRawUnsafe("CREATE TRIGGER fail_xp BEFORE UPDATE OF xp ON User BEGIN SELECT RAISE(ABORT, 'xp fault'); END")
  await assert.rejects(act({ kind: 'checkin' }))
  assert.equal((await row(db)).checkedInAt, null); assert.equal(await xp(db), 0)
  await db.$executeRawUnsafe('DROP TRIGGER fail_xp')
  assert.equal((await act({ kind: 'checkin' })).xpAwarded, true); assert.equal(await xp(db), 15)
}))
test('failed final attendance snapshot rolls both stamp and XP back', () => fixture(async (db, act) => {
  await act({ kind: 'rsvp', status: 'going' })
  const failing = { $transaction: (fn, options) => db.$transaction(tx => fn(new Proxy(tx, {
    get: (target, key) => key === 'eventRsvp' ? new Proxy(tx.eventRsvp, {
      get: (delegate, method) => method === 'count' ? async () => { throw Error('snapshot fault') } : delegate[method],
    }) : target[key],
  })), options) }
  await assert.rejects(mutateEvent(failing, 'event', 'me', { kind: 'checkin' }, () => now), /snapshot fault/)
  assert.equal((await row(db)).checkedInAt, null); assert.equal(await xp(db), 0)
}))
test('concurrent attendance converges to one bonus, and only current creator/admin deletes', () => fixture(async (db, act) => {
  await act({ kind: 'rsvp', status: 'going' })
  const results = await Promise.allSettled([act({ kind: 'checkin' }), act({ kind: 'checkin' })])
  for (const r of results) if (r.status === 'rejected') assert.equal(r.reason.status, 409)
  await act({ kind: 'checkin' }); assert.equal(await xp(db), 15)
  await assert.rejects(act({ kind: 'delete' }), { status: 403 })
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId='creator'`
  await assert.rejects(act({ kind: 'delete' }, 'creator'), { status: 403 })
  assert.deepEqual(await act({ kind: 'delete' }, 'admin'), { eventReceiptVersion: 1, userId: 'admin', eventId: 'event', ok: true })
  assert.equal(await db.eventRsvp.count(), 0)
  await assert.rejects(act({ kind: 'checkin' }), { status: 404 })
}))
async function routes(path, deps) {
  const source = stripTypeScriptTypes(await readFile(new URL('../../'+path, import.meta.url), 'utf8'))
    .replace(/^import .*$/gm, '').replace(/export /g, '')
  return new Function(...Object.keys(deps), source + '; return {post:typeof POST === "function" ? POST : null,get:typeof GET === "function" ? GET : null,del:typeof DELETE === "function" ? DELETE : null}')(...Object.values(deps))
}
test('all event mutation routes bind presented credentials before running the action', async () => {
  for (const kind of ['rsvp','checkin','delete']) {
    let called = false
    const deps = { NextResponse: { json: Response.json }, db: {}, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      requireActionOwner: async () => Response.json({}, { status: 403 }), mutateEvent: async () => { called = true }, EventOperationError }
    const route = await routes('src/app/api/events/[id]/'+(kind === 'delete' ? '' : kind+'/')+'route.ts', deps)
    const req = new Request('https://events.test?userId=me', { method: kind === 'delete' ? 'DELETE' : 'POST',
      headers: { authorization: 'Bearer wrong-owner' }, ...(kind === 'delete' ? {} : { body: JSON.stringify({ userId:'me',status:'going' }) }) })
    assert.equal((await (route.post ?? route.del)(req, {params: Promise.resolve({id:'event'})})).status, 403)
    assert.equal(called,false)
  }
})
test('real create/list routes preserve owner and require current membership in their transaction', () => fixture(async (db) => {
  const route = await routes('src/app/api/conversations/[id]/events/route.ts', { db, NextResponse: { json: Response.json },
    safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '', requireActionOwner: async () => null })
  const ctx = {params: Promise.resolve({id:'room'})}
  const create = () => route.post(new Request('https://events.test', {method:'POST',body:JSON.stringify({ userId:'me', title:'Meet two',startsAt:now.toISOString() })}),ctx)
  const created = await create(); assert.equal(created.status,201)
  const event = (await created.json()).event
  assert.ok(event.id); assert.equal(event.createdById,'me'); assert.equal(event.startsAt,now.toISOString())
  const page = await route.get(new Request('https://events.test?userId=me'),ctx)
  assert.equal((await page.json()).events.length,2)
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId='me'`
  assert.equal((await create()).status,403)
  assert.equal((await route.get(new Request('https://events.test?userId=me'),ctx)).status,403)
  assert.equal(await db.groupEvent.count(),2)
}))
