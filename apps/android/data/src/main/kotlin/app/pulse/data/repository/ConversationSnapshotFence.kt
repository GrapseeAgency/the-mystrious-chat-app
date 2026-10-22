package app.pulse.data.repository

/** Concurrent readers are allowed; a write boundary retires only that room's reads. */
internal class ConversationSnapshotFence {
    data class Read internal constructor(internal val room: String, internal val generation: Long, internal val revision: Long)
    private val lock = Any()
    private var generation = 0L
    private val revisions = mutableMapOf<String, Long>()

    fun beginRead(room: String): Read = synchronized(lock) {
        Read(room, generation, revisions[room] ?: 0L)
    }
    fun isCurrent(read: Read): Boolean = synchronized(lock) {
        read.generation == generation && read.revision == (revisions[read.room] ?: 0L)
    }
    fun invalidate(room: String) = synchronized(lock) {
        revisions[room] = (revisions[room] ?: 0L) + 1L
    }
    fun clear() = synchronized(lock) {
        generation++
        revisions.clear()
    }
}
