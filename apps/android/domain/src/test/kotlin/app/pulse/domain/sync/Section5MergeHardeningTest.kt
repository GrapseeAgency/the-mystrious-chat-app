package app.pulse.domain.sync

import app.pulse.domain.model.Message
import app.pulse.domain.model.QuoteBounds
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Section-5 "Old Android expiry loss" + "Uncached child-root provenance" +
 * "Machine-sender semantics": merge hardening. */
class Section5MergeHardeningTest {
    private val base = Message("m1", "room", "peer", "Real Name", Message.Kind.TEXT, "body", "2026-10-24T00:00:00Z")

    @Test fun `malformed incoming deadline never overwrites a parseable prior`() {
        val prior = base.copy(expiresAtEpochMs = 5000L)
        val merged = mergeMessageState(prior, base.copy(expiresAtEpochMs = Long.MIN_VALUE))
        assertEquals(5000L, merged.expiresAtEpochMs)
    }

    @Test fun `parseable incoming heals a poisoned prior instead of clamping to it`() {
        val poisoned = base.copy(expiresAtEpochMs = Long.MIN_VALUE)
        val healed = mergeMessageState(poisoned, base.copy(expiresAtEpochMs = 9000L))
        assertEquals(9000L, healed.expiresAtEpochMs)
    }

    @Test fun `absent incoming keeps the known deadline and earliest of two parseable wins`() {
        assertEquals(5000L, mergeMessageState(base.copy(expiresAtEpochMs = 5000L), base).expiresAtEpochMs)
        assertEquals(3000L, mergeMessageState(base.copy(expiresAtEpochMs = 5000L), base.copy(expiresAtEpochMs = 3000L)).expiresAtEpochMs)
    }

    @Test fun `raw wire expiry survives merges when the parsed side is silent`() {
        val prior = base.copy(expiresAtEpochMs = 5000L, expiresAtRaw = "2026-10-24T00:00:05Z")
        assertEquals("2026-10-24T00:00:05Z", mergeMessageState(prior, base).expiresAtRaw)
        // A fresh authoritative raw value replaces the retained one.
        val fresh = mergeMessageState(prior, base.copy(expiresAtEpochMs = 6000L, expiresAtRaw = "2026-10-24T00:00:06Z"))
        assertEquals("2026-10-24T00:00:06Z", fresh.expiresAtRaw)
    }

    @Test fun `learned provenance survives re-delivery of a v1 envelope`() {
        val prior = base.copy(replyToId = "src", replyToBody = "cached", replyToAuthor = "Quiet Raven",
            quoteBounds = QuoteBounds(1, sourceEditedAtEpochMs = 1234L, sourceRootId = "root"))
        val reDelivered = prior.copy(quoteBounds = QuoteBounds(1))
        val merged = mergeMessageState(prior, reDelivered)
        assertEquals(1234L, merged.quoteBounds?.sourceEditedAtEpochMs)
        assertEquals("root", merged.quoteBounds?.sourceRootId)
        // The certified snapshot itself is intact after redaction.
        assertEquals("cached", merged.replyToBody)
    }

    @Test fun `later envelope without certifiable bounds does not downgrade certified bounds`() {
        val farFuture = 4102444800000L // 2100-01-01 - merge re-redacts at wall-clock now
        val prior = base.copy(replyToId = "src", replyToBody = "cached", replyToAuthor = "Quiet Raven",
            quoteBounds = QuoteBounds(1, expiresAtEpochMs = farFuture))
        val legacy = prior.copy(quoteBounds = null)
        val merged = mergeMessageState(prior, legacy)
        assertEquals(1, merged.quoteBounds?.version)
        assertEquals(farFuture, merged.quoteBounds?.expiresAtEpochMs)
        assertEquals("cached", merged.replyToBody)
    }

    @Test fun `machine marked rows follow the server flag not client metadata`() {
        val automation = base.copy(viaAutomation = true, authorId = "me")
        assertFalse(automation.isOutgoingFor("me"))
        // Pinned contract (IncomingMessagePreviewTest): legacy client-writable
        // payload flags are display-only ("Unverified integration") and keep
        // the member's own rows classified as outgoing.
        val legacy = base.copy(authorId = "me", payload = """{"bot": true}""".trimIndent())
        assertEquals("Unverified integration", legacy.integrationLabel)
        assertTrue(legacy.isOutgoingFor("me"))
        // A plain human row still counts as outgoing for its author.
        assertTrue(base.copy(authorId = "me").isOutgoingFor("me"))
        // Deleted rows drop the integration label entirely.
        assertNull(automation.copy(deletedAt = "now").integrationLabel)
    }
}
