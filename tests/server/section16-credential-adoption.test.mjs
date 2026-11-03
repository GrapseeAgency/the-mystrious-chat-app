// Section 16 item 4 - legacy web credential migration (Wave 8 §3.11 phase 2).
//
// The hardened routes reject headerless calls (requireActionOwner/Actor:
// 401 without a bearer, 403 for someone else's credential), and the native
// HTTP clients attach `Authorization: Bearer` on every request family
// (Wave8ApiTest). The shared web apiJson callers did not - this suite pins
// the web-side adoption end to end:
//   - apiJson attaches the registered resolver's token (and stays honest
//     when the resolver is absent, null, or throws),
//   - the token store round-trips through localStorage with a defensive
//     size bound, reuses a stored credential instead of rotating, and only
//     mints when absent,
//   - identity creation hands the mounted web shell a usable credential on
//     the same 201 (raw token, sha256 stored server-side),
//   - reclaiming the identity from a second browser rotates the primary but
//     the first device's token keeps working (R52 grace list) - the
//     concurrent-device reality the reuse-or-mint rule protects.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import {
  generateSessionToken,
  hashSessionToken,
  bearerToken,
  tokenAccepted,
  pushLegacyTokenHash,
} from '../../src/lib/session-token.ts'
const { PrismaClient, Prisma } = createRequire(import.meta.url)('../../prisma/generated-client')

async function load(path, bindings, expression) {
  const text = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}

// --- apiJson bearer attach (the shared web helper) --------------------------

test('apiJson attaches the registered session bearer and stays honest without one', async () => {
  const calls = []
  const fetchStub = async (path, init) => {
    calls.push(init)
    return { ok: true, status: 200, headers: { get: () => null }, json: async () => ({}) }
  }
  const { apiJson, setAuthTokenResolver } = await load('src/lib/pulse-utils.ts', { fetch: fetchStub }, '({apiJson,setAuthTokenResolver})')

  // no resolver registered -> headerless (the pre-adoption behavior, still the
  // honest fallback for tests, SSR and private-mode storage failures)
  await apiJson('/api/health')
  assert.equal(calls[0].headers.Authorization, undefined)
  assert.equal(calls[0].headers['Content-Type'], 'application/json')
  assert.equal(calls[0].cache, 'no-store')

  // a live resolver -> the bearer rides exactly like the native clients
  setAuthTokenResolver(() => 'web-token-1')
  await apiJson('/api/conversations')
  assert.equal(calls[1].headers.Authorization, 'Bearer web-token-1')
  assert.equal(calls[1].headers['Content-Type'], 'application/json')

  // caller headers still merge (and can still override)
  await apiJson('/api/settings', { method: 'PATCH', headers: { 'X-Trace': 't1' } })
  assert.equal(calls[2].headers['X-Trace'], 't1')
  assert.equal(calls[2].headers.Authorization, 'Bearer web-token-1')

  // a resolver that throws degrades to headerless, never to an invented token
  setAuthTokenResolver(() => { throw Error('storage exploded') })
  await apiJson('/api/health')
  assert.equal(calls[3].headers.Authorization, undefined)

  // explicit teardown resets (sign-out path)
  setAuthTokenResolver(null)
  await apiJson('/api/health')
  assert.equal(calls[4].headers.Authorization, undefined)
})

// --- the web token store (localStorage + reuse-or-mint) ----------------------

test('the web token store round-trips, bounds, reuses and only mints when absent', async () => {
  const backing = new Map()
  globalThis.window = {
    localStorage: {
      getItem: (k) => (backing.has(k) ? backing.get(k) : null),
      setItem: (k, v) => backing.set(k, String(v)),
      removeItem: (k) => backing.delete(k),
    },
  }
  let mintResult = { ok: true, status: 200, json: async () => ({ token: 'minted-1' }) }
  const mintCalls = []
  try {
    const mod = await load('src/lib/session-token-client.ts', {
      fetch: async (path, init) => { mintCalls.push({ path, init }); return mintResult },
    }, '({readStoredToken,storeSessionToken,clearSessionToken,mintSessionToken,ensureSessionCredential})')

    assert.equal(mod.readStoredToken('u1'), null)
    mod.storeSessionToken('u1', 'tok-1')
    assert.equal(mod.readStoredToken('u1'), 'tok-1')

    // defensive bound: an oversized/garbage blob is never adopted as a credential
    mod.storeSessionToken('u2', 'x'.repeat(200))
    assert.equal(mod.readStoredToken('u2'), null)

    mod.clearSessionToken('u1')
    assert.equal(mod.readStoredToken('u1'), null)

    // mint: happy path posts the name to the reclaim route
    assert.equal(await mod.mintSessionToken('Ada'), 'minted-1')
    assert.equal(mintCalls[0].path, '/api/users/login')
    assert.equal(JSON.parse(mintCalls[0].init.body).name, 'Ada')
    assert.equal(mintCalls[0].init.method, 'POST')
    // mint: unknown name / server failure -> null, never an invented token
    mintResult = { ok: false, status: 404, json: async () => ({ error: 'no' }) }
    assert.equal(await mod.mintSessionToken('Ada'), null)
    mintResult = { ok: true, status: 200, json: async () => ({ token: 42 }) }
    assert.equal(await mod.mintSessionToken('Ada'), null)

    // ensure: reuse beats rotation - a browser with a stored credential must
    // not log every other device out by reclaiming on every boot
    mintResult = { ok: true, status: 200, json: async () => ({ token: 'fresh-2' }) }
    mintCalls.length = 0
    mod.storeSessionToken('u9', 'have-one')
    await mod.ensureSessionCredential({ id: 'u9', name: 'Ada' })
    assert.equal(mintCalls.length, 0)
    assert.equal(mod.readStoredToken('u9'), 'have-one')
    await mod.ensureSessionCredential({ id: 'u8', name: 'Ben' })
    assert.equal(mintCalls.length, 1)
    assert.equal(mod.readStoredToken('u8'), 'fresh-2')
  } finally {
    delete globalThis.window
  }
})

// --- end to end through the real routes --------------------------------------

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'webcred-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const name of ['User', 'UserWallet', 'Conversation', 'ConversationParticipant']) {
      const model = Prisma.dmmf.datamodel.models.find(m => m.name === name)
      const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
        const type = ['Int', 'Boolean', 'BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'Float' ? 'REAL' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
        let def = ''
        if (typeof f.default === 'string') def = ` DEFAULT '${f.default.replaceAll("'", "''")}'`
        else if (typeof f.default === 'number' || typeof f.default === 'boolean') def = ` DEFAULT ${Number(f.default)}`
        else if (f.default?.name === 'now') def = ' DEFAULT CURRENT_TIMESTAMP'
        return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : ''}${def}`
      })
      if (model.primaryKey && model.primaryKey.fields.length > 1) {
        columns.push(`PRIMARY KEY (${model.primaryKey.fields.map(f => `"${f}"`).join(',')})`)
      }
      await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
    }
    const { requireActionOwner } = await load('src/lib/wallet-authorization.ts',
      { db, bearerToken, tokenAccepted, hashSessionToken }, '({requireActionOwner})')
    const mapUser = (u) => ({ id: u.id, name: u.name, username: u.username, color: u.color })
    const createUser = await load('src/app/api/users/route.ts', {
      db, mapUser, ensurePulseBot: async () => {},
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      normalizeColor: c => typeof c === 'string' ? c : 'emerald',
      normalizeUsername: v => String(v ?? '').toLowerCase(),
      suggestUsername: async () => 'suggestion', generateSessionToken, hashSessionToken,
      NextResponse: Response, USER_NAME_MAX: 40,
    }, 'POST')
    const loginName = await load('src/app/api/users/login/route.ts', {
      db, mapUser, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      generateSessionToken, hashSessionToken, pushLegacyTokenHash, NextResponse: Response,
    }, 'POST')
    const listConversations = (userId, token) => load('src/app/api/conversations/route.ts', {
      db, requireActionOwner,
      createConversationOnce: async () => { throw Error('unused in this suite') },
      DirectConversationError: class DirectConversationError extends Error {},
      openDirectConversation: async () => { throw Error('unused in this suite') },
      CONVERSATION_FULL_INCLUDE: { participants: true },
      buildConversationSummary: async row => ({ id: row.id, updatedAt: row.updatedAt.toISOString(), pinnedAt: null }),
      GROUP_NAME_MAX: 80, notifySocket: async () => {},
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      NextResponse: Response,
    }, 'GET').then(GET => GET(new Request(`https://cred.test/api/conversations?userId=${encodeURIComponent(userId)}`, {
      headers: token ? { authorization: `Bearer ${token}` } : {},
    })))
    await run({ db, createUser, loginName, listConversations })
  } finally {
    await db.$disconnect()
    await rm(dir, { recursive: true, force: true })
  }
}

test('identity creation hands the mounted web shell a working credential; rotation keeps other devices signed in', () => fixture(async ({ db, createUser, loginName, listConversations }) => {
  const created = await createUser(new Request('https://cred.test/api/users', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'Ada', color: 'amber' }),
  }))
  assert.equal(created.status, 201)
  const { user, token } = await created.json()
  assert.equal(typeof token, 'string')
  assert.ok(token.length >= 32, 'raw 32-byte hex credential rides the 201')
  assert.equal((await db.user.findUnique({ where: { id: user.id } })).sessionTokenHash, hashSessionToken(token))

  // the route the mounted shell loads first: the fresh credential works,
  // headerless is 401 (this is the migration this suite pins), a foreign
  // credential is 403
  assert.equal((await listConversations(user.id, token)).status, 200)
  assert.equal((await listConversations(user.id, null)).status, 401)
  assert.equal((await listConversations(user.id, 'not-a-real-credential')).status, 403)

  // reclaiming from a SECOND browser rotates the primary hash; the first
  // browser's credential keeps working (R52 grace list) - concurrent devices
  const second = await loginName(new Request('https://cred.test/api/users/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'Ada' }),
  }))
  assert.equal(second.status, 200)
  const reclaimed = (await second.json()).token
  assert.notEqual(reclaimed, token)
  assert.equal((await listConversations(user.id, token)).status, 200, 'first device survives the reclaim')
  assert.equal((await listConversations(user.id, reclaimed)).status, 200, 'second device works')

  // an identity whose credential neither matches primary nor grace list is out
  assert.equal((await listConversations(user.id, generateSessionToken())).status, 403)
}))
