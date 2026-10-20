package app.pulse.feature.calls

import app.pulse.domain.call.CallSnapshot
import app.pulse.domain.model.CallState
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
}
