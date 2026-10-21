package app.pulse.android.mirror

import app.pulse.domain.model.Message

internal fun threadMessageLabel(message: Message): String = when {
    message.isDeleted -> "Message deleted"
    message.viewOnce -> "View-once message"
    else -> message.body.ifBlank { "(media)" }
}

internal fun threadAuthorLabel(message: Message): String =
    if (message.anon) message.anonAlias?.takeIf { it.isNotBlank() } ?: "Anonymous" else message.authorName

/** One parent row precedes replies. A missing/expired/deleted target is not index zero. */
internal fun threadJumpIndex(replies: List<Message>, root: Message, target: String, now: Long): Int? =
    app.pulse.domain.sync.threadReplyTargetIndex(replies, root, target, now)?.plus(1)
