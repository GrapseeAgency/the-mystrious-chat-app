/**
 * Pure kernels for the 1:1 call signal journal (R56 - section 7, items 1+2).
 *
 * The relay is a single process with no database; "durable" here means the
 * strongest contract an in-memory relay can honestly offer:
 *   • OFFER and ANSWER are acknowledged TRANSACTIONS - each is emitted with a
 *     socket.io ack and re-emitted on a bounded interval until SOME device of
 *     the target party acknowledges receipt (or the bounded attempt budget is
 *     spent; the 30s ring timeout still owns the endgame).
 *   • A device that joins (or rejoins) mid-ring is replayed the live offer
 *     plus the caller's journaled ICE, instead of silently missing it.
 *   • ICE candidates are journaled per direction (bounded, drop-new-when-full,
 *     same policy as the group engine's 256-entry early-ICE cap) so a replay
 *     after a mid-ring device swap still carries the candidates already sent.
 *
 * Every function here is pure and side-effect free so the contract itself is
 * unit-testable without booting the relay (tests/server/call-signal-journal.test.mjs).
 */

export interface CallSignalJournal {
  /** OFFER acknowledged by at least one callee device. */
  offerAcked: boolean
  /** ANSWER acknowledged by the caller's device. */
  answerAcked: boolean
  /** Emission attempts spent on the OFFER transaction (including the first). */
  offerAttempts: number
  /** Emission attempts spent on the ANSWER transaction (including the first). */
  answerAttempts: number
  /** Caller→callee ICE journal, oldest first. */
  callerToCalleeIce: unknown[]
  /** Callee→caller ICE journal, oldest first. */
  calleeToCallerIce: unknown[]
}

/** Matches the group engine's early-ICE cap (GroupCallEngine / index.ts gcall:ice). */
export const CALL_JOURNAL_ICE_CAP = 256
/** Bounded replay budget per transaction; 6 attempts x 5s spans the 30s ring. */
export const CALL_JOURNAL_MAX_ATTEMPTS = Math.max(1, Number(process.env.PULSE_CALL_REPLAY_ATTEMPTS ?? 6) || 6)

export function createCallSignalJournal(): CallSignalJournal {
  return {
    offerAcked: false,
    answerAcked: false,
    offerAttempts: 0,
    answerAttempts: 0,
    callerToCalleeIce: [],
    calleeToCallerIce: [],
  }
}

export type CallJournalSignal = 'offer' | 'answer'

/** True when the transaction still has replay budget left (first emit included). */
export function replayPermitted(journal: CallSignalJournal, signal: CallJournalSignal): boolean {
  const spent = signal === 'offer' ? journal.offerAttempts : journal.answerAttempts
  return spent < CALL_JOURNAL_MAX_ATTEMPTS
}

/** Records one emission attempt. Returns true if the emit itself is permitted. */
export function noteAttempt(journal: CallSignalJournal, signal: CallJournalSignal): boolean {
  if (!replayPermitted(journal, signal)) return false
  if (signal === 'offer') journal.offerAttempts += 1
  else journal.answerAttempts += 1
  return true
}

/**
 * Records an acknowledgment. Returns true only on the FALSE→TRUE transition so
 * a relay can stop replaying exactly once (late duplicate acks are no-ops).
 */
export function noteAck(journal: CallSignalJournal, signal: CallJournalSignal): boolean {
  if (signal === 'offer') {
    if (journal.offerAcked) return false
    journal.offerAcked = true
    return true
  }
  if (journal.answerAcked) return false
  journal.answerAcked = true
  return true
}

/** Which direction the candidate travels for a party. */
export type CallJournalDirection = 'callerToCallee' | 'calleeToCaller'

/**
 * Journals one outbound ICE payload. Bounded, drop-new-when-full: once the cap
 * is reached further candidates are dropped from the REPLAY window only (the
 * live emit still happens). This mirrors the group engine's queue policy.
 */
export function journalIce(
  journal: CallSignalJournal,
  direction: CallJournalDirection,
  payload: unknown,
): boolean {
  const target = direction === 'callerToCallee' ? journal.callerToCalleeIce : journal.calleeToCallerIce
  if (target.length >= CALL_JOURNAL_ICE_CAP) return false
  target.push(payload)
  return true
}

/** Both transactions settled (no further replay work for this session). */
export function journalSettled(journal: CallSignalJournal): boolean {
  return journal.offerAcked && journal.answerAcked
}
