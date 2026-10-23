import test from 'node:test'
import assert from 'node:assert/strict'
import { quoteSnippet, redactDeletedMessage } from '../../src/lib/message-disclosure.ts'
import { normalizedReactionGroups } from '../../src/lib/reaction-contract.ts'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path, { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
const { Prisma, PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
const now = new Date('2026-10-23T00:00:00Z')
const source = { id: 'parent', content: 'private body', deletedAt: null, expiresAt: null, viewOnce: false,
  anon: false, anonAlias: null, viaAutomation: false, payload: null, sender: { name: 'Real Name' },
  conversationId: 'room', parentId: null, parent: null }
const quote = patch => quoteSnippet({ ...source, ...patch }, 'room', now)

test('quotes mask anonymous identities, machine origin and unverified legacy markers', () => {
  assert.equal(quote({ anon: true, anonAlias: 'Quiet Raven' }).senderName, 'Quiet Raven')
  assert.equal(quote({ anon: true, anonAlias: '  ' }).senderName, 'Anonymous')
  assert.equal(quote({ viaAutomation: true }).senderName, 'Automation')
  assert.equal(quote({ viaAutomation: true, payload: '{"bot":true}' }).senderName, 'Bot')
  assert.equal(quote({ viaAutomation: true, payload: '{"webhook":true,"webhookName":"\\u202eBuilds\\n"}' }).senderName, 'Webhook · Builds')
  assert.equal(quote({ payload: '{"webhook":true}' }).senderName, 'Unverified integration')
  assert.equal(quote({ payload: '{"webhook":"true"}' }).senderName, 'Real Name')
  assert.equal(quote({ anon: true, viaAutomation: true, payload: '{"webhook":true}' }).senderName, 'Anonymous')
})
test('quote disclosure includes independent expiry/view-once bounds and no inaccessible identity', () => {
  assert.deepEqual(quote({}), { id: 'parent', content: 'private body', senderName: 'Real Name', deleted: false,
    quoteVersion: 1, expiresAt: null, viewOnce: false })
  assert.equal(quote({ viewOnce: true }).content, 'View-once message')
  const root = { conversationId: 'room', parentId: null, deletedAt: null, expiresAt: new Date(now.getTime() + 1000) }
  const threaded = { parentId: 'root', parent: root, expiresAt: new Date(now.getTime() + 5000) }
  assert.equal(quote(threaded).expiresAt, root.expiresAt.toISOString())
  for (const patch of [ { deletedAt: now }, { expiresAt: now }, { conversationId: 'foreign' },
    { parentId: 'missing' }, { ...threaded, parent: { ...root, conversationId: 'foreign' } },
    { ...threaded, parent: { ...root, parentId: 'nested' } },
    { ...threaded, parent: { ...root, deletedAt: now } }, { ...threaded, parent: { ...root, expiresAt: now } } ]) {
    const row = quote(patch)
    assert.equal(row.deleted, true); assert.equal(row.content, ''); assert.equal(row.senderName, 'Unavailable message')
  }
})
test('real Prisma quote projection and serializer carry anonymity and root lifetime', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-quote-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const model of Prisma.dmmf.datamodel.models) {
      const columns = model.fields.filter(f => f.kind !== 'object' && !f.isList).map(f => {
        const type = ['Int','Boolean','BigInt'].includes(f.type) ? 'INTEGER' : f.type === 'Float' ? 'REAL' : f.type === 'DateTime' ? 'DATETIME' : 'TEXT'
        const def = typeof f.default === 'string' ? ` DEFAULT '${f.default.replaceAll("'", "''")}'` :
          typeof f.default === 'number' || typeof f.default === 'boolean' ? ` DEFAULT ${Number(f.default)}` : f.default?.name === 'now' ? ' DEFAULT CURRENT_TIMESTAMP' : ''
        return `"${f.name}" ${type}${f.isId ? ' PRIMARY KEY' : ''}${def}`
      })
      await db.$executeRawUnsafe(`CREATE TABLE "${model.dbName ?? model.name}" (${columns.join(',')})`)
    }
    const text = (await readFile(new URL('../../src/lib/serializers.ts', import.meta.url), 'utf8'))
      .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export \{[^}]+\} from [^\n]+$/gm, '').replace(/^export /gm, '')
    const bindings = { path, db, normalizedReactionGroups, quoteSnippet, redactDeletedMessage }
    const { MESSAGE_FULL_INCLUDE, mapMessage } = new Function(...Object.keys(bindings),
      stripTypeScriptTypes(text, { mode: 'transform' }) + ';return {MESSAGE_FULL_INCLUDE,mapMessage}')( ...Object.values(bindings))
    await db.user.create({ data: { id: 'author', name: 'Real Private Name', username: 'author' } })
    await db.conversation.create({ data: { id: 'room', isGroup: true } })
    const expires = new Date(Date.now() + 60000)
    await db.message.create({ data: { id: 'root', conversationId: 'room', senderId: 'author', content: 'root', expiresAt: expires } })
    await db.message.create({ data: { id: 'parent', conversationId: 'room', senderId: 'author', content: 'secret', parentId: 'root', anon: true, anonAlias: 'Quiet Raven' } })
    await db.message.create({ data: { id: 'child', conversationId: 'room', senderId: 'author', content: 'reply', replyToId: 'parent' } })
    const read = async () => mapMessage(await db.message.findUniqueOrThrow({ where: { id: 'child' }, include: MESSAGE_FULL_INCLUDE })).replyTo
    assert.equal((await read()).senderName, 'Quiet Raven')
    assert.equal((await read()).expiresAt, expires.toISOString())
    await db.message.update({ where: { id: 'parent' }, data: { viewOnce: true } })
    assert.equal((await read()).content, 'View-once message')
    await db.message.update({ where: { id: 'root' }, data: { deletedAt: new Date() } })
    assert.equal((await read()).content, ''); assert.equal((await read()).senderName, 'Unavailable message')
  } finally { await db.$disconnect(); await rm(dir, { recursive: true, force: true }) }
})
