/**
 * Group-call server-contract E2E (web-truth verification). R8.
 * Two peers join a group call on the live socket service; asserts:
 * roster broadcast, ring relay via /notify, targeted offer/answer/ICE relay,
 * non-member + spoofed-identity drops, leave roster updates, last-leave
 * ended event, /gcall probe, and the 8-member gcall:full cap.
 */
import { io } from 'socket.io-client'

const URL = 'http://localhost:3003'
const CONV = `conv-gcall-e2e-${Date.now().toString(36)}`

let pass = 0
let fail = 0
function assert(cond: boolean, name: string) {
  if (cond) { pass += 1; console.log(`  OK ${name}`) } else { fail += 1; console.log(`  FAIL ${name}`) }
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

const sockA = io(URL, { path: '/', transports: ['polling', 'websocket'] })
const sockB = io(URL, { path: '/', transports: ['polling', 'websocket'] })

const waitEvent = (sock: ReturnType<typeof io>, event: string, timeoutMs = 4000) =>
  new Promise<any>((resolve) => {
    const timer = setTimeout(() => resolve(null), timeoutMs)
    sock.once(event as never, (payload: unknown) => { clearTimeout(timer); resolve(payload) })
  })

async function main() {
  sockA.emit('join', { userId: 'userA' })
  sockB.emit('join', { userId: 'userB' })
  await sleep(400)

  sockA.emit('gcall:join', { conversationId: CONV, kind: 'voice', user: { id: 'userA', name: 'Alice', color: 'emerald', avatar: null } })
  const state1 = await waitEvent(sockA, 'gcall:state')
  assert(!!state1 && state1.members.length === 1 && state1.members[0].id === 'userA', 'A joins -> roster has A only')
  assert(!!state1 && state1.hostId === 'userA' && state1.kind === 'voice', 'state carries hostId + kind')

  const probe1 = await (await fetch(`http://localhost:3000/api/group-call-state?conversationId=${CONV}`)).json()
  assert(Array.isArray(probe1.members) && probe1.members.length === 1, 'probe returns live roster (1 member)')

  const ringPromise = waitEvent(sockB, 'gcall:ring')
  await fetch(`${URL}/notify`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ event: 'gcall:ring', recipients: ['userB'], payload: { type: 'gcall:ring', conversationId: CONV, kind: 'voice', caller: { id: 'userA', name: 'Alice', color: 'emerald', avatar: null }, title: 'E2E Group' } }),
  })
  const ring = await ringPromise
  assert(!!ring && ring.caller && ring.caller.id === 'userA' && ring.kind === 'voice', 'gcall:ring relayed to B via /notify')

  sockB.emit('gcall:join', { conversationId: CONV, kind: 'voice', user: { id: 'userB', name: 'Bob', color: 'rose', avatar: null } })
  const stateA2 = await waitEvent(sockA, 'gcall:state')
  const stateB2 = await waitEvent(sockB, 'gcall:state')
  assert(!!stateA2 && stateA2.members.length === 2, 'A sees roster of 2')
  assert(!!stateB2 && stateB2.members.length === 2, 'B sees roster of 2')
  assert(!!stateA2 && stateA2.members[0].id === 'userA' && stateA2.members[1].id === 'userB', 'roster join-ordered')

  sockB.emit('gcall:offer', { conversationId: CONV, from: 'userB', to: 'userA', kind: 'voice', sdp: 'v=offer-b' })
  const offer = await waitEvent(sockA, 'gcall:offer')
  assert(!!offer && offer.sdp === 'v=offer-b' && offer.from === 'userB', 'offer relayed A<-B')

  let foreignArrived = false
  sockA.once('gcall:offer', () => { foreignArrived = true })
  const sockC = io(URL, { path: '/', transports: ['polling', 'websocket'] })
  sockC.emit('join', { userId: 'userC' })
  await sleep(200)
  sockC.emit('gcall:offer', { conversationId: CONV, from: 'userC', to: 'userA', kind: 'voice', sdp: 'v=foreign' })
  await sleep(500)
  assert(!foreignArrived, 'offer from non-member C dropped')
  sockC.disconnect()

  sockA.emit('gcall:answer', { conversationId: CONV, from: 'userA', to: 'userB', sdp: 'v=answer-a' })
  const answer = await waitEvent(sockB, 'gcall:answer')
  assert(!!answer && answer.sdp === 'v=answer-a', 'answer relayed B<-A')

  sockA.emit('gcall:ice', { conversationId: CONV, from: 'userA', to: 'userB', candidate: 'candidate-1', sdpMid: '0', sdpMLineIndex: 0 })
  const ice = await waitEvent(sockB, 'gcall:ice')
  assert(!!ice && ice.candidate === 'candidate-1' && ice.sdpMLineIndex === 0, 'ICE relayed with mid/index')

  let spoofArrived = false
  sockB.once('gcall:ice', () => { spoofArrived = true })
  sockA.emit('gcall:ice', { conversationId: CONV, from: 'userB', to: 'userA', candidate: 'spoof', sdpMid: '0', sdpMLineIndex: 0 })
  await sleep(400)
  assert(!spoofArrived, 'spoofed identity (from != socket user) dropped')

  sockA.emit('gcall:leave', { conversationId: CONV, from: 'userA' })
  const stateB3 = await waitEvent(sockB, 'gcall:state')
  assert(!!stateB3 && stateB3.members.length === 1, 'A leaves -> roster 1')
  const endedPromise = waitEvent(sockB, 'gcall:ended')
  sockB.emit('gcall:leave', { conversationId: CONV, from: 'userB' })
  const ended = await endedPromise
  assert(!!ended && ended.reason === 'leave', 'last leave -> gcall:ended')

  const probe2 = await (await fetch(`http://localhost:3000/api/group-call-state?conversationId=${CONV}`)).json()
  assert(Array.isArray(probe2.members) && probe2.members.length === 0, 'probe shows no call after end')

  const socks: ReturnType<typeof io>[] = []
  for (let i = 0; i < 9; i += 1) {
    const s = io(URL, { path: '/', transports: ['polling', 'websocket'] })
    s.emit('join', { userId: `cap${i}` })
    socks.push(s)
    await sleep(80)
  }
  let fullSeen = false
  socks[8].once('gcall:full', () => { fullSeen = true })
  for (let i = 0; i < 9; i += 1) {
    socks[i].emit('gcall:join', { conversationId: CONV, kind: 'voice', user: { id: `cap${i}`, name: `Cap${i}`, color: 'emerald', avatar: null } })
    await sleep(120)
  }
  await sleep(600)
  assert(fullSeen, '9th join rejected with gcall:full')
  for (const s of socks) s.disconnect()

  sockA.disconnect()
  sockB.disconnect()
  console.log(`\nRESULT: ${pass} passed, ${fail} failed`)
  process.exit(fail > 0 ? 1 : 0)
}

main().catch((err) => { console.error('e2e crashed:', err); process.exit(1) })
