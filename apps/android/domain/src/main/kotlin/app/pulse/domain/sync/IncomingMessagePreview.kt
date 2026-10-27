package app.pulse.domain.sync

import app.pulse.domain.model.Message

data class IncomingMessagePreview(val senderName: String, val body: String)

/** No content disclosure for expired, foreign-room, deleted or single-use rows.
 * Threads retain their separate attention policy; this is a main-room alert. */
fun Message.incomingPreview(viewerId: String?, roomId: String, nowMillis: Long = System.currentTimeMillis()): IncomingMessagePreview? {
    if (viewerId.isNullOrBlank() || conversationId != roomId || isOutgoingFor(viewerId) ||
        id.startsWith("local_") || isDeleted || threadRootId != null || expiresAtEpochMs?.let { it <= nowMillis } == true) return null
    return IncomingMessagePreview(displayAuthorName,
        if (viewOnce) "View-once message" else if (expiresAtEpochMs != null) "Disappearing message" else body.ifBlank { "Sent an attachment" })
}
