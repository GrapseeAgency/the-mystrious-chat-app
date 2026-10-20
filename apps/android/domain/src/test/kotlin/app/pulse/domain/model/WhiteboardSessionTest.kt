package app.pulse.domain.model

import app.pulse.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhiteboardSessionTest {
    private class Fixture(val scope: CoroutineScope) {
        var disk = emptyList<WhiteboardStrokePostDto>()
        var remote = emptyList<WhiteboardStrokeDto>()
        var epoch = 0L
        var version = 1
        var complete = true
        var posts = 0
        var failPost = false
        var badAck = false
        var gate: CompletableDeferred<Unit>? = null
        val posting = CompletableDeferred<Unit>()
        val board = WhiteboardSession(scope, "me", { disk }, { transform -> disk = transform(disk); disk },
            { WhiteboardPageDto(operationsVersion = version, complete = complete, strokes = remote, resetAt = epoch) },
            { batch ->
                assertTrue(disk.containsAll(batch), "POST must follow storage commit")
                posts++
                posting.complete(Unit)
                gate?.await()
                val ids = batch.map { "wb:me:${it.clientId}" }
                remote = (remote + batch.mapIndexed { i, s -> WhiteboardStrokeDto(id = ids[i], userId = "me", color = s.color, width = s.width, points = s.points) }).distinctBy { it.id }
                if (failPost) error("lost ACK")
                WhiteboardPostResultDto(ids = if (badAck) emptyList() else ids, created = batch.size)
            },
            { remote = remote.dropLast(1); WhiteboardUndoResultDto(success = true) },
            { epoch++; remote = emptyList(); WhiteboardClearResultDto(success = true, reset = true, at = epoch) })
        fun draw() = board.draw("#abcdef", 3.0, listOf(listOf(0.0, 0.0), listOf(1.0, 1.0)))
    }

    @Test fun `normalization never produces 501 points and preserves endpoints`() {
        for (size in listOf(501, 999, 1000, 1001, 10000)) {
            val points = (0 until size).map { listOf(it.toDouble() / size, 0.5) }
            val normalized = WhiteboardSession.normalizedPoints(points)
            assertEquals(500, normalized.size)
            assertEquals(points.first(), normalized.first())
            assertEquals(points.last(), normalized.last())
        }
    }
    @Test fun `normalization rejects malformed coordinates and supports stationary dots`() {
        assertTrue(WhiteboardSession.normalizedPoints(listOf(listOf(Double.NaN, 1.0), listOf(1.0))).isEmpty())
        assertEquals(listOf(listOf(0.0, 1.0), listOf(0.0, 1.0)), WhiteboardSession.normalizedPoints(listOf(listOf(-2.0, 4.0))))
    }

    @Test fun `draw persists even while previous upload is suspended`() = runTest {
        val f = Fixture(backgroundScope)
        f.board.start(); runCurrent()
        f.gate = CompletableDeferred()
        f.draw(); runCurrent()
        f.draw(); runCurrent()
        assertEquals(2, f.disk.size)
        assertNotEquals(f.disk[0].clientId, f.disk[1].clientId)
        assertEquals(1, f.posts)
        f.gate!!.complete(Unit); runCurrent()
        assertTrue(f.disk.isEmpty())
        assertEquals(2, f.remote.size)
    }
    @Test fun `lost ACK is reconciled by identity without resending geometry`() = runTest {
        val f = Fixture(backgroundScope)
        f.board.start(); runCurrent()
        f.failPost = true
        f.draw(); runCurrent()
        assertEquals(1, f.disk.size)
        advanceTimeBy(1500); runCurrent()
        assertTrue(f.disk.isEmpty())
        assertEquals(1, f.posts)
    }
    @Test fun `closing during POST retains durable operation for next open`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val f = Fixture(scope)
        f.board.start(); runCurrent()
        f.gate = CompletableDeferred()
        f.draw(); runCurrent()
        scope.cancel(); runCurrent()
        assertEquals(1, f.disk.size)
        assertNotNull(f.disk.single().clientId)
        assertTrue(f.remote.isEmpty())
    }
    @Test fun `authoritative snapshot removes someone elses undone stroke`() = runTest {
        val f = Fixture(backgroundScope)
        f.remote = listOf(WhiteboardStrokeDto(id = "other", userId = "someone"))
        f.board.start(); runCurrent()
        assertEquals(1, f.board.state.value.remote.size)
        f.remote = emptyList()
        advanceTimeBy(1500); runCurrent()
        assertTrue(f.board.state.value.remote.isEmpty())
    }
    @Test fun `old epoch is not uploaded on reopen and unknown legacy draft is retained`() = runTest {
        val f = Fixture(backgroundScope)
        val legacy = WhiteboardStrokePostDto(points = listOf(listOf(0.0, 0.0), listOf(1.0, 1.0)))
        f.disk = listOf(legacy, legacy.copy(clientId = "operation-00000001", baseResetAt = 0))
        f.epoch = 9
        f.board.start(); runCurrent()
        assertEquals(listOf(legacy), f.disk)
        assertEquals(0, f.posts)
    }
    @Test fun `malformed ACK never removes draft`() = runTest {
        val f = Fixture(backgroundScope)
        f.board.start(); runCurrent()
        f.badAck = true
        f.draw(); runCurrent()
        assertEquals(1, f.disk.size)
        assertNotNull(f.board.state.value.error)
    }
    @Test fun `clear waits for upload and prevents drawing until its new epoch is known`() = runTest {
        val f = Fixture(backgroundScope)
        f.board.start(); runCurrent()
        f.gate = CompletableDeferred()
        f.draw(); runCurrent()
        f.board.clearBoard(); runCurrent()
        assertNull(f.draw())
        assertEquals(0L, f.epoch)
        f.gate!!.complete(Unit); runCurrent()
        assertTrue(f.disk.isEmpty()); assertTrue(f.remote.isEmpty())
        assertEquals(1L, f.epoch)
        f.failPost = true
        f.draw(); runCurrent()
        assertEquals(1L, f.disk.single().baseResetAt)
    }
    @Test fun `ACK merges current disk and does not erase another screen append`() = runTest {
        val f = Fixture(backgroundScope)
        f.board.start(); runCurrent()
        f.gate = CompletableDeferred()
        f.draw(); runCurrent()
        val other = f.disk.single().copy(clientId = "other-window-00000001")
        f.disk = f.disk + other
        f.gate!!.complete(Unit); runCurrent()
        // The other append is either still pending or safely uploaded, never discarded.
        assertTrue(other in f.disk || f.remote.any { it.id == "wb:me:${other.clientId}" })
        assertEquals(2, f.remote.size)
    }

    @Test fun `old server cannot trigger unsafe retries`() = runTest {
        val f = Fixture(backgroundScope); f.version = 0
        f.board.start(); runCurrent()
        assertFalse(f.board.state.value.ready)
        assertNull(f.draw()); assertEquals(0, f.posts)
    }
    @Test fun `truncated snapshot is not adopted as complete truth`() = runTest {
        val f = Fixture(backgroundScope); f.complete = false
        f.board.start(); runCurrent()
        assertFalse(f.board.state.value.ready)
        assertNotNull(f.board.state.value.error)
    }
}
