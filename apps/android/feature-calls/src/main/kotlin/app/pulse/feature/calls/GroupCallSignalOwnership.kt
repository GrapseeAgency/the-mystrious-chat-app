package app.pulse.feature.calls

import app.pulse.domain.model.GroupCallSignalData

/** A room name alone does not identify an admitted call, especially after rejoining. */
internal fun matchesGroupCall(signal: GroupCallSignalData, roomId: String?, callId: String): Boolean =
    !roomId.isNullOrBlank() && callId.isNotBlank() && signal.conversationId == roomId && signal.callId == callId

internal fun matchesGroupPeerSignal(
    signal: GroupCallSignalData, roomId: String?, callId: String, viewerId: String, memberIds: Collection<String>,
): Boolean = viewerId.isNotBlank() && matchesGroupCall(signal, roomId, callId) && signal.to == viewerId &&
    signal.from.isNotBlank() && signal.from != viewerId && signal.from in memberIds
