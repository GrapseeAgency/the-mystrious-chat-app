package app.pulse.domain.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update

/** Replayed invalidation only: a revision is never a count or proof of Seen. */
class ReadStateInvalidations {
    private data class State(val reconnect: Long = 0, val rooms: Map<String, Long> = emptyMap())
    private val revisions = MutableStateFlow(State())
    fun observe(room: String): Flow<Long> = revisions.map { it.reconnect + (it.rooms[room] ?: 0L) }.distinctUntilChanged()
    fun settled(room: String) { revisions.update { it.copy(rooms = it.rooms + (room to ((it.rooms[room] ?: 0L) + 1L))) } }
    // A joined relay does not replay events lost while disconnected. Include
    // scopes with no earlier local settlement, and retain this for later mounts.
    fun reconnected() { revisions.update { it.copy(reconnect = it.reconnect + 1L) } }
    fun clear() { revisions.value = State() }
}
