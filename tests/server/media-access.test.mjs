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
