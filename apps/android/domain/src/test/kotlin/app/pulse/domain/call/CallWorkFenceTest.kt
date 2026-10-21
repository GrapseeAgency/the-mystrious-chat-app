package app.pulse.domain.call

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CallWorkFenceTest {
    @Test fun `canceled DM lookup or permission prompt cannot begin a later call`() {
        val work = CallWorkFence()
        val old = work.begin("owner")
        work.invalidate()
        assertFalse(work.owns(old, "owner"))
        val current = work.begin("owner")
        assertFalse(work.owns(old, "owner"))
        assertTrue(work.owns(current, "owner"))
        assertFalse(work.owns(current, "other"))
    }

    @Test fun `old peer SDP ICE and CLOSED callbacks cannot touch replacement even for same call id`() {
        val work = CallWorkFence()
        val old = work.begin("owner", "call")
        work.invalidate() // before PeerConnection.close()
        assertFalse(work.owns(old, "owner", "call"))
        val replacement = work.begin("owner", "call")
        assertFalse(work.owns(old, "owner", "call"))
        assertTrue(work.owns(replacement, "owner", "call"))
        assertFalse(work.owns(replacement, "owner", "other-call"))
        assertFalse(work.owns(replacement, null, "call"))
    }
}
