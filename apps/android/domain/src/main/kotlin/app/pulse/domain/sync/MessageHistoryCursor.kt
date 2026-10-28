package app.pulse.domain.sync

import app.pulse.domain.model.Message
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import java.time.Instant

/** Advance using raw page rows, never a topic-filtered river or an asynchronous UI emission. */
data class MessageHistoryCursor(val id: String, val createdAt: String) {
    fun precedes(other: MessageHistoryCursor): Boolean {
        val left = Instant.parse(createdAt)
        val right = Instant.parse(other.createdAt)
        return left < right || (left == right && id < other.id)
    }

    companion object {
        fun oldest(rows: List<Message>): MessageHistoryCursor? = rows
            .filterNot { it.id.startsWith(TEMP_MESSAGE_PREFIX) }
            .map { MessageHistoryCursor(it.id, it.createdAt) }
            .minWithOrNull(compareBy<MessageHistoryCursor> { Instant.parse(it.createdAt) }.thenBy { it.id })
    }
}

/** Cache conflict filtering can remove every row without exhausting server history. */
data class MessageHistoryPage(
    val messages: List<Message>,
    val nextCursor: MessageHistoryCursor?,
    val hasMore: Boolean,
) {
    companion object {
        fun fromResponse(raw: List<Message>, merged: List<Message>, hasMore: Boolean) =
            MessageHistoryPage(merged, MessageHistoryCursor.oldest(raw), hasMore)
    }
}

/** A bounded, authorized neighborhood, not a claim that the entire room was loaded. */
data class MessageWindow(
    val messages: List<Message>, val parent: Message?, val threadId: String?,
    val older: MessageHistoryCursor?, val newer: MessageHistoryCursor?,
    val hasOlder: Boolean, val hasNewer: Boolean, val cachedOnly: Boolean = false,
)
