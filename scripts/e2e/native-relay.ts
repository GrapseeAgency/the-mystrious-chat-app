// E2E receipt: message:new relay carries the parse-free native block.
// Bob connects to the socket service, Alice sends an effect message,
// we assert native.effect.id + haptic pattern arrive on the wire.
// (The send happens AFTER the listener is armed; the promise resolves
// from the socket event, so ordering is safe.)
import { io } from 'socket.io-client'

const API = 'http://localhost:3000'
const SOCK = 'http://localhost:3003'
const stamp = Date.now().toString(36)

async function j(path, init) {
  const res = await fetch(API + path, init)
  const body = await res.json()
  if (!res.ok) throw new Error(`${path} -> ${res.status} ${JSON.stringify(body)}`)
  return body
}

const [alice, bob] = await Promise.all([
  j('/api/users', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: `QA-Native-A-${stamp}` }) }),
  j('/api/users', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: `QA-Native-B-${stamp}` }) }),
])
const aliceId = alice.user.id
const bobId = bob.user.id

const conv = await j('/api/conversations', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ creatorId: aliceId, memberIds: [bobId], isGroup: false }),
})
const convId = conv.conversation.id

const received = new Promise((resolve, reject) => {
  const sock = io(SOCK, { path: '/', transports: ['polling', 'websocket'] })
  const timer = setTimeout(() => { sock.close(); reject(new Error('timeout waiting message:new')) }, 20000)
  sock.on('connect', () => sock.emit('join', { userId: bobId }))
  sock.on('message:new', (evt) => {
    clearTimeout(timer)
    sock.close()
    resolve(evt)
  })
  sock.on('connect_error', (err) => { clearTimeout(timer); reject(err) })
})

await j(`/api/conversations/${convId}/messages`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ senderId: aliceId, content: 'native bridge probe', payload: { effect: 'confetti' } }),
})

const evt = await received
const native = evt?.native
const ok =
  evt?.type === 'message:new' &&
  native?.effect?.id === 'confetti' &&
  Array.isArray(native?.effect?.hapticPattern) &&
  typeof native?.effect?.hapticIntensity === 'number' &&
  typeof native?.effect?.motionCurve === 'string' &&
  typeof native?.effect?.durationMs === 'number'

console.log(JSON.stringify({ pass: ok, native }, null, 2))
process.exit(ok ? 0 : 1)
