package app.pulse.domain.call

import app.pulse.domain.model.CallKind
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.CallOutcome
import app.pulse.domain.model.CallPeer
import app.pulse.domain.model.CallStatus

/**
 * Outcome → REST row mapper for POST /api/calls (web call-types.ts parity).
 *
 * SINGLE-WRITER RULE: the CALLER's client writes every terminal row exactly
 * once — the callee never writes, so no double rows are possible. The
 * mapper therefore always produces outgoing=true rows (viewer = caller)
 * and refuses to produce anything when the guard cannot hold.
 */
object CallLogMapper {

    /** Terminal outcome → wire status (1:1). */
    fun statusOf(outcome: CallOutcome): CallStatus = when (outcome) {
        CallOutcome.COMPLETED -> CallStatus.COMPLETED
        CallOutcome.MISSED -> CallStatus.MISSED
        CallOutcome.DECLINED -> CallStatus.DECLINED
    }

    /**
     * One terminal row for POST /api/calls { userId, conversationId, peerId,
     * kind, status, durationSec? }. `id`/`startedAt` stay empty — the server
     * assigns them. Returns null when the viewer/peer/conversation guard
     * fails (self-call, blank ids) — such attempts must never POST.
     */
    fun terminalRow(
        viewerId: String,
        conversationId: String,
        peer: CallPeer,
        kind: CallKind,
        status: CallStatus,
        durationSec: Long,
    ): CallLogEntry? {
        if (viewerId.isBlank() || conversationId.isBlank() || peer.id.isBlank()) return null
        if (peer.id == viewerId) return null
        return CallLogEntry(
            id = "",
            conversationId = conversationId,
            callerId = viewerId,
            calleeId = peer.id,
            kind = kind,
            status = status,
            durationSec = durationSec.coerceAtLeast(0L),
            startedAt = "",
            outgoing = true,
            peer = peer,
        )
    }
}
