import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { webcrypto } from 'node:crypto'
import { stripTypeScriptTypes } from 'node:module'
import { dispatchGroupCallRing, relayGroupCallRing, GroupCallRingError } from '../../src/lib/group-call-ringing.ts'

const now = new Date('2026-10-21T12:00:00Z')
const participant = (userId, mutedUntil = null) => ({ userId, mutedUntil })

test('muted group members receive neither live rings nor push, and expired mute resumes both', async () => {
  const deliveries = []
  const members = [participant('caller'), participant('active'), participant('muted', new Date(+now + 1)),
    participant('expired', new Date(+now)), participant('active')]
  const report = await dispatchGroupCallRing(members, 'caller', async recipients => {
    deliveries.push(['live', [...recipients]])
    recipients.push('callback-mutation')
  }, async recipients => {
    deliveries.push(['push', recipients])
    return { sent: 1 }
  }, now)
  assert.deepEqual(deliveries, [['live', ['active', 'expired']], ['push', ['active', 'expired']]])
  assert.deepEqual(report, { rang: 2, pushed: 1 })
})

test('self-only or fully muted rooms perform no delivery', async () => {
  for (const members of [[], [participant('caller')], [participant('muted', new Date(+now + 1000))]]) {
    const fail = async () => { assert.fail('must not deliver to ineligible recipients') }
    assert.deepEqual(await dispatchGroupCallRing(members, 'caller', fail, fail, now), { rang: 0, pushed: 0 })
  }
})

test('a duplicate relay admission skips push as well as live delivery', async () => {
  assert.deepEqual(await dispatchGroupCallRing([participant('peer')], 'caller', async () => false,
    async () => { assert.fail('duplicate must not push'); }), { rang: 0, pushed: 0 })
})

test('relay acknowledgement must bind call, caller, conversation, version and absolute deadline', async t => {
  const previous = process.env.CRON_SECRET
  t.after(() => { if (previous === undefined) delete process.env.CRON_SECRET; else process.env.CRON_SECRET = previous })
  delete process.env.CRON_SECRET
  await assert.rejects(relayGroupCallRing(['peer'], {}, async () => { assert.fail('missing key must not contact relay') }), { status: 503 })
  process.env.CRON_SECRET = 'synthetic-relay-key'
  const payload = { conversationId: 'room', callId: 'call', kind: 'voice', caller: { id: 'caller' }, title: 'Room' }
  const valid = { ok: true, ringReceiptVersion: 1, conversationId: 'room', callId: 'call', callerId: 'caller',
    accepted: true, expiresAt: new Date(Date.now() + 30_000).toISOString() }
  let calls = 0
  const send = body => async (url, options) => {
    calls++
    assert.equal(url, 'http://localhost:3003/notify')
    assert.equal(options.headers['x-pulse-key'], 'synthetic-relay-key')
    assert.deepEqual(JSON.parse(options.body), { event: 'gcall:ring', recipients: ['peer'], payload })
    return Response.json(body)
  }
  assert.deepEqual(await relayGroupCallRing(['peer'], payload, send(valid)), { accepted: true, expiresAt: valid.expiresAt })
  assert.equal((await relayGroupCallRing(['peer'], payload, send({ ...valid, accepted: false }))).accepted, false)
  assert.equal((await relayGroupCallRing(['peer'], payload, send({ ...valid, expiresAt: new Date(0).toISOString() }))).accepted, false)
  for (const patch of [{ ok: false }, { ringReceiptVersion: 0 }, { callId: 'old' }, { callerId: 'other' },
    { conversationId: 'other' }, { expiresAt: 'bad' }, { accepted: 'true' }]) {
    await assert.rejects(relayGroupCallRing(['peer'], payload, send({ ...valid, ...patch })), { status: 503 })
  }
  await assert.rejects(relayGroupCallRing(['peer'], payload, send({ ok: true })), { status: 503 })
  await assert.rejects(relayGroupCallRing(['peer'], payload, async () => new Response('', { status: 409 })), { status: 409 })
  await assert.rejects(relayGroupCallRing(['peer'], payload, async () => { throw new Error('offline') }), { status: 503 })
  assert.ok(calls > 0)
})

test('actual relay refuses the absolute deadline and never trusts a supplied expiry', async () => {
  const file = await readFile(new URL('../../mini-services/pulse-socket/index.ts', import.meta.url), 'utf8')
  const source = stripTypeScriptTypes(file.slice(file.indexOf('function liveGroupCallMember('), file.indexOf('/** Identity checks precede removal:')) +
    file.slice(file.indexOf('async function handleNotify('), file.indexOf('type TypingBody =')))
  let clock = 1_000
  const call = { callId: 'call', kind: 'voice', createdAt: clock, members: new Map([['caller', { accessValidUntil: 60_000 }]]) }
  const events = []
  const deps = {
    readJsonBody: async req => req.body, asTrimmedString: value => typeof value === 'string' ? value.trim() : '',
    NOTIFY_EVENTS: new Set(['gcall:ring']), sanitizeRecipients: value => [...new Set(value)],
    groupCalls: new Map([['room', call]]), roomOf: id => id,
    Date: class extends Date { static now() { return clock } },
    sendJson: (res, status, body) => { res.status = status; res.body = body },
    io: { to: recipient => ({ emit: (event, payload) => events.push({ recipient, event, payload }) }) },
  }
  const handle = new Function(...Object.keys(deps), source + ';return handleNotify')(...Object.values(deps))
  const body = { event: 'gcall:ring', recipients: ['peer'], payload: { conversationId: 'room', callId: 'call',
    kind: 'voice', caller: { id: 'caller' }, expiresAt: new Date(999_999).toISOString() } }
  const invoke = async () => { const res = {}; await handle({ body }, res); return res }
  clock = 46_000
  assert.equal((await invoke()).status, 409)
  assert.equal(events.length, 0)
  assert.equal(call.ringRecipients, undefined)
  clock--
  assert.equal((await invoke()).body.accepted, true)
  assert.equal(events[0].payload.expiresAt, new Date(46_000).toISOString())
  assert.equal((await invoke()).body.accepted, false)
  assert.equal(events.length, 1)
})

test('actual ring route authorizes ownership and membership before live admission and shares the deadline with push', async () => {
  const source = stripTypeScriptTypes(await readFile(new URL('../../src/app/api/conversations/[id]/calls/ring/route.ts', import.meta.url), 'utf8'))
    .replace(/^import .*$/gm, '').replace(/export /g, '')
  const expiresAt = new Date(Date.now() + 30_000).toISOString()
  let denied = false, member = true, accepted = true, refusal
  const admissions = [], pushes = []
  const deps = {
    NextResponse: { json: Response.json }, dispatchGroupCallRing, GroupCallRingError,
    db: { conversation: { findUnique: async () => ({ id: 'room', isGroup: true, name: 'Room', participants: [
      participant(member ? 'caller' : 'someone'), participant('peer'), participant('muted', new Date(Date.now() + 60_000)),
    ] }) }, user: { findUnique: async () => ({ id: 'caller', name: 'Caller', color: 'amber', avatar: null }) } },
    safeJson: async req => (await req.json()) ?? {}, strField: v => typeof v === 'string' ? v.trim() : '',
    bearerToken: () => 'token', requireActionOwner: async () => denied ? Response.json({}, { status: 403 }) : null,
    relayGroupCallRing: async (ids, payload) => { admissions.push({ ids, payload }); if (refusal) throw refusal; return { accepted, expiresAt } },
    fanoutPush: async (ids, payload) => { pushes.push({ ids, payload }); return { sent: 1 } },
  }
  const post = new Function(...Object.keys(deps), source + ';return POST')(...Object.values(deps))
  const invoke = body => post(new Request('https://pulse.test/api/conversations/room/calls/ring', {
    method: 'POST', body: JSON.stringify(body), headers: { 'Content-Type': 'application/json' },
  }), { params: Promise.resolve({ id: 'room' }) })
  const request = { userId: 'caller', callId: 'call', kind: 'voice' }
  for (const body of [null, {}, { ...request, callId: '' }, { ...request, kind: 'bad' }]) assert.equal((await invoke(body)).status, 400)
  denied = true; assert.equal((await invoke(request)).status, 403); denied = false
  member = false; assert.equal((await invoke(request)).status, 403); member = true
  assert.equal(admissions.length, 0)
  assert.deepEqual(await (await invoke(request)).json(), { ok: true, rang: 1, pushed: 1 })
  assert.deepEqual(admissions[0].ids, ['peer'])
  assert.deepEqual(pushes[0].ids, ['peer'])
  assert.equal(pushes[0].payload.expiresAt, expiresAt)
  assert.equal(pushes[0].payload.callId, 'call')
  accepted = false; await invoke(request)
  refusal = new GroupCallRingError(409, 'Ended'); assert.equal((await invoke(request)).status, 409)
  assert.equal(pushes.length, 1)
})

test('shared web join retains the expected call and releases permission results after cancellation or owner change', async () => {
  const file = await readFile(new URL('../../src/components/chat/group-call-overlay.tsx', import.meta.url), 'utf8')
  const source = stripTypeScriptTypes(file.slice(file.indexOf('  const acquireMedia ='), file.indexOf('  const teardownMedia =')) +
    file.slice(file.indexOf('  const startJoin ='), file.indexOf('  const startCall =')))
  for (const mode of ['valid', 'cancelled', 'owner-changed']) {
    let approve, stopped = 0
    const emitted = [], refs = {
      localStreamRef: { current: null }, kindRef: { current: 'voice' }, stateRef: { current: 'idle' },
      joinIntentRef: { current: false }, joinGenerationRef: { current: 0 }, joinAttemptRef: { current: null },
      optionsRef: { current: { meId: 'me', meName: 'Me', meColor: 'amber', meAvatar: null, conversationId: 'room' } },
      expectedJoinRef: { current: null }, ringRef: { current: null }, joinedConvRef: { current: null },
      pendingRingRef: { current: false }, joinedCallIdRef: { current: null },
    }
    const noop = () => {}
    const deps = { ...refs, crypto: webcrypto, useCallback: callback => callback, enabled: true, callChannelConnected: true,
      navigator: { mediaDevices: { getUserMedia: () => new Promise(resolve => { approve = resolve }) } },
      setLocalStream: noop, setCameraEnabled: noop, DOMException, toast: Object.assign(noop, { error: noop }),
      setKind: noop, haptic: noop, setError: noop, setSummary: noop, setRing: noop,
      setStateBoth: state => { refs.stateRef.current = state },
      emitCallEvent: (event, payload) => emitted.push({ event, payload }),
      teardownMedia: () => { refs.joinGenerationRef.current++; refs.expectedJoinRef.current = null },
    }
    const start = new Function(...Object.keys(deps), source + ';return startJoin')(...Object.values(deps))
    start('room', 'voice', false, 'observed-call')
    if (mode === 'cancelled') { deps.teardownMedia(); refs.stateRef.current = 'ended' }
    if (mode === 'owner-changed') refs.optionsRef.current.meId = 'another-owner'
    approve({ getTracks: () => [{ stop: () => { stopped++ } }] })
    await new Promise(resolve => setImmediate(resolve))
    if (mode === 'valid') {
      assert.equal(stopped, 0)
      assert.equal(emitted.length, 1)
      assert.equal(emitted[0].payload.expectedCallId, 'observed-call')
      assert.equal(emitted[0].payload.joinAttemptId, refs.joinAttemptRef.current)
      assert.ok(emitted[0].payload.joinAttemptId)
      assert.equal(emitted[0].payload.user.id, 'me')
    } else {
      assert.equal(stopped, 1)
      assert.equal(emitted.length, 0)
      assert.equal(refs.localStreamRef.current, null)
    }
  }
})
