package app.pulse.domain.sync

import app.pulse.domain.model.Message
import app.pulse.domain.model.SavedItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedMessageVisibilityTest {
    private fun message(id: String, expiry: Long? = null) = Message(id = id, conversationId = "room", authorId = "peer",
        authorName = "Peer", body = "original", kind = Message.Kind.TEXT, createdAt = "now", expiresAtEpochMs = expiry)
    private fun item(message: Message) = SavedItem(savedAt = 1L, conversationId = "room", conversationName = "Room", isGroup = true, message = message)

    @Test fun `saved rows disappear at deadline offline and deletion tombstones never render`() = runTest {
        val source = MutableStateFlow(listOf(item(message("expiry", 1000)), item(message("keep")), item(message("deleted").copy(deletedAt = "now"))))
        var rows = emptyList<SavedItem>()
        val observer = backgroundScope.launch { source.withSavedMessageExpiry { testScheduler.currentTime }.collect { rows = it } }
        runCurrent()
        assertEquals(listOf("expiry", "keep"), rows.map { it.message.id })
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("keep"), rows.map { it.message.id })
        source.value = listOf(item(message("keep").copy(body = "edited")))
        runCurrent()
        assertEquals("edited", rows.single().message.body)
        observer.cancelAndJoin()
        source.value = emptyList(); runCurrent()
        assertEquals(1, rows.size)
    }

    @Test fun `view once messages cannot become detached pin or search previews`() {
        val once = message("once").copy(viewOnce = true, pinnedAt = "now")
        assertTrue(reconcileMessageSelection(listOf(once), emptyList(), 1).isEmpty())
    }

    @Test fun `pin and search selections follow edits deletes and expiry without treating partial history as removal`() {
        val original = message("edit").copy(pinnedAt = "pinned")
        val unknown = message("outside-window").copy(pinnedAt = "pinned")
        val gone = message("gone").copy(pinnedAt = "pinned")
        val selected = listOf(original, unknown, gone, message("expired", 1))
        val current = listOf(original.copy(body = "new body", pinnedAt = null), gone.copy(deletedAt = "now"))
        assertEquals(listOf("edit", "outside-window"), reconcileMessageSelection(selected, current, 1).map { it.id })
        assertEquals("new body", reconcileMessageSelection(selected, current, 1).first().body)
        assertEquals(listOf("outside-window"), reconcileMessageSelection(selected, current, 1) { it.pinnedAt != null }.map { it.id })
    }
}
