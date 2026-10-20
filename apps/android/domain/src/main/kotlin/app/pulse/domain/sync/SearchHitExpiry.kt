package app.pulse.domain.sync

import app.pulse.domain.model.MessageHit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

fun MessageHit.isVisibleSearchHit(now: Long = System.currentTimeMillis()): Boolean =
    !deleted && !viewOnce && (expiresAtEpochMs?.let { it > now } != false)

@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<List<MessageHit>>.withSearchHitExpiry(nowMillis: () -> Long = System::currentTimeMillis): Flow<List<MessageHit>> =
    transformLatest { hits ->
        var visible = hits
        while (true) {
            val now = nowMillis()
            visible = visible.filter { it.isVisibleSearchHit(now) }
            emit(visible)
            val deadline = visible.mapNotNull { it.expiresAtEpochMs }.minOrNull() ?: break
            delay((deadline - now).coerceIn(1L, 30_000L))
        }
    }.distinctUntilChanged()
