package app.pulse.domain.sync

import app.pulse.domain.model.Message
import app.pulse.domain.model.QuoteBounds
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QuoteSourcePrivacyTest {
    private val source = Message("source", "room", "peer", "Real Name", Message.Kind.TEXT, "old source body", "2026-10-24T00:00:00Z")
    private val quote = source.copy(id = "reply", body = "reply", replyToId = "source", replyToBody = "newer server quote",
        replyToAuthor = "Quiet Raven", quoteBounds = QuoteBounds(1))
    @Test fun `known deletion revokes quote without deleting reply or trusting legacy metadata`() {
        val dead = quote.restrictQuote(source.copy(deletedAt = "now"), nowMillis = 0)
        assertEquals("reply", dead.body); assertEquals("source", dead.replyToId)
        assertEquals("", dead.replyToBody); assertEquals(true, dead.quoteBounds?.deleted)
        assertEquals("", mergeMessageState(dead, quote).replyToBody)
        assertEquals("", quote.copy(quoteBounds = null).restrictQuote(source, nowMillis = 0).replyToBody)
    }
    @Test fun `cached source only tightens lifetime view once and attribution`() {
        val live = quote.restrictQuote(source, nowMillis = 0)
        assertEquals("newer server quote", live.replyToBody)
        assertEquals("Quiet Raven", live.replyToAuthor) // cached account name cannot deanonymize a quote
        val single = quote.restrictQuote(source.copy(viewOnce = true, expiresAtEpochMs = 1000), nowMillis = 0)
        assertEquals("View-once message", single.replyToBody)
        assertEquals(1000L, single.quoteBounds?.expiresAtEpochMs)
        assertEquals("", single.restrictQuote(source, nowMillis = 1000).replyToBody)
        assertEquals("Anonymous", quote.restrictQuote(source.copy(anon = true), nowMillis = 0).replyToAuthor)
    }
    @Test fun `known root privacy and foreign source fail closed but missing cache does not revoke valid quote`() {
        val child = source.copy(threadRootId = "root")
        val root = source.copy(id = "root", expiresAtEpochMs = 500)
        assertEquals(500L, quote.restrictQuote(child, root, 0).quoteBounds?.expiresAtEpochMs)
        for (bad in listOf(root.copy(deletedAt = "now"), root.copy(conversationId = "foreign"), root.copy(threadRootId = "nested")))
            assertEquals("", quote.restrictQuote(child, bad, 0).replyToBody)
        assertEquals("", quote.restrictQuote(source.copy(conversationId = "foreign"), nowMillis = 0).replyToBody)
        assertEquals(quote, quote.restrictQuote(null, nowMillis = 0))
    }
}
