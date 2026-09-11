/**
 * Wave 3 — local E2E NATIVE-CALL SIGNALLING gate through the REAL edge
 * gateway (:81, XTransformPort=3003) against the REAL pulse-socket service.
 *
 * Proves the complete call state machine wiring the native clients depend on
 * (offer → answer → ICE → connected → hangup + every failure path):
 *   1. offer relay (caller identity decoration intact)
 *   2. answer relay (SDP round trip)
 *   3. ICE relay (flat candidate triple, both directions)
 *   4. hangup relay (durationSec on the wire — NOT durationMs)
 *   5. reject  → caller gets call:reject, callee gets call:cancel 'cancel'
 *   6. caller cancel → both sides get call:cancel 'cancel'
 *   7. busy    → the second caller gets call:cancel 'busy'
 *   8. offline → caller gets call:cancel 'offline'
 *   9. identity gate → a spoofed `from` is dropped by the relay
 *  10. server ring timeout (30s) → BOTH sides get call:cancel 'timeout'
 *  11. disconnect teardown → survivor gets call:cancel 'timeout'
 *  12. REST /api/calls: single-writer POST (201) + viewer-relative GET rows
 *
 * Mirrors the exact connection path the native clients dial.
 */
const { io } = require('socket.io-client')

const EDGE = 'http://localhost:81'
const API = 'http://localhost:81/api'
const QUERY = { XTransformPort: '3003' }
const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms))
function once(socket, event, timeout = 8000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting ${event}`)), timeout)
    socket.once(event, (data) => { clearTimeout(t); resolve(data) })
  })
}
function onceMatching(socket, event, predicate, timeout = 8000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting ${event}`)), timeout)
    const handler = (data) => {
      if (predicate(data)) { clearTimeout(t); socket.off(event, handler); resolve(data) }
    }
    socket.on(event, handler)
  })
}
async function api(path, method = 'GET', body = null) {
  const res = await fetch(API + path, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await res.json().catch(() => null)
  return { status: res.status, json }
}

async function main() {
  const stamp = Date.now()

  // ── identities + DM ────────────────────────────────────────
  const ua = await api('/users', 'POST', { name: `W3A ${stamp}`, color: 'emerald' })
  const ub = await api('/users', 'POST', { name: `W3B ${stamp}`, color: 'rose' })
  const uc = await api('/users', 'POST', { name: `W3C ${stamp}`, color: 'amber' })
  const a = ua.json.user ?? ua.json
  const b = ub.json.user ?? ub.json
  const c = uc.json.user ?? uc.json
  const dmRes = await api('/conversations', 'POST', { creatorId: a.id, memberIds: [b.id], isGroup: false })
  const dm = dmRes.json.conversation ?? dmRes.json
  check('seed A/B/C + DM(A,B)', !!(a?.id && b?.id && c?.id && dm?.id), JSON.stringify({ a: ua.status, b: ub.status, c: uc.status, dm: dmRes.status }))

  const A = io(EDGE, { query: QUERY, transports: ['websocket'] })
  const B = io(EDGE, { query: QUERY, transports: ['websocket'] })
  const C = io(EDGE, { query: QUERY, transports: ['websocket'] })
  const joinedA = once(A, 'joined'); const joinedB = once(B, 'joined'); const joinedC = once(C, 'joined')
  A.on('connect', () => A.emit('join', { userId: a.id }))
  B.on('connect', () => B.emit('join', { userId: b.id }))
  C.on('connect', () => C.emit('join', { userId: c.id }))
  const [ja, jb, jc] = await Promise.all([joinedA, joinedB, joinedC])
  check('A/B/C joined', Array.isArray(ja.onlineUserIds) && Array.isArray(jb.onlineUserIds) && Array.isArray(jc.onlineUserIds))

  const base = () => ({ conversationId: dm.id, kind: 'voice' })

  // ── 1-4. happy path: offer → answer → ICE → hangup ────────
  const offerAtB = onceMatching(B, 'call:offer', (d) => d.callId === 'call-happy')
  A.emit('call:offer', { ...base(), callId: 'call-happy', from: a.id, to: b.id, sdp: 'offer-sdp', callerName: a.name, callerColor: 'emerald', callerAvatar: null })
  const offer = await offerAtB
  check('1. offer relayed to callee (identity intact)',
    offer.sdp === 'offer-sdp' && offer.from === a.id && offer.to === b.id && offer.callerName === a.name && offer.kind === 'voice')

  const answerAtA = onceMatching(A, 'call:answer', (d) => d.callId === 'call-happy')
  B.emit('call:answer', { ...base(), callId: 'call-happy', from: b.id, to: a.id, sdp: 'answer-sdp' })
  const answer = await answerAtA
  check('2. answer relayed to caller (SDP round trip)', answer.sdp === 'answer-sdp' && answer.from === b.id)

  const iceAtA = onceMatching(A, 'call:ice', (d) => d.candidate?.includes('typ host'))
  B.emit('call:ice', { ...base(), callId: 'call-happy', from: b.id, to: a.id, candidate: 'candidate:1 1 udp 2130706431 10.0.0.1 8998 typ host', sdpMid: '0', sdpMLineIndex: 0 })
  const ice = await iceAtA
  check('3. ICE relayed B→A (flat triple)', ice.sdpMid === '0' && ice.sdpMLineIndex === 0)

  const hangupAtB = onceMatching(B, 'call:hangup', (d) => d.callId === 'call-happy')
  A.emit('call:hangup', { ...base(), callId: 'call-happy', from: a.id, to: b.id, durationSec: 15 })
  const hangup = await hangupAtB
  // The relay computes its own elapsed durationSec (it does NOT echo the
  // caller's number) — the pinned wire fact is durationSec (never durationMs).
  check('4. hangup relayed with server-computed durationSec (wire ≠ contracts.ts durationMs)', Number.isFinite(hangup.durationSec))

  // ── 5. reject ──────────────────────────────────────────────
  const rejectAtA = onceMatching(A, 'call:reject', (d) => d.callId === 'call-reject')
  const cancelAfterRejectAtB = onceMatching(B, 'call:cancel', (d) => d.callId === 'call-reject')
  A.emit('call:offer', { ...base(), callId: 'call-reject', from: a.id, to: b.id, sdp: 'o', callerName: a.name })
  await onceMatching(B, 'call:offer', (d) => d.callId === 'call-reject')
  B.emit('call:reject', { ...base(), callId: 'call-reject', from: b.id, to: a.id })
  const rej = await rejectAtA
  const rejCancel = await cancelAfterRejectAtB
  check('5. reject → caller gets call:reject, callee gets call:cancel', rej.from === b.id && rejCancel.reason === 'cancel')

  // ── 6. caller cancel ───────────────────────────────────────
  const cancelAtB = onceMatching(B, 'call:cancel', (d) => d.callId === 'call-cancel')
  const cancelAtA = onceMatching(A, 'call:cancel', (d) => d.callId === 'call-cancel')
  A.emit('call:offer', { ...base(), callId: 'call-cancel', from: a.id, to: b.id, sdp: 'o', callerName: a.name })
  await onceMatching(B, 'call:offer', (d) => d.callId === 'call-cancel')
  A.emit('call:cancel', { ...base(), callId: 'call-cancel', from: a.id, to: b.id })
  const ccB = await cancelAtB
  const ccA = await cancelAtA
  check('6. caller cancel → BOTH sides told (reason cancel)', ccB.reason === 'cancel' && ccA.reason === 'cancel')

  // ── 7. busy ────────────────────────────────────────────────
  A.emit('call:offer', { ...base(), callId: 'call-busy-holder', from: a.id, to: b.id, sdp: 'o', callerName: a.name })
  await onceMatching(B, 'call:offer', (d) => d.callId === 'call-busy-holder')
  const busyAtC = onceMatching(C, 'call:cancel', (d) => d.reason === 'busy' && d.callId === 'call-busy')
  C.emit('call:offer', { ...base(), callId: 'call-busy', from: c.id, to: b.id, sdp: 'o', callerName: c.name })
  const busy = await busyAtC
  check('7. busy callee → second caller gets call:cancel reason=busy', busy.callId === 'call-busy')
  A.emit('call:cancel', { ...base(), callId: 'call-busy-holder', from: a.id, to: b.id })
  await wait(300)

  // ── 8. offline ─────────────────────────────────────────────
  const offlineAtA = onceMatching(A, 'call:cancel', (d) => d.reason === 'offline' && d.callId === 'call-offline')
  A.emit('call:offer', { ...base(), callId: 'call-offline', from: a.id, to: 'ghost-user-never-joined', sdp: 'o', callerName: a.name })
  const offline = await offlineAtA
  check('8. offline callee → caller gets call:cancel reason=offline', offline.reason === 'offline' && offline.callId === 'call-offline')

  // ── 9. identity gate (spoofed from) ────────────────────────
  let spoofLeak = false
  const spoofHandler = (d) => { if (d.callId === 'call-spoof') spoofLeak = true }
  B.on('call:offer', spoofHandler)
  A.emit('call:offer', { ...base(), callId: 'call-spoof', from: 'NOT-' + a.id, to: b.id, sdp: 'spoof', callerName: 'Impostor' })
  await wait(2500)
  B.off('call:offer', spoofHandler)
  check('9. spoofed from ≠ joined id is dropped by the relay', !spoofLeak)

  // ── 10. server ring timeout (30s, authoritative) ──────────
  const timeoutAtA = onceMatching(A, 'call:cancel', (d) => d.callId === 'call-slow' && d.reason === 'timeout', 40000)
  const timeoutAtB = onceMatching(B, 'call:cancel', (d) => d.callId === 'call-slow' && d.reason === 'timeout', 40000)
  A.emit('call:offer', { ...base(), callId: 'call-slow', from: a.id, to: b.id, sdp: 'o', callerName: a.name })
  const [tA, tB] = await Promise.all([timeoutAtA, timeoutAtB])
  check('10. 30s ring timeout → BOTH sides get call:cancel reason=timeout', !!tA && !!tB)

  // ── 11. disconnect teardown ────────────────────────────────
  const tearAtB = onceMatching(B, 'call:cancel', (d) => d.callId === 'call-tear' && d.reason === 'timeout', 40000)
  A.emit('call:offer', { ...base(), callId: 'call-tear', from: a.id, to: b.id, sdp: 'o', callerName: a.name })
  await onceMatching(B, 'call:offer', (d) => d.callId === 'call-tear')
  A.disconnect() // raw transport death — no cancel/hangup emitted
  const tear = await tearAtB
  check('11. caller disconnect → callee ring torn down (timeout cancel)', !!tear)

  // ── 12. REST call log (single-writer, viewer-relative) ────
  const post = await api('/calls', 'POST', { userId: a.id, conversationId: dm.id, peerId: b.id, kind: 'voice', status: 'completed', durationSec: 61 })
  check('12a. caller POST /api/calls → 201 row', post.status === 201 && !!post.json?.item?.id)
  const getA = await api(`/calls?userId=${a.id}`)
  const rowA = (getA.json?.items || []).find((r) => r.id === post.json?.item?.id)
  check('12b. caller GET → outgoing=true + duration', rowA?.outgoing === true && rowA?.durationSec === 61)
  const getB = await api(`/calls?userId=${b.id}`)
  const rowB = (getB.json?.items || []).find((r) => r.id === post.json?.item?.id)
  check('12c. callee GET → same row, outgoing=false + resolved peer', rowB?.outgoing === false && rowB?.peer?.id === a.id)

  const failedPost = await api('/calls', 'POST', { userId: a.id, conversationId: dm.id, peerId: c.id, kind: 'voice', status: 'completed', durationSec: 1 })
  check('12d. POST against a non-participant peer is rejected (400)', failedPost.status === 400)

  C.disconnect(); B.disconnect(); A.disconnect()

  const passed = results.filter((r) => r.ok).length
  console.log(`\nWAVE3-CALL-GATE: ${passed}/${results.length} ${passed === results.length ? 'PASSED' : 'FAILED'}`)
  process.exit(passed === results.length ? 0 : 1)
}

main().catch((err) => { console.error('GATE ERROR:', err.message); process.exit(1) })
