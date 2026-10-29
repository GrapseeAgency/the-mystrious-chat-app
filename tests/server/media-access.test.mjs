import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { bearerToken, tokenAccepted, hashSessionToken } from '../../src/lib/session-token.ts'
const { PrismaClient, Prisma } = createRequire(import.meta.url)('../../prisma/generated-client')
async function load(path, bindings, expression) {
  const text = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}
async function table(db, name) {
  const model = Prisma.dmmf.datamodel.models.find(m => m.name === name)
  const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
    const type = ['Int', 'Boolean', 'BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'Float' ? 'REAL' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
    return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : ''}`
  })
  await db.$executeRawUnsafe(`CREATE TABLE "${name}" (${columns.join(',')})`)
}
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'media-access-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const name of ['User', 'Conversation', 'ConversationParticipant', 'Message', 'StatusStory']) await table(db, name)
    await db.user.create({ data: { id: 'member', name: 'member', sessionTokenHash: hashSessionToken('synthetic-member'), legacyTokenHashes: '[]' } })
    await db.user.create({ data: { id: 'stranger', name: 'stranger', sessionTokenHash: hashSessionToken('synthetic-stranger'), legacyTokenHashes: '[]' } })
    await db.conversation.create({ data: { id: 'room', isGroup: true } })
    await db.conversationParticipant.create({ data: { id: 'cp1', userId: 'member', conversationId: 'room' } })
    await db.message.create({ data: { id: 'm1', conversationId: 'room', senderId: 'member', content: 'photo', imagePath: 'photo-1.jpg' } })
    const mediaAccessDecision = await load('src/lib/media-access.ts', { db, bearerToken, tokenAccepted, hashSessionToken }, 'mediaAccessDecision')
    const request = (token) => new Request('https://media.test/api/uploads/photo-1.jpg',
      token ? { headers: { authorization: `Bearer ${token}` } } : {})
    await run({ db, mediaAccessDecision, request })
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
}
test('a member with a live referencing message can fetch, strangers and stale sessions cannot', () => fixture(async ({ db, mediaAccessDecision, request }) => {
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'photo-1.jpg')).kind, 'granted')
  assert.equal((await mediaAccessDecision(request('synthetic-stranger'), 'photo-1.jpg')).kind, 'denied')
  const denied = await mediaAccessDecision(request('synthetic-stranger'), 'photo-1.jpg')
  assert.equal(denied.kind === 'denied' ? denied.status : 0, 403)
  assert.equal((await mediaAccessDecision(request('invalid-token'), 'photo-1.jpg')).kind, 'denied')
  const stale = await mediaAccessDecision(request('invalid-token'), 'photo-1.jpg')
  assert.equal(stale.kind === 'denied' ? stale.status : 0, 401)
  // Headerless (web migration window) stays open.
  assert.equal((await mediaAccessDecision(request(null), 'photo-1.jpg')).kind, 'open')
}))
test('tombstoning or expiring the referencing message revokes the previously reachable URL', () => fixture(async ({ db, mediaAccessDecision, request }) => {
  await db.message.update({ where: { id: 'm1' }, data: { deletedAt: new Date() } })
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'photo-1.jpg')).kind, 'denied')
}))
test('own avatar, own live story, and room photos stay reachable for the owner', () => fixture(async ({ db, mediaAccessDecision, request }) => {
  await db.user.update({ where: { id: 'member' }, data: { avatar: 'avatar.png' } })
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'avatar.png')).kind, 'granted')
  await db.statusStory.create({ data: { id: 's1', userId: 'member', caption: '', expiresAt: new Date(Date.now() + 60_000), imagePath: 'story.jpg' } })
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'story.jpg')).kind, 'granted')
  await db.conversation.update({ where: { id: 'room' }, data: { photo: 'room-photo.jpg' } })
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'room-photo.jpg')).kind, 'granted')
  // An expired story stops serving even to its owner (24h expiry is its revocation).
  await db.statusStory.update({ where: { id: 's1' }, data: { expiresAt: new Date(Date.now() - 1) } })
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'story.jpg')).kind, 'denied')
}))
test('R45 re-audit: a co-member avatar/cover stays reachable, a stranger avatar does not', () => fixture(async ({ db, mediaAccessDecision, request }) => {
  // The stranger shares no room with the member: their avatar stays 403 even
  // though the requester is authenticated (the Android Coil loader attaches
  // the token to avatar fetches too - the co-member grant must be the ONLY
  // new audience).
  await db.user.update({ where: { id: 'stranger' }, data: { avatar: 'stranger-avatar.png', coverImage: 'stranger-cover.jpg' } })
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'stranger-avatar.png')).kind, 'denied')
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'stranger-cover.jpg')).kind, 'denied')
  // A co-member (shares the room) keeps avatar + cover reachable while the
  // shared room exists...
  await db.user.create({ data: { id: 'peer', name: 'peer', sessionTokenHash: hashSessionToken('synthetic-peer'), legacyTokenHashes: '[]', avatar: 'peer-avatar.png', coverImage: 'peer-cover.jpg' } })
  await db.conversationParticipant.create({ data: { id: 'cp2', userId: 'peer', conversationId: 'room' } })
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'peer-avatar.png')).kind, 'granted')
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'peer-cover.jpg')).kind, 'granted')
  // ...and loses both the moment the last shared room is gone (leaving a
  // room revokes the previously reachable avatar/cover URL).
  await db.conversationParticipant.delete({ where: { id: 'cp2' } })
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'peer-avatar.png')).kind, 'denied')
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'peer-cover.jpg')).kind, 'denied')
}))
test('R46 re-audit: a just-uploaded file nothing references yet is denied for authenticated natives', () => fixture(async ({ db, mediaAccessDecision, request }) => {
  // The channel-creation photo chain: POST /api/uploads lands the bytes, but
  // no row references the file until POST /api/channels creates it. The
  // authenticated fetch (the app-wide Android Coil loader attaches the
  // session bearer to every /api/uploads/ fetch) is denied 403 - which is
  // why the native composers preview LOCAL bytes, never the fresh upload.
  // Headerless stays open (the web <img> migration window).
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'fresh-upload.png')).kind, 'denied')
  const denied = await mediaAccessDecision(request('synthetic-member'), 'fresh-upload.png')
  assert.equal(denied.kind === 'denied' ? denied.status : 0, 403)
  assert.equal((await mediaAccessDecision(request(null), 'fresh-upload.png')).kind, 'open')
  // Once the channel row references it as its photo, the creating member is
  // granted through the room-photo rule.
  await db.conversation.create({ data: { id: 'fresh-room', isGroup: true, photo: 'fresh-upload.png' } })
  await db.conversationParticipant.create({ data: { id: 'cp3', userId: 'member', conversationId: 'fresh-room' } })
  assert.equal((await mediaAccessDecision(request('synthetic-member'), 'fresh-upload.png')).kind, 'granted')
}))
