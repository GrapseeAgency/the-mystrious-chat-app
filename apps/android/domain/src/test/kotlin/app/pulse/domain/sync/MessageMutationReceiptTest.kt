package app.pulse.domain.sync

import app.pulse.domain.model.Message
import app.pulse.domain.model.Reaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageMutationReceiptTest {
    private fun row() = Message(id = "m", conversationId = "room", authorId = "me", authorName = "Me",
        kind = Message.Kind.TEXT, body = "old", createdAt = "2026-10-22T00:00:00Z")

    @Test fun `late edit advances text but preserves newer reactions and pins`() {
        val current = row().copy(reactions = listOf(Reaction("fire", "peer")), pinnedAt = "2026-10-22T00:02:00Z")
        val ack = row().copy(body = "edited", editedAt = "2026-10-22T00:01:00Z")
        val merged = mergeMessageMutationReceipt(current, ack, MessageMutationKind.EDIT, true)
        assertEquals("edited", merged.body)
        assertEquals(current.reactions, merged.reactions)
        assertEquals(current.pinnedAt, merged.pinnedAt)
        val newerEdit = current.copy(body = "latest", editedAt = "2026-10-22T00:03:00Z")
        assertEquals("latest", mergeMessageMutationReceipt(newerEdit, ack, MessageMutationKind.EDIT, true).body)
    }

    @Test fun `stale pin acknowledgement cannot rewind events and tombstones always redact`() {
        val current = row().copy(reactions = listOf(Reaction("fire", "peer")))
        assertEquals(current, mergeMessageMutationReceipt(current, row().copy(pinnedAt = "now"), MessageMutationKind.PIN, true))
        val dead = row().copy(deletedAt = "2026-10-22T00:01:00Z")
        assertEquals("", mergeMessageMutationReceipt(current, dead, MessageMutationKind.DELETE, true).body)
        val merged = mergeMessageMutationReceipt(dead, row().copy(body = "edit", editedAt = "now"), MessageMutationKind.EDIT, true)
        assertTrue(merged.isDeleted)
        assertEquals("", merged.body)
    }

    @Test fun `action receipt requires exact identity ownership and edited or deleted state`() {
        val original = row()
        val edit = original.copy(body = "edit", editedAt = "2026-10-22T00:01:00Z")
        validateMessageMutationReceipt(original, edit, "me", MessageMutationKind.EDIT, "edit")
        for (bad in listOf(edit.copy(id = "other"), edit.copy(conversationId = "other"),
            edit.copy(authorId = "other"), edit.copy(body = "wrong"), edit.copy(editedAt = null), edit.copy(deletedAt = "now"))) {
            assertThrows(IllegalStateException::class.java) { validateMessageMutationReceipt(original, bad, "me", MessageMutationKind.EDIT, "edit") }
        }
        assertThrows(IllegalStateException::class.java) { validateMessageMutationReceipt(original, original, "me", MessageMutationKind.DELETE) }
        validateMessageMutationReceipt(original, original.copy(deletedAt = "now"), "me", MessageMutationKind.DELETE)
    }
}
