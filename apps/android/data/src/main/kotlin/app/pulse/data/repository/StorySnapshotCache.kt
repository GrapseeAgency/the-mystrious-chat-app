package app.pulse.data.repository

import app.pulse.protocol.StoriesPageDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes cache commits, not network requests. A late feed cannot undo a confirmed delete. */
internal class StorySnapshotCache(
    private val read: suspend (String) -> StoriesPageDto?,
    private val write: suspend (String, StoriesPageDto) -> Unit,
    private val erase: suspend (String) -> Unit,
) {
    data class Ticket(val key: String, val revision: Long)
    private data class State(
        var revision: Long = 0,
        val removed: MutableSet<String> = mutableSetOf(),
        val viewed: MutableSet<String> = mutableSetOf(),
        var acceptedRevision: Long = 0,
        var page: StoriesPageDto? = null,
        var denied: Boolean = false,
    )
    private val lock = Mutex()
    private val states = mutableMapOf<String, State>()
    private fun state(key: String) = states.getOrPut(key) { State() }
    private fun filtered(key: String, page: StoriesPageDto): StoriesPageDto = page.copy(groups = page.groups.mapNotNull { group ->
        val stories = group.stories.filterNot { it.id in state(key).removed }.map {
            if (it.id in state(key).viewed) it.copy(viewedByMe = true) else it
        }
        if (stories.isEmpty()) null else group.copy(stories = stories, allSeen = if (group.mine) group.allSeen else stories.all { it.viewedByMe })
    })
    private suspend fun snapshot(key: String): StoriesPageDto? {
        val current = state(key)
        if (current.denied) return null
        return (current.page ?: read(key))?.let { filtered(key, it) }
    }
    suspend fun begin(key: String): Ticket = lock.withLock { Ticket(key, ++state(key).revision) }
    suspend fun accept(ticket: Ticket, page: StoriesPageDto): StoriesPageDto? = lock.withLock {
        if (state(ticket.key).revision != ticket.revision) return@withLock snapshot(ticket.key)
        val accepted = filtered(ticket.key, page)
        state(ticket.key).page = accepted
        state(ticket.key).denied = false
        state(ticket.key).acceptedRevision = ticket.revision
        try { write(ticket.key, accepted) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { Unit /* Retain the fresh in-memory snapshot. */ }
        accepted
    }
    suspend fun cached(ticket: Ticket): StoriesPageDto? = lock.withLock { snapshot(ticket.key) }
    suspend fun viewed(key: String, id: String, count: Int) = lock.withLock {
        state(key).revision++
        state(key).viewed.add(id)
        snapshot(key)?.let { page ->
            val updated = page.copy(groups = page.groups.map { group -> group.copy(stories = group.stories.map {
                if (it.id == id) it.copy(viewCount = count) else it
            }) })
            state(key).page = updated
            write(key, updated)
        }
    }
    suspend fun remove(key: String, id: String) = lock.withLock {
        state(key).revision++
        state(key).removed.add(id)
        snapshot(key)?.let { page -> state(key).page = page; write(key, page) }
    }
    suspend fun deny(ticket: Ticket) = lock.withLock {
        // Merely starting a newer request is not proof of authorization. Only
        // a newer accepted network snapshot may supersede this access refusal.
        if (state(ticket.key).acceptedRevision <= ticket.revision) {
            state(ticket.key).revision++
            state(ticket.key).page = null
            state(ticket.key).denied = true
            erase(ticket.key)
        }
    }
}
