package app.pulse.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RoomPolicyScalarsTest {
    @Test fun `a quoted policy cannot become permission even with the shared lenient decoder`() {
        for (field in listOf("isGroup", "broadcastMode", "isSelf", "myManualUnread", "screenPrivacy", "myScreenPrivacy", "dmBlocked")) {
            for (bad in listOf("\"false\"", "\"true\"", "0", "[]", "{}")) {
                assertTrue(runCatching { PulseJson.decodeFromString(ConversationSummaryDto.serializer(), """{"id":"room","$field":$bad}""") }.isFailure, "$field=$bad")
            }
        }
        for (field in listOf("ttlSeconds", "slowModeSeconds", "unreadCount")) {
            assertTrue(runCatching { PulseJson.decodeFromString(ConversationSummaryDto.serializer(), """{"id":"room","$field":"0"}""") }.isFailure)
        }
    }
    @Test fun `explicit false zero null and older missing fields retain their distinct meanings`() {
        val row = PulseJson.decodeFromString(ConversationSummaryDto.serializer(), """{"id":"room","screenPrivacy":false,"myScreenPrivacy":null,"ttlSeconds":0}""")
        assertEquals(false, row.screenPrivacy); assertNull(row.myScreenPrivacy); assertEquals(0, row.ttlSeconds)
        val reopened = PulseJson.decodeFromString(ConversationSummaryDto.serializer(), PulseJson.encodeToString(ConversationSummaryDto.serializer(), row))
        assertEquals(row, reopened)
        assertNull(PulseJson.decodeFromString(ConversationSummaryDto.serializer(), """{"id":"room"}""").screenPrivacy)
    }
}
