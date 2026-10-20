package app.pulse.domain.sync

import app.pulse.domain.model.MessageHit
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
class SearchHitExpiryTest {
    private fun hit(id: String, expiry: Long? = null) = MessageHit(id = id, conversationId = "room", conversationName = "Room",
        isGroup = true, senderName = "Peer", content = "autumn", createdAt = "now", expiresAtEpochMs = expiry)

    @Test fun `retained search removes expired deleted and view once rows without another network call`() = runTest {
        val source = MutableStateFlow(listOf(hit("soon", 1000), hit("keep"), hit("deleted").copy(deleted = true), hit("once").copy(viewOnce = true)))
        var rows = emptyList<MessageHit>()
        val job = backgroundScope.launch { source.withSearchHitExpiry { testScheduler.currentTime }.collect { rows = it } }
        runCurrent()
        assertEquals(listOf("soon", "keep"), rows.map { it.id })
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf("keep"), rows.map { it.id })
        source.value = emptyList(); runCurrent()
        assertTrue(rows.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun `a new query cancels the old expiry schedule and disposed collection cannot publish`() = runTest {
        val source = MutableStateFlow(listOf(hit("old", 1000)))
        var rows = emptyList<MessageHit>()
        val job = backgroundScope.launch { source.withSearchHitExpiry { testScheduler.currentTime }.collect { rows = it } }
        runCurrent()
        source.value = listOf(hit("new", 2000)); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals("new", rows.single().id)
        job.cancelAndJoin()
        advanceTimeBy(1000); runCurrent()
        assertEquals("new", rows.single().id)
    }
}
