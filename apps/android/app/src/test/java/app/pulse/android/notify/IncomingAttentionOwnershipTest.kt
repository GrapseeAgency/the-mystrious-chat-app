package app.pulse.android.notify

import org.junit.Assert.*
import org.junit.Test

class IncomingAttentionOwnershipTest {
    @Test fun closingObscuredRoomCannotClearThreadOrReplacementRoom() {
        val room = IncomingAttention.beginReading("room")
        val thread = IncomingAttention.beginReading("room")
        try {
            room()
            assertEquals("room", IncomingAttention.activeReadingRoom)
            val replacement = IncomingAttention.beginReading("other")
            try {
                thread()
                assertEquals("other", IncomingAttention.activeReadingRoom)
            } finally { replacement() }
            assertNull(IncomingAttention.activeReadingRoom)
            thread() // duplicate cleanup stays harmless
            assertNull(IncomingAttention.activeReadingRoom)
        } finally { thread(); room() }
    }
}
