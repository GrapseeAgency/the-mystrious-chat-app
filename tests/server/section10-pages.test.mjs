// Section 10 items 2/5/8/10 - bounded history pages for event history,
// personal tasks, Kanban boards, and whiteboard strokes. Legacy callers keep
// their exact old shapes; paged walks drain any size with nextCursor null
// exactly when hasMore is false.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import * as section10 from '../../src/lib/section10-contracts.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

async function routes(path, deps) {
  const source = stripTypeScriptTypes(await readFile(new URL('../../' + path, import.meta.url), 'utf8'))
    .replace(/^import [\s\S]*?from '.*'$/gm, '').replace(/^import .*$/gm, '').replace(/export /g, '')
  return new Function(...Object.keys(deps), source + '; return {GET:typeof GET==="function"?GET:null,POST:typeof POST==="function"?POST:null}')(...Object.values(deps))
}

async function fixture(tables, seed, run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-s10-pages-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of tables) await db.$executeRawUnsafe(sql)
    if (seed) await seed(db)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

const BASE = [
  'CREATE TABLE User(id TEXT PRIMARY KEY, name TEXT)',
  'CREATE TABLE Conversation(id TEXT PRIMARY KEY, isGroup BOOLEAN, metadataRev INTEGER DEFAULT 0)',
  'CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY,userId TEXT,conversationId TEXT,role TEXT,UNIQUE(userId,conversationId))',
  'CREATE TABLE RoomListRevision(conversationId TEXT,kind TEXT,rev INTEGER DEFAULT 0,PRIMARY KEY(conversationId,kind))',
  "INSERT INTO User VALUES ('me','Me')",
  'INSERT INTO Conversation (id,isGroup) VALUES (\'room\',true)',
  "INSERT INTO ConversationParticipant VALUES ('p','me','room','member')",
]

test('item 2: event history pages drain past the fifty-event boundary in creation order', () => fixture([
  ...BASE,
  'CREATE TABLE GroupEvent(id TEXT PRIMARY KEY,conversationId TEXT,title TEXT,description TEXT NOT NULL DEFAULT "",location TEXT NOT NULL DEFAULT "",startsAt DATETIME,createdById TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
  'CREATE TABLE EventRsvp(id TEXT PRIMARY KEY,eventId TEXT,userId TEXT,status TEXT,checkedInAt DATETIME,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(eventId,userId))',
], async db => {
  const base = Date.parse('2026-10-20T00:00:00Z')
  for (let i = 0; i < 70; i++) {
    await db.$executeRawUnsafe(`INSERT INTO GroupEvent (id,conversationId,title,description,location,startsAt,createdAt) VALUES ('e${String(i).padStart(2, '0')}','room','Event ${i}','','',${base + i},${base + i})`)
  }
}, async db => {
  const route = await routes('src/app/api/conversations/[id]/events/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(),
    strField: v => typeof v === 'string' ? v.trim() : '', requireActionOwner: async () => null, ...section10,
  })
  const ctx = { params: Promise.resolve({ id: 'room' }) }

  // Paged walk: 70 events in 10-row pages, newest created first.
  const seen = []
  let cursor = null
  for (let pages = 0; pages < 10; pages++) {
    const url = `https://events.test?userId=me&limit=10${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`
    const body = await (await route.GET(new Request(url), ctx)).json()
    seen.push(...body.events.map(e => e.id))
    if (!body.hasMore) { assert.equal(body.nextCursor, null); break }
    assert.ok(body.nextCursor, 'hasMore implies a continuation')
    cursor = body.nextCursor
  }
  assert.equal(seen.length, 70)
  assert.equal(new Set(seen).size, 70)
  assert.equal(seen[0], 'e69', 'newest first')
  assert.equal(seen.at(-1), 'e00')

  // Legacy shape is untouched: still the merged cap of 50.
  const legacy = await (await route.GET(new Request('https://events.test?userId=me'), ctx)).json()
  assert.ok(!('hasMore' in legacy))
  assert.ok(legacy.events.length <= 50)
  assert.equal(typeof legacy.eventsRev, 'number')
  // A bad cursor is a 400, never a silent page-one reset.
  const bad = await route.GET(new Request('https://events.test?userId=me&limit=10&cursor=garbage'), ctx)
  assert.equal(bad.status, 400)
  const badLimit = await route.GET(new Request('https://events.test?userId=me&limit=61'), ctx)
  assert.equal(badLimit.status, 400)
}))

test('item 5: personal-task history pages drain to completion for one owner', () => fixture([
  'CREATE TABLE User(id TEXT PRIMARY KEY)',
  'CREATE TABLE HubTask(id TEXT PRIMARY KEY,ownerId TEXT,title TEXT,status TEXT NOT NULL DEFAULT "todo",createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updatedAt DATETIME NOT NULL)',
  'CREATE TABLE TaskCreationOperation(userId TEXT,clientId TEXT,requestHash TEXT,taskId TEXT,PRIMARY KEY(userId,clientId))',
  "INSERT INTO User VALUES ('me')",
], async db => {
  for (let i = 0; i < 120; i++) {
    await db.hubTask.create({ data: { id: `t${String(i).padStart(3, '0')}`, ownerId: i < 115 ? 'me' : 'other', title: `Task ${i}` } })
  }
}, async db => {
  const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/hub/tasks/route.ts', import.meta.url), 'utf8'))
    .replace(/^import [\s\S]*?from '.*'$/gm, '').replace(/^import .*$/gm, '').replace(/export /g, '')
  const deps = { db, NextResponse: { json: Response.json }, safeJson: r => r.json(),
    strField: v => typeof v === 'string' ? v.trim() : '', requireActionOwner: async () => null, ...section10 }
  const route = new Function(...Object.keys(deps), source + '; return {GET}')(...Object.values(deps))

  const seen = []
  let cursor = null
  for (let pages = 0; pages < 5; pages++) {
    const url = `https://hub.test?userId=me&limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`
    const body = await (await route.GET(new Request(url))).json()
    seen.push(...body.tasks.map(t => t.id))
    if (!body.hasMore) { assert.equal(body.nextCursor, null); break }
    cursor = body.nextCursor
  }
  assert.equal(seen.length, 115, 'only the owner rows')
  assert.equal(new Set(seen).size, 115)
  assert.equal(seen[0], 't114', 'newest created first')
}))

test('item 8: kanban boards page past 500 cards; the legacy snapshot still refuses partial boards', () => fixture([
  ...BASE,
  'CREATE TABLE KanbanCard(id TEXT PRIMARY KEY,conversationId TEXT,title TEXT,column TEXT,position INTEGER,assigneeId TEXT,createdById TEXT,sourceMessageId TEXT,sourceRevokedAt TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updatedAt DATETIME NOT NULL)',
], async db => {
  const base = Date.parse('2026-10-20T00:00:00Z')
  for (let i = 0; i < 505; i++) {
    const column = ['todo', 'doing', 'done'][i % 3]
    await db.$executeRawUnsafe(`INSERT INTO KanbanCard (id,conversationId,title,column,position,createdAt,updatedAt) VALUES ('c${String(i).padStart(3, '0')}','room','Card ${i}','${column}',${i},${base + i},${base + i})`)
  }
}, async db => {
  const { kanbanOperation, KanbanError } = await import('../../src/lib/kanban-operation.ts')
  const listPaged = limit => kanbanOperation(db, 'me', { kind: 'list', conversationId: 'room', limit: String(limit), cursor: null }, () => new Date())

  const page1 = await listPaged(200)
  assert.equal(page1.cards.length, 200)
  assert.equal(page1.hasMore, true)
  assert.ok(page1.nextCursor)
  assert.equal(typeof page1.boardRev, 'number')
  // No 413 boundary in paged mode: the walk completes.
  const page2 = await kanbanOperation(db, 'me', { kind: 'list', conversationId: 'room', limit: '200', cursor: page1.nextCursor }, () => new Date())
  assert.equal(page2.cards.length, 200)
  const page3 = await kanbanOperation(db, 'me', { kind: 'list', conversationId: 'room', limit: '200', cursor: page2.nextCursor }, () => new Date())
  assert.equal(page3.cards.length, 105)
  assert.equal(page3.hasMore, false)
  assert.equal(page3.nextCursor, null)
  const all = [...page1.cards, ...page2.cards, ...page3.cards]
  assert.equal(new Set(all.map(c => c.id)).size, 505)

  // Legacy snapshot mode: unchanged complete-or-error refusal.
  await assert.rejects(() => kanbanOperation(db, 'me', { kind: 'list', conversationId: 'room' }, () => new Date()),
    error => error instanceof KanbanError && error.status === 413)
}))

test('item 10: whiteboard stroke pages drain past the four-thousand-stroke snapshot', () => fixture([
  ...BASE,
  'CREATE TABLE WhiteboardStroke(id TEXT PRIMARY KEY,conversationId TEXT,userId TEXT,color TEXT,width REAL,points TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
  'CREATE TABLE LogEvent(id TEXT PRIMARY KEY,userId TEXT,kind TEXT,message TEXT,meta TEXT,createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)',
], async db => {
  const base = Date.now() - 3600_000
  const rows = []
  for (let i = 0; i < 4050; i++) {
    rows.push(`('s${String(i).padStart(4, '0')}','room','me','#000000',3,'[]',${base + i})`)
  }
  await db.$executeRawUnsafe(`INSERT INTO WhiteboardStroke (id,conversationId,userId,color,width,points,createdAt) VALUES ${rows.join(',')}`)
}, async db => {
  const route = await routes('src/app/api/conversations/[id]/whiteboard/route.ts', {
    db, NextResponse: { json: Response.json }, safeJson: r => r.json(),
    strField: v => typeof v === 'string' ? v.trim() : '',
    boardResetAt: async () => null,
    commitBoardStrokes: async () => ({}),
    clearBoard: async () => ({}),
    WhiteboardConflict: class extends Error {},
  })
  const ctx = { params: Promise.resolve({ id: 'room' }) }

  const seen = []
  let cursor = null
  for (let pages = 0; pages < 12; pages++) {
    const url = `https://wb.test?requesterId=me&limit=500${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`
    const body = await (await route.GET(new Request(url), ctx)).json()
    seen.push(...body.strokes.map(s => s.id))
    if (!body.hasMore) { assert.equal(body.nextCursor, null); break }
    assert.ok(body.nextCursor)
    cursor = body.nextCursor
  }
  assert.equal(seen.length, 4050, 'the whole board drains, no 4000 ceiling')
  assert.equal(new Set(seen).size, 4050)
  assert.equal(seen[0], 's0000', 'oldest stroke first (playback order)')
  assert.equal(seen.at(-1), 's4049')
  const bad = await route.GET(new Request('https://wb.test?requesterId=me&limit=501'), ctx)
  assert.equal(bad.status, 400)
}))
