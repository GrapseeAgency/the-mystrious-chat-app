/**
 * Wave 5 runtime E2E — voice / stage / space flows against the LIVE
 * pulse-socket relay (:3003), the same service the native clients use.
 * Node socket.io-client; mirrors the acceptance ledger.
 */
const { io } = require('socket.io-client')

const URL = 'http://127.0.0.1:3003'
let pass = 0, fail = 0
const ok = (name, cond) => { if (cond) { pass++; console.log('PASS —', name) } else { fail++; console.log('FAIL —', name) } }
const b64Chunk = (n) => Buffer.alloc(4000 * 2).fill(0x11).subarray(0, Math.min(n, 96000)).toString('base64')

const mk = () => io(URL, { path: '/socket.io', transports: ['websocket'], reconnection: false, timeout: 4000 })
const user = (id) => ({ id, name: 'E2E ' + id, username: id, color: 'emerald' })

async function main() {
  const a = mk(), b = mk(), c = mk()
  await Promise.all([a, b, c].map((s) => new Promise((res, rej) => { s.on('connect', res); s.on('connect_error', rej) })))
  console.log('3 clients connected')

  // ── VOICE ──────────────────────────────────────────────
  const rosterA = new Promise((r) => a.once('voice:roster', r))
  a.emit('voice:join', { conversationId: 'w5e2e', user: user('va') })
  const r1 = await rosterA
  ok('voice:join → voice:roster (self seen)', r1.conversationId === 'w5e2e' && r1.peers.length === 1 && r1.peers[0].id === 'va')

  const rosterB2 = new Promise((r) => a.once('voice:roster', r))
  b.emit('voice:join', { conversationId: 'w5e2e', user: user('vb') })
  const r2 = await rosterB2
  ok('second join → 2-peer roster broadcast', r2.peers.length === 2)

  const pttEcho = new Promise((r) => a.once('voice:ptt', r))
  a.emit('voice:ptt', { conversationId: 'w5e2e', userId: 'va', on: true })
  const p1 = await pttEcho
  ok('voice:ptt echoed to sender (self glow)', p1.userId === 'va' && p1.on === true)

  const chunkAtB = new Promise((r) => b.once('voice:chunk', r))
  let chunkAtA = false
  a.once('voice:chunk', () => { chunkAtA = true })
  a.emit('voice:chunk', { conversationId: 'w5e2e', userId: 'va', seq: 1, data: b64Chunk(8000) })
  const ch = await chunkAtB
  ok('voice:chunk reaches peer B, NOT sender', ch.userId === 'va' && ch.seq === 1 && !chunkAtA && ch.data.length > 1000)

  // identity gate: B cannot spoof A's chunk
  let spoofed = false
  c.on('voice:chunk', () => { spoofed = true })
  b.emit('voice:chunk', { conversationId: 'w5e2e', userId: 'va', seq: 2, data: b64Chunk(2000) })
  await new Promise((r) => setTimeout(r, 300))
  ok('identity gate blocks spoofed chunk', !spoofed)

  const transcriptAtB = new Promise((r) => b.once('voice:transcript', r))
  a.emit('voice:transcript', { conversationId: 'w5e2e', userId: 'va', text: 'hello stage' })
  const tr = await transcriptAtB
  ok('voice:transcript relayed w/ server-stamped name', tr.text === 'hello stage' && tr.name === 'E2E va' && !!tr.at)

  const pttOff = new Promise((r) => b.once('voice:ptt', r))
  a.emit('voice:leave', { conversationId: 'w5e2e' })
  const po = await pttOff
  ok('voice:leave forces ptt off + prunes', po.on === false)

  // ── STAGE ──────────────────────────────────────────────
  const stA = new Promise((r) => a.once('stage:state', r))
  a.emit('stage:join', { conversationId: 'w5e2e', user: user('va'), asHost: false })
  const s1 = await stA
  ok('first joiner becomes HOST (server truth)', s1.host?.id === 'va' && s1.speakers.some((p) => p.id === 'va'))

  const stB = new Promise((r) => a.once('stage:state', r))
  b.emit('stage:join', { conversationId: 'w5e2e', user: user('vb'), asHost: false })
  const s2 = await stB
  ok('second joiner is listener', s2.listeners.some((p) => p.id === 'vb') && s2.listenerCount === 1)

  const stHand = new Promise((r) => a.once('stage:state', r))
  b.emit('stage:hand', { conversationId: 'w5e2e', user: { id: 'vb' }, raised: true })
  const s3 = await stHand
  ok('raise hand → FIFO queue', s3.hands.length === 1 && s3.hands[0].id === 'vb')

  // listener cannot approve (host-only) — must NOT promote
  b.emit('stage:approve', { conversationId: 'w5e2e', byUserId: 'vb', targetUserId: 'vb' })
  await new Promise((r) => setTimeout(r, 300))
  const stQ = new Promise((r) => a.once('stage:state', r))
  a.emit('stage:approve', { conversationId: 'w5e2e', byUserId: 'va', targetUserId: 'vb' })
  const s4 = await stQ
  ok('host-only approve promotes hand→speaker', s4.speakers.some((p) => p.id === 'vb') && s4.hands.length === 0)

  // host mute demotes speaker → real voice-seat enforcement. C observes the
  // enforcement roster broadcast from the voice room (A already left; the
  // demoted B is the seat being removed). B also gets the forced ptt-off.
  c.emit('voice:join', { conversationId: 'w5e2e', user: user('vc') })
  await new Promise((r) => setTimeout(r, 250))
  const muteState = new Promise((r) => a.once('stage:state', r))
  const voiceGone = new Promise((r) => c.once('voice:roster', (p) => r(p)))
  const pttOffB = new Promise((r) => b.once('voice:ptt', (p) => r(p)))
  a.emit('stage:mute', { conversationId: 'w5e2e', byUserId: 'va', targetUserId: 'vb' })
  const s5 = await muteState
  const vr = await voiceGone
  const pb = await pttOffB
  ok('stage:mute demotes speaker → listener', s5.listeners.some((p) => p.id === 'vb'))
  ok('enforcement removes the voice seat (roster broadcast)', vr.peers.every((p) => p.id !== 'vb'))
  ok('forced ptt-off reaches the demoted speaker', pb.userId === 'vb' && pb.on === false)

  const endedAtB = new Promise((r) => b.once('stage:ended', r))
  a.emit('stage:end', { conversationId: 'w5e2e', byUserId: 'va' })
  const se = await endedAtB
  ok('stage:end → stage:ended to everyone', se.conversationId === 'w5e2e')

  // ── SPACE ──────────────────────────────────────────────
  const spA = new Promise((r) => a.once('space:state', r))
  a.emit('space:join', { conversationId: 'w5e2e', user: user('va') })
  const sp1 = await spA
  ok('space:join → state @ 0.5/0.5', sp1.players.length === 1 && sp1.players[0].x === 0.5 && sp1.players[0].y === 0.5)

  b.emit('space:join', { conversationId: 'w5e2e', user: user('vb') })
  await new Promise((r) => setTimeout(r, 400))

  // out-of-range → clamped into 0..1 (server clamp01). Register first, emit second.
  let res1; const mv1p = new Promise((r) => { res1 = r }); b.once('space:state', res1)
  a.emit('space:move', { conversationId: 'w5e2e', x: -0.7, y: 1.9 })
  const c1 = (await mv1p).players.find((p) => p.id === 'va')
  ok('out-of-range move clamped to the 0..1 box', c1 && c1.x === 0 && c1.y === 1)

  // 40 ms later → inside the 80 ms server window → swallowed, no broadcast
  await new Promise((r) => setTimeout(r, 40))
  let swallowed = true
  const spy = () => { swallowed = false }
  b.on('space:state', spy)
  a.emit('space:move', { conversationId: 'w5e2e', x: 0.9, y: 0.9 })
  await new Promise((r) => setTimeout(r, 180))
  b.off('space:state', spy)
  ok('inside-80ms move swallowed by the server throttle', swallowed)

  // past the window → accepted
  let res3; const mv3p = new Promise((r) => { res3 = r }); b.once('space:state', res3)
  a.emit('space:move', { conversationId: 'w5e2e', x: 0.25, y: 0.75 })
  const c3 = (await mv3p).players.find((p) => p.id === 'va')
  ok('move accepted after the throttle window', c3 && c3.x === 0.25 && c3.y === 0.75)

  let resGone; const spGone = new Promise((r) => { resGone = r }); b.once('space:state', resGone)
  a.emit('space:leave', { conversationId: 'w5e2e' })
  const sp3 = await spGone
  ok('space:leave prunes + rebroadcasts', sp3.players.length === 1)

  ;[a, b, c].forEach((s) => s.disconnect())
  console.log(`\nE2E RESULT: ${pass} PASS / ${fail} FAIL`)
  process.exit(fail === 0 ? 0 : 1)
}

main().catch((e) => { console.error('E2E crashed:', e.message); process.exit(1) })
