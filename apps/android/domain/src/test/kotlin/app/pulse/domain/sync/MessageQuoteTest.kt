package app.pulse.domain.sync

import app.pulse.domain.model.Message
import app.pulse.domain.model.QuoteBounds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageQuoteTest {
    private fun message(bounds: QuoteBounds? = null) = Message("reply", "room", "peer", "Peer", Message.Kind.TEXT,
        "reply body", "2026-10-23T00:00:00Z", replyToId = "source", replyToBody = "secret",
        replyToAuthor = "Real Name", quoteBounds = bounds)

    @Test fun `legacy and view once snippets cannot reveal cached body`() {
        assertEquals("Quoted message unavailable", message().visibleQuote(0)?.content)
        assertEquals("Reply", message().redactQuote(0).replyToAuthor)
        assertEquals("", message().redactQuote(0).replyToBody)
        assertEquals("View-once message", message(QuoteBounds(1, viewOnce = true)).redactQuote(0).replyToBody)
        assertEquals("Anonymous", message().copy(anon = true).displayAuthorName)
        assertEquals("Alias", message().copy(anon = true, anonAlias = "Alias").displayAuthorName)
    }

    @Test fun `quote expires independently while containing reply stays visible offline`() = runTest {
        val source = MutableStateFlow(listOf(message(QuoteBounds(1, expiresAtEpochMs = 1000))))
        val emissions = mutableListOf<List<Message>>()
        backgroundScope.launch { source.withMessageExpiry { testScheduler.currentTime }.collect { emissions += it } }
        runCurrent()
        assertEquals("secret", emissions.last().single().visibleQuote(0)?.content)
        advanceTimeBy(1000); runCurrent()
        val retained = emissions.last().single()
        assertEquals("reply body", retained.body)
        assertEquals("source", retained.replyToId)
        assertEquals("", retained.replyToBody)
        assertEquals("Unavailable message", retained.visibleQuote(1000)?.senderName)
        assertEquals(2, emissions.size)
    }

    @Test fun `late snapshots cannot reopen a deleted quote or extend its deadline`() {
        val deadline = 4_000_000_000_000L
        val prior = message(QuoteBounds(1, deadline, deleted = true))
        val incoming = message(QuoteBounds(1, deadline + 1000))
        val merged = mergeMessageState(prior, incoming)
        assertEquals(deadline, merged.quoteBounds?.expiresAtEpochMs)
        assertEquals(true, merged.quoteBounds?.deleted)
        assertEquals("", merged.replyToBody)
    }
}
