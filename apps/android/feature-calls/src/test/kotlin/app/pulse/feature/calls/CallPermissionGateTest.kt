package app.pulse.feature.calls

import app.pulse.domain.call.CallSnapshot
import app.pulse.domain.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPermissionGateTest {
    @Test fun `only the ringing call that requested permission may be answered`() {
        val ringing = CallSnapshot(state = CallState.INCOMING_RINGING, callId = "a")
        assertTrue(canApplyCallPermission("a", ringing))
        assertFalse(canApplyCallPermission("a", ringing.copy(callId = "b")))
        assertFalse(canApplyCallPermission(null, ringing))
        assertFalse(canApplyCallPermission("a", ringing.copy(state = CallState.ENDED)))
        assertFalse(canApplyCallPermission("a", ringing.copy(state = CallState.OUTGOING_RINGING)))
    }

    @Test fun `outgoing grant belongs to the original session and target only once`() {
        val gate = OutgoingCallPermissionGate<String>()
        assertFalse(gate.begin(null, "peer"))
        assertFalse(gate.begin("", "peer"))
        assertTrue(gate.begin("a-session-1", "voice-peer"))
        assertFalse(gate.begin("a-session-1", "video-peer"))
        assertEquals("voice-peer", gate.complete(true, "a-session-1"))
        assertNull(gate.complete(true, "a-session-1"))
    }

    @Test fun `switch and same-user replacement session cannot inherit a permission gesture`() {
        val gate = OutgoingCallPermissionGate<String>()
        assertTrue(gate.begin("a-session-1", "peer"))
        assertNull(gate.complete(true, "b-session-1"))
        assertTrue(gate.begin("a-session-1", "peer"))
        assertNull(gate.complete(true, "a-session-2"))
        assertTrue(gate.begin("a-session-2", "peer"))
        assertNull(gate.complete(true, null))
    }

    @Test fun `denial and disposed screen retire permission without starting a call`() {
        val gate = OutgoingCallPermissionGate<String>()
        assertTrue(gate.begin("owner", "peer"))
        assertNull(gate.complete(false, "owner"))
        assertTrue(gate.begin("owner", "next-peer"))
        gate.cancel()
        assertNull(gate.complete(true, "owner"))
        assertTrue(gate.begin("owner", "fresh-peer"))
        assertEquals("fresh-peer", gate.complete(true, "owner"))
    }
}
