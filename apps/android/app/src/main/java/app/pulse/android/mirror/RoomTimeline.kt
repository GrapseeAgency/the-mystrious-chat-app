package app.pulse.android.mirror

import app.pulse.domain.model.Message

internal fun visibleRoomMessages(rows: List<Message>, topicId: String?, now: Long = System.currentTimeMillis()): List<Message> =
    rows.filter { it.threadRootId == null && (topicId == null || it.topicId == topicId) &&
        (it.expiresAtEpochMs?.let { expiry -> expiry > now } ?: true) }
        .sortedWith(compareBy<Message> { MirrorRoomEpoch(it.createdAt) }.thenBy { it.id })

/** Raw rows, never the filtered/rendered list, advance history across hidden replies. */
internal fun oldestRoomMessage(rows: List<Message>): Message? = rows
    .filterNot { it.id.startsWith("local_") }
    .minWithOrNull(compareBy<Message> { MirrorRoomEpoch(it.createdAt) }.thenBy { it.id })
