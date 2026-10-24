import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { stripTypeScriptTypes } from 'node:module'

async function fixture() {
  const file = await readFile(new URL('../../mini-services/pulse-socket/index.ts', import.meta.url), 'utf8')
  const source = stripTypeScriptTypes(file.slice(file.indexOf('const GROUP_CALL_ACCESS_LEASE_MS ='), file.indexOf('function asGroupMember(')))
  let clock = 1000, verdict = async () => 'allowed', tick
  const groupCalls = new Map(), removals = []
  const deps = {
    groupCalls, Date: class extends Date { static now() { return clock } },
    groupCallAccessVerdict: (...args) => verdict(...args),
    setInterval: fn => { tick = fn; return { unref() {} } },
    leaveGroupCall: (userId, reason, socketId) => {
      removals.push({ userId, reason, socketId })
      for (const call of groupCalls.values()) {
        if (call.members.get(userId)?.socketId === socketId) call.members.delete(userId)
      }
    },
  }
  const methods = new Function(...Object.keys(deps), source + ';return {refreshGroupCallMemberAccess,expireGroupCallAccess,liveGroupCallMember,queueGroupAccessRefresh,drainGroupAccessRefreshes}')(...Object.values(deps))
  const seed = (room = 'room', owner = 'owner') => {
    const member = { userId: owner, socketId: `socket-${owner}`, joinAttemptId: 'attempt', accessValidUntil: 31_000 }
    const call = { conversationId: room, callId: `call-${room}`, members: new Map([[owner, member]]) }
    groupCalls.set(room, call)
    return { call, member }
  }
  return { ...methods, seed, groupCalls, removals, now: value => { clock = value }, verdict: fn => { verdict = fn }, tick: () => tick() }
}

test('temporary authority failure never extends a lease; expiry stops signaling and removes membership', async () => {
  const h = await fixture(), { call, member } = h.seed()
  h.verdict(async () => 'unavailable')
  await h.refreshGroupCallMemberAccess(call, member)
  assert.equal(member.accessValidUntil, 31_000)
  assert.equal(h.removals.length, 0)
  assert.equal(h.liveGroupCallMember(call, 'owner'), member)
  h.now(31_000)
  assert.equal(h.liveGroupCallMember(call, 'owner'), undefined)
  h.expireGroupCallAccess()
  assert.deepEqual(h.removals, [{ userId: 'owner', reason: 'access-unavailable', socketId: 'socket-owner' }])
})

test('a definite membership refusal revokes immediately without waiting for lease expiry', async () => {
  const h = await fixture(), { call, member } = h.seed()
  h.verdict(async () => 'denied')
  await h.refreshGroupCallMemberAccess(call, member)
  assert.equal(h.removals[0].reason, 'membership-revoked')
  assert.equal(call.members.size, 0)
})

test('refresh uses request time and cannot resurrect expired permission', async () => {
  for (const replyTime of [5000, 31_000]) {
    const h = await fixture(), { call, member } = h.seed()
    let reply
    h.verdict(() => new Promise(resolve => { reply = resolve }))
    const work = h.refreshGroupCallMemberAccess(call, member)
    h.now(replyTime); reply('allowed'); await work
    if (replyTime < 31_000) {
      assert.equal(member.accessValidUntil, 31_000, 'network latency cannot extend the deadline')
      assert.equal(h.removals.length, 0)
    } else assert.equal(h.removals[0].reason, 'access-unavailable')
  }
})

test('late refusal cannot remove a replacement call or a new attempt on the same device', async () => {
  for (const replaceCall of [true, false]) {
    const h = await fixture(), { call, member } = h.seed()
    let reply
    h.verdict(() => new Promise(resolve => { reply = resolve }))
    const work = h.refreshGroupCallMemberAccess(call, member)
    if (replaceCall) h.seed()
    else call.members.set('owner', { ...member, joinAttemptId: 'new-attempt' })
    reply('denied'); await work
    assert.equal(h.removals.length, 0)
    assert.equal(h.groupCalls.get('room').members.size, 1)
  }
})

test('periodic refresh covers quiet calls and coalesces events with at most eight checks in flight', async () => {
  const h = await fixture()
  for (let i = 0; i < 3; i++) {
    const { call } = h.seed(`room-${i}`, `owner-${i}`)
    for (let j = 1; j < 8; j++) call.members.set(`peer-${i}-${j}`, { userId: `peer-${i}-${j}`, socketId: `socket-${i}-${j}`, accessValidUntil: 31_000 })
  }
  let active = 0, peak = 0, requests = 0
  const replies = []
  h.verdict(() => { active++; requests++; peak = Math.max(peak, active); return new Promise(resolve => replies.push(() => { active--; resolve('allowed') })) })
  h.tick()
  for (let i = 0; i < 10; i++) h.queueGroupAccessRefresh('room-2')
  assert.equal(active, 8)
  while (replies.length) {
    const batch = replies.splice(0)
    for (const reply of batch) reply()
    await new Promise(resolve => setImmediate(resolve))
  }
  assert.equal(peak, 8)
  assert.equal(requests, 24)
  assert.equal(h.removals.length, 0)
})
