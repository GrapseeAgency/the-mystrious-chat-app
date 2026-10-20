package app.pulse.domain.sync

import app.pulse.domain.model.Conversation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/** Normalize consumed deadlines, making repeated projection idempotent. A preview
 * expiring says nothing about unrelated unread messages or a manual-unread flag. */
fun Conversation.withExpiredSummaryRemoved(now: Long): Conversation {
    val expiredPreview = lastMessageExpiresAtEpochMs?.let { it <= now } == true
    val expired = unreadExpirations.filter { it.expiresAtEpochMs <= now }
    var remaining = unreadCount.coerceAtLeast(0)
    expired.forEach { remaining -= it.count.coerceIn(0, remaining) }
    val updated = copy(unreadCount = remaining, unreadExpirations = unreadExpirations.filter { it.expiresAtEpochMs > now })
    return if (!expiredPreview) updated else updated.copy(
        lastMessagePreview = null, lastMessageAuthorName = null, lastMessageKind = null,
        lastMessageMine = false, lastMessageDeleted = false, lastMessageIsReply = false,
        lastMessageIsImage = false, lastMessageIsAudio = false, lastMessageIsFile = false,
        lastMessageFileName = null, lastMessageExpiresAtEpochMs = null,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<List<Conversation>>.withConversationExpiry(nowMillis: () -> Long = System::currentTimeMillis): Flow<List<Conversation>> =
    transformLatest { snapshot ->
        var current = snapshot
        while (true) {
            val now = nowMillis()
            current = current.map { it.withExpiredSummaryRemoved(now) }
            emit(current)
            val next = current.flatMap { row ->
                listOfNotNull(row.lastMessageExpiresAtEpochMs) + row.unreadExpirations.map { it.expiresAtEpochMs }
            }.minOrNull() ?: break
            delay((next - now).coerceIn(1L, 30_000L))
        }
    }.distinctUntilChanged()
