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
  leaveGroup,
  removeGroupMember,
  setGroupRole,
} from '../../src/lib/group-membership.ts'
const { PrismaClient } = createRequire(import.meta.url)('../../prisma/generated-client')
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'pulse-membership-'))
  const db = new PrismaClient({ datasourceUrl: `file:${join(dir, 'test.sqlite')}` })
  try {
    for (const sql of [
      'CREATE TABLE User(id TEXT PRIMARY KEY)',
      'CREATE TABLE Conversation(id TEXT PRIMARY KEY, isGroup BOOLEAN, broadcastMode BOOLEAN, updatedAt DATETIME)',
      `CREATE TABLE ConversationParticipant(id TEXT PRIMARY KEY, userId TEXT, conversationId TEXT,
        role TEXT DEFAULT 'member', joinedAt DATETIME DEFAULT CURRENT_TIMESTAMP, lastReadAt DATETIME DEFAULT CURRENT_TIMESTAMP,
        pinnedAt DATETIME, mutedUntil DATETIME, archivedAt DATETIME, handRaisedAt DATETIME,
        screenPrivacy BOOLEAN DEFAULT false, manualUnread BOOLEAN DEFAULT false, draft TEXT,
        UNIQUE(userId,conversationId))`,
      "INSERT INTO User VALUES ('a'),('b'),('c'),('d')",
      "INSERT INTO Conversation VALUES ('group',true,false,CURRENT_TIMESTAMP),('channel',true,true,CURRENT_TIMESTAMP),('dm',false,false,CURRENT_TIMESTAMP)",
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
