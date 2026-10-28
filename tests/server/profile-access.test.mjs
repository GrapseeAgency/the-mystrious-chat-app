import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
import { privacyPreferenceEnabled } from '../../src/lib/privacy-preferences.ts'
const { PrismaClient, Prisma } = createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path, bindings, expression) {
  const text = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}
async function fixture(run, routeDatabase = db => db) {
  const dir = await mkdtemp(join(tmpdir(), 'profile-owner-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    const model = Prisma.dmmf.datamodel.models.find(m => m.name === 'User')
    const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
      const type = ['Int', 'Boolean', 'BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'Float' ? 'REAL' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
      return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : ''}`
    })
    await db.$executeRawUnsafe(`CREATE TABLE User (${columns.join(',')})`)
    await db.$executeRawUnsafe('CREATE UNIQUE INDEX user_username_unique ON User(username)')
    for (const id of ['owner', 'other']) await db.user.create({ data: { id, name: id, about: 'kept bio', color: 'amber',
      sessionTokenHash: hashSessionToken(`synthetic-${id}`), legacyTokenHashes: '[]', preferences: '{"lastSeenVisible":false}' } })
    const requireActionOwner = await load('src/lib/wallet-authorization.ts', { db, bearerToken, tokenAccepted }, 'requireActionOwner')
    const feedHints = []
    const { PATCH } = await load('src/app/api/users/[id]/route.ts', {
      db: routeDatabase(db), Prisma, requireActionOwner, NextResponse: { json: Response.json }, privacyPreferenceEnabled,
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      notifySocket: async (...args) => feedHints.push(args),
      USER_NAME_MAX: 32, ABOUT_MAX: 140, STATUS_ICON_IDS: ['coffee'],
      normalizeColor: v => v, normalizeUsername: v => /^[a-z0-9_]{3,20}$/.test(v) ? v : null,
      suggestUsername: async v => `${v}_2`, mapUser: u => ({ id: u.id, name: u.name, about: u.about, avatar: u.avatar, coverImage: u.coverImage, statusText: u.statusText }),
    }, '({PATCH})')
    const patch = (body, token = 'synthetic-owner', id = 'owner') => PATCH(new Request('https://profile.test', {
      method: 'PATCH', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body),
    }), { params: Promise.resolve({ id }) })
    await run({ db, patch, PATCH, feedHints })
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}

test('profile writes require the target owner credential before reading the body', () => fixture(async ({ db, patch, PATCH }) => {
  for (const token of [null, 'synthetic-other', 'invalid']) {
    const response = await patch({ name: 'hijacked', avatar: '/api/uploads/known-photo.jpg' }, token)
    assert.equal(response.status, token ? 403 : 401)
  }
  const rejected = await PATCH({ headers: new Headers(), json: () => { throw Error('must not read anonymous body') } }, { params: Promise.resolve({ id: 'owner' }) })
  assert.equal(rejected.status, 401)
  assert.equal((await db.user.findUnique({ where: { id: 'owner' } })).name, 'owner')
  assert.equal((await patch({ name: 'wrong target' }, 'synthetic-owner', 'other')).status, 403)
}))

test('confirmed owner can clear the bio and change only the supplied photo field', () => fixture(async ({ db, patch }) => {
  assert.equal((await patch({ avatar: '/api/uploads/known-photo.jpg' })).status, 200)
  const response = await patch({ about: '', coverImage: '/api/uploads/known-cover.jpg' })
  assert.equal(response.status, 200)
  const user = await db.user.findUnique({ where: { id: 'owner' } })
  assert.equal(user.about, '')
  assert.equal(user.name, 'owner')
  assert.equal(user.avatar, '/api/uploads/known-photo.jpg')
  assert.equal(user.coverImage, '/api/uploads/known-cover.jpg')
  assert.equal((await patch({ avatar: '' })).status, 200)
  assert.equal((await db.user.findUnique({ where: { id: 'owner' } })).avatar, null)
}))

test('photo receipts must be normalized and profile limits use UTF16 units', () => fixture(async ({ patch }) => {
  for (const avatar of ['bare-file.jpg', 'https://elsewhere.test/image.jpg', '/api/uploads/../secret']) assert.equal((await patch({ avatar })).status, 400)
  assert.equal((await patch({ name: '🌿'.repeat(16), about: '🌿'.repeat(70), statusText: '🌿'.repeat(24) })).status, 200)
  for (const body of [{ name: '🌿'.repeat(17) }, { about: '🌿'.repeat(71) }, { statusText: '🌿'.repeat(25) }]) assert.equal((await patch(body)).status, 400)
}))

test('credential rotation denies the old profile writer and retains explicitly accepted legacy credentials', () => fixture(async ({ db, patch }) => {
  await db.user.update({ where: { id: 'owner' }, data: { sessionTokenHash: hashSessionToken('synthetic-new'), legacyTokenHashes: '[]' } })
  assert.equal((await patch({ name: 'old token' })).status, 403)
  assert.equal((await patch({ name: 'new token' }, 'synthetic-new')).status, 200)
  await db.user.update({ where: { id: 'owner' }, data: { legacyTokenHashes: JSON.stringify([hashSessionToken('synthetic-owner')]) } })
  assert.equal((await patch({ name: 'legacy accepted' })).status, 200)
  assert.equal((await patch({ name: 'foreign' }, 'synthetic-other')).status, 403)
}))

test('simultaneous handle claims produce one success and an actionable conflict, not a false success or generic 500', async () => {
  let arrived = 0, release
  const barrier = new Promise(resolve => { release = resolve })
  await fixture(async ({ db, patch }) => {
    const responses = await Promise.all([
      patch({ username: 'shared_handle' }),
      patch({ username: 'shared_handle' }, 'synthetic-other', 'other'),
    ])
    assert.deepEqual(responses.map(r => r.status).sort(), [200, 409])
    const failure = await responses.find(r => r.status === 409).json()
    assert.equal(failure.code, 'username_taken')
    assert.equal(failure.suggestion, 'shared_handle_2')
    assert.equal(await db.user.count({ where: { username: 'shared_handle' } }), 1)
  }, db => ({ user: {
    findUnique: async args => {
      const result = await db.user.findUnique(args)
      if (args.where.username === 'shared_handle') {
        assert.equal(result, null)
        if (++arrived === 2) release()
        await barrier
      }
      return result
    },
    update: args => db.user.update(args),
  } }))
})

test('malformed field types cannot silently clear a bio, handle, photo or status', () => fixture(async ({ db, patch }) => {
  for (const field of ['name', 'username', 'about', 'color', 'avatar', 'coverImage', 'statusEmoji', 'statusText']) {
    for (const value of [false, 0, {}, []]) assert.equal((await patch({ [field]: value })).status, 400)
  }
  assert.equal((await patch({ about: null })).status, 400)
  assert.equal((await db.user.findUnique({ where: { id: 'owner' } })).about, 'kept bio')
  assert.equal((await patch({ username: 'owned_handle' })).status, 200)
  assert.equal((await patch({ username: '' })).status, 200)
  assert.equal((await db.user.findUnique({ where: { id: 'owner' } })).username, null)
}))

test('profile rename invalidates only the owner private feeds after the write', () => fixture(async ({ patch, feedHints }) => {
  assert.equal((await patch({name:'New name'})).status,200)
  assert.deepEqual(feedHints,[['private-feeds:changed',['owner'],{userId:'owner'}]])
  await patch({name:'Rejected'},'synthetic-other')
  assert.equal(feedHints.length,1)
}))
