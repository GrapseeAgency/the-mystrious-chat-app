package app.pulse.domain.sync

import app.pulse.domain.model.Message

fun canRequestViewOnce(message: Message, viewerId: String, now: Long = System.currentTimeMillis()): Boolean =
    viewerId.isNotBlank() && message.viewOnce && message.authorId != viewerId && !message.isDeleted && message.viewedAt == null &&
        !message.id.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX) && !message.imagePath.isNullOrBlank() &&
        (message.expiresAtEpochMs?.let { it > now } != false)

/** The consumed flag belongs to this response, not a previously cached burn stamp. */
fun acceptsViewOnceGrant(consumed: Boolean, requested: Message, returned: Message, viewerId: String,
    now: Long = System.currentTimeMillis()): Boolean = consumed && returned.id == requested.id &&
    returned.conversationId == requested.conversationId && returned.authorId == requested.authorId &&
    returned.threadRootId == requested.threadRootId && returned.authorId != viewerId && returned.viewOnce &&
    returned.viewedAt != null && !returned.isDeleted && !returned.imagePath.isNullOrBlank() &&
    (returned.expiresAtEpochMs?.let { it > now } != false)
