package app.pulse.domain.sync

import app.pulse.domain.model.Message
import app.pulse.domain.model.Reaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageMergeTest {
    private fun row() = Message(id = "m", conversationId = "room", authorId = "peer", authorName = "Peer",
        kind = Message.Kind.IMAGE, body = "private caption", createdAt = "2026-10-21T00:00:00Z",
        imagePath = "secret.jpg", payload = "secret", replyToBody = "quoted secret", pinnedAt = "now", expiresAtEpochMs = 9000)

    @Test fun `delayed live response cannot resurrect deleted content or media`() {
        val live = row()
        val deleted = mergeMessageState(live, live.copy(deletedAt = "2026-10-21T00:01:00Z"))
        for (result in listOf(deleted, mergeMessageState(deleted, live), mergeMessageState(null, deleted))) {
            assertTrue(result.isDeleted)
            assertEquals("", result.body)
            assertNull(result.imagePath)
            assertNull(result.payload)
            assertNull(result.replyToBody)
            assertNull(result.pinnedAt)
            assertEquals(9000L, result.expiresAtEpochMs) // redaction must not erase the deadline
        }
    }

    @Test fun `consumption never reopens but unrelated fresh fields still update`() {
        val consumed = row().copy(viewOnce = true, viewedAt = 1000L)
        val incoming = row().copy(reactions = listOf(Reaction("heart", "me")))
        val merged = mergeMessageState(consumed, incoming)
        assertEquals(1000L, merged.viewedAt)
        assertTrue(merged.viewOnce)
        assertEquals(9000L, mergeMessageState(consumed, incoming.copy(expiresAtEpochMs = null)).expiresAtEpochMs)
        assertEquals(9000L, mergeMessageState(consumed, incoming.copy(expiresAtEpochMs = 10000)).expiresAtEpochMs)
        assertEquals(incoming.reactions, merged.reactions)
        assertEquals(1000L, mergeMessageState(consumed, incoming.copy(viewedAt = 2000L)).viewedAt)
    }

    @Test fun `older edit cannot overwrite text while newer reaction metadata is retained`() {
        val edited = row().copy(body = "new text", editedAt = "2026-10-21T00:02:00Z")
        val old = row().copy(editedAt = "2026-10-21T00:01:00Z", reactions = listOf(Reaction("heart", "me")))
        val merged = mergeMessageState(edited, old)
        assertEquals(edited.body, merged.body)
        assertEquals(edited.editedAt, merged.editedAt)
        assertEquals(old.reactions, merged.reactions)
        val newer = old.copy(body = "newest", editedAt = "2026-10-21T00:03:00Z")
        assertEquals(newer.body, mergeMessageState(merged, newer).body)
    }
}
