package app.pulse.android.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorRoomJumpTest {
    @Test fun outgoingRoomCannotReceiveTheDestinationsJump() {
        val jump = MirrorRoomJump("new-room", "message")
        assertNull(jump.forRoom("old-room", "new-room"))
        assertEquals(jump, jump.forRoom("new-room", "new-room"))
        assertNull(jump.forRoom("new-room", null))
        assertNull(jump.forRoom("new-room", "other-room"))
    }
    @Test fun repeatedSameMessageTapIsANewDeliveryNotAnOldCompletion() {
        val first = MirrorRoomJump("room", "message")
        val second = MirrorRoomJump("room", "message")
        assertNotEquals(first.deliveryId, second.deliveryId)
        assertNotEquals(first, second)
        var pending: MirrorRoomJump? = second
        if (pending == first) pending = null
        assertEquals(second, pending)
    }
    @Test fun anchoredAndRoomOnlyRemindersHaveExplicitTargetsButMissingRoomsDoNot() {
        assertEquals(MirrorReminderTarget("room", "message"), MirrorReminderTarget.from(" room ", " message "))
        assertEquals(MirrorReminderTarget("room", null), MirrorReminderTarget.from("room", " "))
        assertEquals(MirrorReminderTarget("room", null), MirrorReminderTarget.from("room", null))
        assertNull(MirrorReminderTarget.from(null, "message"))
        assertNull(MirrorReminderTarget.from(" ", "message"))
    }
}
