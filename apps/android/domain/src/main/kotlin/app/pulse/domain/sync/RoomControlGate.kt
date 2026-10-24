package app.pulse.domain.sync

/** Main-dispatcher ownership/order fence for a room manager. This does not make
 * network writes idempotent; it prevents stale replies from rewriting local UI. */
class RoomControlGate(private val currentOwner: () -> String?) {
    data class Ticket internal constructor(val owner: String, internal val revision: Long)
    private var owner: String? = null
    private var revision = 0L
    private var writer: Ticket? = null

    private fun synchronizeOwner(): String? {
        val next = currentOwner()
        if (next != owner) { owner = next; revision++; writer = null }
        return next
    }
    fun beginRead(): Ticket? {
        val account = synchronizeOwner() ?: return null
        if (writer != null) return null
        return Ticket(account, ++revision)
    }
    fun beginWrite(): Ticket? {
        val account = synchronizeOwner() ?: return null
        if (writer != null) return null
        return Ticket(account, ++revision).also { writer = it }
    }
    fun isCurrent(ticket: Ticket): Boolean = currentOwner() == ticket.owner && revision == ticket.revision
    fun finishWrite(ticket: Ticket): Boolean {
        if (writer != ticket) return false
        writer = null
        revision++
        return true
    }
    fun invalidate() { writer = null; revision++ }
}
