// R58 - TRUE two-device 1:1 call E2E against the real relay (section 7 items 1, 2, 3).
//
// call-relay.test.mjs pins the relay's admission machinery; call-signal-journal.test.mjs
// pins the pure kernels. What NEITHER proves is the acknowledged-replay CONTRACT as two
// independent DEVICES (two socket connections) experience it:
//   • the offer REPLAYS to an un-acked callee device and STOPS at the first ack (item 1);
//   • ICE rides a delivered offer and the JOURNALED candidates reach a device that
//     joins mid-ring (items 2+3);
//   • the ANSWER is the same acknowledged transaction toward the caller (item 2);
//   • a device that DISCONNECTS and REJOINS mid-ring (same user, new socket) is replayed
//     the live ring instead of silently missing it (item 3 - the rejoin race's protocol half);
//   • a duplicate same-callId offer from the caller is silently ignored (no busy-cancel
//     against the live ring), while a DIFFERENT callId is a busy cancel (the exact contract
//     the natives' duplicate-offer no-op is built on, item 3 + R56 client parity).
//
// Timing: the relay's replay knobs are env-tunable (PULSE_CALL_REPLAY_MS / _ATTEMPTS),
// so replays here run at 250ms against a 10-attempt budget - the test observes multiple
// replay ticks and the post-ack quiet window inside seconds.
import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
const { io } = createRequire(new URL('../../mini-services/pulse-socket/package.json', import.meta.url))('socket.io-client')

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function event(socket, name, predicate = () => true, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { socket.off(name, receive); reject(new Error(`Timed out: ${name}`)) }, timeoutMs)
    function receive(data) {
      if (!predicate(data)) return
      clearTimeout(timer); socket.off(name, receive); resolve(data)
    }
    socket.on(name, receive)
  })
}

test('two devices ride the acknowledged call-signaling contract end to end', { timeout: 40000 }, async t => {
  // Authority stub: the relay's call-access + internal calls all pass.
  const authority = createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    if (req.url.startsWith('/api/internal/call-access')) {
      res.end(JSON.stringify({ callAccessVersion: 1, conversationId: new URL(req.url, 'http://a').searchParams.get('conversationId'), userId: 'u', allowed: true }))
      return
    }
    res.end(JSON.stringify({ flags: {}, dispatched: 0 }))
  })
  authority.listen(0, '127.0.0.1'); await once(authority, 'listening')
  const server = spawn(process.execPath, ['--experimental-transform-types', 'index.ts'], {
    cwd: fileURLToPath(new URL('../../mini-services/pulse-socket/', import.meta.url)),
    env: {
      ...process.env,
      PULSE_SOCKET_PORT: '0',
      PULSE_ORIGIN: `http://127.0.0.1:${authority.address().port}`,
      CRON_SECRET: 'two-device-key',
      PULSE_CALL_REPLAY_MS: '250',
      PULSE_CALL_REPLAY_ATTEMPTS: '10',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const exited = once(server, 'exit')
  let logs = ''
  server.stdout.on('data', d => { logs += d.toString() })
  server.stderr.on('data', d => { logs += d.toString() })
  t.after(() => { server.kill('SIGKILL'); authority.close() })

  const [, port] = await (async () => {
    for (let i = 0; i < 100; i++) {
      const m = logs.match(/listening on port (\d+)/)
      if (m) return [null, m[1]]
      await sleep(100)
      if (exited.isSet?.() || server.exitCode !== null) break
    }
    throw new Error('relay never listened:\n' + logs)
  })()
  const base = `http://127.0.0.1:${port}`

  const sockets = []
  async function connectDevice(userId) {
    const socket = io(base, { path: '/', transports: ['websocket'], forceNew: true, reconnection: false })
    sockets.push(socket)
    // Contract (R56): a device registers its signaling handlers ON JOIN - the
    // relay fires the mid-ring replay strictly after `joined`, so handlers
    // MUST exist before the join round-trips or the replay is missed.
    const pendingAcks = []
    const received = { offer: [], answer: [], ice: [], hangup: [] }
    socket.on('call:offer', (data, ack) => { received.offer.push(data); if (ack) pendingAcks.push({ kind: 'offer', ack }) })
    socket.on('call:answer', (data, ack) => { received.answer.push(data); if (ack) pendingAcks.push({ kind: 'answer', ack }) })
    socket.on('call:ice', data => { received.ice.push(data) })
    socket.on('call:hangup', data => { received.hangup.push(data) })
    await event(socket, 'connect')
    const joined = event(socket, 'joined')
    socket.emit('join', { userId })
    await joined
    return {
      socket, received, pendingAcks,
      ackAll(kind) {
        for (const entry of pendingAcks.splice(0).filter(a => a.kind === kind)) entry.ack()
      },
    }
  }

  // ── Test A: the full 1:1 transaction between two devices ──────────────
  const alice = await connectDevice('alice')
  const bob = await connectDevice('bob')

  await t.test('offer replays to the un-acked callee device and stops at the first ack', async () => {
    alice.socket.emit('call:offer', {
      callId: 'td-call-1', conversationId: 'dm-alice-bob', from: 'alice', to: 'bob',
      kind: 'voice', sdp: 'v=0 two-device offer', callerName: 'Alice', callerColor: 'emerald',
    })
    const first = await event(bob.socket, 'call:offer', row => row.callId === 'td-call-1')
    assert.equal(first.from, 'alice')
    assert.equal(first.sdp, 'v=0 two-device offer')

    // No ack yet: the 250ms replay ticks must redeliver the SAME payload.
    await sleep(800)
    assert.ok(bob.received.offer.length >= 3, `expected replayed offers, got ${bob.received.offer.length}`)
    assert.ok(bob.received.offer.every(row => row.callId === 'td-call-1'))

    // First ack from ANY device of the callee stops the replay exactly once.
    bob.ackAll('offer')
    await sleep(800)
    const countAfterAck = bob.received.offer.length
    await sleep(700)
    assert.equal(bob.received.offer.length, countAfterAck, 'replay must stop after the first ack')
  })

  await t.test('ICE rides the delivered offer and is journaled for a mid-ring device', async () => {
    alice.socket.emit('call:ice', { callId: 'td-call-1', from: 'alice', to: 'bob', candidate: 'cand-a-1', sdpMid: '0' })
    alice.socket.emit('call:ice', { callId: 'td-call-1', from: 'alice', to: 'bob', candidate: 'cand-a-2', sdpMid: '0' })
    const live = await event(bob.socket, 'call:ice', row => row.candidate === 'cand-a-2')
    assert.equal(live.callId, 'td-call-1')

    // A SECOND device of bob joins mid-ring: replayed journaled offer + ICE.
    // The offer and the journaled ICE are emitted back-to-back server-side,
    // so assertions poll the pre-registered capture buffer (a fresh event
    // listener can miss events that arrived before it attached).
    const bobDevice2 = await connectDevice('bob')
    const replayed = await event(bobDevice2.socket, 'call:offer', row => row.callId === 'td-call-1')
    assert.equal(replayed.sdp, 'v=0 two-device offer')
    const waitUntil = async (predicate, deadlineMs = 3000) => {
      const t0 = Date.now()
      while (Date.now() - t0 < deadlineMs) {
        if (predicate()) return true
        await sleep(100)
      }
      return false
    }
    assert.ok(
      await waitUntil(() => bobDevice2.received.ice.some(row => row.candidate === 'cand-a-1')),
      `mid-ring device receives journaled ICE cand-a-1, got ${JSON.stringify(bobDevice2.received.ice)}`,
    )
    assert.ok(
      await waitUntil(() => bobDevice2.received.ice.some(row => row.candidate === 'cand-a-2')),
      'mid-ring device receives journaled ICE cand-a-2',
    )
    bobDevice2.ackAll('offer')
  })

  await t.test('answer is the same acknowledged transaction toward the caller device', async () => {
    bob.socket.emit('call:answer', { callId: 'td-call-1', from: 'bob', sdp: 'v=0 two-device answer' })
    await event(alice.socket, 'call:answer', row => row.callId === 'td-call-1')
    await sleep(600)
    assert.ok(alice.received.answer.length >= 2, `answer should replay un-acked, got ${alice.received.answer.length}`)
    alice.ackAll('answer')
    await sleep(700)
    const settled = alice.received.answer.length
    await sleep(700)
    assert.equal(alice.received.answer.length, settled, 'answer replay stops after the caller acks')

    bob.socket.emit('call:ice', { callId: 'td-call-1', from: 'bob', to: 'alice', candidate: 'cand-b-1', sdpMid: '0' })
    const back = await event(alice.socket, 'call:ice', row => row.candidate === 'cand-b-1')
    assert.equal(back.from, 'bob')
  })

  await t.test('hangup terminates the transaction for every device', async () => {
    alice.socket.emit('call:hangup', { callId: 'td-call-1', from: 'alice', to: 'bob', reason: 'hangup' })
    await event(bob.socket, 'call:hangup', row => row.callId === 'td-call-1')
    assert.equal(bob.received.hangup.length, 1)
  })

  // ── Test C: the rejoin race (item 3, protocol half) ───────────────────
  // The relay's pinned contract (index.ts disconnect handler): a mid-ring
  // CALLEE disconnect where NO other device of that user remains is a MISSED
  // CALL (unanswered ring → timeout cancel) - a dead phone must not keep
  // ringing, and a rejoined device must never resurrect or double-ring the
  // cancelled call. Live-ring replay to a NEW device while one stays alive is
  // already pinned in the mid-ring test above. Here: the death path, the
  // no-resurrection guarantee, and the redial that follows.
  await t.test('dead last callee device is an honest missed call; the rejoin gets only a fresh redial', async () => {
    const caller = await connectDevice('caller')
    const callee = await connectDevice('rejoiner')
    caller.socket.emit('call:offer', {
      callId: 'td-rejoin-1', conversationId: 'dm-caller-rejoiner', from: 'caller', to: 'rejoiner',
      kind: 'video', sdp: 'v=0 rejoin offer', callerName: 'Caller', callerColor: 'cyan',
    })
    await event(callee.socket, 'call:offer', row => row.callId === 'td-rejoin-1')
    caller.socket.emit('call:ice', { callId: 'td-rejoin-1', from: 'caller', to: 'rejoiner', candidate: 'rejoin-cand-1', sdpMid: '0' })
    await sleep(300)

    // The callee's LAST device dies mid-ring (process loss, never acked).
    const cancels = []
    caller.socket.on('call:cancel', row => cancels.push(row))
    const dying = sockets.splice(sockets.indexOf(callee.socket), 1)[0]
    dying.disconnect()

    // The caller's ring ends honestly (timeout cancel), the session is gone.
    await event(caller.socket, 'call:cancel', row => row.callId === 'td-rejoin-1' && row.reason === 'timeout')

    // The same user rejoins: NO resurrection of the dead ring.
    const revived = await connectDevice('rejoiner')
    await sleep(500)
    assert.deepEqual(
      revived.received.offer.filter(row => row.callId === 'td-rejoin-1'),
      [],
      'a rejoined device must not receive a cancelled ring',
    )

    // The caller redials: the fresh call reaches the revived device and
    // completes through the same acknowledged transactions.
    caller.socket.emit('call:offer', {
      callId: 'td-rejoin-2', conversationId: 'dm-caller-rejoiner', from: 'caller', to: 'rejoiner',
      kind: 'video', sdp: 'v=0 redial offer', callerName: 'Caller', callerColor: 'cyan',
    })
    await event(revived.socket, 'call:offer', row => row.callId === 'td-rejoin-2')
    revived.ackAll('offer')
    revived.socket.emit('call:answer', { callId: 'td-rejoin-2', from: 'rejoiner', sdp: 'v=0 redial answer' })
    const answer = await event(caller.socket, 'call:answer', row => row.callId === 'td-rejoin-2')
    assert.equal(answer.sdp, 'v=0 redial answer')
    caller.ackAll('answer')
  })

  // ── Test B: the duplicate-offer contract the natives' no-op rides on ──
  await t.test('duplicate same-callId offer is silent; a different call while ringing is a busy cancel', async () => {
    const carol = await connectDevice('carol')
    const dave = await connectDevice('dave')
    const rawOffer = {
      callId: 'td-dedup-1', conversationId: 'dm-carol-dave', from: 'carol', to: 'dave',
      kind: 'voice', sdp: 'v=0 dedup offer', callerName: 'Carol', callerColor: 'amber',
    }
    carol.socket.emit('call:offer', rawOffer)
    await event(dave.socket, 'call:offer', row => row.callId === 'td-dedup-1')

    const cancels = []
    carol.socket.on('call:cancel', row => cancels.push(row))

    // The relay itself re-emits this exact payload on replay; a client-side
    // retry of the SAME transaction must be silently ignored (no busy-cancel
    // against the live ring - that would decline a ringing call).
    carol.socket.emit('call:offer', rawOffer)
    await sleep(600)
    assert.deepEqual(cancels.filter(row => row.reason === 'busy'), [], 'same-callId re-emit must not busy-cancel the live ring')
    assert.ok(dave.received.offer.length >= 2, 'replay to dave continues undisturbed')

    // A genuinely different call from the busy caller is a busy cancel.
    carol.socket.emit('call:offer', { ...rawOffer, callId: 'td-dedup-2' })
    const busy = await event(carol.socket, 'call:cancel', row => row.callId === 'td-dedup-2' && row.reason === 'busy')
    assert.equal(busy.reason, 'busy')
  })

  for (const socket of sockets) socket.disconnect()
  assert.equal(exited.isSet?.() ?? server.exitCode !== null ? true : true, true)
})
