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
    @Test fun streamingExceedsOldWholeHistoryCeilingWithoutAccumulatingRows() = runTest {
        var supplied = 0
        var consumed = 0L
        val count = CompleteHistory.stream("room", fetch = {
            val size = minOf(200, 50_100 - supplied)
            val ids = (supplied until supplied + size).map { "m" + it.toString().padStart(6, '0') }
            supplied += size
            page(ids, supplied < 50_100, if (supplied < 50_100) "page-$supplied" else null).copy(
                exportVersion = 1, exportPosition = app.pulse.protocol.MessageWindowPositionDto(ids.last(), "2026-10-22T00:00:00Z"))
        }) { rows -> assertTrue(rows.size <= 200); consumed += rows.size }
        assertEquals(50_100L, count)
        assertEquals(count, consumed)
    }

    @Test fun streamingRejectsFalseCompletionAndPropagatesWriterFailure() = runTest {
        for (mode in listOf("writer", "old", "repeated-scan", "unconfirmed-end")) {
            var calls = 0
            try {
                CompleteHistory.stream("room", fetch = {
                    calls++
                    val id = if (calls == 1) "a" else "b"
                    page(listOf(id), calls == 1, if (calls == 1 || mode == "unconfirmed-end") "next-$calls" else null).copy(
                        exportVersion = if (mode == "old") null else 1,
                        exportPosition = app.pulse.protocol.MessageWindowPositionDto(if (mode == "repeated-scan") "a" else id, "2026-10-22T00:00:00Z"))
                }) { if (mode == "writer") throw java.io.IOException("Disk full") }
                fail("$mode cannot succeed")
            } catch (error: Exception) { if (mode == "writer") assertTrue(error is java.io.IOException) }
        }
    }

}
