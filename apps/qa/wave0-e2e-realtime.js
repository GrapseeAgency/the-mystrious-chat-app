/**
 * Wave 0 — local E2E realtime verification through the REAL edge gateway
 * (:81, XTransformPort query route) against the REAL pulse-socket service.
 * Exercises: connect → join → joined ack → presence (bidirectional) →
 * typing relay → /notify message:new round-trip → disconnect → presence drop.
 * Mirrors the exact native client path (query-param routing).
 */
const { io } = require('socket.io-client')

const EDGE = 'http://localhost:81'
const QUERY = { XTransformPort: '3003' }
const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`)
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms))
function once(socket, event, timeout = 5000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting ${event}`)), timeout)
    socket.once(event, (data) => { clearTimeout(t); resolve(data) })
  })
}

async function main() {
  const A = io(EDGE, { query: QUERY, transports: ['websocket'] })
  const B = io(EDGE, { query: QUERY, transports: ['websocket'] })

  // 1+2. connect + join → joined ack
  const joinedA = once(A, 'joined')
  const joinedB = once(B, 'joined')
  A.on('connect', () => A.emit('join', { userId: 'e2e-user-A' }))
  B.on('connect', () => B.emit('join', { userId: 'e2e-user-B' }))
  const [ja, jb] = await Promise.all([joinedA, joinedB])
  check('A connect+join → joined ack', Array.isArray(ja.onlineUserIds), JSON.stringify(ja))
  check('B connect+join → joined ack', Array.isArray(jb.onlineUserIds), JSON.stringify(jb))

  // 3. presence snapshots carry both users on both clients
  const presA = once(A, 'presence:snapshot')
  const presB = once(B, 'presence:snapshot')
  await wait(300)
  const [pa, pb] = await Promise.all([presA, presB])
  check('presence snapshot at A contains A+B', pa.onlineUserIds.includes('e2e-user-A') && pa.onlineUserIds.includes('e2e-user-B'), JSON.stringify(pa))
  check('presence snapshot at B contains A+B', pb.onlineUserIds.includes('e2e-user-A') && pb.onlineUserIds.includes('e2e-user-B'), JSON.stringify(pb))

  // 4. typing relay A → B (and NOT back to A)
  const typingAtB = once(B, 'typing')
  A.emit('typing', { recipients: ['e2e-user-B'], conversationId: 'e2e-conv', userId: 'e2e-user-A', userName: 'E2E A', isTyping: true })
  const tp = await typingAtB
  check('typing relayed A→B', tp.conversationId === 'e2e-conv' && tp.userId === 'e2e-user-A' && tp.isTyping === true, JSON.stringify(tp))

  // 5. /notify message:new round-trip through the edge
  const msgAtB = once(B, 'message:new')
  const res = await fetch(`${EDGE}/notify?XTransformPort=3003`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      event: 'message:new',
      recipients: ['e2e-user-B'],
      payload: { type: 'message:new', conversationId: 'e2e-conv', recipientIds: ['e2e-user-B'], message: { id: 'e2e-msg-1', conversationId: 'e2e-conv', senderId: 'e2e-user-A', content: 'wave0 round trip', kind: 'text', createdAt: new Date().toISOString() } },
    }),
  })
  const notifyBody = await res.json()
  check('POST /notify via edge accepted', res.ok && notifyBody.ok === true && notifyBody.delivered === 1, JSON.stringify(notifyBody))
  const msg = await msgAtB
  check('message:new round-trip B receives authoritative row', msg.message && msg.message.id === 'e2e-msg-1' && msg.message.content === 'wave0 round trip', JSON.stringify(msg).slice(0, 160))

  // 6. disconnect → presence drop
  const dropAtB = once(B, 'presence:snapshot', 6000)
  A.close()
  let dropped = false
  for (let i = 0; i < 5 && !dropped; i++) {
    const snap = await dropAtB
    dropped = !snap.onlineUserIds.includes('e2e-user-A')
  }
  check('A disconnect → presence drop at B', dropped)

  B.close()
  const failed = results.filter((r) => !r.ok)
  console.log(`\nE2E RESULT: ${results.length - failed.length}/${results.length} passed`)
  process.exit(failed.length ? 1 : 0)
}

main().catch((e) => { console.error('E2E ERROR:', e.message); process.exit(1) })
