import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import {
  addGroupMembers,
  GroupMembershipError,
  joinChannel,
  groupInvite,
  joinGroupInvite,
  leaveGroup,
  removeGroupMember,
  setGroupRole,
} from '../../src/lib/group-membership.ts'
import { unreadSnapshot, exactMessageReadState } from '../../src/lib/message-visibility.ts'
import { captureReadIntent, advanceReadReceipt } from '../../src/lib/read-receipt.ts'
import {
  conversationMetadataPatch,
  patchConversationMetadata,
  patchMyScreenPrivacy,
} from '../../src/lib/conversation-metadata.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-membership-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of [
      'CREATE TABLE User(id TEXT PRIMARY KEY, preferences TEXT)',
      'CREATE TABLE Conversation(id TEXT PRIMARY KEY, isGroup BOOLEAN, broadcastMode BOOLEAN, updatedAt DATETIME, inviteCode TEXT UNIQUE, name TEXT, photo TEXT, screenPrivacy BOOLEAN DEFAULT false, ttlSeconds INTEGER DEFAULT 0, slowModeSeconds INTEGER DEFAULT 0)',
      `CREATE TABLE ConversationParticipant(draftRevision TEXT NOT NULL DEFAULT 0, id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT,
        role TEXT DEFAULT 'member', joinedAt DATETIME DEFAULT CURRENT_TIMESTAMP, lastReadAt DATETIME DEFAULT CURRENT_TIMESTAMP,
        pinnedAt DATETIME, mutedUntil DATETIME, archivedAt DATETIME, handRaisedAt DATETIME,
        screenPrivacy BOOLEAN DEFAULT false, manualUnread BOOLEAN DEFAULT false, draft TEXT,
        UNIQUE(userId,conversationId))`,
      'CREATE TABLE Message(id TEXT PRIMARY KEY,conversationId TEXT,senderId TEXT,createdAt DATETIME,parentId TEXT,topicId TEXT,deletedAt DATETIME,expiresAt DATETIME,viaAutomation BOOLEAN DEFAULT false)',
      "CREATE TABLE MessageReadObservation(participantId TEXT,messageId TEXT,observedAt DATETIME,source TEXT DEFAULT 'visible',PRIMARY KEY(participantId,messageId),FOREIGN KEY(participantId) REFERENCES ConversationParticipant(id) ON DELETE CASCADE)",
      'CREATE TABLE ReadIntentState(participantId TEXT PRIMARY KEY,revision TEXT,FOREIGN KEY(participantId) REFERENCES ConversationParticipant(id) ON DELETE CASCADE)',
      "INSERT INTO User(id) VALUES ('a'),('b'),('c'),('d')",
      "INSERT INTO Conversation(id,isGroup,broadcastMode,updatedAt) VALUES ('group',true,false,CURRENT_TIMESTAMP),('channel',true,true,CURRENT_TIMESTAMP),('dm',false,false,CURRENT_TIMESTAMP)",
      `INSERT INTO ConversationParticipant(id,userId,conversationId,role,joinedAt,lastReadAt) VALUES
        ('ga','a','group','admin',1577836800000,1577836800000),('gb','b','group','member',1577923200000,1577836800000),
        ('gc','c','group','member',1578009600000,1577836800000),
        ('ca','a','channel','admin',1577836800000,1577836800000),('cb','b','channel','admin',1577923200000,1577836800000),
        ('cc','c','channel','member',1578009600000,1577836800000)`,
    ])
      await db.$executeRawUnsafe(sql)
    await run(db)
  } finally {
    await db.$disconnect()
    await rm(dir, { recursive: true, force: true })
  }
}
const member = (db, room, user) =>
  db.conversationParticipant.findUnique({
    where: { userId_conversationId: { userId: user, conversationId: room } },
    select: { role: true, lastReadAt: true },
  })
const admins = (db, room) =>
  db.conversationParticipant.count({ where: { conversationId: room, role: 'admin' } })
function validRace(results) {
  for (const result of results)
    if (result.status === 'rejected')
      assert.ok([400, 403, 409].includes(result.reason.status), String(result.reason))
}

test('explicit role replay is harmless and a self-demoted member cannot regain admin or manage others', () =>
  fixture(async (db) => {
    assert.equal((await setGroupRole(db, 'group', 'a', 'b', 'admin')).changed, true)
    assert.equal((await setGroupRole(db, 'group', 'a', 'b', 'admin')).changed, false)
    await setGroupRole(db, 'group', 'a', 'a', 'member')
    assert.equal((await setGroupRole(db, 'group', 'a', 'a', 'member')).changed, false)
    await assert.rejects(setGroupRole(db, 'group', 'a', 'a', 'admin'), { status: 403 })
    await assert.rejects(setGroupRole(db, 'group', 'a', 'c', 'admin'), { status: 403 })
    await assert.rejects(addGroupMembers(db, 'group', 'a', ['d']), { status: 403 })
    await assert.rejects(setGroupRole(db, 'group', 'b', 'b', 'member'), { status: 400 })
    assert.equal(await admins(db, 'group'), 1)
  }))
test('group last-admin departure promotes the oldest remaining member atomically', () =>
  fixture(async (db) => {
    const result = await leaveGroup(db, 'group', 'a')
    assert.equal(result.promotedUserId, 'b')
    assert.deepEqual(new Set(result.recipients), new Set(['b', 'c']))
    assert.equal(await member(db, 'group', 'a'), null)
    assert.equal((await member(db, 'group', 'b')).role, 'admin')
    await assert.rejects(leaveGroup(db, 'group', 'd'), { status: 403 })
  }))
test('channel last-admin rule cannot be bypassed through the generic group endpoint', () =>
  fixture(async (db) => {
    await leaveGroup(db, 'channel', 'a', true)
    for (const channelOnly of [false, true])
      await assert.rejects(leaveGroup(db, 'channel', 'b', channelOnly), { status: 403 })
    await leaveGroup(db, 'channel', 'c', true)
    assert.equal(await admins(db, 'channel'), 1)
  }))
test('concurrent channel admins leaving cannot orphan the remaining subscribers', () =>
  fixture(async (db) => {
    validRace(
      await Promise.allSettled([
        leaveGroup(db, 'channel', 'a', true),
        leaveGroup(db, 'channel', 'b', true),
      ]),
    )
    assert.ok((await admins(db, 'channel')) >= 1)
    assert.notEqual(await member(db, 'channel', 'c'), null)
  }))
test('demotion racing departure shares the last-admin guard across endpoint aliases', () =>
  fixture(async (db) => {
    validRace(
      await Promise.allSettled([
        setGroupRole(db, 'channel', 'a', 'b', 'member'),
        leaveGroup(db, 'channel', 'a'),
      ]),
    )
    assert.ok((await admins(db, 'channel')) >= 1)
  }))
test('removing a possible successor racing the last admin departure never leaves an orphaned group', () =>
  fixture(async (db) => {
    validRace(
      await Promise.allSettled([
        removeGroupMember(db, 'group', 'a', 'b'),
        leaveGroup(db, 'group', 'a'),
      ]),
    )
    assert.ok((await admins(db, 'group')) >= 1)
    assert.notEqual(await member(db, 'group', 'c'), null)
  }))
test('two concurrent self-demotions retain an administrator', () =>
  fixture(async (db) => {
    validRace(
      await Promise.allSettled([
        setGroupRole(db, 'channel', 'a', 'a', 'member'),
        setGroupRole(db, 'channel', 'b', 'b', 'member'),
      ]),
    )
    assert.ok((await admins(db, 'channel')) >= 1)
  }))
test('an ordinary empty group can be left without inventing a successor', () =>
  fixture(async (db) => {
    await removeGroupMember(db, 'group', 'a', 'b')
    await removeGroupMember(db, 'group', 'a', 'c')
    assert.deepEqual(await leaveGroup(db, 'group', 'a'), { promotedUserId: null, recipients: [] })
    await assert.rejects(leaveGroup(db, 'group', 'd'), { status: 403 })
  }))
test('failed membership writes roll back promotion and succession together', () =>
  fixture(async (db) => {
    await db.$executeRawUnsafe(
      "CREATE TRIGGER no_bump BEFORE UPDATE ON Conversation BEGIN SELECT RAISE(ABORT,'storage failed'); END",
    )
    await assert.rejects(setGroupRole(db, 'group', 'a', 'b', 'admin'))
    assert.equal((await member(db, 'group', 'b')).role, 'member')
    await db.$executeRawUnsafe('DROP TRIGGER no_bump')
    await db.$executeRawUnsafe(
      "CREATE TRIGGER no_departure BEFORE DELETE ON ConversationParticipant BEGIN SELECT RAISE(ABORT,'storage failed'); END",
    )
    await assert.rejects(leaveGroup(db, 'group', 'a'))
    assert.equal((await member(db, 'group', 'a')).role, 'admin')
    assert.equal((await member(db, 'group', 'b')).role, 'member')
  }))
test('join and add retries preserve existing roles and read boundaries', () =>
  fixture(async (db) => {
    const before = await member(db, 'channel', 'a')
    assert.equal((await joinChannel(db, 'channel', 'a')).already, true)
    assert.deepEqual(await member(db, 'channel', 'a'), before)
    const results = await Promise.allSettled([
      joinChannel(db, 'channel', 'd'),
      joinChannel(db, 'channel', 'd'),
    ])
    validRace(results)
    await joinChannel(db, 'channel', 'd')
    assert.equal(
      await db.conversationParticipant.count({ where: { conversationId: 'channel', userId: 'd' } }),
      1,
    )
    assert.deepEqual((await addGroupMembers(db, 'group', 'a', [' d ', 'd', 'a'])).added, ['d'])
    assert.deepEqual((await addGroupMembers(db, 'group', 'a', ['d'])).added, [])
    await assert.rejects(addGroupMembers(db, 'group', 'a', ['missing']), {
      status: 400,
      missing: ['missing'],
    })
    await assert.rejects(joinChannel(db, 'dm', 'a'), { status: 400 })
  }))
test('only current admins can remove members and retries never kick a promoted admin', () =>
  fixture(async (db) => {
    assert.equal((await removeGroupMember(db, 'group', 'a', 'c')).changed, true)
    assert.equal((await removeGroupMember(db, 'group', 'a', 'c')).changed, false)
    await assert.rejects(removeGroupMember(db, 'group', 'b', 'c'), { status: 403 })
    await setGroupRole(db, 'group', 'a', 'b', 'admin')
    await assert.rejects(removeGroupMember(db, 'group', 'a', 'b'), { status: 403 })
    await assert.rejects(removeGroupMember(db, 'group', 'a', 'a'), { status: 400 })
  }))
test('contention is an explicit retryable membership conflict, not a fabricated success', async () => {
  for (const code of ['P1008', 'P2028', 'P2034', 'P2002']) {
    await assert.rejects(
      leaveGroup(
        {
          $transaction: async () => {
            throw { code }
          },
        },
        'room',
        'a',
      ),
      { status: 409 },
    )
  }
})

test('actual targeted PATCH handler projects private fields for the requesting admin', async () => {
  const source = await readFile(
    new URL('../../src/app/api/conversations/[id]/members/[userId]/route.ts', import.meta.url),
    'utf8',
  )
  const javascript = stripTypeScriptTypes(
    source
      .replace(/^import[\s\S]*?\bfrom ['"][^'"]+['"][ \t]*;?\r?\n/gm, '')
      .replace(/^export /gm, ''),
    { mode: 'transform' },
  )
  const calls = []
  const deps = {
    NextResponse: Response,
    GroupMembershipError,
    CONVERSATION_FULL_INCLUDE: {},
    bearerToken: () => null,
    requireActionOwner: async () => null,
    notifySocket: async () => {},
    safeJson: (req) => req.json(),
    strField: (value) => (typeof value === 'string' ? value.trim() : ''),
    setGroupRole: async (...args) => {
      calls.push(args)
      return { changed: true, recipients: ['requester', 'target'] }
    },
    removeGroupMember: async () => {
      throw Error('not used')
    },
    db: {
      conversation: {
        findFirst: async (query) => {
          assert.equal(query.where.participants.some.userId, 'requester')
          return {}
        },
      },
    },
    buildConversationDetail: async (_, viewer) => ({
      myDraft: viewer === 'requester' ? 'my own draft' : 'TARGET PRIVATE DRAFT',
    }),
  }
  const { PATCH } = new Function(...Object.keys(deps), javascript + '\nreturn { PATCH };')(
    ...Object.values(deps),
  )
  const response = await PATCH(
    new Request('https://pulse.test', {
      method: 'PATCH',
      body: JSON.stringify({ requesterId: 'requester', action: 'promote' }),
    }),
    { params: Promise.resolve({ id: 'group', userId: 'target' }) },
  )
  assert.equal(response.status, 200)
  assert.deepEqual(await response.json(), { conversation: { myDraft: 'my own draft' } })
  assert.deepEqual(calls[0].slice(1), ['group', 'requester', 'target', 'admin'])
  deps.bearerToken = () => 'foreign-owner-token'
  deps.requireActionOwner = async (_, actor) => {
    assert.equal(actor, 'requester')
    return Response.json({ error: 'Not this action owner' }, { status: 403 })
  }
  const deniedHandler = new Function(...Object.keys(deps), javascript + '\nreturn { PATCH };')(
    ...Object.values(deps),
  ).PATCH
  const denied = await deniedHandler(
    new Request('https://pulse.test', {
      method: 'PATCH',
      body: JSON.stringify({ requesterId: 'requester', action: 'promote' }),
    }),
    { params: Promise.resolve({ id: 'group', userId: 'target' }) },
  )
  assert.equal(denied.status, 403)
  assert.equal(calls.length, 1)
})

test('lazy invite creation converges and only a current admin may rotate it', () =>
  fixture(async (db) => {
    let sequence = 0
    const mint = () => `CODE${++sequence}`
    const results = await Promise.allSettled([
      groupInvite(db, 'group', 'a', false, mint),
      groupInvite(db, 'group', 'a', false, mint),
    ])
    validRace(results)
    const current = await groupInvite(db, 'group', 'a', false, mint)
    for (const result of results)
      if (result.status === 'fulfilled') assert.equal(result.value.inviteCode, current.inviteCode)
    assert.equal((await groupInvite(db, 'group', 'a', false, mint)).changed, false)
    await assert.rejects(groupInvite(db, 'group', 'b', true, mint), { status: 403 })
    await assert.rejects(
      groupInvite(db, 'group', 'a', true, () => current.inviteCode),
      { status: 503 },
    )
    const next = await groupInvite(db, 'group', 'a', true, mint)
    assert.notEqual(next.inviteCode, current.inviteCode)
    await assert.rejects(joinGroupInvite(db, current.inviteCode, 'd'), { status: 404 })
  }))
test('duplicate invite redemption keeps one membership and preserves admin/read state', () =>
  fixture(async (db) => {
    const { inviteCode } = await groupInvite(db, 'group', 'a', false, () => 'INVITE01')
    const before = await member(db, 'group', 'a')
    assert.equal((await joinGroupInvite(db, inviteCode, 'a')).alreadyMember, true)
    assert.deepEqual(await member(db, 'group', 'a'), before)
    validRace(
      await Promise.allSettled([
        joinGroupInvite(db, inviteCode, 'd'),
        joinGroupInvite(db, inviteCode, 'd'),
      ]),
    )
    assert.equal((await joinGroupInvite(db, inviteCode.toLowerCase(), 'd')).alreadyMember, true)
    assert.equal(
      await db.conversationParticipant.count({ where: { conversationId: 'group', userId: 'd' } }),
      1,
    )
  }))
test('redemption list-update failure rolls back membership and a revoked invite stays revoked', () =>
  fixture(async (db) => {
    await groupInvite(db, 'group', 'a', false, () => 'INVITE01')
    await db.$executeRawUnsafe(
      "CREATE TRIGGER no_bump BEFORE UPDATE ON Conversation BEGIN SELECT RAISE(ABORT,'storage failed'); END",
    )
    await assert.rejects(joinGroupInvite(db, 'INVITE01', 'd'))
    assert.equal(await member(db, 'group', 'd'), null)
    await db.$executeRawUnsafe('DROP TRIGGER no_bump')
    const results = await Promise.allSettled([
      joinGroupInvite(db, 'INVITE01', 'd'),
      groupInvite(db, 'group', 'a', true, () => 'INVITE02'),
    ])
    for (const result of results)
      if (result.status === 'rejected') assert.ok([404, 409].includes(result.reason.status))
    await groupInvite(db, 'group', 'a', true, () => 'INVITE03')
    await assert.rejects(joinGroupInvite(db, 'INVITE01', 'd'), { status: 404 })
  }))

test('metadata changes respect current membership and retain participant-level privacy controls', () =>
  fixture(async (db) => {
    await assert.rejects(
      patchConversationMetadata(db, 'group', 'b', { name: 'Forbidden rename' }),
      { status: 403 },
    )
    await patchConversationMetadata(db, 'group', 'b', { screenPrivacy: true })
    assert.equal(
      (
        await db.conversation.findUnique({
          where: { id: 'group' },
          select: { screenPrivacy: true },
        })
      ).screenPrivacy,
      true,
    )
    await patchConversationMetadata(db, 'group', 'a', {
      name: 'Confirmed',
      photo: '/api/uploads/photo.jpg',
    })
    await setGroupRole(db, 'group', 'a', 'b', 'admin')
    await setGroupRole(db, 'group', 'b', 'a', 'member')
    await assert.rejects(patchConversationMetadata(db, 'group', 'a', { name: 'Late rename' }), {
      status: 403,
    })
    await assert.rejects(patchConversationMetadata(db, 'group', 'd', { screenPrivacy: false }), {
      status: 403,
    })
    assert.equal(
      (await db.conversation.findUnique({ where: { id: 'group' }, select: { name: true } })).name,
      'Confirmed',
    )
  }))
test('malformed mixed metadata fields cannot silently clear a photo or partially apply a rename', () => {
  for (const body of [
    { name: 'Valid', photo: {} },
    { name: 'Valid', photo: false },
    { name: 'Valid', broadcast: 'false' },
    { photo: null, screenPrivacy: 1 },
    { photo: '/api/uploads/../secret.jpg' },
    {},
  ]) {
    assert.throws(() => conversationMetadataPatch(body, 48), { status: 400 })
  }
  assert.deepEqual(
    conversationMetadataPatch({ name: ' New ', photo: null, screenPrivacy: false }, 48),
    { name: 'New', photo: null, screenPrivacy: false },
  )
  assert.deepEqual(conversationMetadataPatch({ broadcast: false }, 48), { broadcastMode: false })
})

test('TTL remains participant-level while slow mode requires a current group admin', () =>
  fixture(async (db) => {
    await patchConversationMetadata(db, 'group', 'b', { ttlSeconds: 86400 })
    await assert.rejects(patchConversationMetadata(db, 'group', 'b', { slowModeSeconds: 5 }), {
      status: 403,
    })
    await patchConversationMetadata(db, 'group', 'a', { slowModeSeconds: 5 })
    await removeGroupMember(db, 'group', 'a', 'b')
    await assert.rejects(patchConversationMetadata(db, 'group', 'b', { ttlSeconds: 604800 }), {
      status: 403,
    })
    assert.deepEqual(
      await db.conversation.findUnique({
        where: { id: 'group' },
        select: { ttlSeconds: true, slowModeSeconds: true },
      }),
      { ttlSeconds: 86400, slowModeSeconds: 5 },
    )
    await assert.rejects(patchConversationMetadata(db, 'group', 'a', { ttlSeconds: -1 }), {
      status: 400,
    })
    await assert.rejects(patchConversationMetadata(db, 'group', 'a', { slowModeSeconds: 2 }), {
      status: 400,
    })
  }))
test('personal privacy writes only the current member and preserves DM comfort/TTL policy', () =>
  fixture(async (db) => {
    await patchMyScreenPrivacy(db, 'group', 'b', true)
    const flag = (userId) =>
      db.conversationParticipant.findUnique({
        where: { userId_conversationId: { userId, conversationId: 'group' } },
        select: { screenPrivacy: true },
      })
    assert.equal((await flag('b')).screenPrivacy, true)
    assert.equal((await flag('a')).screenPrivacy, false)
    await removeGroupMember(db, 'group', 'a', 'b')
    await assert.rejects(patchMyScreenPrivacy(db, 'group', 'b', false), { status: 403 })
    await db.conversationParticipant.create({
      data: { conversationId: 'dm', userId: 'b' },
      select: { id: true },
    })
    await patchConversationMetadata(db, 'dm', 'b', { ttlSeconds: 86400, screenPrivacy: true })
    await patchMyScreenPrivacy(db, 'dm', 'b', true)
    await assert.rejects(patchConversationMetadata(db, 'dm', 'b', { slowModeSeconds: 5 }), {
      status: 400,
    })
  }))

test('committed membership routes wake only affected accounts, including departed members, without relying on socket delivery', () => fixture(async db => {
  const hints = [], sockets = []
  const bindings = {
    NextResponse: Response, GroupMembershipError,
    db: { conversation: { findFirst: async () => ({ id: 'group' }) } },
    CONVERSATION_FULL_INCLUDE: {}, buildConversationDetail: async row => row,
    safeJson: req => req.json(), strField: value => typeof value === 'string' ? value.trim() : '',
    bearerToken: req => req.headers.get('authorization'),
    requireActionOwner: async (req, owner) => req.headers.get('authorization') === `Bearer synthetic-${owner}`
      ? null : Response.json({ error: 'denied' }, { status: 403 }),
    invalidateNativeReadState: (owners, room) => hints.push([owners, room]),
    notifySocket: async (...args) => sockets.push(args),
    addGroupMembers: (_, ...args) => addGroupMembers(db, ...args),
    leaveGroup: (_, ...args) => leaveGroup(db, ...args),
    removeGroupMember: (_, ...args) => removeGroupMember(db, ...args),
    setGroupRole: (_, ...args) => setGroupRole(db, ...args),
    joinChannel: (_, ...args) => joinChannel(db, ...args),
    joinGroupInvite: (_, ...args) => joinGroupInvite(db, ...args),
  }
  async function invoke(path, method, body, params, token = body.requesterId ?? body.userId) {
    const text = (await readFile(new URL(`../../src/app/api/${path}/route.ts`, import.meta.url), 'utf8'))
      .replace(/^import[\s\S]*?\bfrom ['"][^'"]+['"][ \t]*;?\r?\n/gm, '').replace(/^export /gm, '')
    const handler = new Function(...Object.keys(bindings), stripTypeScriptTypes(text, {mode:'transform'}) + `;return ${method}`)(...Object.values(bindings))
    return handler(new Request('https://pulse.test', { method, headers: { authorization: `Bearer synthetic-${token}` }, body: JSON.stringify(body) }), { params: Promise.resolve(params) })
  }
  const targeted = 'conversations/[id]/members/[userId]', collection = 'conversations/[id]/members'
  assert.equal((await invoke(targeted,'DELETE',{requesterId:'a'},{id:'group',userId:'b'},'wrong')).status,403)
  assert.deepEqual(hints, [])
  assert.equal((await invoke(targeted,'DELETE',{requesterId:'a'},{id:'group',userId:'b'})).status,200)
  assert.deepEqual(hints.pop(), ['b','group'])
  assert.ok(sockets.at(-1)[1].includes('b'), 'The removed member also remains a socket recipient')
  assert.equal(await member(db,'group','b'), null)
  assert.equal((await invoke(targeted,'DELETE',{requesterId:'a'},{id:'group',userId:'b'})).status,200)
  assert.equal((await invoke(targeted,'DELETE',{requesterId:'a'},{id:'group',userId:'a'})).status,400)
  assert.deepEqual(hints, [], 'No wake-up for unchanged or refused removal')
  assert.equal((await invoke(collection,'DELETE',{requesterId:'c'},{id:'group'})).status,200)
  assert.deepEqual(hints.pop(), ['c','group'])
  assert.equal((await invoke(collection,'POST',{requesterId:'a',userIds:['d','d','a']},{id:'group'})).status,200)
  assert.deepEqual(hints.pop(), [['d'],'group'], 'Only the newly joined accounts, in one batch')
  const channel = 'channels/[id]/subscribe'
  assert.equal((await invoke(channel,'POST',{userId:'d'},{id:'channel'})).status,200)
  assert.deepEqual(hints.pop(), ['d','channel'])
  assert.equal((await invoke(channel,'POST',{userId:'d'},{id:'channel'})).status,200)
  assert.deepEqual(hints, [])
  assert.equal((await invoke(channel,'DELETE',{userId:'d'},{id:'channel'})).status,200)
  assert.deepEqual(hints.pop(), ['d','channel'])
  await groupInvite(db,'group','a',false,()=>'NATIVE42')
  assert.equal((await invoke('invite/[code]/join','POST',{userId:'b'},{code:'NATIVE42'})).status,200)
  assert.deepEqual(hints.pop(), ['b','group'])
  assert.equal((await invoke('invite/[code]/join','POST',{userId:'b'},{code:'NATIVE42'})).status,200)
  assert.deepEqual(hints, [])
}))


for (const entry of ['add', 'channel', 'invite']) test(`${entry}: membership creation exempts existing equal-time history, not same-time future arrivals or actual Seen`, async t => {
  t.mock.timers.enable({ apis: ['Date'], now: new Date('2030-01-02T03:04:05.678Z') })
  try {
    await fixture(async db => {
      const room = entry === 'channel' ? 'channel' : 'group', at = new Date()
      await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES ('before',${room},'a',${at})`
      const futureStamp = new Date(at.getTime() + 1)
      await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES ('existing-skew',${room},'a',${futureStamp})`
      if (entry === 'invite') await groupInvite(db,room,'a',false,()=>'HISTORY42')
      const join = () => entry === 'add' ? addGroupMembers(db,room,'a',['d']) : entry === 'channel'
        ? joinChannel(db,room,'d') : joinGroupInvite(db,'HISTORY42','d')
      await join()
      const snapshot = () => unreadSnapshot(db,room,'d',at,at)
      assert.equal((await snapshot()).unreadCount,0,'Pre-existing messages are history, even at the join timestamp or under clock skew')
      assert.deepEqual(await exactMessageReadState(db,room,'d','before',at),[],'A history exemption is not Seen')
      await db.$executeRaw`INSERT INTO Message(id,conversationId,senderId,createdAt) VALUES ('after',${room},'a',${at})`
      await join() // A retry must not reclassify arrivals as old history.
      assert.equal((await snapshot()).unreadCount,1)
      assert.equal((await snapshot()).firstUnreadMessageId,'after')
      const original = await captureReadIntent(db,room,'d')
      await advanceReadReceipt(db,room,'d','before',original.readIntent)
      const membership = await db.conversationParticipant.findUnique({where:{userId_conversationId:{userId:'d',conversationId:room}},select:{id:true}})
      assert.deepEqual(await exactMessageReadState(db,room,'d','before',at),[membership.id],'Actually reading history upgrades only that exact observation')
      assert.equal((await snapshot()).unreadCount,1,'Reading history cannot sweep the new equal-time arrival')
      await db.conversationParticipant.delete({where:{id:membership.id},select:{id:true}})
      await join()
      assert.equal((await snapshot()).unreadCount,0,'A genuinely new membership gets a new history snapshot')
      assert.deepEqual(await exactMessageReadState(db,room,'d','before',at),[])
      await assert.rejects(advanceReadReceipt(db,room,'d','after',original.readIntent),{status:410})
    })
  } finally { t.mock.timers.reset() }
})

test('membership and its boundary exclusions roll back together if history storage is unavailable',()=>fixture(async db=>{
  await db.$executeRawUnsafe('DROP TABLE MessageReadObservation')
  await assert.rejects(addGroupMembers(db,'group','a',['d']))
  assert.equal(await member(db,'group','d'),null)
  await assert.rejects(joinChannel(db,'channel','d'))
  assert.equal(await member(db,'channel','d'),null)
}))
