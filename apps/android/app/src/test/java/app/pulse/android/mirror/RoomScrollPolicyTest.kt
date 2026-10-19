package app.pulse.android.mirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomScrollPolicyTest {
    @Test fun `initial message window follows tail`() {
        assertTrue(shouldFollowMessageTail(0, -1, null, "m1", false, false))
    }
    @Test fun `history reader is not pulled down by arrival`() {
        assertFalse(shouldFollowMessageTail(100, 30, "m100", "m101", false, false))
    }
    @Test fun `near tail follows arrivals`() {
        assertTrue(shouldFollowMessageTail(100, 99, "m100", "m101", false, false))
    }
    @Test fun `prepending history never changes tail anchor`() {
        assertFalse(shouldFollowMessageTail(100, 99, "m100", "m100", true, false))
    }
    @Test fun `own send follows unless an explicit jump owns scroll`() {
        assertTrue(shouldFollowMessageTail(100, 30, "m100", "m101", true, false))
        assertFalse(shouldFollowMessageTail(100, 30, "m100", "m101", true, true))
    }
}
