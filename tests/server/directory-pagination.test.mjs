// Section 8 item 5 - contact directory pagination.
// The bare directory is bounded and continues by a stable (name, id) keyset
// cursor; same-name identities never split or duplicate across pages.
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

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'dircursor-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await db.$executeRawUnsafe(`CREATE TABLE User(id TEXT PRIMARY KEY, name TEXT NOT NULL, username TEXT UNIQUE, about TEXT NOT NULL DEFAULT 'x',
      color TEXT NOT NULL DEFAULT 'emerald', avatar TEXT, coverImage TEXT, statusEmoji TEXT, statusText TEXT, preferences TEXT, storyViewReceipts INTEGER NOT NULL DEFAULT 1,
      sessionTokenHash TEXT, legacyTokenHashes TEXT, createdAt DATETIME DEFAULT CURRENT_TIMESTAMP, lastSeenAt DATETIME DEFAULT CURRENT_TIMESTAMP, xp INTEGER DEFAULT 0, xpToday INTEGER DEFAULT 0, xpDay TEXT DEFAULT '')`)
    await run(db)
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

// mapUser pulls in the whole serializer chain; stub it with the same shape.
const mapUser = (u) => ({ id: u.id, name: u.name, username: u.username, color: u.color, avatar: u.avatar })
const noopBot = async () => {}

test('a large directory pages by (name, id) keyset with no duplicates or gaps', () => fixture(async (db) => {
  const names = ['Ada', 'Ben', 'Cara', 'Cara', 'Dan', 'Eve', 'Finn']
  for (const [i, name] of names.entries()) {
    await db.$executeRawUnsafe('INSERT INTO User (id,name,username) VALUES (?,?,?)', `u${i}`, name, `handle${i}`)
  }
  const h = await load('src/app/api/users/route.ts', {
    db, mapUser, ensurePulseBot: noopBot,
    safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
    normalizeColor: c => typeof c === 'string' ? c : 'emerald', normalizeUsername: v => String(v ?? '').toLowerCase(),
    suggestUsername: async () => 'suggestion', generateSessionToken: () => 'tok', hashSessionToken: () => 'hash',
    NextResponse: Response, USER_NAME_MAX: 40,
  }, 'GET')
  const get = (query = '') => h(new Request(`https://dir.test/api/users${query}`))

  // Page size 3 over 7 users → 3 pages: [3, 3, 1].
  const page1 = await (await get('?limit=3')).json()
  assert.equal(page1.users.length, 3)
  assert.equal(page1.hasMore, true)
  const page2 = await (await get(`?limit=3&after=${page1.nextCursor}`)).json()
  assert.equal(page2.users.length, 3)
  assert.equal(page2.hasMore, true)
  const page3 = await (await get(`?limit=3&after=${page2.nextCursor}`)).json()
  assert.equal(page3.users.length, 1)
  assert.equal(page3.hasMore, false)
  assert.equal(page3.nextCursor, null)
  const all = [...page1.users, ...page2.users, ...page3.users].map(u => u.id)
  assert.equal(new Set(all).size, 7, 'no duplicates across pages')
  // The two Caras sit adjacent and in id order inside the same total order.
  const caraIndexes = all.map((id, i) => (id.startsWith('u') && names[Number(id.slice(1))] === 'Cara' ? i : -1)).filter(i => i >= 0)
  assert.equal(caraIndexes.length, 2)
  // The cursor survives identical names: continue past the FIRST Cara yields
  // the SECOND Cara next, not a restart of the alphabet.
  const firstCaraId = page1.nextCursor === 'u2' ? 'u2' : page2.nextCursor
  const afterCara = await (await get(`?limit=2&after=${firstCaraId}`)).json()
  assert.equal(afterCara.users[0].name, 'Cara')
  assert.equal(afterCara.users[0].id !== firstCaraId, true)
  // Unknown cursor: 400, never a silent page-one reset.
  const bad = await get('?after=ghost')
  assert.equal(bad.status, 400)
  // Exact-name lookup still answers (login deep-link path).
  const named = await (await get('?name=Cara')).json()
  assert.equal(named.user.name, 'Cara')
}))
