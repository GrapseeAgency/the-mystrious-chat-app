// Section 8 item 10 - quick-phrase replay and revisions.
// Lost-ACK retries replay the SAME outcome; every accepted write carries the
// rail's monotonic listRev so cross-device orderings are comparable.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { readFile } from 'node:fs/promises'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

async function load(path, bindings, expression) {
  const text = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'phrrev-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe(`CREATE TABLE User(id TEXT PRIMARY KEY, sessionTokenHash TEXT, legacyTokenHashes TEXT)`)
    await db.$executeRawUnsafe(`CREATE TABLE QuickPhrase(id TEXT PRIMARY KEY, userId TEXT, text TEXT, position INTEGER DEFAULT 0, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)`)
    await db.$executeRawUnsafe('CREATE TABLE UserListRevision(userId TEXT, kind TEXT, rev INTEGER DEFAULT 0, PRIMARY KEY(userId, kind))')
    await db.$executeRawUnsafe('CREATE TABLE QuickPhraseOperation(userId TEXT, clientId TEXT, requestHash TEXT, action TEXT, phraseId TEXT, PRIMARY KEY(userId, clientId))')
    for (const id of ['me', 'other']) await db.$executeRawUnsafe('INSERT INTO User VALUES (?,?,?)', id, hashSessionToken(`synthetic-${id}`), '[]')
    const requireActionOwner = await load('src/lib/wallet-authorization.ts', { db, bearerToken, tokenAccepted }, 'requireActionOwner')
    const h = await load('src/app/api/users/[id]/phrases/route.ts', {
      db, requireActionOwner, NextResponse: { json: Response.json }, createHash: (await import('node:crypto')).createHash,
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
    }, '({GET,POST,PATCH,DELETE})')
    const ctx = (id = 'me') => ({ params: Promise.resolve({ id }) })
    const req = (method, token = 'synthetic-me', body, owner = 'me') => new Request(
      `https://phrase.test/api/users/${owner}/phrases${method === 'GET' ? '' : ''}`,
      { method, headers: token ? { Authorization: `Bearer ${token}` } : {}, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    await run(db, h, { ctx, req })
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

const CLIENT = 'phrase-journal-000000001'

test('a lost-ACK phrase creation replays to the same row without a duplicate', () => fixture(async (db, h, t) => {
  const first = await h.POST(t.req('POST', 'synthetic-me', { text: 'On my way', clientId: CLIENT }), t.ctx())
  assert.equal(first.status, 201)
  const a = await first.json()
  const retry = await h.POST(t.req('POST', 'synthetic-me', { text: 'On my way', clientId: CLIENT }), t.ctx())
  assert.equal(retry.status, 200)
  const b = await retry.json()
  assert.equal(b.phrase.id, a.phrase.id)
  assert.equal(b.replayed, true)
  assert.equal(b.listRev, a.listRev, 'replay must not spend a revision')
  assert.equal(await db.quickPhrase.count(), 1)
  // Same identity, different words: refuse instead of silently diverging.
  const conflict = await h.POST(t.req('POST', 'synthetic-me', { text: 'BRB', clientId: CLIENT }), t.ctx())
  assert.equal(conflict.status, 409)
}))

test('every accepted write carries a strictly increasing rail revision across devices', () => fixture(async (db, h, t) => {
  const deviceA = await h.POST(t.req('POST', 'synthetic-me', { text: 'A', clientId: CLIENT }), t.ctx())
  const deviceB = await h.POST(t.req('POST', 'synthetic-me', { text: 'B', clientId: CLIENT + '2' }), t.ctx())
  const a = await deviceA.json(), b = await deviceB.json()
  assert.equal(b.listRev, a.listRev + 1)
  // Stale device A re-applies its cached list (listRev 1) after B's write
  // (listRev 2): the revision comparison exposes the staleness.
  const snapshot = await h.GET(t.req('GET'), t.ctx())
  const list = await snapshot.json()
  assert.ok(list.listRev >= b.listRev)
  assert.deepEqual(list.phrases.map(p => p.text), ['A', 'B'], 'position order is stable across devices')
  const patch = await h.PATCH(t.req('PATCH', 'synthetic-me', { phraseId: a.phrase.id, text: 'A2', clientId: CLIENT + '3' }), t.ctx())
  assert.equal(patch.status, 200)
  const patched = await patch.json()
  assert.equal(patched.listRev, b.listRev + 1)
  const patchReplay = await h.PATCH(t.req('PATCH', 'synthetic-me', { phraseId: a.phrase.id, text: 'A2', clientId: CLIENT + '3' }), t.ctx())
  const replayed = await patchReplay.json()
  assert.equal(replayed.replayed, true)
  assert.equal(replayed.listRev, patched.listRev)
}))

test('deletion replays cleanly and concurrent appends still respect the quota', () => fixture(async (db, h, t) => {
  const created = await h.POST(t.req('POST', 'synthetic-me', { text: 'Bye', clientId: CLIENT }), t.ctx())
  const { phrase } = await created.json()
  const delReq = new Request(`https://phrase.test/api/users/me/phrases?phraseId=${phrase.id}&clientId=${CLIENT}-del0`, {
    method: 'DELETE', headers: { Authorization: 'Bearer synthetic-me' } })
  const delOk = await h.DELETE(delReq, t.ctx())
  assert.equal(delOk.status, 200)
  const delReceipt = await delOk.json()
  assert.equal(delReceipt.deleted, true)
  const delReplay = await h.DELETE(new Request(`https://phrase.test/api/users/me/phrases?phraseId=${phrase.id}&clientId=${CLIENT}-del0`, {
    method: 'DELETE', headers: { Authorization: 'Bearer synthetic-me' } }), t.ctx())
  assert.equal(delReplay.status, 200)
  assert.equal((await delReplay.json()).replayed, true)
  // Eleven more creations (pools of 3 to keep SQLite's serializer honest):
  // the quota of 12 holds and no thirteenth row can ever appear.
  const attempt = async (text, clientId) => {
    for (let i = 0; ; i++) {
      const r = await h.POST(t.req('POST', 'synthetic-me', { text, clientId }), t.ctx())
      if (r.status === 409 && i < 12) continue
      return r.status
    }
  }
  const jobs = Array.from({ length: 11 }, (_, i) => [`Q${i}`, `${CLIENT}-q${i}`.padEnd(20, '0')])
  const statuses = []
  for (let i = 0; i < jobs.length; i += 2) {
    statuses.push(...await Promise.all(jobs.slice(i, i + 2).map(([text, clientId]) => attempt(text, clientId))))
  }
  assert.ok(statuses.every(s => s === 201 || s === 400), 'only quota-respecting outcomes are allowed')
  assert.equal(await db.quickPhrase.count(), 11)
  // The freed slot from the deletion is reusable: the 12th phrase fits.
  const refill = await attempt('Q11b', CLIENT + 'refill00000000')
  assert.equal(refill, 201)
  const overflow = await attempt('Q12', CLIENT + 'overflow000000')
  assert.equal(overflow, 400)
  assert.equal(await db.quickPhrase.count(), 12)
}))
