package app.pulse.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 14 item 10 - the ownership-proofing verdict for legacy unowned
 * data. The scan is faked at the exact seam production reads through, so
 * every refuse/adopt decision is pinned without a device database.
 */
class LegacyDataRecoveryTest {

    /** Scriptable scan; null = unreadable, exactly like the SQL seam. */
    private class FakeScan(
        var conversations: Long? = 3,
        var messages: Long? = 40,
        var drafts: Long? = 1,
        var foreignConversation: Boolean? = false,
        var orphanAuthor: Boolean? = false,
        var outbox: Long? = 0,
        var outboxProven: Long? = 0,
    ) : LegacyDataRecovery.Scan {
        override fun conversationCount(): Long? = conversations
        override fun messageCount(): Long? = messages
        override fun draftCount(): Long? = drafts
        override fun hasForeignConversation(owner: String): Boolean? = foreignConversation
        override fun hasOrphanAuthor(): Boolean? = orphanAuthor
        override fun outboxCount(): Long? = outbox
        override fun outboxProven(owner: String): Long? = outboxProven
    }

    @Test
    fun `a cache where every conversation lists the owner is adoptable`() {
        val verdict = LegacyDataRecovery().verdict("viewer-a", FakeScan())
        assertEquals(
            LegacyDataRecovery.Verdict.Adopt(conversations = 3, messages = 40, drafts = 1, outbox = 0, outboxSkipped = 0),
            verdict,
        )
    }

    @Test
    fun `an unreadable database refuses without touching anything`() {
        val verdict = LegacyDataRecovery().verdict("viewer-a", FakeScan(conversations = null))
        assertTrue(verdict is LegacyDataRecovery.Verdict.Unreadable)
    }

    @Test
    fun `a conversation without this owner in its members refuses the whole import`() {
        val verdict = LegacyDataRecovery().verdict("viewer-a", FakeScan(foreignConversation = true))
        assertEquals(
            LegacyDataRecovery.Verdict.Refuse("conversation without this owner in its members"),
            verdict,
        )
    }

    @Test
    fun `a message authored outside its conversation's members refuses the whole import`() {
        val verdict = LegacyDataRecovery().verdict("viewer-a", FakeScan(orphanAuthor = true))
        assertEquals(
            LegacyDataRecovery.Verdict.Refuse("message authored outside its conversation's members"),
            verdict,
        )
    }

    @Test
    fun `an empty cache adopts nothing - the decision is stamped, the file retained`() {
        val verdict = LegacyDataRecovery().verdict(
            "viewer-a",
            FakeScan(conversations = 0, messages = 0, drafts = 0, outbox = 0, outboxProven = 0),
        )
        assertEquals(LegacyDataRecovery.Verdict.Adopt(0, 0, 0, 0, 0), verdict)
    }

    @Test
    fun `queued sends without a proven owner refuse - they are never replayed under a foreign credential`() {
        val verdict = LegacyDataRecovery().verdict(
            "viewer-a",
            FakeScan(conversations = 0, messages = 0, drafts = 0, outbox = 2, outboxProven = 0),
        )
        assertEquals(LegacyDataRecovery.Verdict.Refuse("queued sends without a provable owner"), verdict)
    }

    @Test
    fun `only owner-proven queue rows may be adopted; the rest are skipped and retained`() {
        val verdict = LegacyDataRecovery().verdict(
            "viewer-a",
            FakeScan(outbox = 4, outboxProven = 1),
        )
        assertEquals(
            LegacyDataRecovery.Verdict.Adopt(conversations = 3, messages = 40, drafts = 1, outbox = 1, outboxSkipped = 3),
            verdict,
        )
    }

    @Test
    fun `an unreadable outbox or proof column refuses instead of guessing`() {
        val unreadableOutbox = LegacyDataRecovery().verdict("viewer-a", FakeScan(outbox = null))
        assertTrue(unreadableOutbox is LegacyDataRecovery.Verdict.Unreadable)
        val unreadableProof = LegacyDataRecovery().verdict("viewer-a", FakeScan(outboxProven = null))
        assertTrue(unreadableProof is LegacyDataRecovery.Verdict.Unreadable)
    }

    @Test
    fun `an unreadable messages count refuses even when conversations look ownable`() {
        val verdict = LegacyDataRecovery().verdict("viewer-a", FakeScan(messages = null))
        assertTrue(verdict is LegacyDataRecovery.Verdict.Unreadable)
    }
}
