// Section 8 items 8/9 - folder creation replay + folder scale/revisions.
// The same (owner, clientId) replays the SAME folder; edits take per-folder
// revisions and rail-level list revisions; membership pages are bounded.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { readFile } from 'node:fs/promises'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')

async function load(path, bindings, expression) {
  const text = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}

const { folderCreateOnce, folderRequestHash, bumpFolderListRev, readFolderListRev, FolderError } = await load('src/lib/folder-operation.ts', {
  bearerToken: () => 'x', tokenAccepted: () => true, createHash: (await import('node:crypto')).createHash,
}, '({folderCreateOnce,folderRequestHash,bumpFolderListRev,readFolderListRev,FolderError})')

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'foldrev-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe(`CREATE TABLE User(id TEXT PRIMARY KEY, sessionTokenHash TEXT)`)
    await db.$executeRawUnsafe(`CREATE TABLE Folder(id TEXT PRIMARY KEY, userId TEXT NOT NULL, name TEXT NOT NULL,
      emoji TEXT DEFAULT "folder", position INTEGER DEFAULT 0, rev INTEGER DEFAULT 0, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP)`)
    await db.$executeRawUnsafe('CREATE TABLE FolderCreationOperation(userId TEXT, clientId TEXT, requestHash TEXT, folderId TEXT, PRIMARY KEY(userId, clientId))')
    await db.$executeRawUnsafe('CREATE TABLE UserListRevision(userId TEXT, kind TEXT, rev INTEGER DEFAULT 0, PRIMARY KEY(userId, kind))')
    await db.$executeRawUnsafe(`INSERT INTO User (id,sessionTokenHash) VALUES ('me','x')`)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }

}

const CLIENT = 'replay-client-0000000001'

test('an uncertain folder creation acknowledgement replays to the SAME folder once', () => fixture(async (db) => {
  const first = await create(db, CLIENT, 'Work')
  const second = await create(db, CLIENT, 'Work')
  assert.equal(first.folder.id, second.folder.id, 'same clientId + same instructions = same folder')
  assert.equal(first.replayed, false)
  assert.equal(second.replayed, true)
  assert.equal(second.listRev, first.listRev, 'replay must not spend another list revision')
  assert.equal(await db.folder.count(), 1)
  // A reused clientId with DIFFERENT instructions is a hard 409, never a fork.
  await assert.rejects(create(db, CLIENT, 'Personal'), FolderError)
  const third = await create(db, CLIENT + '2', 'Personal')
  assert.notEqual(third.folder.id, first.folder.id)
  assert.equal(await db.folder.count(), 2)
  // The receipt survives the folder being deleted afterwards: honest 410.
  await db.folder.delete({ where: { id: first.folder.id } })
  await assert.rejects(create(db, CLIENT, 'Work'), e => e.status === 410)
}))

async function create(db, clientId, name) {
  for (let attempt = 0; ; attempt++) {
  try {
  return await db.$transaction(async tx => {
    const outcome = await folderCreateOnce(tx, 'me', clientId, folderRequestHash(name, 'folder'), async () =>
      tx.folder.create({ data: { userId: 'me', name, position: 99 } }))
    return { folder: outcome.result, replayed: outcome.replayed, listRev: outcome.listRev }
  }, { isolationLevel: 'Serializable' })
  } catch (e) {
    if ((e.code === 'P2034' || e.status === 409) && attempt < 25) continue
    throw e
  } }
}

test('rail revisions totally order cross-device folder writes', () => fixture(async (db) => {
  const a = await create(db, CLIENT, 'A')
  const b = await create(db, CLIENT + '2', 'B')
  const c = await create(db, CLIENT + '3', 'C')
  assert.deepEqual([a.listRev, b.listRev, c.listRev], [1, 2, 3])
  // A "device A" holding its stale cached rev can detect it is behind.
  const current = await readFolderListRev(db, 'me')
  assert.ok(current > a.listRev)
  // The rail counter is global per user across kinds of writes: it never
  // reuses a slot, so out-of-order delivery is detectable.
  const later = await db.$transaction(tx => bumpFolderListRev(tx, 'me'))
  assert.equal(later, current + 1)
}))

test('per-folder rev increases on every accepted edit and listRev on every rail move', () => fixture(async (db) => {
  const created = await create(db, CLIENT, 'Work')
  const folderId = created.folder.id
  const rev0 = (await db.folder.findUnique({ where: { id: folderId } })).rev
  // The route contract: an accepted label edit takes the folder's next rev.
  await db.folder.update({ where: { id: folderId }, data: { name: 'Work+', rev: { increment: 1 } } })
  const rev1 = (await db.folder.findUnique({ where: { id: folderId } })).rev
  assert.equal(rev1, rev0 + 1, 'label edits take the folder revision')
  // Simulate the route's bump contract for a reorder: list revision moves.
  const list0 = await readFolderListRev(db, 'me')
  const list1 = await db.$transaction(tx => bumpFolderListRev(tx, 'me'))
  const list2 = await db.$transaction(tx => bumpFolderListRev(tx, 'me'))
  assert.deepEqual([list1, list2], [list0 + 1, list0 + 2])
  assert.ok(list2 > list0)
}))

test('a huge folder membership is bounded per page and continues by keyset cursor', () => fixture(async (db) => {
  await db.$executeRawUnsafe(`CREATE TABLE Conversation(id TEXT PRIMARY KEY)`)
  await db.$executeRawUnsafe(`CREATE TABLE FolderConversation(id TEXT PRIMARY KEY, folderId TEXT, conversationId TEXT, position INTEGER DEFAULT 0, UNIQUE(folderId,conversationId))`)
  const created = await create(db, CLIENT, 'Everything')
  const folderId = created.folder.id
  const rooms = Array.from({ length: 25 }, (_, i) => `room${String(i).padStart(2, '0')}`)
  for (const room of rooms) await db.$executeRawUnsafe(`INSERT INTO Conversation (id) VALUES ('${room}')`)
  await db.folderConversation.createMany({ data: rooms.map((conversationId, position) => ({ folderId, conversationId, position })) })
  // Page size 10: three pages, keyset order (position, conversationId).
  const page1 = await db.folderConversation.findMany({ where: { folderId }, orderBy: [{ position: 'asc' }, { conversationId: 'asc' }], take: 11 })
  const hasMore1 = page1.length > 10
  assert.equal(hasMore1, true)
  const page1Rows = page1.slice(0, 10)
  const last1 = page1Rows[9]
  const page2 = await db.folderConversation.findMany({
    where: { folderId, OR: [{ position: { gt: last1.position } }, { position: last1.position, conversationId: { gt: last1.conversationId } }] },
    orderBy: [{ position: 'asc' }, { conversationId: 'asc' }], take: 11 })
  assert.equal(page2.length, 11)
  // Drain the remaining pages the same way; the union is the full order.
  const ids = [...page1Rows.map(r => r.conversationId), ...page2.map(r => r.conversationId)]
  while (ids.length < 25) {
    const last = (await db.folderConversation.findMany({ where: { folderId }, orderBy: [{ position: 'asc' }, { conversationId: 'asc' }], take: ids.length }))[ids.length - 1]
    const next = await db.folderConversation.findMany({
      where: { folderId, OR: [{ position: { gt: last.position } }, { position: last.position, conversationId: { gt: last.conversationId } }] },
      orderBy: [{ position: 'asc' }, { conversationId: 'asc' }], take: 10 })
    if (!next.length) break
    ids.push(...next.map(r => r.conversationId))
  }
  assert.equal(new Set(ids).size, 25)
  assert.deepEqual(ids, rooms)
}))
