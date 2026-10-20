package app.pulse.data.local

import app.pulse.domain.model.Message
import org.junit.Assert.*
import org.junit.Test

class RichOutboxEnvelopeTest {
    @Test fun privacyMetadataAndExactEmptyPayloadSurviveStorage() {
        val original = Message(id = "local_operation", conversationId = "room", authorId = "owner",
            authorName = "Owner", kind = Message.Kind.TEXT, body = "masked", createdAt = "now",
            anon = true, anonAlias = "Quiet Raven", topicId = "topic", replyToId = "quote", payload = "{}")
        val echo = MessageEntity.from(original).toDomain()
        val operation = OutboxEntity(conversationId = "room", clientId = "operation", content = "masked",
            createdAt = "now", wirePayload = original.payload).toEntry()
        assertTrue(echo.anon)
        assertEquals("Quiet Raven", echo.anonAlias)
        assertEquals("topic", echo.topicId)
        assertEquals("quote", echo.replyToId)
        assertEquals("{}", operation.wirePayload)
        assertNull(operation.failureReason)
    }
    @Test fun userPayloadCannotOverrideInternalPrivacyMetadata() {
        val original = Message(id = "message", conversationId = "room", authorId = "owner",
            authorName = "Owner", kind = Message.Kind.TEXT, body = "public", createdAt = "now",
            anon = false, payload = """{"pulse:anon":true,"pulse:anonAlias":"forged"}""")
        val restored = MessageEntity.from(original).toDomain()
        assertFalse(restored.anon)
        assertNull(restored.anonAlias)
    }

}
