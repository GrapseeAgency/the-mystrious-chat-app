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
    @Test fun `later source edits propagate to cached non-expiring quotes and stamp the refresh`() {
        val edited = source.copy(body = "edited body", editedAt = "2026-10-25T00:00:00Z")
        val once = quote.restrictQuote(edited, nowMillis = 0)
        assertEquals("edited body", once.replyToBody)
        assertEquals(edited.editedAt, once.quoteBounds?.sourceEditedAtEpochMs?.let { java.time.Instant.ofEpochMilli(it).toString() })
        // The same stamp does not re-refresh (idempotent), and an older stamp never rewinds.
        val again = once.restrictQuote(edited, nowMillis = 0)
        assertEquals("edited body", again.replyToBody)
        val older = once.restrictQuote(source.copy(body = "rewind attempt", editedAt = "2026-10-24T12:00:00Z"), nowMillis = 0)
        assertEquals("edited body", older.replyToBody)
        // A newer edit still lands.
        val newest = once.restrictQuote(source.copy(body = "newest", editedAt = "2026-10-26T00:00:00Z"), nowMillis = 0)
        assertEquals("newest", newest.replyToBody)
    }
    @Test fun `edit propagation never bypasses view-once or deleted captions`() {
        val single = quote.copy(quoteBounds = QuoteBounds(1, viewOnce = true))
        assertEquals("View-once message", single.restrictQuote(source.copy(editedAt = "2026-10-25T00:00:00Z"), nowMillis = 0).replyToBody)
        val dead = quote.restrictQuote(source.copy(deletedAt = "now", editedAt = "2026-10-25T00:00:00Z"), nowMillis = 0)
        assertEquals("", dead.replyToBody)
    }
    @Test fun `learned sourceRootId survives child eviction for root-only revocation`() {
        val child = source.copy(threadRootId = "root")
        val learned = quote.restrictQuote(child, null, 0)
        assertEquals("root", learned.quoteBounds?.sourceRootId)
        // After the child row is uncached, the learned provenance stays put.
        val kept = learned.restrictQuote(null, nowMillis = 0)
        assertEquals("root", kept.quoteBounds?.sourceRootId)
    }
}
