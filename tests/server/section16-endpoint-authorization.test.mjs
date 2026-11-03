// Section 16 item 3 - remaining endpoint authorization review.
//
// The wallet/owner hardening landed route-by-route over several sections;
// this matrix pins the AUTHORITY of every remaining family explicitly so
// silent regressions surface as failures:
//   - message list/send/history (conversations GET, messages GET/POST) and
//     the legacy poll delegate: owner-bound Bearer (401 headerless, 403
//     foreign credential, 200/201 owner),
//   - the three legacy message routes that previously trailed their newer
//     siblings in authority (react, viewed, thread): now owner-bound too,
//   - file storage (POST /api/uploads): any valid session actor (natives
//     attach the bearer on every request family; the web apiJson callers
//     now do too) - anonymous byte storage is closed,
//   - discovery with credentials (search): owner-bound,
//   - open-by-design identity routes (users directory GET, login mint)
//     stay open - they are the adoption entry points, not protected rooms.
//
// GET /api/uploads/[file] keeps its separate media-access decision (that is
// the <img> migration window, pinned in media-access.test.mjs) and the
// socket join token-optional window is the explicitly deferred Section 16
// item 2 (broader credential migration) - neither is re-decided here.
import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, readFile, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import {
  bearerToken,
  tokenAccepted,
  hashSessionToken,
  generateSessionToken,
  pushLegacyTokenHash,
} from '../../src/lib/session-token.ts'
import { mutateMessage, MessageMutationError } from '../../src/lib/message-mutation.ts'
import { threadRootAvailable, unexpiredMessages, visibleInboxPreview } from '../../src/lib/message-visibility.ts'
import { savedMessagesWhere } from '../../src/lib/message-visibility.ts'
import { visibleSearchHit } from '../../src/lib/search-visibility.ts'
import * as feedPagination from '../../src/lib/feed-pagination.ts'
import * as sync from '../../src/lib/message-sync.ts'
import * as operations from '../../src/lib/message-operation.ts'
import { messageDeliveryDecision } from '../../src/lib/scheduled-delivery.ts'
import { REACTION_IDS } from '../../src/lib/icon-ids.ts'
import { clipUtf16 } from '../../src/lib/text-limits.ts'
import { peerBlock, PeerBlockError } from '../../src/lib/peer-block.ts'
const { PrismaClient, Prisma } = createRequire(import.meta.url)('../../prisma/generated-client')

async function load(path, bindings, expression) {
  const text = (await readFile(new URL(`../../${path}`, import.meta.url), 'utf8'))
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\s*$/gm, '').replace(/^export /gm, '')
  return new Function(...Object.keys(bindings), stripTypeScriptTypes(text, { mode: 'transform' }) + `;return ${expression}`)(...Object.values(bindings))
}

const TABLES = ['User', 'UserWallet', 'Conversation', 'ConversationParticipant', 'Message', 'Reaction',
  'Poll', 'PollOption', 'PollVote', 'MessageTranslation', 'LinkPreview', 'MessageReadObservation',
  'ReadIntentState', 'SavedMessage', 'SavedMessageState', 'UserBlock', 'UploadedFile', 'UploadOperation']

async function createTables(db) {
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
  for (const sql of (await readFile(new URL('../../prisma/patches/20261019_message_send_operation.sql', import.meta.url), 'utf8')).split(';').map(s => s.trim()).filter(Boolean)) {
    await db.$executeRawUnsafe(sql)
  }
}

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'auth-matrix-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    await createTables(db)
    for (const id of ['owner', 'other', 'outsider']) {
      await db.user.create({ data: {
        id, name: id, color: 'amber',
        sessionTokenHash: hashSessionToken(`synthetic-${id}`), legacyTokenHashes: '[]',
      } })
    }
    await db.conversation.create({ data: { id: 'room', isGroup: true } })
    for (const id of ['owner', 'other']) {
      await db.conversationParticipant.create({ data: { userId: id, conversationId: 'room', role: id === 'owner' ? 'admin' : 'member' } })
    }
    await db.message.create({ data: { id: 'msg-1', conversationId: 'room', senderId: 'other', content: 'hello' } })
    await db.message.create({ data: { id: 'msg-root', conversationId: 'room', senderId: 'other', content: 'thread root' } })
    await db.message.create({ data: { id: 'msg-reply', conversationId: 'room', senderId: 'owner', content: 'thread reply', parentId: 'msg-root' } })
    await db.message.create({ data: { id: 'msg-eyes', conversationId: 'room', senderId: 'other', content: 'eyes only', viewOnce: true } })

    const { requireActionOwner, requireActionActor } = await load('src/lib/wallet-authorization.ts',
      { db, bearerToken, tokenAccepted, hashSessionToken }, '({requireActionOwner,requireActionActor})')
    const auth = (token) => (token ? { authorization: `Bearer ${token}` } : {})

    const listConversations = await load('src/app/api/conversations/route.ts', {
      db, requireActionOwner,
      createConversationOnce: async () => { throw Error('unused in this suite') },
      DirectConversationError: class DirectConversationError extends Error {},
      openDirectConversation: async () => { throw Error('unused in this suite') },
      CONVERSATION_FULL_INCLUDE: { participants: true },
      buildConversationSummary: async row => ({ id: row.id, updatedAt: row.updatedAt.toISOString(), pinnedAt: null }),
      GROUP_NAME_MAX: 80, notifySocket: async () => {},
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      NextResponse: Response,
    }, 'GET')

    const notices = []
    const messageDeps = {
      db, requireActionOwner, requireActionActor, peerBlock, PeerBlockError, NextResponse: Response,
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      notifySocket: async (...args) => notices.push(args),
      ...sync, parseIsoDate: v => typeof v === 'string' && Number.isFinite(Date.parse(v)) ? new Date(v) : null,
      MESSAGES_DEFAULT_LIMIT: 200, MESSAGES_MAX_LIMIT: 100, MESSAGE_MAX: 2000,
      MESSAGE_FULL_INCLUDE: { reactions: true }, mapMessage: r => ({ id: r.id, content: r.content }),
      ...operations, messageDeliveryDecision,
      createAutomationReply: async () => {}, markAutomationNotifyDelivered: async () => {},
      threadRootAvailable, dayKey: date => date.toISOString().slice(0, 10),
      XP_DAILY_CAP: 0, XP_PER_MESSAGE: 0,
      nativeBlockFromPayload: () => null, maybeAiReply: async () => {},
      botWillRespond: () => false, maybeBotReply: async () => {},
      deliverMessageNotification: async () => false, fanoutPush: async () => {}, deliverLivePush: async () => {},
      stat, path: { join }, UPLOADS_DIR: join(dir, 'uploads'),
      AUDIO_EXT_REGEX: /\.(webm|mp3|ogg|wav|m4a|aac)$/, DOC_EXT_REGEX: /\.(pdf|txt|csv|zip)$/,
      DOC_MAX_BYTES: 10 * 1024 * 1024, memberIdsOf: async () => ['owner', 'other'], clipUtf16,
      console: { error: () => {}, log: () => {} },
    }
    const messagesRoute = await load('src/app/api/conversations/[id]/messages/route.ts', messageDeps, '({GET,POST})')
    const pollRoute = await load('src/app/api/conversations/[id]/poll/route.ts', {
      createMessage: messagesRoute.POST, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
    }, 'POST')

    const reactRoute = await load('src/app/api/messages/[id]/react/route.ts', {
      db, mutateMessage, MessageMutationError, requireActionOwner,
      mapMessage: r => ({ id: r.id, content: r.content }), memberIdsOf: async () => ['owner', 'other'],
      notifySocket: async (...args) => notices.push(args), MESSAGE_FULL_INCLUDE: { reactions: true },
      REACTION_EMOJIS: REACTION_IDS, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      NextResponse: Response,
    }, 'POST')

    const viewedRoute = await load('src/app/api/messages/[id]/viewed/route.ts', {
      db, mutateMessage, MessageMutationError, requireActionOwner,
      mapMessage: r => ({ id: r.id, content: r.content }), memberIdsOf: async () => ['owner', 'other'],
      notifySocket: async (...args) => notices.push(args), MESSAGE_FULL_INCLUDE: { reactions: true },
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      NextResponse: Response,
    }, 'POST')

    const threadRoute = await load('src/app/api/messages/[id]/thread/route.ts', {
      db, requireActionOwner, threadRootAvailable: async () => true,
      readThreadSnapshot: (dbArg, id, userId, readRoot, readReplies) => {
        return { parent: { id: 'msg-root' }, replies: [{ id: 'msg-reply' }] }
      },
      ThreadReadError: class ThreadReadError extends Error {},
      mapMessage: r => ({ id: r.id, content: r.content }), MESSAGE_FULL_INCLUDE: { reactions: true },
      NextResponse: Response,
    }, 'GET')

    const uploadDeps = await load('src/lib/upload-input.ts', { DOC_MAX_BYTES: 10 * 1024 * 1024 },
      '({readUploadBody,decodeUploadBase64,UploadInputError,MAX_UPLOAD_JSON_BYTES})')
    const uploadOps = await load('src/lib/upload-operation.ts', { createHash: (await import('node:crypto')).createHash },
      '({uploadOperation,replayUploadOperation,commitUploadOperation,UploadOperationError})')
    const uploadsRoute = await load('src/app/api/uploads/route.ts', {
      db, mkdir: (await import('node:fs/promises')).mkdir,
      writeFile: (await import('node:fs/promises')).writeFile,
      rename: (await import('node:fs/promises')).rename,
      unlink: (await import('node:fs/promises')).unlink,
      path: { join }, randomUUID: (await import('node:crypto')).randomUUID,
      NextResponse: Response, DOC_MAX_BYTES: 10 * 1024 * 1024,
      UPLOADS_DIR: join(dir, 'uploads'), UPLOAD_MIME: { png: 'image/png' },
      strField: v => typeof v === 'string' ? v.trim() : '',
      requireActionActor, ...uploadDeps, ...uploadOps,
      console: { error: () => {} },
    }, 'POST')

    const searchRoute = await load('src/app/api/search/route.ts', {
      db, requireActionOwner, visibleSearchHit, unexpiredMessages, visibleInboxPreview,
      mapMessage: r => ({ id: r.id, content: r.content, createdAt: r.createdAt.toISOString() }),
      MESSAGE_FULL_INCLUDE: { sender: true }, clipUtf16, ...feedPagination,
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      notifySocket: async () => {}, NextResponse: Response,
    }, 'GET')

    const mapUser = (u) => ({ id: u.id, name: u.name, username: u.username, color: u.color })
    const usersRoute = await load('src/app/api/users/route.ts', {
      db, mapUser, ensurePulseBot: async () => {},
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      normalizeColor: c => typeof c === 'string' ? c : 'emerald', normalizeUsername: v => String(v ?? '').toLowerCase(),
      suggestUsername: async () => 'suggestion', generateSessionToken, hashSessionToken,
      NextResponse: Response, USER_NAME_MAX: 40,
    }, 'GET')
    const loginRoute = await load('src/app/api/users/login/route.ts', {
      db, mapUser, safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      generateSessionToken, hashSessionToken, pushLegacyTokenHash, NextResponse: Response,
    }, 'POST')

    await run({
      db, auth, listConversations, messagesRoute, pollRoute, reactRoute, viewedRoute, threadRoute,
      uploadsRoute, searchRoute, usersRoute, loginRoute,
    })
  } finally {
    await db.$disconnect()
    await rm(dir, { recursive: true, force: true })
  }
}

test('the remaining message family is owner-bound end to end', () => fixture(async ({ auth, listConversations, messagesRoute, pollRoute }) => {
  // conversation list
  assert.equal((await listConversations(new Request('https://a.test/api/conversations?userId=owner'))).status, 401)
  assert.equal((await listConversations(new Request('https://a.test/api/conversations?userId=owner', { headers: auth('synthetic-other') }))).status, 403)
  assert.equal((await listConversations(new Request('https://a.test/api/conversations?userId=owner', { headers: auth('synthetic-owner') }))).status, 200)
  // history read is ACTOR-derived: a valid credential identifies the reader,
  // then the participant gate decides. Headerless is 401; a room member's
  // credential reads 200; a valid actor outside the room is 403.
  const history = (token) => messagesRoute.GET(new Request('https://a.test/api?userId=owner', { headers: auth(token) }), { params: Promise.resolve({ id: 'room' }) })
  assert.equal((await history(null)).status, 401)
  assert.equal((await history('synthetic-owner')).status, 200)
  assert.equal((await history('synthetic-other')).status, 200, 'another room member is a valid reader')
  assert.equal((await history('synthetic-outsider')).status, 403, 'a valid actor outside the room cannot read history')
  // send
  const send = (token, body = { senderId: 'owner', content: 'matrix hello' }) =>
    messagesRoute.POST(new Request('https://a.test/api', { method: 'POST', headers: auth(token), body: JSON.stringify(body) }),
      { params: Promise.resolve({ id: 'room' }) })
  assert.equal((await send(null)).status, 401)
  assert.equal((await send('synthetic-other')).status, 403)
  const delivered = await send('synthetic-owner')
  assert.equal(delivered.status, 201)
  // legacy poll delegate inherits the send authority (headers pass through)
  const poll = (token) => pollRoute(new Request('https://a.test/api', {
    method: 'POST', headers: auth(token),
    body: JSON.stringify({ senderId: 'owner', clientId: 'poll-matrix-000001', question: 'Lunch?', options: ['Yes', 'No'] }),
  }), { params: Promise.resolve({ id: 'room' }) })
  assert.equal((await poll(null)).status, 401)
  assert.equal((await poll('synthetic-other')).status, 403)
  assert.equal((await poll('synthetic-owner')).status, 201)
}))

test('the legacy react/viewed/thread routes no longer trail their newer siblings in authority', () => fixture(async ({ auth, db, reactRoute, viewedRoute, threadRoute }) => {
  const call = (route, token, url, body, id = 'msg-1') => route(new Request(url, {
    method: 'POST', headers: auth(token), body: JSON.stringify(body ?? {}),
  }), { params: Promise.resolve({ id }) })

  for (const [name, route, body] of [
    ['react', reactRoute, { userId: 'owner', emoji: 'heart' }],
    ['viewed', viewedRoute, { userId: 'owner' }],
  ]) {
    assert.equal((await call(route, null, `https://a.test/${name}`, body)).status, 401, name)
    assert.equal((await call(route, 'synthetic-other', `https://a.test/${name}`, body)).status, 403, name)
  }
  assert.equal((await call(reactRoute, 'synthetic-owner', 'https://a.test/react', { userId: 'owner', emoji: 'heart' })).status, 200)
  assert.equal(await db.reaction.count({ where: { messageId: 'msg-1', userId: 'owner' } }), 1)
  // view-once consumption is owner-bound and only a true view-once target accepts it
  const consumed = await call(viewedRoute, 'synthetic-owner', 'https://a.test/viewed', { userId: 'owner' }, 'msg-eyes')
  assert.equal(consumed.status, 200)
  assert.equal((await consumed.json()).consumed, true)
  // thread content reads
  const thread = (token, userId = 'owner') => threadRoute(new Request(`https://a.test/thread?userId=${userId}`, { headers: auth(token) }),
    { params: Promise.resolve({ id: 'msg-root' }) })
  assert.equal((await thread(null)).status, 401)
  assert.equal((await thread('synthetic-other')).status, 403)
  const read = await thread('synthetic-owner')
  assert.equal(read.status, 200)
  assert.equal((await read.json()).parent.id, 'msg-root')
}))

test('file storage requires a session actor; discovery search is owner-bound', () => fixture(async ({ auth, uploadsRoute, searchRoute }) => {
  const tinyPng = 'data:image/png;base64,' + Buffer.alloc(16, 1).toString('base64')
  const upload = (token) => uploadsRoute(new Request('https://a.test/api/uploads', {
    method: 'POST', headers: auth(token), body: JSON.stringify({ dataUrl: tinyPng }),
  }))
  assert.equal((await upload(null)).status, 401, 'anonymous byte storage is closed')
  assert.equal((await upload('not-a-real-credential')).status, 403)
  assert.equal((await upload('synthetic-owner')).status, 201)
  assert.equal((await upload('synthetic-other')).status, 201, 'any valid session actor may store bytes')

  const search = (token, userId = 'owner') => searchRoute(new Request(`https://a.test/api/search?userId=${userId}&q=hello`, { headers: auth(token) }))
  assert.equal((await search(null)).status, 401)
  assert.equal((await search('synthetic-other')).status, 403)
  assert.equal((await search('synthetic-owner')).status, 200)
}))

test('the open-by-design identity routes stay open: adoption entry points, not protected rooms', () => fixture(async ({ usersRoute, loginRoute }) => {
  const directory = await usersRoute(new Request('https://a.test/api/users'))
  assert.equal(directory.status, 200)
  assert.equal((await directory.json()).users.length, 3)
  const mint = await loginRoute(new Request('https://a.test/api/users/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'owner' }),
  }))
  assert.equal(mint.status, 200)
  assert.equal(typeof (await mint.json()).token, 'string')
}))
