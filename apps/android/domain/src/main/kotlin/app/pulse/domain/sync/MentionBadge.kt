package app.pulse.domain.sync

import app.pulse.domain.model.FeedPage
import app.pulse.domain.model.MentionItem
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class MentionBadgeSnapshot(val count: Int, val expiresAt: Long?)

/** Badge chrome caps at 99+. Retain at most 100 live identities, not the feed. */
suspend fun mentionBadgeSnapshot(now: () -> Long = System::currentTimeMillis,
    fetch: suspend (String?) -> FeedPage<MentionItem>): MentionBadgeSnapshot {
    val retained = linkedMapOf<String, MentionItem>()
    var cursor: String? = null
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = fetch(cursor)
        currentCoroutineContext().ensureActive()
        check(page.items.size <= 100 && page.hasMore == (page.nextCursor != null) &&
            (page.nextCursor == null || page.nextCursor != cursor)) { "Mention feed did not advance" }
        retained.entries.removeAll { !it.value.isVisible(now()) }
        page.items.filter { it.isVisible(now()) }.forEach { if (retained.size < 100) retained[it.messageId] = it }
        if (retained.size >= 100 || !page.hasMore) return MentionBadgeSnapshot(retained.size,
            retained.values.mapNotNull { messageExpiryEpochMs(it.expiresAt) }.minOrNull())
        cursor = page.nextCursor
    }
}

/** A mutation/expiry hides stale numeric truth immediately. One in-flight
 * scan plus one trailing pass; transport errors never masquerade as zero. */
@OptIn(ExperimentalCoroutinesApi::class)
fun mentionBadgeFlow(changes: Flow<Unit>, now: () -> Long = System::currentTimeMillis,
    fetch: suspend (String?) -> FeedPage<MentionItem>): Flow<Int?> = channelFlow {
    val hints = Channel<Unit>(Channel.CONFLATED)
    val revision = AtomicLong()
    var expiry: Job? = null
    fun invalidate() { synchronized(revision) { revision.incrementAndGet(); trySend(null); hints.trySend(Unit) } }
    launch { changes.collect { invalidate() } }
    launch { while (true) { delay(30_000); hints.trySend(Unit) } }
    invalidate()
    launch {
        for (ignored in hints) {
            val ticket = revision.get()
            try {
                val snapshot = mentionBadgeSnapshot(now, fetch)
                val current = synchronized(revision) {
                    if (revision.get() == ticket) { trySend(snapshot.count); true } else false
                }
                if (!current) continue
                expiry?.cancel()
                expiry = snapshot.expiresAt?.let { deadline -> launch {
                    while (now() < deadline) delay((deadline - now()).coerceIn(1, 1000))
                    invalidate()
                } }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { synchronized(revision) { if (revision.get() == ticket) trySend(null) } }
        }
    }
    awaitCancellation()
}.buffer(Channel.CONFLATED).distinctUntilChanged()
