package app.pulse.data.remote

import app.pulse.protocol.ChatMessageDto
import app.pulse.protocol.MessagesPageDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompleteHistoryTest {
    private fun row(id: String, room: String = "room") = ChatMessageDto(id, room, "me", "hello", createdAt = "2026-10-22T00:00:00Z")
    private fun page(ids: List<String>, more: Boolean = false, cursor: String? = null, version: Int? = 1) =
        MessagesPageDto(ids.map { row(it) }, more, ids.size, version, cursor)

    @Test fun followsStableCursorPastFormerPageCapIncludingTimestampTies() = runTest {
        var calls = 0
        val result = CompleteHistory.load("room") { cursor ->
            assertEquals(if (calls == 0) null else "cursor-$calls", cursor)
            calls++
            page(listOf("m" + calls.toString().padStart(3, '0')), calls < 30, if (calls < 30) "cursor-$calls" else null)
        }
        assertEquals(30, calls); assertEquals(30, result.size)
    }

    @Test fun emptyCompleteRoomIsSuccess() = runTest {
        assertTrue(CompleteHistory.load("room") { page(emptyList()) }.isEmpty())
    }

    @Test fun failuresNeverReturnTheAlreadyFetchedPrefix() = runTest {
        for (mode in listOf("network", "cancel", "repeated", "empty-more", "old", "wrong-room", "row-limit", "byte-limit")) {
            var calls = 0
            try {
                CompleteHistory.load("room", maximumRows = if (mode == "row-limit") 1 else 100,
                    maximumBytes = if (mode == "byte-limit") 1L else 1024L * 1024) {
                    calls++
                    if (calls == 1) page(listOf("a"), true, "next") else when (mode) {
                        "network" -> throw IllegalStateException("offline")
                        "cancel" -> throw CancellationException("cancelled")
                        "repeated" -> page(listOf("a"))
                        "empty-more" -> page(emptyList(), true, "later")
                        "old" -> page(listOf("b"), version = null)
                        "wrong-room" -> MessagesPageDto(listOf(row("b", "other")), syncVersion = 1)
                        else -> page(listOf("b"))
                    }
                }
                fail("$mode must not return a partial history")
            } catch (error: Exception) {
                if (mode == "cancel") assertTrue(error is CancellationException)
            }
        }
    }
}
