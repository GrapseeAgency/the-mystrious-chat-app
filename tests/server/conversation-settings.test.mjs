import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'
import * as metadata from '../../src/lib/conversation-metadata.ts'

async function action(route, body, { denied = null, stillMember = true } = {}) {
  const source = await readFile(new URL(`../../src/app/api/conversations/[id]/${route}/route.ts`, import.meta.url), 'utf8')
  const javascript = stripTypeScriptTypes(source.replace(/^import[\s\S]*?\bfrom ['"][^'"]+['"][ \t]*;?\r?\n/gm, '').replace(/^export /gm, ''), { mode: 'transform' })
  const writes = [], notices = []; let serialized = 0
  const deps = {
    ...metadata, NextResponse: Response, CONVERSATION_FULL_INCLUDE: {},
    safeJson: req => req.json(), strField: value => typeof value === 'string' ? value.trim() : '',
    bearerToken: () => 'supplied', requireActionOwner: async (_, actor) => { assert.equal(actor, 'viewer'); return denied },
    patchConversationMetadata: async (_, room, actor, patch) => {
      assert.equal(room, 'room'); assert.equal(actor, 'viewer'); writes.push(patch); return ['viewer', 'other']
    },
    patchMyScreenPrivacy: async (_, room, actor, on) => {
      assert.equal(room, 'room'); assert.equal(actor, 'viewer'); writes.push({ on })
    },
    notifySocket: async (_, recipients, payload) => { assert.deepEqual(recipients, payload.recipientIds); notices.push(recipients) },
    buildConversationDetail: async (_, actor) => { serialized++; assert.equal(actor, 'viewer'); return { id: 'room', ttlSeconds: 86400 } },
    db: { $transaction: async (work, options) => {
      assert.equal(options.isolationLevel, 'Serializable')
      return work({ conversation: { findFirst: async query => {
        assert.deepEqual(query.where, { id: 'room', participants: { some: { userId: 'viewer' } } })
        return stillMember ? { id: 'room' } : null
      } } })
    } },
  }
  const PATCH = new Function(...Object.keys(deps), javascript + '\nreturn PATCH;')(...Object.values(deps))
  const response = await PATCH(new Request(`https://pulse.test/api/conversations/room/${route}`, {
    method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: 'viewer', ...body }),
  }), { params: Promise.resolve({ id: 'room' }) })
  return { response, writes, notices, serialized }
}

test('all settings routes bind supplied credentials before mutations', async () => {
  for (const [route, body] of [['disappearing', { ttlSeconds: 86400 }], ['slow-mode', { seconds: 5 }], ['screen-privacy', { on: true }]]) {
    const result = await action(route, body, { denied: Response.json({ error: 'Wrong owner' }, { status: 403 }) })
    assert.equal(result.response.status, 403)
    assert.deepEqual(result.writes, [])
    assert.deepEqual(result.notices, [])
  }
})
test('invalid TTL, throttle and privacy inputs cannot produce settings writes', async () => {
  for (const [route, body] of [['disappearing', { ttlSeconds: '86400' }], ['disappearing', {}], ['slow-mode', { seconds: 3 }], ['screen-privacy', { on: 'false' }]]) {
    const result = await action(route, body)
    assert.equal(result.response.status, 400)
    assert.deepEqual(result.writes, [])
  }
})
test('personal privacy notifies only its owner; slow mode notifies current room recipients', async () => {
  const personal = await action('screen-privacy', { on: false })
  assert.equal(personal.response.status, 200)
  assert.deepEqual(await personal.response.json(), { ok: true, screenPrivacy: false })
  assert.deepEqual(personal.writes, [{ on: false }])
  assert.deepEqual(personal.notices, [['viewer']])
  const throttle = await action('slow-mode', { seconds: 5 })
  assert.equal(throttle.response.status, 200)
  assert.deepEqual(await throttle.response.json(), { ok: true, slowModeSeconds: 5 })
  assert.deepEqual(throttle.writes, [{ slowModeSeconds: 5 }])
  assert.deepEqual(throttle.notices, [['viewer', 'other']])
})
test('TTL detail cannot disclose room data after the requester loses membership', async () => {
  const allowed = await action('disappearing', { ttlSeconds: 86400 })
  assert.equal(allowed.response.status, 200)
  assert.equal(allowed.serialized, 1)
  assert.deepEqual(allowed.writes, [{ ttlSeconds: 86400 }])
  const removed = await action('disappearing', { ttlSeconds: 86400 }, { stillMember: false })
  assert.equal(removed.response.status, 403)
  assert.equal(removed.serialized, 0)
})
test('settings contention is retryable, not an unchecked database error', async () => {
  const db = { $transaction: async () => { throw { code: 'P2034' } } }
  await assert.rejects(metadata.patchConversationMetadata(db, 'room', 'viewer', { ttlSeconds: 86400 }), { status: 409 })
  await assert.rejects(metadata.patchMyScreenPrivacy(db, 'room', 'viewer', true), { status: 409 })
})
