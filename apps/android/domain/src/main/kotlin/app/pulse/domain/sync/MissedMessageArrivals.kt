package app.pulse.domain.sync

import app.pulse.domain.model.Message

/** A local jump-pill counter, NOT a server unread watermark. Only realtime
 * arrivals add IDs. Snapshots/history may invalidate them but never add counts. */
class MissedMessageArrivals {
    private data class Scope(val roomId: String, val viewerId: String?, val topicId: String?)
    private data class Pending(val row: Message, val observed: Boolean)
    private var scope: Scope? = null
    private var readingTail = true
    private var seeded = false
    private var snapshot = emptyMap<String, Message>()
    private val seen = linkedSetOf<String>()
    private val pending = linkedMapOf<String, Pending>()
    val count: Int get() = pending.size

    fun configure(roomId: String, viewerId: String?, topicId: String?) {
        val next = Scope(roomId, viewerId, topicId)
        if (scope == next) return
        scope = next
        seeded = false
        snapshot = emptyMap()
        pending.clear()
        seen.clear()
    }

    fun setReadingTail(value: Boolean) {
        readingTail = value
        if (value) pending.clear()
    }

    private fun remember(id: String): Boolean {
        if (!seen.add(id)) return false
        // Session replay protection is bounded; pending IDs are separately kept.
        while (seen.size > 4096) seen.remove(seen.first())
        return true
    }

    private fun eligible(row: Message, nowMillis: Long): Boolean {
        val key = scope ?: return false
        return !key.viewerId.isNullOrBlank() && row.conversationId == key.roomId && !row.isOutgoingFor(key.viewerId) &&
            !row.id.startsWith("local_") && row.threadRootId == null && !row.isDeleted &&
            (key.topicId == null || row.topicId == key.topicId) &&
            (row.expiresAtEpochMs?.let { it > nowMillis } != false)
    }

    fun receive(row: Message, nowMillis: Long) {
        if (!eligible(row, nowMillis)) return
        if (!remember(row.id) || pending.containsKey(row.id) || readingTail) return
        val cached = snapshot[row.id]
        if (cached != null && !eligible(cached, nowMillis)) return
        pending[row.id] = Pending(row, cached != null)
    }

    fun observe(rows: List<Message>, nowMillis: Long) {
        snapshot = rows.associateBy { it.id }
        if (!seeded && rows.isNotEmpty()) {
            rows.forEach { remember(it.id) }
            seeded = true
        }
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (id, value) = iterator.next()
            val current = snapshot[id]
            if (!eligible(value.row, nowMillis) || (current != null && !eligible(current, nowMillis)) ||
                (current == null && value.observed)) {
                iterator.remove()
            } else if (current != null) {
                // A late snapshot cannot extend an already-observed deadline.
                val expiry = listOfNotNull(current.expiresAtEpochMs, value.row.expiresAtEpochMs).minOrNull()
                pending[id] = Pending(current.copy(expiresAtEpochMs = expiry), true)
            }
        }
        if (readingTail) pending.clear()
    }
}
