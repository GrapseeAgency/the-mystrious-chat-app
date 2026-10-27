package app.pulse.domain.sync

import app.pulse.domain.model.Conversation
import app.pulse.domain.model.UnreadExpiration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationExpiryTest {
    private fun row() = Conversation("room", Conversation.Kind.GROUP, "Group",
        lastMessagePreview = "private file", lastMessageFileName = "private.pdf", lastMessageIsFile = true,
        lastMessageAuthorName = "Peer", lastMessageExpiresAtEpochMs = 1000,
        unreadCount = 4, myManualUnread = true, myDraft = "unfinished", isPinned = true,
        unreadExpirations = listOf(UnreadExpiration(1000, 2), UnreadExpiration(2000, 1)))

    @Test fun `offline deadline removes preview metadata and only the expired portion of unread`() = runTest {
        val source = MutableStateFlow(listOf(row()))
        var visible = emptyList<Conversation>()
        val reader = backgroundScope.launch { source.withConversationExpiry { testScheduler.currentTime }.collect { visible = it } }
        runCurrent()
        assertEquals(4, visible.single().unreadCount)
        advanceTimeBy(1000); runCurrent()
        val expired = visible.single()
        assertNull(expired.lastMessagePreview)
        assertNull(expired.lastMessageFileName)
        assertNull(expired.lastMessageAuthorName)
        assertFalse(expired.lastMessageIsFile)
        assertEquals(2, expired.unreadCount)
        assertTrue(expired.myManualUnread)
        assertTrue(expired.isPinned)
        assertEquals("unfinished", expired.myDraft)
        assertEquals(expired, expired.withExpiredSummaryRemoved(1000))
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, visible.single().unreadCount) // permanent unread remains
        reader.cancelAndJoin()
    }

    @Test fun `root bounded reply buckets retire offline without loading any thread history`() = runTest {
        val source = MutableStateFlow(listOf(row().copy(lastMessageExpiresAtEpochMs = 3000,
            unreadCount = 7, unreadExpirations = listOf(UnreadExpiration(2500, 1),
                UnreadExpiration(3000, 4), UnreadExpiration(4000, 1)))))
        var visible = emptyList<Conversation>()
        val reader = backgroundScope.launch { source.withConversationExpiry { testScheduler.currentTime }.collect { visible = it } }
        runCurrent()
        advanceTimeBy(2500); runCurrent()
        assertEquals(6, visible.single().unreadCount)
        assertNotNull(visible.single().lastMessagePreview)
        advanceTimeBy(500); runCurrent()
        assertEquals(2, visible.single().unreadCount)
        assertNull(visible.single().lastMessagePreview)
        assertEquals("unfinished", visible.single().myDraft)
        assertTrue(visible.single().myManualUnread)
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, visible.single().unreadCount)
        assertTrue(visible.single().unreadExpirations.isEmpty())
        reader.cancelAndJoin()
    }

    @Test fun `new snapshot cancels old deadlines and stopped observers stay silent`() = runTest {
        val source = MutableStateFlow(listOf(row()))
        val emissions = mutableListOf<List<Conversation>>()
        val reader = backgroundScope.launch { source.withConversationExpiry { testScheduler.currentTime }.collect { emissions += it } }
        runCurrent()
        source.value = listOf(row().copy(lastMessageExpiresAtEpochMs = null, unreadExpirations = emptyList(), unreadCount = 7))
        runCurrent(); advanceTimeBy(3000); runCurrent()
        assertEquals(7, emissions.last().single().unreadCount)
        assertEquals("private file", emissions.last().single().lastMessagePreview)
        reader.cancelAndJoin()
        val count = emissions.size
        source.value = emptyList(); runCurrent()
        assertEquals(count, emissions.size)
    }

    @Test fun `preview expiry alone never acknowledges unread from an older server`() {
        val oldServer = row().copy(unreadExpirations = emptyList())
        assertEquals(4, oldServer.withExpiredSummaryRemoved(1000).unreadCount)
        assertNull(oldServer.withExpiredSummaryRemoved(1000).lastMessagePreview)
    }

    @Test fun `clock jump is rechecked and consumed deadlines cannot revive within the stream`() = runTest {
        var wallClock = 0L
        val source = MutableStateFlow(listOf(row().copy(lastMessageExpiresAtEpochMs = 90000,
            unreadExpirations = listOf(UnreadExpiration(90000, 3)))))
        var visible = emptyList<Conversation>()
        val reader = backgroundScope.launch { source.withConversationExpiry { wallClock }.collect { visible = it } }
        runCurrent(); wallClock = 90000; advanceTimeBy(30000); runCurrent()
        assertNull(visible.single().lastMessagePreview)
        assertEquals(1, visible.single().unreadCount)
        wallClock = 0; advanceTimeBy(30000); runCurrent()
        assertNull(visible.single().lastMessagePreview)
        reader.cancelAndJoin()
    }
    @Test fun `scoped expiry includes unloaded replies and manual reminders remain visible in dock`() = runTest {
        val snapshot = app.pulse.domain.model.ReadScopeState(4, "unloaded", listOf(UnreadExpiration(1000, 3)))
        val flow = MutableStateFlow<app.pulse.domain.model.ReadScopeState?>(snapshot)
        val counts = mutableListOf<Int?>()
        val job = launch { flow.withReadScopeExpiry { testScheduler.currentTime }.collect { counts += it } }
        runCurrent()
        assertEquals(4, counts.last())
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, counts.last())
        assertEquals(1, row().copy(unreadCount = 0).badgeUnreadCount)
        assertEquals(4, row().badgeUnreadCount)
        job.cancelAndJoin()
    }
}
