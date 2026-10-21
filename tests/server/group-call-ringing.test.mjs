import test from 'node:test'
import assert from 'node:assert/strict'
import { dispatchGroupCallRing } from '../../src/lib/group-call-ringing.ts'

const now = new Date('2026-10-21T12:00:00Z')
const participant = (userId, mutedUntil = null) => ({ userId, mutedUntil })

test('muted group members receive neither live rings nor push, and expired mute resumes both', async () => {
  const deliveries = []
  const members = [participant('caller'), participant('active'), participant('muted', new Date(+now + 1)),
    participant('expired', new Date(+now)), participant('active')]
  const report = await dispatchGroupCallRing(members, 'caller', async recipients => {
    deliveries.push(['live', [...recipients]])
    recipients.push('callback-mutation')
  }, async recipients => {
    deliveries.push(['push', recipients])
    return { sent: 1 }
  }, now)
  assert.deepEqual(deliveries, [['live', ['active', 'expired']], ['push', ['active', 'expired']]])
  assert.deepEqual(report, { rang: 2, pushed: 1 })
})

test('self-only or fully muted rooms perform no delivery', async () => {
  for (const members of [[], [participant('caller')], [participant('muted', new Date(+now + 1000))]]) {
    const fail = async () => { assert.fail('must not deliver to ineligible recipients') }
    assert.deepEqual(await dispatchGroupCallRing(members, 'caller', fail, fail, now), { rang: 0, pushed: 0 })
  }
})
