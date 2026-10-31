// Section 8 item 7 - reporting durability.
// Offline reports journal a clientId; a lost-ACK replay returns the SAME
// stored receipt (reportId + evidenceRev, no timestamp refresh); edited
// evidence under the same identity is a 409; a new submission bumps evidence.
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
  const dir = await mkdtemp(join(tmpdir(), 'repdur-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  const t = {}
  try {
    await db.$executeRawUnsafe(`CREATE TABLE User(id TEXT PRIMARY KEY, sessionTokenHash TEXT, legacyTokenHashes TEXT)`)
    await db.$executeRawUnsafe(`CREATE TABLE AccountReport(id TEXT PRIMARY KEY, reporterId TEXT, reportedId TEXT,
      reason TEXT, details TEXT DEFAULT "", evidenceRev INTEGER DEFAULT 0, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, UNIQUE(reporterId, reportedId, reason))`)
    await db.$executeRawUnsafe('CREATE TABLE ReportOperation(reporterId TEXT, clientId TEXT, requestHash TEXT, reportId TEXT, evidenceRev INTEGER, PRIMARY KEY(reporterId, clientId))')
    for (const id of ['me', 'other']) await db.$executeRawUnsafe('INSERT INTO User (id,sessionTokenHash) VALUES (?,?)', id, hashSessionToken(`synthetic-${id}`))
    const requireActionOwner = await load('src/lib/wallet-authorization.ts', { db, bearerToken, tokenAccepted }, 'requireActionOwner')
    const h = await load('src/app/api/users/[id]/report/route.ts', {
      db, requireActionOwner, NextResponse: Response, createHash: (await import('node:crypto')).createHash,
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
    }, '({GET,POST})')
    t.ctx = (id = 'other') => ({ params: Promise.resolve({ id }) })
    t.req = (method, token = 'synthetic-me', body, target = 'other') => new Request(
      `https://report.test/api/users/${target}/report${method === 'GET' ? '?userId=me' : ''}`,
      { method, headers: token ? { Authorization: `Bearer ${token}` } : {}, ...(method === 'POST' ? { body: JSON.stringify({ userId: 'me', reason: 'spam', ...body }) } : {}) })
    // Content 409s under parallel runs retry the SAME evidence, like real clients.
    t.retryPost = async (body, target = 'other') => {
      for (let i = 0; ; i++) {
        const r = await h.POST(t.req('POST', 'synthetic-me', body, target), t.ctx(target))
        if (r.status === 409 && i < 20) continue
        return r
      }
    }
    await run(db, h, t)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

const CLIENT = 'report-journal-000000001'

test('a lost-ACK report replays to the SAME receipt without refreshing evidence', () => fixture(async (db, h, t) => {
  const first = await t.retryPost({ details: ' initial evidence ', clientId: CLIENT })
  assert.equal(first.status, 201)
  const a = await first.json()
  assert.equal(a.replayed, false)
  assert.equal(a.evidenceRev, 0)
  const stamp0 = (await db.accountReport.findFirst()).createdAt
  const replay = await t.retryPost({ details: ' initial evidence ', clientId: CLIENT })
  assert.equal(replay.status, 201, 'replay keeps the first-submission status')
  const b = await replay.json()
  assert.equal(b.replayed, true)
  assert.equal(b.reportId, a.reportId)
  assert.equal(b.evidenceRev, a.evidenceRev, 'replay must not bump the evidence revision')
  const row = await db.accountReport.findFirst()
  assert.equal(row.createdAt.getTime(), stamp0.getTime(), 'replay must not refresh the timestamp')
  assert.equal(await db.accountReport.count(), 1)
  // Same identity, different evidence: refuse.
  const conflict = await t.retryPost({ details: ' edited evidence ', clientId: CLIENT })
  assert.equal(conflict.status, 409)
  assert.equal((await db.accountReport.findFirst()).details, ' initial evidence ')
}))

test('a NEW submission refreshes evidence and takes the next evidence revision', () => fixture(async (db, h, t) => {
  await t.retryPost({ details: 'v1', clientId: CLIENT })
  const second = await t.retryPost({ details: 'v2', clientId: CLIENT + '2' })
  assert.equal(second.status, 200)
  const receipt = await second.json()
  assert.equal(receipt.updated, true)
  assert.equal(receipt.replayed, false)
  assert.equal(receipt.evidenceRev, 1)
  const row = await db.accountReport.findFirst()
  assert.equal(row.details, 'v2')
  // The first operation's receipt still replays against the SAME report row.
  const replay = await t.retryPost({ details: 'v1', clientId: CLIENT })
  const body = await replay.json()
  assert.equal(body.replayed, true)
  assert.equal(body.reportId, receipt.reportId, 'both journals resolve to the same moderated row')
}))

test('evidence keeps exact UTF-16 text and history stays private per reporter', () => fixture(async (db, h, t) => {
  const details = '😀'.repeat(250)
  const ok = await t.retryPost({ details, clientId: CLIENT })
  assert.equal(ok.status, 201)
  for (const bad of [details + 'x', '😀'.repeat(251)]) {
    assert.equal((await t.retryPost({ details: bad, clientId: CLIENT + Math.random().toString(36).slice(2) + 'pad00' })).status, 400)
  }
  const history = await h.GET(t.req('GET', 'synthetic-me', undefined, 'other'), t.ctx('other'))
  const body = await history.json()
  assert.equal(body.reportHistoryVersion, 1)
  assert.deepEqual(body.reasons.map(r => r.reason), ['spam'])
  // Another viewer cannot read this reporter's private history at all.
  const foreign = await h.GET(t.req('GET', 'synthetic-other', undefined, 'other'), t.ctx('other'))
  assert.equal(foreign.status, 403)
}))
