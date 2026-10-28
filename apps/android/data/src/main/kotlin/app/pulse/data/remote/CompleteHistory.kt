package app.pulse.data.remote

import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.MessagesPageDto
import app.pulse.protocol.PulseJson
import java.time.Instant
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Complete bounded snapshot, never a successful prefix. The server fixes an upper
 * boundary in its sync cursor, so arrivals cannot keep this scan running forever. */
internal object CompleteHistory {
    const val START = "0001-01-01T00:00:00.000Z"
    /** Constant-memory traversal. Only the caller owns the unshared temporary
     * file; a failed page throws rather than returning a successful prefix. */
    suspend fun stream(room: String, fetch: suspend (String?) -> MessagesPageDto,
        consume: suspend (List<ChatMessageDto>) -> Unit): Long {
        var cursor: String? = null
        var scanned: app.pulse.domain.sync.MessageHistoryCursor? = null
        var previous: app.pulse.domain.sync.MessageHistoryCursor? = null
        var count = 0L
        while (true) {
            coroutineContext.ensureActive()
            val page = fetch(cursor)
            coroutineContext.ensureActive()
            check(page.exportVersion == 1 && page.messages.size <= 200) { "Update the gateway for streaming export." }
            val next = page.exportPosition?.let { app.pulse.domain.sync.MessageHistoryCursor(it.id, it.createdAt) }
            check(!page.hasMore || (!page.nextSyncCursor.isNullOrBlank() && page.nextSyncCursor != cursor && next != null)) { "Export did not advance" }
            check(page.hasMore || page.nextSyncCursor == null) { "Export completion was not confirmed" }
            if (next != null) {
                check(next.id.isNotBlank() && (scanned == null || scanned.precedes(next))) { "Export scan repeated a position" }
                Instant.parse(next.createdAt)
            }
            for (row in page.messages) {
                check(row.conversationId == room && row.id.isNotBlank() && !row.id.startsWith("local_")) { "Export returned an unrelated row" }
                val position = app.pulse.domain.sync.MessageHistoryCursor(row.id, row.createdAt)
                Instant.parse(row.createdAt)
                check(previous == null || previous.precedes(position)) { "Export repeated or reordered rows" }
                check(next != null && !next.precedes(position)) { "Export row exceeds its scan position" }
                previous = position
            }
            consume(page.messages)
            count += page.messages.size
            if (!page.hasMore) return count
            scanned = next
            cursor = page.nextSyncCursor
        }
    }

    suspend fun load(
        room: String,
        maximumRows: Int = 50_000,
        maximumBytes: Long = 32L * 1024 * 1024,
        fetch: suspend (String?) -> MessagesPageDto,
    ): List<ChatMessageDto> {
        val result = mutableListOf<ChatMessageDto>()
        var cursor: String? = null
        var previous: Pair<Instant, String>? = null
        var bytes = 0L
        while (true) {
            coroutineContext.ensureActive()
            val page = fetch(cursor)
            coroutineContext.ensureActive()
            check(page.syncVersion == 1) { "Update the gateway before exporting or clearing complete history." }
            check(!page.hasMore || (page.messages.isNotEmpty() && !page.nextSyncCursor.isNullOrBlank() && page.nextSyncCursor != cursor)) {
                "History did not advance. No partial history was accepted."
            }
            for (message in page.messages) {
                check(message.conversationId == room && message.id.isNotBlank() && !message.id.startsWith("local_")) { "History returned an unrelated message." }
                val position = Instant.parse(message.createdAt) to message.id
                val last = previous
                check(last == null || position.first > last.first || (position.first == last.first && position.second > last.second)) {
                    "History repeated or reordered a page. No partial history was accepted."
                }
                previous = position
            }
            bytes += PulseJson.encodeToString(MessagesPageDto.serializer(), page).toByteArray(Charsets.UTF_8).size
            check(result.size + page.messages.size <= maximumRows && bytes <= maximumBytes) {
                "This chat exceeds the safe in-memory history limit. No partial export or clear was started."
            }
            result.addAll(page.messages)
            if (!page.hasMore) return result
            cursor = page.nextSyncCursor
        }
    }
}
