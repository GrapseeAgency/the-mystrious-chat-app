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
    @Test fun `an incoming exclusive owner stops passive playback before acquiring`() {
        val gate = AudioOwnership()
        var stopped = false
        assertTrue(gate.acquirePlayback("note") { stopped = true; gate.release("note") })
        assertTrue(gate.acquireExclusive("call"))
        assertTrue(stopped)
        gate.release("note")
        assertFalse(gate.acquire("recorder"))
    }
    @Test fun `exclusive sessions cannot preempt other exclusive owners`() {
        val gate = AudioOwnership()
        assertTrue(gate.acquireExclusive("recorder"))
        assertFalse(gate.acquirePlayback("note") { error("must not run") })
        assertFalse(gate.acquireExclusive("call"))
    }
    @Test fun `preemption rechecks ownership rather than overwriting a competing winner`() {
        val gate = AudioOwnership()
        gate.acquirePlayback("note") {
            gate.release("note")
            gate.acquireExclusive("room")
        }
        assertFalse(gate.acquireExclusive("call"))
        assertFalse(gate.acquire("other"))
        gate.release("room")
        assertTrue(gate.acquire("call"))
    }

}
