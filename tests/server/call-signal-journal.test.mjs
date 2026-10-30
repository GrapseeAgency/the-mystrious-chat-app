import test from 'node:test'
import assert from 'node:assert/strict'
import {
  createCallSignalJournal,
  journalIce,
  journalSettled,
  noteAck,
  noteAttempt,
  replayPermitted,
  CALL_JOURNAL_ICE_CAP,
  CALL_JOURNAL_MAX_ATTEMPTS,
} from '../../mini-services/pulse-socket/call-journal.ts'

test('R56 call signal journal: OFFER/ANSWER are bounded acknowledged transactions', () => {
  const journal = createCallSignalJournal()
  assert.equal(journal.offerAcked, false)
  assert.equal(journalSettled(journal), false)
  // First emit is always permitted; the budget bounds every replay after it.
  for (let attempt = 0; attempt < CALL_JOURNAL_MAX_ATTEMPTS; attempt++) {
    assert.equal(replayPermitted(journal, 'offer'), true)
    assert.equal(noteAttempt(journal, 'offer'), true)
  }
  assert.equal(replayPermitted(journal, 'offer'), false)
  assert.equal(noteAttempt(journal, 'offer'), false, 'budget exhausted, no further emits')
  // An ack after budget exhaustion still settles the transaction (late ack).
  assert.equal(noteAck(journal, 'offer'), true, 'first ack is the transition')
  assert.equal(noteAck(journal, 'offer'), false, 'duplicate acks are no-ops')
  // Answer transaction has its own independent budget.
  assert.equal(replayPermitted(journal, 'answer'), true)
  assert.equal(journalSettled(journal), false)
  assert.equal(noteAttempt(journal, 'answer'), true)
  assert.equal(noteAck(journal, 'answer'), true)
  assert.equal(journalSettled(journal), true, 'both settled stops all replay')
})

test('R56 call signal journal: ICE replay window is bounded and drops new candidates when full', () => {
  const journal = createCallSignalJournal()
  for (let index = 0; index < CALL_JOURNAL_ICE_CAP; index++) {
    assert.equal(journalIce(journal, 'callerToCallee', { candidate: `c${index}` }), true)
  }
  assert.equal(journalIce(journal, 'callerToCallee', { candidate: 'overflow' }), false)
  assert.equal(journal.callerToCalleeIce.length, CALL_JOURNAL_ICE_CAP)
  assert.equal(journal.callerToCalleeIce[0].candidate, 'c0', 'oldest kept')
  assert.equal(journal.callerToCalleeIce.at(-1).candidate, `c${CALL_JOURNAL_ICE_CAP - 1}`, 'overflow never entered')
  // The callee→caller direction has its own window.
  assert.equal(journalIce(journal, 'calleeToCaller', { candidate: 'up' }), true)
  assert.equal(journal.calleeToCallerIce.length, 1)
})

test('R56 call signal journal: ack of one direction does not settle the other', () => {
  const journal = createCallSignalJournal()
  noteAttempt(journal, 'offer')
  noteAck(journal, 'offer')
  assert.equal(journalSettled(journal), false, 'answer still un-acked and un-attempted')
  assert.equal(replayPermitted(journal, 'answer'), true)
})
