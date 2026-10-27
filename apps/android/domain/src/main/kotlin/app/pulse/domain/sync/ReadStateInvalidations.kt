package app.pulse.domain.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update

/** Replayed invalidation only: a revision is never a count or proof of Seen. */
class ReadStateInvalidations {
    private val revisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    fun observe(room: String): Flow<Long> = revisions.map { it[room] ?: 0L }.distinctUntilChanged()
    fun settled(room: String) { revisions.update { it + (room to ((it[room] ?: 0L) + 1L)) } }
    fun clear() { revisions.value = emptyMap() }
}
