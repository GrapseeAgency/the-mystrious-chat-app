import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

test('internal call access refuses missing/wrong keys and returns only a versioned bound verdict', async () => {
  const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/internal/call-access/route.ts', import.meta.url), 'utf8'))
    .replace(/^import .*$/gm, '').replace(/export /g, '')
  let allowed = true
  const env = {}, calls = []
  const deps = { process: { env }, NextResponse: { json: Response.json }, db: {},
    canJoinConversationCall: async (_, room, user) => { calls.push({ room, user }); return allowed } }
  const get = new Function(...Object.keys(deps), source + ';return GET')(...Object.values(deps))
  const request = (query = 'conversationId=room&userId=reader', key = 'key') => get(new Request(`https://pulse.test/api/internal/call-access?${query}`, { headers: { 'x-pulse-key': key } }))
  assert.equal((await request()).status, 401)
  env.CRON_SECRET = 'key'
  assert.equal((await request(undefined, 'wrong')).status, 401)
  for (const query of ['', 'userId=reader', 'conversationId=room', `conversationId=room&userId=${'a'.repeat(65)}`]) assert.equal((await request(query)).status, 400)
  assert.equal(calls.length, 0)
  for (allowed of [true, false]) {
    assert.deepEqual(await (await request()).json(), { callAccessVersion: 1, conversationId: 'room', userId: 'reader', allowed })
  }
  assert.deepEqual(calls, [{ room: 'room', user: 'reader' }, { room: 'room', user: 'reader' }])
})

test('relay membership verdicts are fail-closed and cannot be borrowed from another room or user', async () => {
  const file = await readFile(new URL('../../mini-services/pulse-socket/index.ts', import.meta.url), 'utf8')
  const source = stripTypeScriptTypes(file.slice(file.indexOf('async function groupCallAccessAllowed('), file.indexOf('type PrivacyFlags =')))
  const valid = { callAccessVersion: 1, conversationId: 'room', userId: 'reader', allowed: true }
  const make = (fetch, key = 'key') => new Function('fetch', 'PRIVACY_KEY', 'NEXT_API_BASE', source + ';return groupCallAccessAllowed')(fetch, key, 'https://authority.test')
  const signal = new AbortController().signal
  for (const patch of [{}, { callAccessVersion: 0 }, { conversationId: 'other' }, { userId: 'other' }, { allowed: 'true' }, { allowed: false }]) {
    const check = make(async (url, options) => {
      assert.equal(new URL(url).searchParams.get('userId'), 'reader')
      assert.equal(options.headers['x-pulse-key'], 'key')
      return Response.json({ ...valid, ...patch })
    })
    assert.equal(await check('room', 'reader', signal), Object.keys(patch).length === 0)
  }
  for (const response of [null, [], {}, { allowed: true }]) assert.equal(await make(async () => Response.json(response))('room', 'reader', signal), false)
  assert.equal(await make(async () => { throw new Error('offline') })('room', 'reader', signal), false)
  assert.equal(await make(async () => new Response('', { status: 503 }))('room', 'reader', signal), false)
  const noFetch = async () => { assert.fail('must not request access') }
  assert.equal(await make(noFetch, '')('room', 'reader', signal), false)
  const cancelled = new AbortController(); cancelled.abort()
  assert.equal(await make(noFetch)('room', 'reader', cancelled.signal), false)
})

test('an old admission refusal cannot fall through to terminate the same call under a newer attempt', async () => {
  const file = await readFile(new URL('../../src/components/chat/group-call-overlay.tsx', import.meta.url), 'utf8')
  const start = file.indexOf('  const handleGroupCallEvent =')
  const source = stripTypeScriptTypes(file.slice(start, file.indexOf('  useEffect(', start)))
  for (const phase of ['joining', 'active']) {
    let releases = 0
    const refs = { stateRef: { current: phase }, optionsRef: { current: { meId: 'me' } },
      joinAttemptRef: { current: 'current-attempt' }, joinedConvRef: { current: 'room' },
      joinedCallIdRef: { current: phase === 'active' ? 'same-call' : null },
      expectedJoinRef: { current: phase === 'joining' ? { roomId: 'room', callId: 'same-call' } : null },
      ringRef: { current: null }, ongoingTargetRef: { current: null } }
    const noop = () => {}
    const deps = { ...refs, useCallback: fn => fn, applyRoster: noop, emitCallEvent: noop, ensurePeerConnection: noop,
      setStateBoth: state => { refs.stateRef.current = state }, setSummary: noop, setRing: noop,
      setOngoingElsewhere: noop, setOngoingMembers: noop,
      teardownMedia: () => { releases++; refs.expectedJoinRef.current = null; refs.joinedCallIdRef.current = null; refs.joinedConvRef.current = null } }
    const handle = new Function(...Object.keys(deps), source + ';return handleGroupCallEvent')(...Object.values(deps))
    const ended = { conversationId: 'room', callId: 'same-call' }
    handle('gcall:ended', { ...ended, joinAttemptId: 'old-attempt' })
    assert.equal(refs.stateRef.current, phase)
    assert.equal(releases, 0)
    handle('gcall:ended', phase === 'joining' ? { ...ended, joinAttemptId: 'current-attempt' } : ended)
    assert.equal(refs.stateRef.current, 'ended')
    assert.equal(releases, 1)
  }
})
