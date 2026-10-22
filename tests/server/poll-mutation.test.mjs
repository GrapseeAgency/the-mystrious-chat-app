import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { mutatePoll } from '../../src/lib/poll-mutation.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = new Date('2026-10-22T00:00:00Z')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-poll-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of [
      `CREATE TABLE Message(id TEXT PRIMARY KEY, conversationId TEXT, senderId TEXT, parentId TEXT, deletedAt DATETIME, expiresAt DATETIME)`,
      `CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT, UNIQUE(userId,conversationId))`,
      `CREATE TABLE Poll(id TEXT PRIMARY KEY, messageId TEXT UNIQUE, question TEXT, closedAt DATETIME, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)`,
      `CREATE TABLE PollOption(id TEXT PRIMARY KEY, pollId TEXT, text TEXT, position INTEGER NOT NULL DEFAULT 0)`,
      `CREATE TABLE PollVote(id TEXT PRIMARY KEY, pollId TEXT, optionId TEXT, userId TEXT, createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(pollId,userId))`,
      `INSERT INTO Message(id,conversationId,senderId) VALUES ('m','room','author'),('root','room','author')`,
      `INSERT INTO ConversationParticipant VALUES ('a','author','room'),('p','peer','room')`,
      `INSERT INTO Poll(id,messageId,question) VALUES ('poll','m','Question')`,
      `INSERT INTO PollOption(id,pollId,text,position) VALUES ('yes','poll','Yes',0),('no','poll','No',1),('foreign','another','Other',0)`,
    ]) await db.$executeRawUnsafe(sql)
    const read = tx => tx.poll.findUniqueOrThrow({ where: { id: 'poll' }, select: { closedAt: true,
      options: { orderBy: { position: 'asc' }, select: { id: true, votes: { select: { userId: true } } } } } })
    await run(db, (owner, action, snapshot = read) => mutatePoll(db, 'poll', owner, action, snapshot, () => now))
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
const vote = optionId => ({ kind: 'vote', optionId })
test('same-choice retries are no-ops and moving a vote never duplicates it', () => fixture(async (db, act) => {
  assert.equal((await act('peer', vote('yes'))).changed, true)
  const first = await db.pollVote.findFirst()
  assert.equal((await act('peer', vote('yes'))).changed, false)
  assert.deepEqual(await db.pollVote.findFirst(), first)
  await act('peer', vote('no'))
  assert.equal(await db.pollVote.count(), 1)
  assert.equal((await db.pollVote.findFirst()).optionId, 'no')
  await assert.rejects(act('peer', vote('foreign')), { status: 400 })
  assert.equal((await db.pollVote.findFirst()).optionId, 'no')
}))
test('closed tally cannot accept votes and close replay still checks current membership', () => fixture(async (db, act) => {
  await act('peer', vote('yes'))
  await assert.rejects(act('peer', { kind: 'close' }), { status: 403 })
  const closed = await act('author', { kind: 'close' })
  assert.equal(closed.message.closedAt.toISOString(), now.toISOString())
  assert.equal(closed.message.options[0].votes.length, 1)
  assert.equal((await act('author', { kind: 'close' })).changed, false)
  await assert.rejects(act('peer', vote('no')), { status: 400 })
  assert.equal((await db.pollVote.findFirst()).optionId, 'yes')
  await db.$executeRaw`DELETE FROM ConversationParticipant WHERE userId = 'author'`
  await assert.rejects(act('author', { kind: 'close' }), { status: 403 })
}))
test('source deletion, expiry and unavailable parents block both mutation kinds', () => fixture(async (db, act) => {
  for (const patch of ["deletedAt = CURRENT_TIMESTAMP", "deletedAt = NULL, expiresAt = 0",
    "expiresAt = NULL, parentId = 'missing'", "parentId = 'root'"]) {
    await db.$executeRawUnsafe(`UPDATE Message SET ${patch} WHERE id = 'm'`)
    await db.$executeRawUnsafe("UPDATE Message SET deletedAt = CURRENT_TIMESTAMP WHERE id = 'root'")
    await assert.rejects(act('peer', vote('yes')), { status: 410 })
    await assert.rejects(act('author', { kind: 'close' }), { status: 410 })
  }
  assert.equal(await db.pollVote.count(), 0)
  assert.equal((await db.poll.findUnique({ where: { id: 'poll' } })).closedAt, null)
}))
test('snapshot failure rolls back both vote movement and closure', () => fixture(async (db, act) => {
  await act('peer', vote('yes'))
  const fail = async () => { throw Error('snapshot failed') }
  await assert.rejects(act('peer', vote('no'), fail), /snapshot failed/)
  assert.equal((await db.pollVote.findFirst()).optionId, 'yes')
  await assert.rejects(act('author', { kind: 'close' }, fail), /snapshot failed/)
  assert.equal((await db.poll.findUnique({ where: { id: 'poll' } })).closedAt, null)
}))
test('concurrent close and vote serialize or explicitly refuse contention; closure stays frozen', () => fixture(async (db, act) => {
  const results = await Promise.allSettled([act('author', { kind: 'close' }), act('peer', vote('yes'))])
  for (const result of results) if (result.status === 'rejected') assert.ok([400,409].includes(result.reason.status))
  await act('author', { kind: 'close' })
  const votes = await db.pollVote.findMany()
  await assert.rejects(act('peer', vote('no')), { status: 400 })
  assert.deepEqual(await db.pollVote.findMany(), votes)
}))
test('poll routes fan out neutral tally to all members including the actor and bind bearer ownership', async () => {
  const { readFile } = await import('node:fs/promises')
  const { stripTypeScriptTypes } = await import('node:module')
  for (const kind of ['vote', 'close']) {
    const source = stripTypeScriptTypes(await readFile(new URL(`../../src/app/api/polls/[id]/${kind}/route.ts`, import.meta.url), 'utf8'))
      .replace(/^import .*$/gm, '').replace(/export /g, '')
    const calls = [], fanouts = []
    const dependencies = {
      NextResponse: { json: Response.json }, db: {}, PollMutationError: Error,
      mutatePoll: async (_, id, owner, action) => { calls.push({ id, owner, action }); return { message: { id: 'm' }, conversationId: 'room', changed: true } },
      mapMessage: (m, viewer = null) => ({ ...m, viewer }), memberIdsOf: async () => ['author', 'peer'],
      notifySocket: async (...args) => { fanouts.push(args) }, safeJson: req => req.json(),
      strField: v => typeof v === 'string' ? v.trim() : '', MESSAGE_FULL_INCLUDE: {},
      bearerToken: req => req.headers.get('Authorization'), requireActionOwner: async () => Response.json({}, { status: 403 }),
    }
    const post = new Function(...Object.keys(dependencies), source + '; return POST')(...Object.values(dependencies))
    const request = (body, auth = false) => post(new Request('https://poll.test', { method: 'POST', body: JSON.stringify(body),
      headers: auth ? { Authorization: 'Bearer wrong-owner' } : {} }), { params: Promise.resolve({ id: 'poll' }) })
    assert.equal((await request({})).status, 400)
    assert.equal((await request({ userId: 'author', optionId: 'yes' }, true)).status, 403)
    assert.equal(calls.length, 0)
    const response = await request({ userId: 'author', optionId: 'yes' })
    assert.equal(response.status, 200)
    assert.deepEqual(calls, [{ id: 'poll', owner: 'author', action: kind === 'vote' ? { kind, optionId: 'yes' } : { kind } }])
    assert.deepEqual(fanouts[0][1], ['author', 'peer'])
    assert.equal(fanouts[0][2].message.viewer, null)
    assert.equal((await response.json()).message.viewer, 'author')
  }
})
