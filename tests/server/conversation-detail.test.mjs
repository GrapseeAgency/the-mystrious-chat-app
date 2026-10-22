import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

async function handler({ member = false, token = null, denied = null } = {}) {
  const source = await readFile(new URL('../../src/app/api/conversations/[id]/route.ts', import.meta.url), 'utf8')
  const javascript = stripTypeScriptTypes(source.replace(/^import[\s\S]*?\bfrom ['"][^'"]+['"][ \t]*;?\r?\n/gm, '').replace(/^export /gm, ''), { mode: 'transform' })
  let serialized = 0, queried = 0
  const deps = {
    NextResponse: Response, CONVERSATION_FULL_INCLUDE: {}, GROUP_NAME_MAX: 48,
    bearerToken: () => token, requireActionOwner: async (_, owner) => { assert.equal(owner, 'viewer'); return denied },
    buildConversationDetail: async (_, owner) => { serialized++; assert.equal(owner, 'viewer'); return { id: 'room', myDraft: 'private draft', lastMessage: 'private body' } },
    db: { $transaction: async (work, options) => {
      assert.equal(options.isolationLevel, 'Serializable')
      return work({ conversation: { findFirst: async query => {
        queried++
        assert.deepEqual(query.where, { id: 'room', participants: { some: { userId: 'viewer' } } })
        return member ? { id: 'room' } : null
      } } })
    } },
  }
  const { GET } = new Function(...Object.keys(deps), javascript + '\nreturn { GET };')(...Object.values(deps))
  const response = await GET(new Request('https://pulse.test/api/conversations/room?userId=viewer'), { params: Promise.resolve({ id: 'room' }) })
  return { response, serialized, queried }
}

test('nonmembers cannot obtain conversation detail or trigger private-field serialization', async () => {
  const result = await handler()
  assert.equal(result.response.status, 404)
  assert.equal(result.serialized, 0)
  assert.equal(result.queried, 1)
  const body = await result.response.json()
  assert.equal(body.conversation, undefined)
})
test('a current member receives only their viewer projection from the guarded read', async () => {
  const result = await handler({ member: true })
  assert.equal(result.response.status, 200)
  assert.equal(result.serialized, 1)
  assert.equal((await result.response.json()).conversation.myDraft, 'private draft')
})
test('a supplied foreign credential is rejected before any conversation query', async () => {
  const result = await handler({ member: true, token: 'different-owner', denied: Response.json({ error: 'Wrong owner' }, { status: 403 }) })
  assert.equal(result.response.status, 403)
  assert.equal(result.serialized, 0)
  assert.equal(result.queried, 0)
})

async function patchHandler(body, { denied = null, stillMember = true } = {}) {
  const { conversationMetadataPatch, ConversationMetadataError } = await import('../../src/lib/conversation-metadata.ts')
  const source = await readFile(new URL('../../src/app/api/conversations/[id]/route.ts', import.meta.url), 'utf8')
  const javascript = stripTypeScriptTypes(source.replace(/^import[\s\S]*?\bfrom ['"][^'"]+['"][ \t]*;?\r?\n/gm, '').replace(/^export /gm, ''), { mode: 'transform' })
  const writes = []; let serialized = 0
  const deps = {
    NextResponse: Response, CONVERSATION_FULL_INCLUDE: {}, GROUP_NAME_MAX: 48,
    safeJson: req => req.json(), strField: value => typeof value === 'string' ? value.trim() : '',
    bearerToken: () => 'supplied', requireActionOwner: async (_, owner) => { assert.equal(owner, 'viewer'); return denied },
    conversationMetadataPatch, ConversationMetadataError,
    patchConversationMetadata: async (_, room, actor, patch) => {
      assert.equal(room, 'room'); assert.equal(actor, 'viewer'); writes.push(patch); return ['viewer', 'member']
    },
    notifySocket: async (_, recipients) => assert.deepEqual(recipients, ['viewer', 'member']),
    buildConversationDetail: async (_, owner) => { serialized++; assert.equal(owner, 'viewer'); return { id: 'room', myDraft: 'my private draft' } },
    db: { $transaction: async (work, options) => {
      assert.equal(options.isolationLevel, 'Serializable')
      return work({ conversation: { findFirst: async query => {
        assert.deepEqual(query.where, { id: 'room', participants: { some: { userId: 'viewer' } } })
        return stillMember ? { id: 'room' } : null
      } } })
    } },
  }
  const { PATCH } = new Function(...Object.keys(deps), javascript + '\nreturn { PATCH };')(...Object.values(deps))
  const response = await PATCH(new Request('https://pulse.test/api/conversations/room', {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ requesterId: 'viewer', ...body }),
  }), { params: Promise.resolve({ id: 'room' }) })
  return { response, writes, serialized }
}

test('actual metadata PATCH rejects malformed mixed fields before writing anything', async () => {
  for (const body of [{ name: 'New', photo: {} }, { name: 'New', broadcast: 'false' }, { name: 'New', screenPrivacy: null }]) {
    const result = await patchHandler(body)
    assert.equal(result.response.status, 400)
    assert.deepEqual(result.writes, [])
    assert.equal(result.serialized, 0)
  }
})
test('actual metadata PATCH rejects a supplied foreign credential before mutation', async () => {
  const result = await patchHandler({ name: 'New' }, { denied: Response.json({ error: 'Wrong owner' }, { status: 403 }) })
  assert.equal(result.response.status, 403)
  assert.deepEqual(result.writes, [])
})
test('metadata response is projected only for the requester who still belongs to the room', async () => {
  const result = await patchHandler({ name: ' New ', screenPrivacy: false })
  assert.equal(result.response.status, 200)
  assert.deepEqual(result.writes, [{ name: 'New', screenPrivacy: false }])
  assert.equal(result.serialized, 1)
  const removed = await patchHandler({ name: 'New' }, { stillMember: false })
  assert.equal(removed.writes.length, 1) // write committed; response must not disclose a now-inaccessible room
  assert.equal(removed.response.status, 403)
  assert.equal(removed.serialized, 0)
})
