import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
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
  const server = spawn(process.execPath, ['--experimental-transform-types', 'index.ts'], {
    cwd: fileURLToPath(new URL('../../mini-services/pulse-socket/', import.meta.url)),
    env: { ...process.env, PULSE_SOCKET_PORT: '0', PULSE_ORIGIN: 'http://127.0.0.1:1' },
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
    async function join(socket, id, room = 'room') {
      const state = event(socket, 'gcall:state', row => row.conversationId === room)
      socket.emit('gcall:join', { conversationId: room, kind: 'voice', user: { id, name: id } })
      return state
    }
    const alice = await connect('alice'), idleAlice = await connect('alice')
    const bob = await connect('bob'), idleBob = await connect('bob')
    await join(alice, 'alice')
    const state = await join(bob, 'bob')
    const callId = state.callId
    await t.test('ring admission requires the live call and member, is single-use, and ends idle invitations', async () => {
      const starter = await connect('starter'), invitee = await connect('invitee')
      const live = await join(starter, 'starter', 'ring-room')
      const raw = { conversationId: 'ring-room', callId: live.callId, kind: 'voice', caller: { id: 'starter', name: 'Starter' }, title: 'Room' }
      const send = async payload => fetch(`${base}/notify`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
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
  }
})
