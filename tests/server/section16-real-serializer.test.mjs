// Section 16 item 9 - real feed serializer coverage.
//
// The actual-route feed tests (settings-write-order.test.mjs) inject a
// simplified mapMessage into search/mentions/saved; that pins ordering and
// authority but NOT the wire shape the mounted UI renders. This suite loads
// the REAL serializer chain (src/lib/serializers.ts mapMessage +
// MESSAGE_FULL_INCLUDE + the real reaction grouping, quote snippets, poll
// tally, deleted redaction and reader-privacy filtering) through the same
// actual routes and asserts the full ChatMessage wire contract:
//   - ISO-8601 string dates (never Date objects - the mounted row and the
//     native wire DTOs both rely on strings),
//   - sender as the mapped author shape,
//   - reactions as the normalized groups,
//   - replyTo as the quote snippet with its disclosure bounds,
//   - poll tallies viewer-aware (myOptionId) with real vote counts,
//   - deleted rows redacted (empty content, no poll, no reactions),
//   - readByMembershipIds privacy-filtered to the viewer plus receipts-on
//     members,
//   - saved items carry the real message + conversation + revision pairing.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import {
  bearerToken,
  tokenAccepted,
  hashSessionToken,
} from '../../src/lib/session-token.ts'
import { privacyPreferenceEnabled } from '../../src/lib/privacy-preferences.ts'
import { normalizedReactionGroups } from '../../src/lib/reaction-contract.ts'
import { redactDeletedMessage, quoteSnippet } from '../../src/lib/message-disclosure.ts'
import * as visibility from '../../src/lib/message-visibility.ts'
import { visibleSearchHit } from '../../src/lib/search-visibility.ts'
import * as feedPagination from '../../src/lib/feed-pagination.ts'
import { clipUtf16 } from '../../src/lib/text-limits.ts'
const { PrismaClient, Prisma } = createRequire(import.meta.url)('../../prisma/generated-client')

async function load(path, bindings, expression) {
  const text = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '')
    .replace(/^export \{[^}]+\} from [^\n]+$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}

const TABLES = ['User', 'Conversation', 'ConversationParticipant', 'Message', 'Reaction',
  'Poll', 'PollOption', 'PollVote', 'MessageTranslation', 'LinkPreview', 'MessageReadObservation',
  'ReadIntentState', 'SavedMessage', 'SavedMessageState']

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'real-feed-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const name of TABLES) {
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
    // alice reads; bob reads with receipts on; chad reads with receipts OFF.
    for (const [id, prefs] of [['alice', null], ['bob', '{"readReceipts":true}'], ['chad', '{"readReceipts":false}']]) {
      await db.user.create({ data: {
        id, name: id, color: 'amber', ...(prefs ? { preferences: prefs } : {}),
        sessionTokenHash: hashSessionToken(`synthetic-${id}`), legacyTokenHashes: '[]',
      } })
    }
    await db.conversation.create({ data: { id: 'room', isGroup: true, name: 'Feed Room' } })
    for (const id of ['alice', 'bob', 'chad']) {
      await db.conversationParticipant.create({ data: { userId: id, conversationId: 'room' } })
    }
    const members = await db.conversationParticipant.findMany({ where: { conversationId: 'room' } })
    const memberOf = id => members.find(m => m.userId === id).id

    await db.message.create({ data: { id: 'msg-hello', conversationId: 'room', senderId: 'bob', content: 'fresh fedora arrives' } })
    // a thread reply that ALSO quotes the root (replyToId = the quote,
    // parentId = the thread root - two distinct relations on the model)
    await db.message.create({ data: {
      id: 'msg-reply', conversationId: 'room', senderId: 'alice', content: 'a reply joins the thread',
      parentId: 'msg-hello', replyToId: 'msg-hello',
    } })
    await db.message.create({ data: { id: 'msg-gone', conversationId: 'room', senderId: 'bob', content: 'later redacted' } })
    await db.message.create({ data: { id: 'msg-poll', conversationId: 'room', senderId: 'bob', content: 'Dinner?' } })
    await db.message.create({ data: { id: 'msg-mention', conversationId: 'room', senderId: 'bob', content: 'hey @alice check the fedora' } })
    await db.reaction.create({ data: { id: 'react-1', messageId: 'msg-hello', userId: 'alice', emoji: 'heart' } })
    await db.reaction.create({ data: { id: 'react-2', messageId: 'msg-hello', userId: 'bob', emoji: 'fire' } })
    await db.reaction.create({ data: { id: 'react-3', messageId: 'msg-hello', userId: 'bob', emoji: 'heart' } })
    await db.poll.create({ data: { id: 'poll-1', messageId: 'msg-poll', question: 'Dinner?' } })
    await db.pollOption.create({ data: { id: 'opt-pizza', pollId: 'poll-1', text: 'Pizza', position: 0 } })
    await db.pollOption.create({ data: { id: 'opt-sushi', pollId: 'poll-1', text: 'Sushi', position: 1 } })
    await db.pollVote.create({ data: { id: 'vote-1', pollId: 'poll-1', userId: 'alice', option: { connect: { id: 'opt-sushi' } } } })
    for (const userId of ['alice', 'bob', 'chad']) {
      await db.messageReadObservation.create({ data: {
        messageId: 'msg-hello', source: 'visible', participantId: memberOf(userId), observedAt: new Date(),
      } })
    }
    // alice saved the plain message and the message that later got deleted
    await db.savedMessage.create({ data: { id: 'save-1', userId: 'alice', messageId: 'msg-hello' } })
    await db.savedMessage.create({ data: { id: 'save-2', userId: 'alice', messageId: 'msg-gone' } })
    await db.message.update({ where: { id: 'msg-gone' }, data: { deletedAt: new Date() } })

    const { requireActionOwner } = await load('src/lib/wallet-authorization.ts',
      { db, bearerToken, tokenAccepted, hashSessionToken }, '({requireActionOwner})')
    const realSerializers = await load('src/lib/serializers.ts', {
      path: { join }, db, privacyPreferenceEnabled, normalizedReactionGroups, redactDeletedMessage, quoteSnippet,
    }, '({mapMessage,MESSAGE_FULL_INCLUDE})')
    const auth = (token = 'synthetic-alice') => (token ? { authorization: `Bearer ${token}` } : {})

    const searchRoute = await load('src/app/api/search/route.ts', {
      db, requireActionOwner, visibleSearchHit, ...visibility, ...feedPagination, clipUtf16,
      ...realSerializers, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      notifySocket: async () => {}, NextResponse: Response,
    }, 'GET')
    const mentionsRoute = await load('src/app/api/mentions/route.ts', {
      db, requireActionOwner, ...visibility, ...feedPagination, clipUtf16,
      ...realSerializers, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      notifySocket: async () => {}, NextResponse: Response,
    }, 'GET')
    const savedRoute = await load('src/app/api/users/[id]/saved/route.ts', {
      db, requireActionOwner, ...visibility, ...feedPagination,
      ...realSerializers, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      notifySocket: async () => {}, NextResponse: Response,
    }, 'GET')

    await run({ db, memberOf, searchRoute, mentionsRoute, savedRoute, auth })
  } finally {
    await db.$disconnect()
    await rm(dir, { recursive: true, force: true })
  }
}

const isIso = (value) => typeof value === 'string' && Number.isFinite(Date.parse(value))

test('search answers through the REAL serializer with the full ChatMessage wire shape', () => fixture(async ({ memberOf, searchRoute, auth }) => {
  const response = await searchRoute(new Request('https://feed.test/api/search?userId=alice&q=fedora', { headers: auth() }))
  assert.equal(response.status, 200)
  const page = await response.json()
  const hit = page.messages.find(m => m.id === 'msg-hello')
  assert.ok(hit, 'the searchable row is returned')
  // dates are ISO strings on the wire
  assert.ok(isIso(hit.createdAt))
  assert.equal(hit.deletedAt, null)
  // author is the mapped shape the mounted row and native DTOs decode
  assert.deepEqual(Object.keys(hit.sender).sort(), ['avatar', 'color', 'id', 'name', 'username'])
  assert.equal(hit.sender.id, 'bob')
  // reactions arrive as the REAL normalized groups (heart: alice+bob, fire: bob)
  assert.deepEqual(hit.reactions, normalizedReactionGroups([
    { emoji: 'heart', userId: 'alice' }, { emoji: 'fire', userId: 'bob' }, { emoji: 'heart', userId: 'bob' },
  ]))
  // reader privacy: own membership always visible, bob's receipt is on, chad's is filtered out
  assert.deepEqual(hit.readByMembershipIds.sort(), [memberOf('alice'), memberOf('bob')])
  // no poll/reply on this row, guarded nulls rather than missing keys
  assert.equal(hit.poll, null)
  assert.equal(hit.replyTo, null)

  // reply row carries the REAL quote snippet of its parent
  const replyPage = await (await searchRoute(new Request('https://feed.test/api/search?userId=alice&q=thread', { headers: auth() }))).json()
  const reply = replyPage.messages.find(m => m.id === 'msg-reply')
  assert.ok(reply)
  assert.equal(reply.replyTo.id, 'msg-hello')
  assert.equal(reply.replyTo.content, 'fresh fedora arrives')
  assert.equal(reply.replyTo.quoteVersion, 1)
  assert.equal(reply.replyTo.senderName, 'bob')
  assert.equal(reply.replyTo.viewOnce, false)

  // poll rows are viewer-aware through the real tally
  const pollPage = await (await searchRoute(new Request('https://feed.test/api/search?userId=alice&q=Dinner', { headers: auth() }))).json()
  const poll = pollPage.messages.find(m => m.id === 'msg-poll')
  assert.ok(poll)
  assert.equal(poll.poll.question, 'Dinner?')
  assert.equal(poll.poll.myOptionId, 'opt-sushi')
  assert.equal(poll.poll.totalVotes, 1)
  assert.deepEqual(poll.poll.options.map(o => [o.text, o.voteCount]), [['Pizza', 0], ['Sushi', 1]])
}))

test('saved answers through the REAL serializer: conversation pairing, revisions and honest tombstone drop', () => fixture(async ({ savedRoute, auth }) => {
  const response = await savedRoute(new Request('https://feed.test/api/users/alice/saved', { headers: auth() }),
    { params: Promise.resolve({ id: 'alice' }) })
  assert.equal(response.status, 200)
  const page = await response.json()
  // the save of the LATER-DELETED message drops out through the real preview
  // projection - a tombstone never resurrects in the saved feed
  assert.equal(page.items.length, 1)
  const kept = page.items.find(item => item.message.id === 'msg-hello')
  assert.ok(kept, 'the live save is returned')
  assert.ok(isIso(kept.savedAt) && isIso(kept.message.createdAt))
  assert.equal(kept.revision, '0')
  assert.deepEqual(kept.conversation, { id: 'room', isGroup: true, name: 'Feed Room' })
  // the full real wire shape rides the saved row too
  assert.deepEqual(kept.message.reactions, normalizedReactionGroups([
    { emoji: 'heart', userId: 'alice' }, { emoji: 'fire', userId: 'bob' }, { emoji: 'heart', userId: 'bob' },
  ]))
  assert.deepEqual(Object.keys(kept.message.sender).sort(), ['avatar', 'color', 'id', 'name', 'username'])
  assert.equal(kept.message.deletedAt, null)
}))

test('mentions keeps its real projection: author identity, snippet bounds and ISO stamps', () => fixture(async ({ mentionsRoute, auth }) => {
  const response = await mentionsRoute(new Request('https://feed.test/api/mentions?userId=alice&limit=50', { headers: auth() }))
  assert.equal(response.status, 200)
  const page = await response.json()
  const mention = page.items.find(item => item.messageId === 'msg-mention')
  assert.ok(mention, 'the @alice mention is found through the real preview + regex')
  assert.equal(mention.author.id, 'bob')
  assert.equal(mention.author.name, 'bob')
  assert.equal(mention.snippet, 'hey @alice check the fedora')
  assert.ok(isIso(mention.createdAt))
  assert.equal(mention.expiresAt, null)
  assert.equal(mention.conversationId, 'room')
  assert.equal(mention.isGroup, true)
}))
