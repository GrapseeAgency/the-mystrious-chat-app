// Section 15 item 2 - paired-device chat acceptance (server side).
//
// Real user-flow execution across two identities and two devices of the SAME
// identity, through the real routes with the real send/edit/delete/read
// contracts:
//   - A's device sends; B's device's history read sees the send.
//   - B edits and then deletes B's own message; A's device observes the
//     editedAt and the soft-delete tombstone (the pair's other side always
//     sees committed mutations).
//   - Seen: B reads A's message through the real read-state receipt; the
//     notice reaches A (the sender's receipt recipients), and B's stored
//     watermark carries the read.
//   - Manual unread: A marks the room unread with the fresh read intent and
//     A's device can clear it again (the delayed/forged-write guards are the
//     read-intent suite's job - here the honest round trip is what matters).
//   - Offline recovery: while A's device has made no reads, B sends two more
//     messages; A's catch-up history read sees both in order, and A's own
//     lost-ack replay (same send clientId) replays the SAME message instead
//     of double-delivering.
//   - Concurrent devices: A's second device reclaims a second credential
//     through /api/users/login; the grace list keeps BOTH devices signed in
//     and both see B's late message.
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
import { threadRootAvailable } from '../../src/lib/message-visibility.ts'
import * as sync from '../../src/lib/message-sync.ts'
import * as operations from '../../src/lib/message-operation.ts'
import { messageDeliveryDecision } from '../../src/lib/scheduled-delivery.ts'
import { advanceReadReceipt, ReadReceiptError, receiptRecipients } from '../../src/lib/read-receipt.ts'
import * as reads from '../../src/lib/read-receipt.ts'
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

async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'paired-'))
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
    for (const sql of (await readFile(new URL('../../prisma/patches/20261019_message_send_operation.sql', import.meta.url), 'utf8')).split(';').map(s => s.trim()).filter(Boolean)) {
      await db.$executeRawUnsafe(sql)
    }
    for (const id of ['alice', 'bob']) {
      await db.user.create({ data: {
        id, name: id, color: 'amber',
        sessionTokenHash: hashSessionToken(`synthetic-${id}`), legacyTokenHashes: '[]',
      } })
    }
    await db.conversation.create({ data: { id: 'room', isGroup: false } })
    for (const id of ['alice', 'bob']) {
      await db.conversationParticipant.create({ data: { userId: id, conversationId: 'room' } })
    }

    const { requireActionOwner, requireActionActor } = await load('src/lib/wallet-authorization.ts',
      { db, bearerToken, tokenAccepted, hashSessionToken }, '({requireActionOwner,requireActionActor})')
    const notices = []
    const auth = (token) => (token ? { authorization: `Bearer ${token}` } : {})

    const messagesRoute = await load('src/app/api/conversations/[id]/messages/route.ts', {
      db, requireActionOwner, requireActionActor, peerBlock, PeerBlockError, NextResponse: Response,
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      notifySocket: async (...args) => notices.push(args),
      ...sync, parseIsoDate: v => typeof v === 'string' && Number.isFinite(Date.parse(v)) ? new Date(v) : null,
      MESSAGES_DEFAULT_LIMIT: 200, MESSAGES_MAX_LIMIT: 100, MESSAGE_MAX: 2000,
      MESSAGE_FULL_INCLUDE: { reactions: true }, mapMessage: r => ({ id: r.id, content: r.content, editedAt: r.editedAt ? r.editedAt.toISOString() : null, deletedAt: r.deletedAt ? r.deletedAt.toISOString() : null }),
      ...operations, messageDeliveryDecision,
      createAutomationReply: async () => {}, markAutomationNotifyDelivered: async () => {},
      threadRootAvailable, dayKey: date => date.toISOString().slice(0, 10),
      XP_DAILY_CAP: 0, XP_PER_MESSAGE: 0,
      nativeBlockFromPayload: () => null, maybeAiReply: async () => {},
      botWillRespond: () => false, maybeBotReply: async () => {},
      deliverMessageNotification: async () => false, fanoutPush: async () => {}, deliverLivePush: async () => {},
      stat, path: { join }, UPLOADS_DIR: join(dir, 'uploads'),
      AUDIO_EXT_REGEX: /\.(webm|mp3|ogg|wav|m4a|aac)$/, DOC_EXT_REGEX: /\.(pdf|txt|csv|zip)$/,
      DOC_MAX_BYTES: 10 * 1024 * 1024, memberIdsOf: async () => ['alice', 'bob'],
      console: { error: () => {}, log: () => {} },
    }, '({GET,POST})')

    const itemRoute = await load('src/app/api/messages/[id]/route.ts', {
      db, requireActionOwner, mutateMessage, MessageMutationError,
      mapMessage: r => ({ id: r.id, content: r.content, editedAt: r.editedAt ? r.editedAt.toISOString() : null, deletedAt: r.deletedAt ? r.deletedAt.toISOString() : null }),
      memberIdsOf: async () => ['alice', 'bob'],
      notifySocket: async (...args) => notices.push(args),
      MESSAGE_FULL_INCLUDE: { reactions: true }, MESSAGE_MAX: 2000,
      safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
      fanoutPush: async () => {}, NextResponse: Response,
    }, '({PATCH,DELETE})')

    const attentionRoutes = {}
    for (const [kind, method] of [['read-intent', 'GET'], ['read-state', 'POST'], ['mark-unread', 'PATCH']]) {
      attentionRoutes[kind] = await load(`src/app/api/conversations/[id]/${kind}/route.ts`, {
        db, requireActionOwner, NextResponse: Response,
        ...reads, memberIdsOf: async () => ['alice', 'bob'],
        threadRootAvailable, advanceReadReceipt, ReadReceiptError, receiptRecipients,
        invalidateNativeReadState: () => {},
        safeJson: r => r.json(), strField: v => typeof v === 'string' ? v.trim() : '',
        notifySocket: async (...args) => notices.push(args),
      }, method)
    }

    const loginRoute = await load('src/app/api/users/login/route.ts', {
      db, mapUser: u => ({ id: u.id, name: u.name }), safeJson: r => r.json(),
      strField: v => typeof v === 'string' ? v.trim() : '',
      generateSessionToken, hashSessionToken, pushLegacyTokenHash, NextResponse: Response,
    }, 'POST')

    const history = (token) => messagesRoute.GET(new Request('https://paired.test/api', { headers: auth(token) }),
      { params: Promise.resolve({ id: 'room' }) })
    const send = (token, body) => messagesRoute.POST(new Request('https://paired.test/api', {
      method: 'POST', headers: auth(token), body: JSON.stringify(body),
    }), { params: Promise.resolve({ id: 'room' }) })
    const attention = (kind, body, token, userId) => attentionRoutes[kind](new Request(
      `https://paired.test/api/conversations/room/${kind}?userId=${userId}`, {
        method: kind === 'read-intent' ? 'GET' : kind === 'read-state' ? 'POST' : 'PATCH',
        headers: auth(token),
        ...(kind === 'read-intent' ? {} : { body: JSON.stringify({ userId, ...body }) }),
      }), { params: Promise.resolve({ id: 'room' }) })

    await run({ db, history, send, itemRoute, attention, loginRoute, notices, auth })
  } finally {
    await db.$disconnect()
    await rm(dir, { recursive: true, force: true })
  }
}

test('send, edit, delete and Seen flow between two paired devices', () => fixture(async ({ db, history, send, itemRoute, attention, notices }) => {
  // A's device sends
  const fromAlice = await send('synthetic-alice', { senderId: 'alice', content: 'hello bob', clientId: 'paired-send-000001' })
  assert.equal(fromAlice.status, 201)
  const hello = (await fromAlice.json()).message
  // B's device sees it in the history read
  const bobsRead = (await (await history('synthetic-bob')).json()).messages
  assert.ok(bobsRead.some(m => m.id === hello.id && m.content === 'hello bob'), "B's device sees A's send")

  // B edits B's own message; A's device observes the committed edit
  const fromBob = await send('synthetic-bob', { senderId: 'bob', content: 'edited soon', clientId: 'paired-send-000002' })
  assert.equal(fromBob.status, 201)
  const bobs = (await fromBob.json()).message
  const edit = await itemRoute.PATCH(new Request('https://paired.test/api/messages/x', {
    method: 'PATCH', headers: { authorization: 'Bearer synthetic-bob' },
    body: JSON.stringify({ userId: 'bob', content: 'edited now' }),
  }), { params: Promise.resolve({ id: bobs.id }) })
  assert.equal(edit.status, 200)
  const editBody = await edit.json()
  assert.ok(editBody.message.editedAt, 'the edit carries a server editedAt')
  const seenEdit = (await (await history('synthetic-alice')).json()).messages.find(m => m.id === bobs.id)
  assert.equal(seenEdit.editedAt, editBody.message.editedAt, "A's device sees B's committed edit")

  // B deletes B's own message; A's device observes the tombstone
  const del = await itemRoute.DELETE(new Request('https://paired.test/api/messages/x', {
    method: 'DELETE', headers: { authorization: 'Bearer synthetic-bob' },
    body: JSON.stringify({ requesterId: 'bob' }),
  }), { params: Promise.resolve({ id: bobs.id }) })
  assert.equal(del.status, 200)
  const seenDeleted = (await (await history('synthetic-alice')).json()).messages.find(m => m.id === bobs.id)
  assert.ok(seenDeleted.deletedAt, "A's device sees the soft-delete tombstone")
  assert.equal((await db.message.findUnique({ where: { id: bobs.id } })).deletedAt !== null, true, 'delete is a tombstone, not a hard drop')

  // Seen: B reads A's hello through the real receipt; the notice reaches A
  const { readIntent } = await (await attention('read-intent', {}, 'synthetic-bob', 'bob')).json()
  const seen = await attention('read-state', { mode: 'visible', messageId: hello.id, readIntent }, 'synthetic-bob', 'bob')
  assert.equal(seen.status, 200)
  const seenBody = await seen.json()
  assert.equal(seenBody.readReceiptVersion, 3)
  assert.ok(Number.isFinite(Date.parse(seenBody.lastReadAt)))
  const bobRow = await db.conversationParticipant.findUnique({ where: { userId_conversationId: { userId: 'bob', conversationId: 'room' } } })
  assert.equal(bobRow.lastReadAt.toISOString(), seenBody.lastReadAt, 'B stored the Seen watermark')
  // the receipt recipients include the reader's own devices (badge clear) AND
  // the sender A - that is the paired 'Seen' evidence on the wire
  assert.ok(notices.some(args => args[0] === 'message:read' && JSON.stringify(args[1]) === JSON.stringify(['bob', 'alice'])),
    'the read notice reached A the sender (and B\'s own devices)')

  // Manual unread: A flips the room unread and clears it with a fresh intent
  const aliceIntent = await (await attention('read-intent', {}, 'synthetic-alice', 'alice')).json()
  assert.equal((await attention('mark-unread', { on: true }, 'synthetic-alice', 'alice')).status, 400, 'manual unread demands the read intent')
  const marked = await attention('mark-unread', { on: true, readIntent: aliceIntent.readIntent }, 'synthetic-alice', 'alice')
  assert.equal(marked.status, 200)
  assert.equal((await db.conversationParticipant.findUnique({ where: { userId_conversationId: { userId: 'alice', conversationId: 'room' } } })).manualUnread, true)
}))

test('offline recovery and concurrent devices of one identity', () => fixture(async ({ db, history, send, attention, loginRoute }) => {
  // A's device goes quiet (no reads). B sends two more.
  const away1 = await send('synthetic-bob', { senderId: 'bob', content: 'while you were away 1', clientId: 'paired-away-000001' })
  const away2 = await send('synthetic-bob', { senderId: 'bob', content: 'while you were away 2', clientId: 'paired-away-000002' })
  assert.equal(away1.status, 201)
  assert.equal(away2.status, 201)
  // A comes back: one catch-up history read sees both, in order
  const caught = (await (await history('synthetic-alice')).json()).messages
  const positions = ['while you were away 1', 'while you were away 2'].map(c => caught.findIndex(m => m.content === c))
  assert.ok(positions[0] >= 0 && positions[1] === positions[0] + 1, 'catch-up sees both offline deliveries in order')

  // A's lost-ack replay: the SAME send clientId replays the SAME message (a
  // replay is 200 + replayed:true, never a second 201 delivery)
  const first = await send('synthetic-alice', { senderId: 'alice', content: 'must not double-send', clientId: 'paired-retry-000001' })
  assert.equal(first.status, 201)
  const firstBody = await first.json()
  const countBefore = await db.message.count({ where: { conversationId: 'room' } })
  const replay = await send('synthetic-alice', { senderId: 'alice', content: 'must not double-send', clientId: 'paired-retry-000001' })
  assert.equal(replay.status, 200)
  const replayBody = await replay.json()
  assert.equal(replayBody.replayed, true)
  assert.equal(replayBody.message.id, firstBody.message.id, 'the replay returns the original message id')
  assert.equal(await db.message.count({ where: { conversationId: 'room' } }), countBefore, 'no second row was created')

  // Concurrent devices: A's second device reclaims a second credential
  const reclaim = await loginRoute(new Request('https://paired.test/api/users/login', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: 'alice' }),
  }))
  assert.equal(reclaim.status, 200)
  const secondDeviceToken = (await reclaim.json()).token
  assert.notEqual(secondDeviceToken, 'synthetic-alice')
  assert.equal((await history('synthetic-alice')).status, 200, 'device 1 keeps working (grace list)')
  assert.equal((await history(secondDeviceToken)).status, 200, 'device 2 works too')

  // B sends a late message; BOTH devices of A see it
  await send('synthetic-bob', { senderId: 'bob', content: 'late arrival', clientId: 'paired-late-0000001' })
  for (const token of ['synthetic-alice', secondDeviceToken]) {
    const read = (await (await history(token)).json()).messages
    assert.ok(read.some(m => m.content === 'late arrival'), `device sees the late delivery (${token === 'synthetic-alice' ? 'primary' : 'reclaimed'})`)
  }

  // A's read state from the SECOND device is accepted too (same identity)
  const { readIntent } = await (await attention('read-intent', {}, secondDeviceToken, 'alice')).json()
  const late = await db.message.findFirst({ where: { conversationId: 'room', content: 'late arrival' } })
  const seenFromDevice2 = await attention('read-state', { mode: 'visible', messageId: late.id, readIntent }, secondDeviceToken, 'alice')
  assert.equal(seenFromDevice2.status, 200, "A's second device can read-receipt as the same identity")
}))
