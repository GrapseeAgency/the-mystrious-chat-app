package app.pulse.data.remote

import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.ThreadSliceDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompleteThreadTest {
    private fun root() = ChatMessageDto("root", "room", "me", "thread", createdAt = "2026-10-22T00:00:00Z")
    private fun page(ids: List<String>, next: String? = null) = ThreadSliceDto(root(),
        ids.map { root().copy(id = it, parentId = "root") }, 1, next != null, next)

    @Test fun loadsPastFourteenPagesWithTimestampTiesAndExpiryEmptyPages() = runTest {
        var calls = 0
        val result = CompleteThread.load("root") { cursor ->
            assertEquals(if (calls == 0) null else "next-$calls", cursor)
            calls++
            page(if (calls == 7) emptyList() else listOf("m" + calls.toString().padStart(3, '0')), if (calls < 30) "next-$calls" else null)
        }
        assertEquals(30, calls); assertEquals(29, result.replies.size)
        assertEquals("root", result.parent?.id)
    }

    @Test fun cannotAcceptPartialUnavailableForeignReorderedOrUnboundedHistory() = runTest {
        for (mode in listOf("offline", "cancel", "cycle", "repeat", "wrong-root", "wrong-room", "wrong-reply", "dead-root", "old", "rows", "bytes", "pages")) {
            var calls = 0
            try {
                CompleteThread.load("root", maximumRows = if (mode == "rows") 1 else 100,
                    maximumBytes = if (mode == "bytes") 1 else 1_000_000,
                    maximumPages = if (mode == "pages") 1 else 100) {
                    calls++
                    if (calls == 1) page(listOf("a"), "next") else when (mode) {
                        "offline" -> throw IllegalStateException("offline")
                        "cancel" -> throw CancellationException("cancelled")
                        "cycle" -> page(emptyList(), "next")
                        "repeat" -> page(listOf("a"))
                        "wrong-root" -> page(listOf("b")).copy(parent = root().copy(id = "other"))
                        "wrong-room" -> page(listOf("b")).copy(parent = root().copy(conversationId = "other"))
                        "wrong-reply" -> page(listOf("b")).copy(replies = listOf(root().copy(id = "b", parentId = "other")))
                        "dead-root" -> page(listOf("b")).copy(parent = root().copy(deletedAt = "now"))
                        "old" -> page(listOf("b")).copy(threadPageVersion = 0)
                        else -> page(listOf("b"))
                    }
                }
                fail("$mode returned a partial thread")
            } catch (error: Exception) { if (mode == "cancel") assertTrue(error is CancellationException) }
        }
    }
}
