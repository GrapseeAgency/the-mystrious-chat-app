import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { mutateSavedMessage, mutateSavedIntent, readSavedMessageStates, SavedMessageError } from '../../src/lib/saved-message-operation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = new Date('2026-10-22T00:00:00Z')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-saved-state-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of [
      'CREATE TABLE SavedMessageState(userId TEXT,messageId TEXT,revision TEXT NOT NULL,PRIMARY KEY(userId,messageId))',
      'CREATE TABLE SavedMessageOperation(userId TEXT,clientId TEXT,messageId TEXT,requestHash TEXT,revision TEXT,outcome TEXT,PRIMARY KEY(userId,clientId))',
      'CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,deletedAt DATETIME,expiresAt DATETIME,viewOnce BOOLEAN NOT NULL DEFAULT false,parentId TEXT)',
      'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,UNIQUE(userId,conversationId))',
      'CREATE TABLE SavedMessage(id TEXT PRIMARY KEY,userId TEXT,messageId TEXT,savedAt DATETIME NOT NULL,UNIQUE(userId,messageId))',
      "INSERT INTO Message(id,conversationId) VALUES ('root','room'),('other','elsewhere')",
      "INSERT INTO Message(id,conversationId,parentId) VALUES ('reply','room','root')",
      "INSERT INTO ConversationParticipant VALUES ('p','me','room'),('q','peer','room')",
    ]) await db.$executeRawUnsafe(sql)
    await run(db, (saved, user = 'me', id = 'reply', read) => mutateSavedMessage(db, user, id, saved, read, () => now))
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
test('explicit save retry retains first timestamp and removal never toggles or touches another owner', () => fixture(async (db, act) => {
  const first = await act(true)
  assert.deepEqual(first, { saved: true, savedAt: now.toISOString(), messageId: 'reply', userId: 'me', savedReceiptVersion: 1 })
  const later = await mutateSavedMessage(db, 'me', 'reply', true, undefined, () => new Date(now.getTime() + 1000))
  assert.deepEqual(later, first)
  await act(true, 'peer')
  for (let i = 0; i < 3; i++) assert.equal((await act(false)).saved, false)
  assert.equal(await db.savedMessage.count({ where: { userId: 'me' } }), 0)
  assert.equal(await db.savedMessage.count({ where: { userId: 'peer' } }), 1)
}))
test('save checks membership and canonical root on retries; remove remains available after departure/deletion', () => fixture(async (db, act) => {
  await act(true)
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId='me'`
  await assert.rejects(act(true), { status: 403 })
  await db.$executeRaw`UPDATE Message SET deletedAt=${now} WHERE id='reply'`
  assert.equal((await act(false)).saved, false)
  assert.equal((await act(false, 'me', 'missing')).saved, false)
}))
test('private expired deleted foreign nested and orphan threads refuse additions', () => fixture(async (db, act) => {
  for (const patch of ["viewOnce=true", "viewOnce=false,deletedAt=0", "deletedAt=NULL,expiresAt=0",
    "expiresAt=NULL,parentId='other'", "parentId='missing'", "parentId='reply'"]) {
    await db.$executeRawUnsafe(`UPDATE Message SET ${patch} WHERE id='reply'`)
    await assert.rejects(act(true), error => [400,410].includes(error.status))
  }
  await db.$executeRaw`UPDATE Message SET parentId='root' WHERE id='reply'`
  await db.$executeRaw`UPDATE Message SET expiresAt=${now} WHERE id='root'`
  await assert.rejects(act(true), { status: 410 })
  assert.equal(await db.savedMessage.count(), 0)
}))
test('failed final receipt rolls back both additions and removals', () => fixture(async (db, act) => {
  const fail = async () => { throw Error('snapshot fault') }
  await assert.rejects(act(true, 'me', 'reply', fail), /snapshot fault/)
  assert.equal(await db.savedMessage.count(), 0)
  await act(true)
  await assert.rejects(act(false, 'me', 'reply', fail), /snapshot fault/)
  assert.equal(await db.savedMessage.count(), 1)
}))
test('concurrent adds converge; legacy toggle stays isolated from explicit retry semantics', () => fixture(async (db, act) => {
  const results = await Promise.allSettled([act(true), act(true)])
  for (const result of results) if (result.status === 'rejected') assert.equal(result.reason.status, 409)
  assert.equal((await act(true)).saved, true)
  assert.equal(await db.savedMessage.count(), 1)
  assert.equal((await act(undefined)).saved, false)
  assert.equal((await act(undefined)).saved, true)
}))
test('state route rejects omitted desired state and wrong owner before calling the mutation', async () => {
  const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/messages/[id]/save-state/route.ts', import.meta.url), 'utf8'))
    .replace(/^import .*$/gm, '').replace(/export /g, '')
  const calls = []; let denied = false
  const dependencies = { NextResponse: { json: Response.json }, db: {}, safeJson: req => req.json(),
    notifySocket: async () => {},
    strField: value => typeof value === 'string' ? value.trim() : '', SavedMessageError,
    requireActionOwner: async () => denied ? Response.json({}, { status: 403 }) : null,
    mutateSavedMessage: async (_, userId, messageId, saved) => { calls.push({ userId, messageId, saved }); return { saved } } }
  const post = new Function(...Object.keys(dependencies), source + '; return POST')(...Object.values(dependencies))
  const request = saved => post(new Request('https://saved.test', { method: 'POST', body: JSON.stringify({ userId: 'me', saved }) }), { params: Promise.resolve({ id: 'reply' }) })
  for (const state of [undefined, null, 0, 'false', {}, []]) assert.equal((await request(state)).status, 400)
  denied = true; assert.equal((await request(true)).status, 403); assert.equal(calls.length, 0)
  denied = false
  for (const saved of [true, false]) { assert.equal((await request(saved)).status, 200); assert.deepEqual(calls.at(-1), { userId: 'me', messageId: 'reply', saved }) }
})

test('offline intent replay never re-applies an old save after another device removes it', () => fixture(async db => {
  const first = { clientId: 'deviceA-save', expectedRevision: '0' }
  const saved = await mutateSavedIntent(db, 'me', 'reply', true, first)
  const removed = await mutateSavedIntent(db, 'me', 'reply', false, { clientId: 'deviceB-remove', expectedRevision: saved.revision })
  assert.equal(removed.saved, false)
  const replay = await mutateSavedIntent(db, 'me', 'reply', true, first)
  assert.equal(replay.saved, false); assert.equal(replay.revision, removed.revision); assert.equal(replay.outcome, 'superseded')
  assert.equal(await db.savedMessage.count(), 0)
  await assert.rejects(mutateSavedIntent(db, 'me', 'reply', false, first), { status: 409 })
}))
test('same-device durable predecessors order save-unsave-save despite lost responses', () => fixture(async db => {
  const a = { clientId: 'first', expectedRevision: '0' }
  const b = { clientId: 'second', predecessorClientId: 'first' }
  const c = { clientId: 'third', predecessorClientId: 'second' }
  await assert.rejects(mutateSavedIntent(db, 'me', 'reply', false, b), { status: 409 })
  await mutateSavedIntent(db, 'me', 'reply', true, a)
  await mutateSavedIntent(db, 'me', 'reply', false, b)
  const final = await mutateSavedIntent(db, 'me', 'reply', true, c)
  for (const [state, intent] of [[true,a],[false,b],[true,c]]) {
    const replay = await mutateSavedIntent(db, 'me', 'reply', state, intent)
    assert.equal(replay.saved, true); assert.equal(replay.revision, final.revision)
  }
  assert.equal(await db.savedMessageOperation.count(), 3)
}))
test('cross-device stale branches are explicit durable conflicts, including their queued descendants', () => fixture(async db => {
  const a = await mutateSavedIntent(db, 'me', 'reply', true, { clientId: 'a', expectedRevision: '0' })
  const stale = { clientId: 'b', expectedRevision: '0' }
  const conflict = await mutateSavedIntent(db, 'me', 'reply', false, stale)
  assert.equal(conflict.outcome, 'conflict'); assert.equal(conflict.saved, true)
  const descendant = await mutateSavedIntent(db, 'me', 'reply', false, { clientId: 'c', predecessorClientId: 'b' })
  assert.equal(descendant.outcome, 'conflict')
  const explicit = await mutateSavedIntent(db, 'me', 'reply', false, { clientId: 'd', expectedRevision: a.revision })
  assert.equal(explicit.outcome, 'applied'); assert.equal(explicit.saved, false)
  assert.equal((await mutateSavedIntent(db, 'me', 'reply', false, stale)).outcome, 'conflict')
}))
test('legacy writes advance causal state; snapshots are owner-bound and bounded', () => fixture(async (db, act) => {
  await act(true)
  const snapshot = (await readSavedMessageStates(db, 'me', ['reply']))[0]
  assert.notEqual(snapshot.revision, '0'); assert.equal(snapshot.saved, true)
  assert.equal((await readSavedMessageStates(db, 'peer', ['reply']))[0].revision, '0')
  const late = await mutateSavedIntent(db, 'me', 'reply', false, { clientId: 'old-offline', expectedRevision: '0' })
  assert.equal(late.outcome, 'conflict')
  for (const ids of [Array.from({length:101},(_,i)=>String(i)), ['reply','reply'], ['']]) {
    await assert.rejects(readSavedMessageStates(db, 'me', ids), { status: 400 })
  }
}))
test('deleted-message tombstones and durable bound rejections prevent resurrection', () => fixture(async db => {
  const intent = { clientId: 'save-retry', expectedRevision: '0' }
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId='me'`
  assert.equal((await mutateSavedIntent(db, 'me', 'reply', true, intent)).outcome, 'rejected')
  assert.equal(await db.savedMessageOperation.count(), 1)
  await db.$executeRaw`INSERT INTO ConversationParticipant VALUES ('p','me','room')`
  assert.equal((await mutateSavedIntent(db, 'me', 'reply', true, intent)).outcome, 'rejected')
  intent.clientId = 'new-after-access-restored'
  const saved = await mutateSavedIntent(db, 'me', 'reply', true, intent)
  await mutateSavedIntent(db, 'me', 'reply', false, { clientId: 'remove', expectedRevision: saved.revision })
  await db.$executeRaw`DELETE FROM Message WHERE id='reply'`
  assert.equal((await mutateSavedIntent(db, 'me', 'reply', true, intent)).saved, false)
}))

test('saved state reconciliation withdraws unavailable bodies even when the bookmark marker remains', () => fixture(async db => {
  await mutateSavedIntent(db, 'me', 'reply', true, { clientId: 'save-visible', expectedRevision: '0' })
  assert.equal((await readSavedMessageStates(db, 'me', ['reply']))[0].saved, true)
  await db.$executeRaw`UPDATE Message SET expiresAt=0 WHERE id='root'`
  assert.equal((await readSavedMessageStates(db, 'me', ['reply']))[0].visible, false)
  assert.equal(await db.savedMessage.count(), 1)
  await db.$executeRaw`UPDATE Message SET expiresAt=NULL WHERE id='root'`
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId='me'`
  assert.equal((await readSavedMessageStates(db, 'me', ['reply']))[0].visible, false)
}))
