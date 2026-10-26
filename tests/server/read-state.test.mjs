import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'
import { receiptRecipients, ReadReceiptError } from '../../src/lib/read-receipt.ts'

async function fixture() {
  const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/conversations/[id]/read-state/route.ts', import.meta.url), 'utf8'))
    .replace(/^import .*$/gm, '').replace(/export /g, '')
  const calls = [], fanouts = []
  let denied = false, privateReceipts = false, refusal
  const dependencies = {
    NextResponse: { json: Response.json },
    db: { user: { findUnique: async () => ({ preferences: privateReceipts ? '{"readReceipts":false}' : null }) } },
    requireActionOwner: async () => denied ? Response.json({}, { status: 403 }) : null,
    advanceReadReceipt: async (_, room, owner, anchor, intent) => {
      calls.push({ room, owner, anchor, intent }); if (refusal) throw refusal
      return new Date('2026-10-22T00:00:10Z')
    }, ReadReceiptError, receiptRecipients,
    memberIdsOf: async () => ['reader', 'peer'], notifySocket: async (...args) => fanouts.push(args),
    safeJson: req => req.json(), strField: value => typeof value === 'string' ? value.trim() : '',
  }
  const post = new Function(...Object.keys(dependencies), source + ';return POST')(...Object.values(dependencies))
  return { calls, fanouts, deny: () => { denied = true }, private: () => { privateReceipts = true },
    refuse: error => { refusal = error },
    request: body => post(new Request('https://read.test', { method: 'POST', body: JSON.stringify({ userId: 'reader', readIntent:'fence', ...body }) }), { params: Promise.resolve({ id: 'room' }) }) }
}
test('read-state requires explicit visible/all intent and cannot downgrade a missing anchor to mark-all', async () => {
  const f = await fixture()
  for (const body of [{ mode:'all',readIntent:null }, { mode:'all',readIntent:'' }, {}, { mode: 'visible' }, { mode: 'visible', messageId: null }, { mode: 'visible', messageId: ' ' },
    { mode: 'visible', messageId: 1 }, { mode: 'all', messageId: 'anchor' }, { mode: 'all', messageId: false }, { mode: 'other' }]) {
    assert.equal((await f.request(body)).status, 400)
  }
  assert.equal(f.calls.length, 0)
  f.deny(); assert.equal((await f.request({ mode: 'all' })).status, 403); assert.equal(f.calls.length, 0)
})
test('read receipt binds version, owner, room, intent, boundary and committed timestamp; private receipts only relay to reader', async () => {
  const f = await fixture()
  for (const mode of ['visible', 'all']) {
    const body = { mode, ...(mode === 'visible' ? { messageId: 'anchor' } : {}) }
    const response = await f.request(body)
    assert.deepEqual(await response.json(), { ok: true, readReceiptVersion: 2, readIntent: 'fence', userId: 'reader', conversationId: 'room',
      mode, messageId: mode === 'visible' ? 'anchor' : null, lastReadAt: '2026-10-22T00:00:10.000Z' })
    assert.deepEqual(f.calls.at(-1), { room: 'room', owner: 'reader', anchor: mode === 'visible' ? 'anchor' : undefined, intent:'fence' })
  }
  assert.deepEqual(f.fanouts[0][1], ['reader', 'peer'])
  f.private(); await f.request({ mode: 'visible', messageId: 'anchor' })
  assert.deepEqual(f.fanouts.at(-1)[1], ['reader'])
})
test('refused/unavailable read boundary cannot produce a success receipt or fanout', async () => {
  const f = await fixture(); f.refuse(new ReadReceiptError(404, 'unavailable'))
  assert.equal((await f.request({ mode: 'visible', messageId: 'anchor' })).status, 404)
  assert.equal(f.fanouts.length, 0)
})
test('opening primary Android chat is navigation only, not an eager mark-all action', async () => {
  const source = await readFile(new URL('../../apps/android/feature-chat/src/main/kotlin/app/pulse/feature/chat/ChatsScreen.kt', import.meta.url), 'utf8')
  const helper = source.slice(source.indexOf('private fun openConversation(')).split('\n}')[0]
  assert.ok(helper.includes('onOpenRoom(conv.id, null)'))
  assert.ok(!helper.includes('markRead'))
})

test('manual unread route validates desired state and returns only the committed bound receipt', async () => {
  const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/conversations/[id]/mark-unread/route.ts', import.meta.url), 'utf8'))
    .replace(/^import .*$/gm, '').replace(/export /g, '')
  let denied = false, refusal
  const calls = [], fanouts = []
  const receipt = { ok: true, unreadReceiptVersion: 2, readIntent: 'fence', conversationId: 'room', userId: 'reader', manualUnread: true }
  const dependencies = {
    NextResponse: { json: Response.json }, db: {}, ReadReceiptError,
    bearerToken: () => 'session', requireActionOwner: async () => denied ? Response.json({}, { status: 403 }) : null,
    setManualUnread: async (_, room, owner, on) => { calls.push({ room, owner, on }); if (refusal) throw refusal; return receipt },
    notifySocket: async (...args) => fanouts.push(args),
    safeJson: async req => { const body = await req.json(); return body && typeof body === 'object' && !Array.isArray(body) ? body : {} },
    strField: value => typeof value === 'string' ? value.trim() : '',
  }
  const patch = new Function(...Object.keys(dependencies), source + ';return PATCH')(...Object.values(dependencies))
  const request = body => patch(new Request('https://pulse.test/api/conversations/room/mark-unread', {
    method: 'PATCH', body: JSON.stringify(body && !Array.isArray(body) ? {readIntent:'fence',...body} : body), headers: { 'Content-Type': 'application/json' },
  }), { params: Promise.resolve({ id: 'room' }) })
  for (const body of [null, [], {}, { userId: 'reader' }, { userId: 'reader', on: 'true' }, { userId: 'reader', on: null }]) {
    assert.equal((await request(body)).status, 400)
  }
  assert.equal(calls.length, 0)
  denied = true
  assert.equal((await request({ userId: 'reader', on: true })).status, 403)
  assert.equal(calls.length, 0)
  denied = false
  const response = await request({ userId: 'reader', on: true })
  assert.equal(response.status, 200)
  assert.deepEqual(await response.json(), receipt)
  assert.deepEqual(calls, [{ room: 'room', owner: 'reader', on: true }])
  assert.deepEqual(fanouts, [['conversation:updated', ['reader'], { conversationId: 'room' }]])
  for (const status of [403, 404, 409]) {
    refusal = new ReadReceiptError(status, 'refused')
    assert.equal((await request({ userId: 'reader', on: false })).status, status)
  }
  assert.equal(fanouts.length, 1)
})
