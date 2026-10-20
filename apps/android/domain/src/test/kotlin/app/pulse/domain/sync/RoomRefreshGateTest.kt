package app.pulse.domain.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RoomRefreshGateTest {
    @Test fun `poll cannot undo a completed mutation`() {
        val gate = RoomRefreshGate()
        val stale = gate.beginRead()!!
        gate.beginWrite()
        assertNull(gate.beginRead())
        gate.endWrite()
        val fresh = gate.beginRead()!!
        assertFalse(gate.accepts(stale))
        assertTrue(gate.accepts(fresh))
    }
    @Test fun `reads wait for all overlapping writers including failed writers`() {
        val gate = RoomRefreshGate()
        gate.beginWrite(); gate.beginWrite(); gate.endWrite()
        assertNull(gate.beginRead())
        gate.endWrite()
        assertTrue(gate.accepts(gate.beginRead()!!))
    }
    @Test fun `late success or failure cannot replace a newer refresh`() {
        val gate = RoomRefreshGate()
        val first = gate.beginRead()!!
        val second = gate.beginRead()!!
        assertFalse(gate.accepts(first)); assertTrue(gate.accepts(second))
    }
}
