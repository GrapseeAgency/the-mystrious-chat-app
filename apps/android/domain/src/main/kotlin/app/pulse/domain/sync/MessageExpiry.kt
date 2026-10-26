package app.pulse.domain.sync

import app.pulse.domain.model.Message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/** Expiry is a clock event, not a database event. Works offline and for thread observers. */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<List<Message>>.withMessageExpiry(nowMillis: () -> Long = System::currentTimeMillis): Flow<List<Message>> =
    transformLatest { rows ->
        var current = rows
        while (true) {
            val now = nowMillis()
            val visible = current.filter { it.expiresAtEpochMs == null || it.expiresAtEpochMs > now }.map { it.redactQuote(now) }
            current = visible
            emit(visible)
            val next = visible.flatMap { listOfNotNull(it.expiresAtEpochMs, it.quoteBounds?.expiresAtEpochMs) }.filter { it > now }.minOrNull() ?: break
            // Re-evaluate boundedly after a wall-clock adjustment; coroutine
            // cancellation/new Room emissions replace the pending deadline.
            delay((next - now).coerceIn(1L, 30_000L))
        }
    }.distinctUntilChanged()


/** Saved libraries expose live messages only, not timeline deletion tombstones. */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<List<app.pulse.domain.model.SavedItem>>.withSavedMessageExpiry(
    nowMillis: () -> Long = System::currentTimeMillis,
): Flow<List<app.pulse.domain.model.SavedItem>> = transformLatest { rows ->
    var current = rows
    while (true) {
        val now = nowMillis()
        current = current.filter { !it.message.isDeleted && !it.message.viewOnce && (it.message.expiresAtEpochMs?.let { expiry -> expiry > now } != false) }
        current = current.map { it.copy(message = it.message.redactQuote(now)) }
        emit(current)
        val next = current.flatMap { listOfNotNull(it.message.expiresAtEpochMs, it.message.quoteBounds?.expiresAtEpochMs) }.filter { it > now }.minOrNull() ?: break
        delay((next - now).coerceIn(1L, 30_000L))
    }
}.distinctUntilChanged()

/** Only absent expiry means permanent. Preserve epoch-zero/negative instants;
 * an explicitly malformed deadline is unavailable, not an unlimited lease. */
fun messageExpiryEpochMs(raw: String?): Long? = raw?.let {
    runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}

/** Expiration schedules include unseen/unloaded replies, not just cached rows. */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<app.pulse.domain.model.ReadScopeState?>.withReadScopeExpiry(
    nowMillis: () -> Long = System::currentTimeMillis,
): Flow<Int?> = transformLatest { snapshot ->
    if (snapshot == null) { emit(null); return@transformLatest }
    while (true) {
        val now = nowMillis()
        emit(snapshot.unreadAt(now))
        val next = snapshot.unreadExpirations.map { it.expiresAtEpochMs }.filter { it > now }.minOrNull() ?: break
        delay((next - now).coerceIn(1L, 30_000L))
    }
}.distinctUntilChanged()

@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<List<app.pulse.domain.model.Topic>>.withTopicUnreadExpiry(
    nowMillis: () -> Long = System::currentTimeMillis,
): Flow<List<app.pulse.domain.model.Topic>> = transformLatest { rows ->
    var current = rows
    while (true) {
        val now = nowMillis()
        current = current.map { it.removingExpiredUnread(now) }
        emit(current)
        val next = current.flatMap { it.unreadExpirations }.map { it.expiresAtEpochMs }.filter { it > now }.minOrNull() ?: break
        delay((next - now).coerceIn(1L, 30_000L))
    }
}.distinctUntilChanged()
