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
