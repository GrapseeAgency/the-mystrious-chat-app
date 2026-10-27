package app.pulse.data.repository

import app.pulse.domain.sync.visibleQuote
import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.PulseJson
import org.junit.Assert.*
import org.junit.Test

class ReplySnippetMappingTest {
    private fun message(metadata: String) = PulseJson.decodeFromString(ChatMessageDto.serializer(), """
        {"id":"reply","conversationId":"room","senderId":"me","content":"reply body","createdAt":"2026-10-23T00:00:00Z",
         "replyTo":{"id":"source","content":"private quote","senderName":"Quiet Raven",$metadata}}
    """).toDomain()

    @Test fun realSnippetDecodesWithoutNestedMessageFieldsAndPreservesPermanentWitness() {
        val row = message(""""deleted":false,"viewOnce":false,"quoteVersion":1,"expiresAt":null""")
        assertEquals("source", row.replyToId)
        assertEquals(1, row.quoteBounds?.version)
        assertNull(row.quoteBounds?.expiresAtEpochMs)
        assertEquals("Quiet Raven", row.visibleQuote(0)?.senderName)
        assertEquals("private quote", row.visibleQuote(0)?.content)
    }
    @Test fun legacyMissingOrInvalidBoundsKeepReplyButHideQuote() {
        for (metadata in listOf(""""deleted":false""", """"deleted":false,"viewOnce":false,"quoteVersion":1""",
            """"deleted":false,"viewOnce":false,"quoteVersion":1,"expiresAt":"invalid"""",
            """"deleted":false,"viewOnce":false,"quoteVersion":2,"expiresAt":null""")) {
            val row = message(metadata)
            assertEquals("reply body", row.body)
            assertEquals("source", row.replyToId)
            assertEquals("Quoted message unavailable", row.visibleQuote(0)?.content)
            assertEquals("Reply", row.visibleQuote(0)?.senderName)
        }
    }
}
