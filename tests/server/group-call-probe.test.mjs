import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'
import { readGroupCallProbe } from '../../src/lib/group-call-access.ts'

const member = { id: 'peer', name: 'Peer', color: 'emerald', avatar: null }
const state = { conversationId: 'room', callId: 'call', kind: 'voice', startedAt: 1000, members: [member] }

test('relay roster parser requires a bound complete state, distinguishes absent calls and strips extra fields', () => {
  assert.deepEqual(readGroupCallProbe({ ok: true, state: null }, 'room'), { callId: '', kind: 'voice', startedAt: null, members: [] })
  const parsed = readGroupCallProbe({ ok: true, state: { ...state, private: 'hidden', members: [{ ...member, socketId: 'secret' }] } }, 'room')
  assert.deepEqual(parsed, { callId: 'call', kind: 'voice', startedAt: 1000, members: [member] })
  for (const raw of [null, {}, [], { ok: true }, { ok: false, state: null },
    ...[{ conversationId: 'other' }, { callId: '' }, { kind: 'invalid' }, { startedAt: null },
      { members: null }, { members: [member, member] }, { members: [{ ...member, id: '' }] }, { members: [null] }]
      .map(patch => ({ ok: true, state: { ...state, ...patch } }))]) {
    assert.equal(readGroupCallProbe(raw, 'room'), null, JSON.stringify(raw))
  }
})

test('public probe checks membership before and after relay IO; never converts outage or malformed data into no-call', async () => {
  const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/group-call-state/route.ts', import.meta.url), 'utf8'))
    .replace(/^import .*$/gm, '').replace(/export /g, '')
  let allowed = true, revokedDuringRequest = false, token = null, denied = null, relay = { ok: true, state }, status = 200, fetches = 0
  const env = { CRON_SECRET: 'key' }
  const deps = { NextResponse: { json: Response.json }, db: {}, process: { env }, readGroupCallProbe,
    bearerToken: () => token, requireActionOwner: async () => denied,
    canJoinConversationCall: async (_, room, user) => { assert.equal(room, 'room'); assert.equal(user, 'me'); return allowed },
    fetch: async (url, options) => {
      fetches++; assert.equal(options.headers['x-pulse-key'], 'key'); assert.equal(options.cache, 'no-store')
      assert.equal(new URL(url).searchParams.get('conversationId'), 'room')
      if (revokedDuringRequest) allowed = false
      if (relay === 'throw') throw Error('offline')
      return Response.json(relay, { status })
    } }
  const get = new Function(...Object.keys(deps), source + ';return GET')(...Object.values(deps))
  const request = (query = 'conversationId=room&userId=me') => get(new Request(`https://pulse.test/api/group-call-state?${query}`))
  for (const query of ['', 'conversationId=room', 'userId=me', `conversationId=room&userId=${'x'.repeat(65)}`]) assert.equal((await request(query)).status, 400)
  allowed = false; assert.equal((await request()).status, 403)
  allowed = true; token = 'present'; denied = Response.json({ error: 'wrong owner' }, { status: 401 })
  assert.equal((await request()).status, 401); assert.equal(fetches, 0)
  token = null; denied = null
  let response = await request()
  assert.equal(response.headers.get('cache-control'), 'private, no-store')
  assert.deepEqual(await response.json(), { callStateVersion: 1, conversationId: 'room', userId: 'me', callId: 'call', kind: 'voice', startedAt: 1000, members: [member] })
  revokedDuringRequest = true; assert.equal((await request()).status, 403)
  revokedDuringRequest = false; allowed = true
  for (relay of ['throw', null, {}, { ok: true }, { ok: true, state: { ...state, conversationId: 'other' } }]) {
    response = await request(); assert.equal(response.status, 503); assert.equal((await response.json()).members, undefined)
  }
  relay = { ok: true, state: null }; status = 503; assert.equal((await request()).status, 503)
  status = 200; assert.deepEqual((await (await request()).json()).members, [])
  env.CRON_SECRET = ''; assert.equal((await request()).status, 503)
})

test('actual web probe ignores unrelated, incomplete and superseded responses instead of changing the Join banner', async () => {
  const file = await readFile(new URL('../../src/components/chat/group-call-overlay.tsx', import.meta.url), 'utf8')
  const start = file.indexOf('    const probe = async () => {')
  const source = stripTypeScriptTypes(file.slice(start, file.indexOf('    void probe()', start)))
  for (const mode of ['valid', 'empty', 'legacy', 'room', 'owner', 'version', 'missing-members', 'missing-call', 'kind', 'started', 'identity-switch', 'new-attempt', 'refused']) {
    const updates = []
    const body = { callStateVersion: 1, conversationId: 'room', userId: 'me', ...state }
    if (mode === 'empty') Object.assign(body, { members: [], callId: '', startedAt: null })
    if (mode === 'room') body.conversationId = 'other'
    if (mode === 'owner') body.userId = 'other'
    if (mode === 'version') body.callStateVersion = 0
    if (mode === 'legacy') delete body.callStateVersion
    if (mode === 'missing-members') delete body.members
    if (mode === 'missing-call') delete body.callId
    if (mode === 'kind') body.kind = 'unknown'
    if (mode === 'started') delete body.startedAt
    const refs = { stateRef: { current: 'idle' }, joinedConvRef: { current: null }, joinGenerationRef: { current: 'attempt' },
      optionsRef: { current: { meId: 'me' } }, ongoingTargetRef: { current: 'old' } }
    const deps = { ...refs, cancelled: false, conversationId: 'room', meId: 'me',
      setOngoingElsewhere: value => updates.push(value), setOngoingMembers: () => {}, setOngoingKind: () => {},
      fetch: async url => {
        assert.equal(new URL(url, 'https://pulse.test').searchParams.get('userId'), 'me')
        if (mode === 'identity-switch') refs.optionsRef.current.meId = 'new-owner'
        if (mode === 'new-attempt') refs.joinGenerationRef.current = 'new-attempt'
        return Response.json(body, { status: mode === 'refused' ? 403 : 200 })
      } }
    const probe = new Function(...Object.keys(deps), source + ';return probe')(...Object.values(deps))
    await probe()
    assert.deepEqual(updates, mode === 'valid' ? [true] : mode === 'empty' ? [false] : [], mode)
    if (mode === 'empty') assert.equal(refs.ongoingTargetRef.current, null)
  }
})
