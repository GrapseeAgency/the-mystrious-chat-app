import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
import { privacyPreferenceSnapshot, privacyPreferenceEnabled } from '../../src/lib/privacy-preferences.ts'
import { clientIdFrom, opHash } from '../../src/lib/section10-contracts.ts'
import * as settingsContracts from '../../src/lib/settings-contracts.ts'
const { PrismaClient, Prisma } = createRequire(import.meta.url)('../../prisma/generated-client')

async function load(path, bindings, expression) {
  const text = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}

const CID = 'a'.repeat(32)
const CID2 = 'b'.repeat(32)
const CID3 = 'c'.repeat(32)
// Intent timeline: T1 < T2 < T3 (device A writes "later" than device B).
const T1 = '2026-01-01T10:00:00.000Z'
const T2 = '2026-01-01T11:00:00.000Z'
const T3 = '2026-01-01T12:00:00.000Z'

async function settingsFixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'section13-settings-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const name of ['User', 'SettingsFieldStamp', 'SettingsRevision', 'SettingsOperation']) {
      const model = Prisma.dmmf.datamodel.models.find(m => m.name === name)
      const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
        const type = ['Int', 'Boolean', 'BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'Float' ? 'REAL' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
        let def = ''
        if (typeof f.default === 'string') def = ` DEFAULT '${f.default.replaceAll("'", "''")}'`
        else if (typeof f.default === 'number' || typeof f.default === 'boolean') def = ` DEFAULT ${Number(f.default)}`
        else if (f.default?.name === 'now') def = ' DEFAULT CURRENT_TIMESTAMP'
        return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : ''}${def}`
      })
      // @@id([a, b]) compound keys: DMMF carries them on model.primaryKey,
      // not on field.isId.
      if (model.primaryKey && model.primaryKey.fields.length > 1) {
        columns.push(`PRIMARY KEY (${model.primaryKey.fields.map(f => `"${f}"`).join(',')})`)
      }
      await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
    }
    await db.$executeRawUnsafe('CREATE UNIQUE INDEX "SettingsOperation_identity" ON "SettingsOperation"("kind","clientId")')
    for (const id of ['owner', 'other']) await db.user.create({
      data: { id, name: id, color: 'amber', preferences: '{"readReceipts":true,"density":"cozy"}',
        sessionTokenHash: hashSessionToken(`synthetic-${id}`), legacyTokenHashes: '[]' },
    })
    const { requireActionOwner } = await load('src/lib/wallet-authorization.ts', { db, bearerToken, tokenAccepted, hashSessionToken }, '({requireActionOwner})')
    const sanitizeConvThemeMap = await load('src/lib/conv-theme.ts', {}, 'sanitizeConvThemeMap')
    const mergePrefs = await load('src/lib/prefs-defaults.ts', { sanitizeConvThemeMap }, 'mergePrefs')
    const notices = []
    const route = await load('src/app/api/settings/route.ts', {
      db, requireActionOwner, mergePrefs, privacyPreferenceSnapshot,
      NextResponse: { json: Response.json }, clientIdFrom, ...settingsContracts,
      notifySocket: async (...args) => notices.push(args),
    }, '({GET,PATCH})')
    const patch = (preferences, extra = {}, token = 'synthetic-owner', userId = 'owner') => route.PATCH(new Request('https://settings.test', {
      method: 'PATCH',
      headers: token ? { authorization: `Bearer ${token}`, 'content-type': 'application/json' } : { 'content-type': 'application/json' },
      body: JSON.stringify({ userId, preferences, ...extra }),
    }))
    const get = (token = 'synthetic-owner', userId = 'owner') => route.GET(new Request(`https://settings.test?userId=${userId}`, {
      headers: token ? { authorization: `Bearer ${token}` } : {},
    }))
    await run({ db, patch, get, notices })
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

test('a durable settings write commits once and replays its stored receipt after a lost ACK', () => settingsFixture(async ({ db, patch }) => {
  const first = await patch({ readReceipts: false }, { clientId: CID, intentAt: T1 })
  assert.equal(first.status, 200)
  const body = await first.json()
  assert.deepEqual(body.applied, ['readReceipts'])
  assert.equal(body.revision, 1)
  assert.equal(body.replayed, undefined)

  // The device timed out and re-sent the SAME durable identity: it gets the
  // stored result back, the blob is not re-merged, and the revision is NOT
  // bumped a second time.
  const replay = await patch({ readReceipts: false }, { clientId: CID, intentAt: T1 })
  assert.equal(replay.status, 200)
  const replayBody = await replay.json()
  assert.equal(replayBody.replayed, true)
  assert.equal(replayBody.revision, 1)
  assert.equal(await db.settingsRevision.count(), 1)
  assert.equal(await db.settingsOperation.count(), 1)
}))

test('a reused settings identity with different instructions is a conflict, never a divergent commit', () => settingsFixture(async ({ patch }) => {
  assert.equal((await patch({ readReceipts: false }, { clientId: CID, intentAt: T1 })).status, 200)
  const clash = await patch({ density: 'compact' }, { clientId: CID, intentAt: T2 })
  assert.equal(clash.status, 409)
  assert.equal((await clash.json()).code, 'settings_operation_mismatch')
}))

test('a slower device writes are resolved field-by-field by explicit intent time', () => settingsFixture(async ({ db, patch }) => {
  // Device A (newer intent T2) writes density.
  const a = await patch({ density: 'compact' }, { clientId: CID, intentAt: T2 })
  assert.equal(a.status, 200)
  // Device B (older intent T1) writes density AND a fresh field. The older
  // density intent must lose; the untouched wallpaper field must still apply.
  const b = await patch({ density: 'cozy', wallpaper: 'forest' }, { clientId: CID2, intentAt: T1 })
  assert.equal(b.status, 200)
  const bBody = await b.json()
  assert.deepEqual(bBody.superseded, ['density'])
  assert.deepEqual(bBody.applied, ['wallpaper'])
  const stored = JSON.parse((await db.user.findUnique({ where: { id: 'owner' } })).preferences)
  assert.equal(stored.density, 'compact') // A's newer write stands
  assert.equal(stored.wallpaper, 'forest') // B's fresh field was not dragged down

  // A still-newer intent (T3) wins from any device.
  const c = await patch({ density: 'cozy' }, { clientId: CID3, intentAt: T3 })
  assert.deepEqual((await c.json()).superseded, [])
  const after = JSON.parse((await db.user.findUnique({ where: { id: 'owner' } })).preferences)
  assert.equal(after.density, 'cozy')
}))

test('distinct fields from two devices merge without erasing each other and revisions march forward', () => settingsFixture(async ({ db, patch, get }) => {
  const a = await patch({ readReceipts: false }, { clientId: CID, intentAt: T1 })
  const b = await patch({ density: 'compact' }, { clientId: CID2, intentAt: T2 })
  assert.deepEqual([a.status, b.status], [200, 200])
  assert.deepEqual([(await a.json()).revision, (await b.json()).revision], [1, 2])
  const stored = JSON.parse((await db.user.findUnique({ where: { id: 'owner' } })).preferences)
  assert.equal(stored.readReceipts, false)
  assert.equal(stored.density, 'compact')

  const view = await (await get()).json()
  assert.equal(view.revision, 2)
  assert.equal(view.fieldStamps.readReceipts.writer, CID)
  assert.equal(view.fieldStamps.density.writer, CID2)
  assert.equal(view.fieldStamps.readReceipts.intentAt, T1)
}))

test('every accepted settings patch is pushed to the owner immediately; rejected writes are silent', () => settingsFixture(async ({ patch, notices }) => {
  assert.equal((await patch({ readReceipts: false }, { clientId: CID, intentAt: T1 })).status, 200)
  assert.deepEqual(notices, [['settings:changed', ['owner'], { userId: 'owner', revision: 1 }]])
  // A foreign credential must neither write nor notify.
  await patch({ density: 'compact' }, { clientId: CID2, intentAt: T2 }, 'synthetic-other')
  await patch({ density: 'compact' }, {}, null)
  assert.equal(notices.length, 1)
  // The owner's next accepted write pushes again with the new revision.
  await patch({ density: 'compact' }, { clientId: CID2, intentAt: T2 })
  assert.deepEqual(notices[1], ['settings:changed', ['owner'], { userId: 'owner', revision: 2 }])
}))

test('legacy callers without receipts keep the immediate-apply behavior with honest provenance', () => settingsFixture(async ({ db, patch, get }) => {
  const legacy = await patch({ density: 'compact' })
  assert.equal(legacy.status, 200)
  const body = await legacy.json()
  assert.deepEqual(body.applied, ['density'])
  assert.equal(body.revision, 1)
  const view = await (await get()).json()
  assert.equal(view.fieldStamps.density.writer, 'legacy')
  assert.equal(JSON.parse((await db.user.findUnique({ where: { id: 'owner' } })).preferences).density, 'compact')
}))

test('intent timestamps are validated, not silently coerced to arrival time', () => settingsFixture(async ({ patch }) => {
  assert.equal((await patch({ density: 'compact' }, { clientId: CID, intentAt: 'not-a-time' })).status, 400)
  assert.equal((await patch({ density: 'compact' }, { clientId: CID, fieldTimes: { density: 42 } })).status, 400)
}))

test('the story receipt column mirror survives the LWW ledger', () => settingsFixture(async ({ db, patch }) => {
  assert.equal((await patch({ storyViewReceipts: false }, { clientId: CID, intentAt: T2 })).status, 200)
  assert.equal((await db.user.findUnique({ where: { id: 'owner' } })).storyViewReceipts, false)
  // An older intent for the same field is superseded and must NOT touch the column.
  const old = await patch({ storyViewReceipts: true }, { clientId: CID2, intentAt: T1 })
  assert.deepEqual((await old.json()).superseded, ['storyViewReceipts'])
  assert.equal((await db.user.findUnique({ where: { id: 'owner' } })).storyViewReceipts, false)
}))

test('preferences payload cap still refuses oversize blobs', () => settingsFixture(async ({ patch }) => {
  // 64-char room ids (the sanitizer's max) x 48 kept entries: a valid map
  // that still breaches the 4KB serialized budget must be refused, not
  // silently persisted.
  const response = await patch({ 'chat.convThemes': Object.fromEntries(
    Array.from({ length: 60 }, (_, i) => [`${'c'.repeat(60)}${i}`, { wallpaper: 'aurora' }]),
  ) }, { clientId: CID, intentAt: T1 })
  assert.equal(response.status, 413)
}))

async function profileFixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'section13-profile-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    const model = Prisma.dmmf.datamodel.models.find(m => m.name === 'User')
    const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
      const type = ['Int', 'Boolean', 'BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'Float' ? 'REAL' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
      let def = ''
      if (typeof f.default === 'string') def = ` DEFAULT '${f.default.replaceAll("'", "''")}'`
      else if (typeof f.default === 'number' || typeof f.default === 'boolean') def = ` DEFAULT ${Number(f.default)}`
      else if (f.default?.name === 'now') def = ' DEFAULT CURRENT_TIMESTAMP'
      return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : ''}${def}`
    })
    await db.$executeRawUnsafe(`CREATE TABLE User (${columns.join(',')})`)
    await db.$executeRawUnsafe('CREATE UNIQUE INDEX user_username_unique ON User(username)')
    await db.$executeRawUnsafe('CREATE TABLE "SettingsOperation" (' +
      '"id" TEXT PRIMARY KEY, "kind" TEXT, "clientId" TEXT, "userId" TEXT, "requestHash" TEXT, "resultJson" TEXT, "createdAt" DATETIME DEFAULT CURRENT_TIMESTAMP)')
    await db.$executeRawUnsafe('CREATE UNIQUE INDEX "SettingsOperation_identity" ON "SettingsOperation"("kind","clientId")')
    for (const id of ['owner', 'other']) await db.user.create({
      data: { id, name: id, about: 'kept bio', color: 'amber',
        sessionTokenHash: hashSessionToken(`synthetic-${id}`), legacyTokenHashes: '[]', preferences: '{"lastSeenVisible":false}' },
    })
    const { requireActionOwner } = await load("src/lib/wallet-authorization.ts", { db, bearerToken, tokenAccepted, hashSessionToken }, "({requireActionOwner})")
    const feedHints = []
    const { PATCH, GET } = await load('src/app/api/users/[id]/route.ts', {
      db, Prisma, requireActionOwner, NextResponse: { json: Response.json }, privacyPreferenceEnabled,
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      notifySocket: async (...args) => feedHints.push(args),
      clientIdFrom, probeSettingsOp: settingsContracts.probeSettingsOp, settleSettingsOp: settingsContracts.settleSettingsOp,
      USER_NAME_MAX: 32, ABOUT_MAX: 140, STATUS_ICON_IDS: ['coffee'],
      normalizeColor: v => v, normalizeUsername: v => /^[a-z0-9_]{3,20}$/.test(v) ? v : null,
      suggestUsername: async v => `${v}_2`, mapUser: u => ({ id: u.id, name: u.name, about: u.about, avatar: u.avatar, coverImage: u.coverImage, statusText: u.statusText }),
    }, '({PATCH,GET})')
    const patch = (body, extra = {}, token = 'synthetic-owner', id = 'owner') => PATCH(new Request('https://profile.test', {
      method: 'PATCH', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify({ ...body, ...extra }),
    }), { params: Promise.resolve({ id }) })
    const get = (id = 'owner') => GET(new Request('https://profile.test'), { params: Promise.resolve({ id }) })
    await run({ db, patch, get, feedHints })
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

test('profile writes replay their durable receipt and a monotonic revision rides every commit', () => profileFixture(async ({ db, patch, get }) => {
  const first = await patch({ about: 'first bio' }, { clientId: CID })
  assert.equal(first.status, 200)
  const body = await first.json()
  assert.equal(body.revision, 2) // profileRev starts at 1
  assert.equal(body.replayed, undefined)
  assert.equal((await db.settingsOperation.count({ where: { kind: 'profile.patch' } })), 1)

  const replay = await patch({ about: 'first bio' }, { clientId: CID })
  assert.equal(replay.status, 200)
  const replayBody = await replay.json()
  assert.equal(replayBody.replayed, true)
  assert.equal(replayBody.revision, 2)
  assert.equal((await db.user.findUnique({ where: { id: 'owner' } })).profileRev, 2)

  const clash = await patch({ about: 'different' }, { clientId: CID })
  assert.equal(clash.status, 409)
  assert.equal((await clash.json()).code, 'profile_operation_mismatch')

  const second = await patch({ name: 'Renamed' }, { clientId: CID2 })
  assert.equal((await second.json()).revision, 3)
  const view = await (await get()).json()
  assert.equal(view.revision, 3)
  assert.equal(view.user.name, 'Renamed')
}))

test('any accepted profile field change is pushed to the owner surfaces, not just renames', () => profileFixture(async ({ patch, feedHints }) => {
  assert.equal((await patch({ about: 'new bio' })).status, 200)
  assert.equal((await patch({ color: 'rose' })).status, 200)
  assert.equal((await patch({ statusText: 'building' })).status, 200)
  assert.deepEqual(feedHints.map(h => h[0]), ['private-feeds:changed', 'private-feeds:changed', 'private-feeds:changed'])
  assert.deepEqual(feedHints[0][1], ['owner'])
  // A foreign credential must neither write nor notify.
  const before = feedHints.length
  await patch({ about: 'hijack' }, {}, 'synthetic-other')
  assert.equal(feedHints.length, before)
}))

test('legacy profile callers keep immediate apply, receipts stay optional', () => profileFixture(async ({ db, patch }) => {
  const legacy = await patch({ name: 'Legacy Name' })
  assert.equal(legacy.status, 200)
  const body = await legacy.json()
  assert.equal(body.revision, 2)
  assert.equal((await db.user.findUnique({ where: { id: 'owner' } })).name, 'Legacy Name')
  assert.equal(await db.settingsOperation.count(), 0)
}))
