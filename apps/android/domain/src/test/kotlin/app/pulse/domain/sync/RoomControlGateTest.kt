package app.pulse.domain.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RoomControlGateTest {
    @Test fun `newer reads and mutations invalidate older refreshes`() {
        val gate = RoomControlGate { "account-session" }
        val old = gate.beginRead()!!
        val fresh = gate.beginRead()!!
        assertFalse(gate.isCurrent(old)); assertTrue(gate.isCurrent(fresh))
        val write = gate.beginWrite()!!
        assertFalse(gate.isCurrent(fresh)); assertTrue(gate.isCurrent(write))
        assertNull(gate.beginRead()); assertNull(gate.beginWrite())
        assertTrue(gate.finishWrite(write))
        assertFalse(gate.isCurrent(write)); assertNotNull(gate.beginRead())
    }
    @Test fun `account replacement refuses queued dispatch and late acceptance`() {
        var owner: String? = "first-session"
        val gate = RoomControlGate { owner }
        val queued = gate.beginWrite()!!
        owner = "second-session"
        assertFalse(gate.isCurrent(queued))
        val replacement = gate.beginWrite()!!
        assertFalse(gate.finishWrite(queued))
        assertTrue(gate.isCurrent(replacement)); assertNull(gate.beginWrite())
        assertTrue(gate.finishWrite(replacement))
        owner = null
        assertNull(gate.beginRead()); assertNull(gate.beginWrite())
    }
    @Test fun `cancellation releases only its own writer and dismissal retires all callbacks`() {
        val gate = RoomControlGate { "owner" }
        val cancelled = gate.beginWrite()!!
        assertTrue(gate.finishWrite(cancelled))
        val next = gate.beginWrite()!!
        assertFalse(gate.finishWrite(cancelled)); assertTrue(gate.isCurrent(next))
        gate.invalidate()
        assertFalse(gate.isCurrent(next)); assertFalse(gate.finishWrite(next))
        assertNotNull(gate.beginWrite())
    }
    @Test fun `a failed delete leaves confirmed data intact and a late read cannot resurrect a successful delete`() {
        val gate = RoomControlGate { "owner" }
        var rows = listOf("rule")
        val oldRead = gate.beginRead()!!
        val failed = gate.beginWrite()!!
        try { throw IllegalStateException("Refused") } catch (_: IllegalStateException) { /* no acknowledged mutation */ }
        finally { gate.finishWrite(failed) }
        assertEquals(listOf("rule"), rows)
        val deleted = gate.beginWrite()!!
        if (gate.isCurrent(deleted)) rows = emptyList()
        gate.finishWrite(deleted)
        if (gate.isCurrent(oldRead)) rows = listOf("rule")
        assertTrue(rows.isEmpty())
    }
}
