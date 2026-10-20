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
        while (true) {
            val now = nowMillis()
            val visible = rows.filter { it.expiresAtEpochMs == null || it.expiresAtEpochMs > now }
            emit(visible)
            val next = visible.mapNotNull { it.expiresAtEpochMs }.minOrNull() ?: break
            // Re-evaluate boundedly after a wall-clock adjustment; coroutine
            // cancellation/new Room emissions replace the pending deadline.
            delay((next - now).coerceIn(1L, 30_000L))
        }
    }.distinctUntilChanged()
