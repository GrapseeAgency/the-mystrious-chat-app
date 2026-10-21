package app.pulse.domain.call

/** Main-thread-owned work: a canceled permission/DM/SDP callback cannot adopt a newer call. */
class CallWorkFence {
    data class Ticket internal constructor(val generation: Long, val ownerId: String, val callId: String?)
    private var generation = 0L
    private var active: Ticket? = null

    fun begin(ownerId: String, callId: String? = null): Ticket =
        Ticket(++generation, ownerId, callId).also { active = it }

    fun invalidate() { generation++; active = null }

    fun owns(ticket: Ticket, ownerId: String?, callId: String? = null): Boolean =
        ticket == active && ticket.ownerId == ownerId && ticket.callId == callId
}
