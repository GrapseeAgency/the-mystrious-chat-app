package app.pulse.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ViewOnceReceiptTest {
    @Test fun `legacy acknowledgement never implicitly grants a view`() {
        val message = """{"id":"photo","conversationId":"room","senderId":"peer","content":"","createdAt":"2026-10-21T00:00:00Z","viewedAt":"2026-10-21T00:01:00Z"}"""
        val legacy = PulseJson.decodeFromString(ViewOnceReceiptDto.serializer(), """{"message":$message}""")
        assertFalse(legacy.consumed)
        val granted = PulseJson.decodeFromString(ViewOnceReceiptDto.serializer(), """{"message":$message,"consumed":true}""")
        assertTrue(granted.consumed)
        val replay = PulseJson.decodeFromString(ViewOnceReceiptDto.serializer(), """{"message":$message,"consumed":false}""")
        assertFalse(replay.consumed)
    }
}
