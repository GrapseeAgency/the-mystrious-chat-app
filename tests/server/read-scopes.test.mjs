import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { unreadSnapshot, threadRootAvailable } from '../../src/lib/message-visibility.ts'
import { ReadReceiptError } from '../../src/lib/read-receipt.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-read-scopes-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'db.sqlite')}` })
  try {
    for (const sql of [
      'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,joinedAt DATETIME,lastReadAt DATETIME,manualUnread BOOLEAN DEFAULT false,UNIQUE(userId,conversationId))',
      'CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,senderId TEXT,createdAt DATETIME,parentId TEXT,topicId TEXT,deletedAt DATETIME,expiresAt DATETIME,viaAutomation BOOLEAN DEFAULT false)',
      'CREATE TABLE Topic(id TEXT PRIMARY KEY,conversationId TEXT)',
    ]) await db.$executeRawUnsafe(sql)
    const migration = (await Promise.all(['20261026_read_intent_state.sql', '20261026_exact_read_observations.sql'].map(name =>
      readFile(new URL(`../../prisma/patches/${name}`, import.meta.url), 'utf8')))).join('\n')
    await db.$transaction(async tx => {
      for (const sql of migration.replace(/^--.*$/gm, '').split(';').map(s => s.trim()).filter(s => s && !['BEGIN IMMEDIATE','COMMIT'].includes(s))) await tx.$executeRawUnsafe(sql)
    })
    await db.$executeRawUnsafe("INSERT INTO ConversationParticipant VALUES ('membership','reader','room',0,0,false)")
    await db.$executeRawUnsafe("INSERT INTO Topic VALUES ('topic-a','room'),('topic-b','room'),('foreign-topic','other')")
    await db.$executeRawUnsafe(`INSERT INTO Message(id,conversationId,senderId,createdAt,parentId,topicId) VALUES
      ('root-a','room','peer',10,NULL,'topic-a'),('root-b','room','peer',10,NULL,'topic-b'),
      ('a','room','peer',20,'root-a',NULL),('b','room','peer',20,'root-b',NULL),
      ('foreign-root','other','peer',10,NULL,NULL)`)
    await db.$executeRawUnsafe("INSERT INTO MessageReadObservation(participantId,messageId,observedAt) VALUES ('membership','a',20)")
    const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/conversations/[id]/read-state/route.ts', import.meta.url), 'utf8'))
      .replace(/^import .*$/gm, '').replace(/export /g, '')
    const bindings = { db, unreadSnapshot, threadRootAvailable, ReadReceiptError, NextResponse: { json: Response.json },
      strField: v => typeof v === 'string' ? v.trim() : '',
      requireActionOwner: async (req, owner) => req.headers.get('authorization') === 'Bearer synthetic-reader' && owner === 'reader'
        ? null : Response.json({ error: 'denied' }, { status: 403 }),
    }
    const get = new Function(...Object.keys(bindings), source + ';return GET')(...Object.values(bindings))
    const request = (query = '', headers = { authorization: 'Bearer synthetic-reader' }) => get(
      new Request(`https://pulse.test/api/conversations/room/read-state?userId=reader${query}`, { headers }),
      { params: Promise.resolve({ id: 'room' }) })
    await run(db, request)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

test('actual scoped route returns independent thread/topic counts and exact anchor', () => fixture(async (_, request) => {
  for (const [query, count, anchor] of [
    ['&threadId=root-a', 0, null], ['&threadId=root-b', 1, 'b'],
    ['&topicId=topic-a', 1, 'root-a'], ['&topicId=topic-b', 2, 'root-b'], ['', 3, 'root-a'],
  ]) {
    const response = await request(query)
    assert.equal(response.status, 200)
    assert.equal(response.headers.get('cache-control'), 'private, no-store')
    const body = await response.json()
    assert.equal(body.readStateVersion, 1); assert.equal(body.conversationId, 'room'); assert.equal(body.userId, 'reader')
    assert.equal(body.unreadCount, count); assert.equal(body.firstUnreadMessageId, anchor)
  }
}))

test('scope reads recheck authority, membership, parent lifetime and topic ownership', () => fixture(async (db, request) => {
  assert.equal((await request('', {})).status, 403)
  for (const query of ['&threadId=', '&topicId=', '&threadId=root-a&topicId=topic-a']) assert.equal((await request(query)).status, 400)
  assert.equal((await request('&topicId=foreign-topic')).status, 404)
  assert.equal((await request('&threadId=foreign-root')).status, 410)
  await db.$executeRawUnsafe("UPDATE Message SET expiresAt=0 WHERE id='root-a'")
  assert.equal((await request('&threadId=root-a')).status, 410)
  await db.$executeRawUnsafe("DELETE FROM ConversationParticipant WHERE id='membership'")
  assert.equal((await request()).status, 403)
}))
