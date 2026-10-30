import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
const { io } = createRequire(new URL('../../mini-services/pulse-socket/package.json', import.meta.url))('socket.io-client')

function event(socket, name, predicate = () => true) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { socket.off(name, receive); reject(new Error(`Timed out: ${name}`)) }, 4000)
    function receive(data) {
      if (!predicate(data)) return
      clearTimeout(timer); socket.off(name, receive); resolve(data)
    }
    socket.on(name, receive)
  })
}

test('real call relay binds membership, signaling and disconnect to the admitted device', { timeout: 15000 }, async t => {
  const accessModes = new Map(), heldAccess = []
  const sendAccess = (res, room, user, allowed = true) => {
    if (!res.destroyed) { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ callAccessVersion: 1, conversationId: room, userId: user, allowed })) }
  }
  const authority = createServer((req, res) => {
    const url = new URL(req.url, 'http://authority.test')
    if (req.headers['x-pulse-key'] !== 'synthetic-call-key') { res.writeHead(401); res.end(); return }
    if (url.pathname === '/api/internal/call-access') {
      const room = url.searchParams.get('conversationId'), user = url.searchParams.get('userId')
      const mode = accessModes.get(`${room}:${user}`) ?? accessModes.get(room)
      if (mode === 'hold') heldAccess.push(() => sendAccess(res, room, user))
      else if (mode === 'error') { res.writeHead(503); res.end() }
      else sendAccess(res, room, user, mode === 'deny' ? false : mode === 'malformed' ? 'true' : true)
      authority.emit('access-request', { room, user })
      return
    }
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ flags: {}, dispatched: 0 }))
  })
  authority.listen(0, '127.0.0.1'); await once(authority, 'listening')
  const authorityURL = `http://127.0.0.1:${authority.address().port}`
  const server = spawn(process.execPath, ['--experimental-transform-types', 'index.ts'], {
    cwd: fileURLToPath(new URL('../../mini-services/pulse-socket/', import.meta.url)),
    env: { ...process.env, PULSE_SOCKET_PORT: '0', PULSE_ORIGIN: authorityURL, CRON_SECRET: 'synthetic-call-key' },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const exited = once(server, 'exit')
  let logs = ''
  const waiting = new Set()
  function consume(data) { logs += data.toString(); for (const notify of waiting) notify() }
  server.stdout.on('data', consume); server.stderr.on('data', consume)
  function logged(pattern) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { waiting.delete(check); reject(new Error(`Missing ${pattern}\n${logs}`)) }, 5000)
      function check() {
        const match = logs.match(pattern)
        if (match) { clearTimeout(timer); waiting.delete(check); resolve(match) }
      }
      waiting.add(check); check()
    })
  }
  const sockets = []
  try {
    const [, port] = await logged(/listening on port (\d+)/)
    const base = `http://127.0.0.1:${port}`
    const relayProbe = url => fetch(url, { headers: { 'x-pulse-key': 'synthetic-call-key' } })
    async function connect(id) {
      const socket = io(base, { path: '/', transports: ['websocket'], forceNew: true, reconnection: false })
      sockets.push(socket)
      await event(socket, 'connect')
      await barrier(socket, id)
      return socket
    }
    async function barrier(socket, id) {
      const ack = event(socket, 'joined')
      socket.emit('join', { userId: id })
      await ack
    }
    async function join(socket, id, room = 'room', joinAttemptId) {
      const state = event(socket, 'gcall:state', row => row.conversationId === room)
      socket.emit('gcall:join', { conversationId: room, kind: 'voice', user: { id, name: id }, ...(joinAttemptId === undefined ? {} : { joinAttemptId }) })
      return state
    }
    await t.test('raw roster probe refuses anonymous and incorrectly keyed requests', async () => {
      assert.equal((await fetch(`${base}/gcall?conversationId=room`)).status, 401)
      assert.equal((await fetch(`${base}/gcall?conversationId=room`, { headers: { 'x-pulse-key': 'wrong' } })).status, 401)
      const response = await relayProbe(`${base}/gcall?conversationId=room`)
      assert.equal(response.status, 200)
      assert.equal(response.headers.get('cache-control'), 'no-store')
      assert.equal((await response.json()).state, null)
    })
    const alice = await connect('alice'), idleAlice = await connect('alice')
    const bob = await connect('bob'), idleBob = await connect('bob')
    await t.test('internal event, typing and online endpoints refuse public requests without touching socket clients', async () => {
      const forged = []
      alice.on('message:deleted', value => forged.push(value))
      for (const key of [null, 'wrong']) {
        for (const endpoint of ['notify', 'typing', 'online']) {
          const response = await fetch(`${base}/${endpoint}`, {
            method: endpoint === 'online' ? 'GET' : 'POST',
            headers: { 'Content-Type': 'application/json', ...(key ? { 'x-pulse-key': key } : {}) },
            ...(endpoint === 'online' ? {} : { body: JSON.stringify({ event: 'message:deleted', recipients: ['alice'],
              payload: { id: 'forged' }, conversationId: 'room', userId: 'bob', isTyping: true }) }),
          })
          assert.equal(response.status, 401, endpoint)
        }
      }
      await barrier(alice, 'alice')
      assert.deepEqual(forged, [])
      const response = await fetch(`${base}/online`, { headers: { 'x-pulse-key': 'synthetic-call-key' } })
      assert.equal(response.status, 200)
      assert.ok(Array.isArray((await response.json()).onlineUserIds))
      const accepted = event(alice, 'message:deleted', value => value.id === 'authorized')
      assert.equal((await fetch(`${base}/notify`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'x-pulse-key': 'synthetic-call-key' },
        body: JSON.stringify({ event: 'message:deleted', recipients: ['alice'], payload: { id: 'authorized' } }) })).status, 200)
      await accepted
      assert.equal((await fetch(base)).status, 200)
    })
    await join(alice, 'alice')
    const state = await join(bob, 'bob')
    const callId = state.callId
    await t.test('ring admission requires the live call and member, is single-use, and ends idle invitations', async () => {
      const starter = await connect('starter'), invitee = await connect('invitee')
      const live = await join(starter, 'starter', 'ring-room')
      const raw = { conversationId: 'ring-room', callId: live.callId, kind: 'voice', caller: { id: 'starter', name: 'Starter' }, title: 'Room' }
      const send = async payload => fetch(`${base}/notify`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'x-pulse-key': 'synthetic-call-key' },
        body: JSON.stringify({ event: 'gcall:ring', recipients: ['invitee', 'invitee', 'starter'], payload }) })
      for (const patch of [{ callId: '' }, { callId: 'old' }, { conversationId: 'missing' }, { kind: 'video' }, { caller: { id: 'outsider' } }]) {
        assert.equal((await send({ ...raw, ...patch })).status, 409)
      }
      const heard = []
      invitee.on('gcall:ring', row => heard.push(row))
      const incoming = event(invitee, 'gcall:ring')
      const receipt = await (await send(raw)).json()
      const ring = await incoming
      assert.equal(receipt.accepted, true)
      assert.equal(receipt.ringReceiptVersion, 1)
      assert.equal(receipt.delivered, 1)
      assert.equal(ring.callId, live.callId)
      assert.equal(Date.parse(ring.expiresAt), live.startedAt + 45_000)
      assert.equal(receipt.expiresAt, ring.expiresAt)
      const repeat = await (await send(raw)).json()
      assert.equal(repeat.accepted, false)
      assert.equal(repeat.delivered, 0)
      await barrier(invitee, 'invitee')
      assert.equal(heard.length, 1)
      const ended = event(invitee, 'gcall:ended', row => row.callId === live.callId)
      starter.emit('gcall:leave', { conversationId: 'ring-room', from: 'starter' })
      await ended
      assert.equal((await send(raw)).status, 409)
      const replacement = await join(starter, 'starter', 'ring-room')
      assert.notEqual(replacement.callId, live.callId)
      assert.equal((await send(raw)).status, 409)
      starter.disconnect(); invitee.disconnect()
    })

    await t.test('invitation joins cannot create replacements, evict another device, or leave a current call on refusal', async () => {
      const host = await connect('bound-host'), otherHostDevice = await connect('bound-host'), joiner = await connect('bound-joiner')
      const original = await join(host, 'bound-host', 'bound-room')
      const existing = await join(joiner, 'bound-joiner', 'own-room')
      const joinRequest = (id, expectedCallId, room = 'bound-room') => ({ conversationId: room, expectedCallId, kind: 'voice', user: { id, name: id } })
      const read = async room => (await (await relayProbe(`${base}/gcall?conversationId=${room}`)).json()).state
      for (const expected of ['', 'old-call']) {
        const refusal = event(joiner, 'gcall:ended', row => row.callId === expected)
        joiner.emit('gcall:join', joinRequest('bound-joiner', expected))
        assert.equal((await refusal).reason, 'unavailable')
        assert.equal((await read('own-room')).callId, existing.callId)
        assert.deepEqual((await read('bound-room')).members.map(row => row.id), ['bound-host'])
      }
      const hostEnds = []; host.on('gcall:ended', row => hostEnds.push(row))
      const refusal = event(otherHostDevice, 'gcall:ended', row => row.callId === original.callId)
      otherHostDevice.emit('gcall:join', joinRequest('bound-host', original.callId))
      await refusal; await barrier(host, 'bound-host')
      assert.equal(hostEnds.length, 0)
      const admitted = event(joiner, 'gcall:state', row => row.callId === original.callId)
      joiner.emit('gcall:join', joinRequest('bound-joiner', original.callId))
      assert.equal((await admitted).members.length, 2)
      assert.equal(await read('own-room'), null)
      host.emit('gcall:leave', { conversationId: 'bound-room', from: 'bound-host' })
      await barrier(host, 'bound-host')
      joiner.emit('gcall:leave', { conversationId: 'bound-room', from: 'bound-joiner' })
      await barrier(joiner, 'bound-joiner')
      const missing = event(joiner, 'gcall:ended', row => row.reason === 'unavailable')
      joiner.emit('gcall:join', joinRequest('bound-joiner', original.callId))
      await missing; assert.equal(await read('bound-room'), null)
      const replacement = await join(host, 'bound-host', 'bound-room')
      const stale = event(joiner, 'gcall:ended', row => row.reason === 'unavailable')
      joiner.emit('gcall:join', joinRequest('bound-joiner', original.callId))
      await stale
      assert.equal((await read('bound-room')).callId, replacement.callId)
      assert.deepEqual((await read('bound-room')).members.map(row => row.id), ['bound-host'])
      host.disconnect(); otherHostDevice.disconnect(); joiner.disconnect()
    })

    await t.test('leave and pre-ack cancellation belong to one join attempt, not the room', async () => {
      const owner = await connect('intent-owner'), peer = await connect('intent-peer')
      const first = await join(owner, 'intent-owner', 'intent-room', 'attempt-A')
      const second = await join(owner, 'intent-owner', 'intent-room', 'attempt-B')
      assert.equal(first.callId, second.callId)
      const leave = patch => owner.emit('gcall:leave', { conversationId: 'intent-room', from: 'intent-owner', ...patch })
      const read = async () => (await (await relayProbe(`${base}/gcall?conversationId=intent-room`)).json()).state
      for (const patch of [{}, { joinAttemptId: 'attempt-A' }, { callId: first.callId, joinAttemptId: 'attempt-A' },
        { callId: 'different-call', joinAttemptId: 'attempt-B' }]) {
        leave(patch); await barrier(owner, 'intent-owner')
        assert.deepEqual((await read()).members.map(row => row.id), ['intent-owner'])
      }
      // A legacy replay must not downgrade the modern member's leave fence.
      owner.emit('gcall:join', { conversationId: 'intent-room', kind: 'voice', user: { id: 'intent-owner' } })
      leave({}); await barrier(owner, 'intent-owner')
      assert.equal((await read()).callId, first.callId)
      // Refused leaves must not even unsubscribe the admitted socket from its roster.
      const stillSubscribed = event(owner, 'gcall:state', row => row.members.length === 2)
      await join(peer, 'intent-peer', 'intent-room')
      await stillSubscribed
      leave({ joinAttemptId: 'attempt-B', callId: second.callId })
      await barrier(owner, 'intent-owner')
      assert.deepEqual((await read()).members.map(row => row.id), ['intent-peer'])
      peer.emit('gcall:leave', { conversationId: 'intent-room', from: 'intent-peer' })
      await barrier(peer, 'intent-peer')
      const replacement = await join(owner, 'intent-owner', 'intent-room', 'attempt-C')
      assert.notEqual(replacement.callId, first.callId)
      leave({ callId: first.callId, joinAttemptId: 'attempt-B' })
      await barrier(owner, 'intent-owner')
      assert.equal((await read()).callId, replacement.callId)
      leave({ joinAttemptId: 'attempt-C' }); await barrier(owner, 'intent-owner')
      assert.equal(await read(), null)
      // Cancel immediately after sending JOIN, before reading its roster/call ID.
      owner.emit('gcall:join', { conversationId: 'intent-room', kind: 'voice', user: { id: 'intent-owner' }, joinAttemptId: 'attempt-D' })
      leave({ joinAttemptId: 'attempt-D' })
      await barrier(owner, 'intent-owner')
      assert.equal(await read(), null)
      owner.disconnect(); peer.disconnect()
    })

    await t.test('current membership is required and stale permission results cannot admit or evict', async () => {
      const owner = await connect('access-owner')
      const current = await join(owner, 'access-owner', 'access-current', 'current-intent')
      const read = async room => (await (await relayProbe(`${base}/gcall?conversationId=${room}`)).json()).state
      const request = (room, attempt) => owner.emit('gcall:join', { conversationId: room, kind: 'voice',
        user: { id: 'access-owner' }, joinAttemptId: attempt })
      for (const mode of ['deny', 'malformed', 'error']) {
        accessModes.set('access-refused', mode)
        const refused = event(owner, 'gcall:ended', row => row.joinAttemptId === mode)
        request('access-refused', mode)
        assert.equal((await refused).reason, 'access-unavailable')
        assert.equal(await read('access-refused'), null)
        assert.equal((await read('access-current')).callId, current.callId)
      }
      accessModes.set('access-held', 'hold')
      let reached = once(authority, 'access-request')
      request('access-held', 'cancelled')
      assert.equal((await reached)[0].room, 'access-held')
      owner.emit('gcall:leave', { conversationId: 'access-held', from: 'access-owner', joinAttemptId: 'cancelled' })
      await barrier(owner, 'access-owner')
      heldAccess.shift()()
      assert.equal(await read('access-held'), null)
      assert.equal((await read('access-current')).callId, current.callId)
      reached = once(authority, 'access-request')
      request('access-held', 'superseded')
      await reached
      const latest = await join(owner, 'access-owner', 'access-latest', 'latest')
      heldAccess.shift()()
      assert.equal(await read('access-held'), null)
      assert.equal((await read('access-latest')).callId, latest.callId)
      // A successful prior admission is never reused as cached permission.
      accessModes.set('access-latest', 'deny')
      const deniedAgain = event(owner, 'gcall:ended', row => row.joinAttemptId === 'retry')
      request('access-latest', 'retry'); await deniedAgain
      assert.equal((await read('access-latest')).callId, latest.callId)
      reached = once(authority, 'access-request')
      request('access-held', 'new-pending')
      await reached
      owner.emit('gcall:leave', { conversationId: 'access-held', from: 'access-owner', joinAttemptId: 'cancelled' })
      await barrier(owner, 'access-owner')
      const permitted = event(owner, 'gcall:state', row => row.conversationId === 'access-held')
      heldAccess.shift()()
      await permitted
      assert.deepEqual((await read('access-held')).members.map(row => row.id), ['access-owner'])
      const switching = await connect('switch-old')
      accessModes.set('identity-held', 'hold')
      reached = once(authority, 'access-request')
      switching.emit('gcall:join', { conversationId: 'identity-held', kind: 'voice', user: { id: 'switch-old' }, joinAttemptId: 'switch' })
      await reached
      await barrier(switching, 'switch-new')
      heldAccess.shift()()
      assert.equal(await read('identity-held'), null)
      switching.disconnect()
      reached = once(authority, 'access-request')
      request('access-held', 'disconnect')
      await reached
      const id = owner.id; owner.disconnect()
      await logged(new RegExp(`disconnect sock=${id} reason=`))
      heldAccess.shift()()
      assert.equal(await read('access-held'), null)
      assert.equal(await read('access-latest'), null)
    })

    await t.test('conversation changes revoke only the removed live member and notify surviving peers', async () => {
      const host = await connect('lease-host'), removed = await connect('lease-removed')
      const initial = await join(host, 'lease-host', 'lease-room', 'host-attempt')
      await join(removed, 'lease-removed', 'lease-room', 'removed-attempt')
      accessModes.set('lease-room:lease-removed', 'deny')
      const kicked = event(removed, 'gcall:ended', row => row.callId === initial.callId && row.reason === 'membership-revoked')
      const roster = event(host, 'gcall:state', row => row.callId === initial.callId && row.members.length === 1)
      await fetch(`${base}/notify`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'x-pulse-key': 'synthetic-call-key' },
        body: JSON.stringify({ event: 'conversation:updated', recipients: ['lease-host', 'lease-removed'], payload: { conversationId: 'lease-room' } }) })
      await kicked
      assert.deepEqual((await roster).members.map(row => row.id), ['lease-host'])
      const offers = []; host.on('gcall:offer', row => offers.push(row))
      removed.emit('gcall:offer', { conversationId: 'lease-room', callId: initial.callId, from: 'lease-removed', to: 'lease-host', kind: 'voice', sdp: 'after-removal' })
      await barrier(removed, 'lease-removed')
      assert.equal(offers.length, 0)
      const stillLive = await (await relayProbe(`${base}/gcall?conversationId=lease-room`)).json()
      assert.equal(stillLive.state.callId, initial.callId)
      assert.deepEqual(stillLive.state.members.map(row => row.id), ['lease-host'])
      // Removing the final conversation member also ends the relay call.
      accessModes.set('lease-room:lease-host', 'deny')
      const ended = event(host, 'gcall:ended', row => row.callId === initial.callId)
      await fetch(`${base}/notify`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'x-pulse-key': 'synthetic-call-key' },
        body: JSON.stringify({ event: 'conversation:updated', recipients: ['lease-host'], payload: { conversationId: 'lease-room' } }) })
      await ended
      assert.equal((await (await relayProbe(`${base}/gcall?conversationId=lease-room`)).json()).state, null)
      host.disconnect(); removed.disconnect()
    })

    let activeAlice = alice
    const received = [], idleReceived = []
    bob.on('gcall:offer', row => received.push(row.sdp))
    idleBob.on('gcall:offer', row => idleReceived.push(row.sdp))
    const offer = (sdp, id = callId) => ({ conversationId: 'room', callId: id, from: 'alice', to: 'bob', kind: 'voice', sdp })

    await t.test('unjoined same-account device cannot leave or inject, and SDP targets only the admitted recipient', async () => {
      idleAlice.emit('gcall:leave', { conversationId: 'room', from: 'alice' })
      idleAlice.emit('gcall:offer', offer('unjoined-injection'))
      await barrier(idleAlice, 'alice')
      alice.emit('gcall:offer', offer('old-call-injection', 'old-call'))
      const delivered = event(bob, 'gcall:offer', row => row.sdp === 'current-offer')
      alice.emit('gcall:offer', offer('current-offer'))
      await delivered
      await barrier(idleBob, 'bob')
      assert.deepEqual(received, ['current-offer'])
      assert.deepEqual(idleReceived, [])
    })

    await t.test('closing an unrelated signed-in device cannot remove the active member', async () => {
      const id = idleAlice.id
      idleAlice.disconnect()
      await logged(new RegExp(`disconnect sock=${id} reason=`))
      const roster = await join(alice, 'alice')
      assert.deepEqual(roster.members.map(row => row.id).sort(), ['alice', 'bob'])
      assert.equal(roster.callId, callId)
    })

    await t.test('explicit device takeover publishes departure then arrival and fences old callbacks', async () => {
      const replacement = await connect('alice')
      activeAlice = replacement
      const rosters = []
      bob.on('gcall:state', row => rosters.push(row.members.map(member => member.id).sort()))
      const oldEnded = event(alice, 'gcall:ended', row => row.reason === 'replaced')
      assert.equal((await join(replacement, 'alice')).callId, callId)
      await oldEnded
      await barrier(bob, 'bob')
      assert.deepEqual(rosters.slice(0, 2), [['bob'], ['alice', 'bob']])
      alice.emit('gcall:leave', { conversationId: 'room', from: 'alice' })
      alice.emit('gcall:offer', offer('old-device-injection'))
      await barrier(alice, 'alice')
      const delivered = event(bob, 'gcall:offer', row => row.sdp === 'replacement-offer')
      replacement.emit('gcall:offer', offer('replacement-offer'))
      await delivered
      assert.deepEqual(received, ['current-offer', 'replacement-offer'])
      const oldSocketId = alice.id
      alice.disconnect()
      await logged(new RegExp(`disconnect sock=${oldSocketId} reason=`))
      assert.equal((await join(replacement, 'alice')).members.length, 2)
      const ended = event(bob, 'gcall:ended')
      bob.emit('gcall:leave', { conversationId: 'room', from: 'bob' })
      await ended
      assert.equal((await join(replacement, 'alice')).members.length, 1)
    })

    await t.test('a full target room cannot evict the existing call on another device', async () => {
      for (let index = 0; index < 8; index++) {
        const id = `full-${index}`
        const client = await connect(id)
        await join(client, id, 'full-room')
      }
      const nextAlice = await connect('alice')
      const full = event(nextAlice, 'gcall:full')
      nextAlice.emit('gcall:join', { conversationId: 'full-room', kind: 'voice', user: { id: 'alice', name: 'Alice' } })
      assert.equal((await full).max, 8)
      const current = await join(activeAlice, 'alice')
      assert.equal(current.callId, callId)
      assert.deepEqual(current.members.map(row => row.id), ['alice'])
    })

    await t.test('direct call ignores unrelated disconnects, duplicate offers and a second answering device', async () => {
      const caller = await connect('direct-caller'), idleCaller = await connect('direct-caller')
      const callee = await connect('direct-callee'), otherCallee = await connect('direct-callee')
      const directId = 'direct-current'
      const request = { callId: directId, conversationId: 'dm', from: 'direct-caller', to: 'direct-callee', kind: 'voice', sdp: 'offer' }
      const cancels = [], answers = [], callerHangups = []
      caller.on('call:cancel', row => cancels.push(row))
      caller.on('call:answer', row => answers.push(row.sdp))
      caller.on('call:hangup', row => callerHangups.push(row))
      const firstRing = event(callee, 'call:offer'), secondRing = event(otherCallee, 'call:offer')
      caller.emit('call:offer', request)
      await Promise.all([firstRing, secondRing])
      caller.emit('call:offer', request) // retransmission must not cancel its own ring as busy
      const unrelatedId = idleCaller.id
      idleCaller.disconnect()
      await logged(new RegExp(`disconnect sock=${unrelatedId} reason=`))
      await barrier(caller, 'direct-caller')
      assert.deepEqual(cancels, [])
      const accepted = event(caller, 'call:answer')
      const otherStopped = event(otherCallee, 'call:cancel', row => row.answeredElsewhere === true)
      callee.emit('call:answer', { callId: directId, from: 'direct-callee', sdp: 'chosen-answer' })
      assert.equal((await accepted).sdp, 'chosen-answer')
      await otherStopped
      const receivedIce = []
      caller.on('call:ice', row => receivedIce.push(row.candidate))
      otherCallee.emit('call:answer', { callId: directId, from: 'direct-callee', sdp: 'losing-answer' })
      otherCallee.emit('call:reject', { callId: directId, from: 'direct-callee' })
      otherCallee.emit('call:hangup', { callId: directId, from: 'direct-callee' })
      otherCallee.emit('call:ice', { callId: directId, from: 'direct-callee', candidate: 'wrong-device' })
      await barrier(otherCallee, 'direct-callee')
      const secondCaller = await connect('direct-caller')
      const busy = event(secondCaller, 'call:cancel')
      secondCaller.emit('call:offer', { ...request, callId: 'new-attempt-from-another-device' })
      assert.equal((await busy).reason, 'busy')
      const liveIce = event(caller, 'call:ice')
      callee.emit('call:ice', { callId: directId, from: 'direct-callee', candidate: 'chosen-device' })
      assert.equal((await liveIce).candidate, 'chosen-device')
      assert.deepEqual(answers, ['chosen-answer'])
      assert.deepEqual(callerHangups, [])
      assert.deepEqual(receivedIce, ['chosen-device'])
      const otherId = otherCallee.id
      otherCallee.disconnect()
      await logged(new RegExp(`disconnect sock=${otherId} reason=`))
      // The actual media owner's disconnect must notify the survivor immediately,
      // not silently delete the session using the unanswered 'timeout' path.
      const ended = event(caller, 'call:hangup')
      callee.disconnect()
      const termination = await ended
      assert.equal(termination.callId, directId)
      assert.equal(termination.to, 'direct-caller')
      assert.ok(termination.durationSec >= 0)
      assert.equal(callerHangups.length, 1)
    })
  } finally {
    for (const socket of sockets) socket.disconnect()
    server.kill('SIGTERM')
    await exited
    authority.closeAllConnections()
    await new Promise(resolve => authority.close(resolve))
  }
})

test('R56 acknowledged call signaling: un-acked transactions replay, acks stop them, late callee devices get offer+ICE', { timeout: 25000 }, async t => {
  const server = spawn(process.execPath, ['--experimental-transform-types', 'index.ts'], {
    cwd: fileURLToPath(new URL('../../mini-services/pulse-socket/', import.meta.url)),
    env: { ...process.env, PULSE_SOCKET_PORT: '0', CRON_SECRET: 'synthetic-call-key', PULSE_CALL_REPLAY_MS: '250' },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const exited = once(server, 'exit')
  let logs = ''
  const waiting = new Set()
  function consume(data) { logs += data.toString(); for (const notify of waiting) notify() }
  server.stdout.on('data', consume); server.stderr.on('data', consume)
  function logged(pattern) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { waiting.delete(check); reject(new Error(`Missing ${pattern}\n${logs}`)) }, 6000)
      function check() {
        const match = logs.match(pattern)
        if (match) { clearTimeout(timer); waiting.delete(check); resolve(match) }
      }
      waiting.add(check); check()
    })
  }
  const sockets = []
  try {
    const [, port, node] = await logged(/listening on port (\d+) node=([\w-]+)/)
    assert.ok(node && node.startsWith('node-'), 'ring receipts and logs carry a node identity (item 5)')
    const base = `http://127.0.0.1:${port}`
    async function connect(id) {
      const socket = io(base, { path: '/', transports: ['websocket'], forceNew: true, reconnection: false })
      sockets.push(socket)
      await event(socket, 'connect')
      const ack = event(socket, 'joined')
      socket.emit('join', { userId: id })
      await ack
      return socket
    }
    const caller = await connect('r56-caller')
    // The FIRST callee device never acks anything (old-build behavior): the
    // relay must keep replaying the offer until SOME device acknowledges.
    const silentCallee = await connect('r56-callee')
    const request = { callId: 'r56-live', conversationId: 'r56-dm', from: 'r56-caller', to: 'r56-callee', kind: 'voice', sdp: 'offer-sdp' }

    await t.test('an un-acked offer is replayed on the bounded interval', async () => {
      const first = event(silentCallee, 'call:offer', row => row.callId === 'r56-live')
      caller.emit('call:offer', request)
      const seen = await first
      assert.equal(seen.sdp, 'offer-sdp')
      await logged(/\[call\] offer replay call=r56-live attempt=2/)
    })

    await t.test('an ack from any callee device stops the replay and late joins get offer + journaled ICE', async () => {
      // Caller ICE before the late device exists — must be journaled for replay.
      const silentIce = event(silentCallee, 'call:ice', row => row.candidate === 'early-caller-ice')
      caller.emit('call:ice', { callId: 'r56-live', from: 'r56-caller', candidate: 'early-caller-ice' })
      await silentIce
      // A NEW device joins mid-ring: it now receives the journaled offer AND
      // the caller's early ICE (the old relay silently missed this).
      const lateDevice = io(base, { path: '/', transports: ['websocket'], forceNew: true, reconnection: false })
      sockets.push(lateDevice)
      await event(lateDevice, 'connect')
      const joinedAck = event(lateDevice, 'joined')
      lateDevice.emit('join', { userId: 'r56-callee' })
      await joinedAck
      const lateOffer = event(lateDevice, 'call:offer', row => row.callId === 'r56-live')
      const lateIce = event(lateDevice, 'call:ice', row => row.candidate === 'early-caller-ice')
      assert.equal((await lateOffer).sdp, 'offer-sdp')
      await lateIce
      await logged(/\[call\] offer re-delivered on join call=r56-live/)
      // Ack via the socket.io ack channel: the replay budget stops.
      lateDevice.off('call:offer')
      let lateAcks = 0
      lateDevice.on('call:offer', (_payload, ack) => { lateAcks++; if (ack) ack() })
      await logged(/\[call\] offer ACK call=r56-live/)
      const countAtStop = lateAcks
      await new Promise(resolve => setTimeout(resolve, 700))
      assert.ok(lateAcks - countAtStop <= 1, `replay stops after ack (saw ${lateAcks - countAtStop} extra)`)
    })

    await t.test('the answer is also a journaled acknowledged transaction', async () => {
      const received = event(caller, 'call:answer', row => row.callId === 'r56-live')
      silentCallee.emit('call:answer', { callId: 'r56-live', from: 'r56-callee', sdp: 'answer-sdp' })
      assert.equal((await received).sdp, 'answer-sdp')
      // Caller never acks → bounded answer replay runs until budget end.
      await logged(/\[call\] answer replay call=r56-live attempt=2/)
      // Now ack from the caller side; replay must stop.
      caller.off('call:answer')
      let callerAcks = 0
      caller.on('call:answer', (_payload, ack) => { callerAcks++; if (ack) ack() })
      await logged(/\[call\] answer ACK call=r56-live/)
      const countAtStop = callerAcks
      await new Promise(resolve => setTimeout(resolve, 700))
      assert.ok(callerAcks - countAtStop <= 1, `answer replay stops after ack (saw ${callerAcks - countAtStop} extra)`)
    })
  } finally {
    for (const socket of sockets) socket.disconnect()
    server.kill('SIGTERM')
    await exited
  }
})
