package app.pulse.feature.calls

import app.pulse.domain.model.GroupCallSignalData

/** A room name alone does not identify an admitted call, especially after rejoining. */
internal fun matchesGroupCall(signal: GroupCallSignalData, roomId: String?, callId: String): Boolean =
    !roomId.isNullOrBlank() && callId.isNotBlank() && signal.conversationId == roomId && signal.callId == callId

internal fun matchesGroupPeerSignal(
    signal: GroupCallSignalData, roomId: String?, callId: String, viewerId: String, memberIds: Collection<String>,
): Boolean = viewerId.isNotBlank() && matchesGroupCall(signal, roomId, callId) && signal.to == viewerId &&
    signal.from.isNotBlank() && signal.from != viewerId && signal.from in memberIds

/** A delayed invitation does not get a fresh 45 seconds on arrival. */
internal fun groupRingDeadline(signal: GroupCallSignalData, nowMs: Long): Long? {
    if (signal.callId.isBlank() || signal.conversationId.isBlank() || signal.from.isBlank()) return null
    val deadline = signal.expiresAt?.let { app.pulse.domain.sync.messageExpiryEpochMs(it) } ?: return null
    if (deadline <= nowMs) return null
    return minOf(deadline, nowMs + 45_000)
}

/** Refusal before admission has no call ID; it must identify the pending attempt. */
internal fun matchesPendingGroupAttempt(signal: GroupCallSignalData, roomId: String?, attemptId: String?): Boolean =
    !roomId.isNullOrBlank() && !attemptId.isNullOrBlank() && signal.conversationId == roomId && signal.joinAttemptId == attemptId
