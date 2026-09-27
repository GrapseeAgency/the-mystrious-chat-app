/**
 * Browser-transport-path E2E: two socket.io peers through the CADDY GATEWAY
 * (the exact path the web browser uses: /?XTransformPort=3003), proving the
 * gcall signaling works end-to-end after the pulse-realtime-provider gate fix.
 */
import { io } from 'socket.io-client'

const URL = 'http://localhost:81'
const CONV = `conv-gw-${Date.now().toString(36)}`
let pass = 0, fail = 0
const assert = (c: boolean, n: string) => { if (c) { pass++; console.log(`  OK ${n}`) } else { fail++; console.log(`  FAIL ${n}`) } }
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

const A = io(URL, { path: '/', transports: ['polling', 'websocket'], query: { XTransformPort: '3003' } })
const B = io(URL, { path: '/', transports: ['polling', 'websocket'], query: { XTransformPort: '3003' } })
const once = (s: ReturnType<typeof io>, ev: string, ms = 5000) => new Promise<any>((res) => {
  const t = setTimeout(() => res(null), ms)
  s.once(ev as never, (p: unknown) => { clearTimeout(t); res(p) })
})

async function main() {
  A.emit('join', { userId: 'userA' }); B.emit('join', { userId: 'userB' })
  await sleep(500)
  A.emit('gcall:join', { conversationId: CONV, kind: 'voice', user: { id: 'userA', name: 'A', color: 'emerald', avatar: null } })
  const s1 = await once(A, 'gcall:state')
  assert(!!s1 && s1.members.length === 1, 'gateway: A join echoed through Caddy')
  B.emit('gcall:join', { conversationId: CONV, kind: 'voice', user: { id: 'userB', name: 'B', color: 'rose', avatar: null } })
  const sA = await once(A, 'gcall:state'); const sB = await once(B, 'gcall:state')
  assert(!!sA && sA.members.length === 2 && !!sB && sB.members.length === 2, 'gateway: roster of 2 on both peers')
  A.emit('gcall:offer', { conversationId: CONV, from: 'userA', to: 'userB', kind: 'voice', sdp: 'v=o' })
  const off = await once(B, 'gcall:offer')
  assert(!!off && off.sdp === 'v=o', 'gateway: offer relayed')
  A.emit('gcall:leave', { conversationId: CONV, from: 'userA' })
  B.emit('gcall:leave', { conversationId: CONV, from: 'userB' })
  await sleep(400)
  A.disconnect(); B.disconnect()
  console.log(`\nRESULT: ${pass} passed, ${fail} failed`)
  process.exit(fail > 0 ? 1 : 0)
}
void main()
