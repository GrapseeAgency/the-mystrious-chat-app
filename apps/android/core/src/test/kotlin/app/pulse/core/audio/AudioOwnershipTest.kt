package app.pulse.core.audio

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AudioOwnershipTest {
    @Test fun `another feature cannot acquire while a room owns audio`() {
        val gate = AudioOwnership()
        assertTrue(gate.acquire("room"))
        assertTrue(gate.acquire("room"))
        assertFalse(gate.acquire("call"))
        gate.release("call")
        assertFalse(gate.acquire("call"))
        gate.release("room")
        assertTrue(gate.acquire("call"))
        gate.release("room") // stale cleanup cannot release the newer call
        assertFalse(gate.acquire("recorder"))
    }
    @Test fun `speaker off selects receiver rather than speaker`() {
        assertEquals(2, communicationRouteType(true, 2, 1))
        assertEquals(1, communicationRouteType(false, 2, 1))
    }
}
