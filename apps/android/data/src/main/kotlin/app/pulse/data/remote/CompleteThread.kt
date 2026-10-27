package app.pulse.data.remote

import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.PulseJson
import app.pulse.protocol.ThreadPageDto
import app.pulse.protocol.ThreadSliceDto
import java.time.Instant
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Never commit a partial prefix as a complete thread. UI cache acceptance is
 * deliberately outside this loader, after every page has passed validation. */
internal object CompleteThread {
    suspend fun load(rootId: String, maximumRows: Int = 10_000, maximumBytes: Long = 16L * 1024 * 1024,
        maximumPages: Int = 512, fetch: suspend (String?) -> ThreadSliceDto): ThreadPageDto {
        val replies = mutableListOf<ChatMessageDto>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var room: String? = null
        var previous: Pair<Instant, String>? = null
        var bytes = 0L
        var pages = 0
        while (true) {
            coroutineContext.ensureActive()
            check(++pages <= maximumPages) { "This thread exceeded the safe paging limit. No partial refresh was accepted." }
            val page = fetch(cursor)
            coroutineContext.ensureActive()
            val parent = page.parent
            check(page.threadPageVersion == 1) { "Update the gateway to load complete threads." }
            check(parent.id == rootId && parent.parentId == null && parent.deletedAt == null && parent.conversationId.isNotBlank() &&
                (room == null || parent.conversationId == room)) { "Thread returned an unrelated or unavailable parent." }
            check(parent.expiresAt?.let { Instant.parse(it).isAfter(Instant.now()) } != false) { "This thread has expired." }
            room = parent.conversationId
            val next = page.nextCursor
            check(if (page.hasMore) !next.isNullOrBlank() && cursors.add(next) else next == null) {
                "Thread paging did not advance. No partial refresh was accepted."
            }
            for (reply in page.replies) {
                check(reply.id.isNotBlank() && reply.id != rootId && !reply.id.startsWith("local_") && reply.parentId == rootId && reply.conversationId == room) {
                    "Thread returned an unrelated reply."
                }
                val position = Instant.parse(reply.createdAt) to reply.id
                val last = previous
                check(last == null || position.first > last.first || (position.first == last.first && position.second > last.second)) {
                    "Thread repeated or reordered replies. No partial refresh was accepted."
                }
                previous = position
            }
            bytes += PulseJson.encodeToString(ThreadSliceDto.serializer(), page).toByteArray(Charsets.UTF_8).size
            check(replies.size + page.replies.size <= maximumRows && bytes <= maximumBytes) {
                "This thread exceeds the safe in-memory limit. No partial refresh was accepted."
            }
            replies.addAll(page.replies)
            if (!page.hasMore) return ThreadPageDto(parent, replies)
            cursor = page.nextCursor
        }
    }
}
