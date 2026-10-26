package app.pulse.domain.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResumedRoomReconciliationTest {
    @Test fun `each resume refreshes even without relay and only rejoin triggers another pass`() = runTest {
        val connection = MutableSharedFlow<Boolean>(replay = 1)
        connection.emit(false)
        var calls = 0
        val firstVisit = launch { reconcileResumedConnections(connection) { calls++ } }
        runCurrent()
        assertEquals(1, calls)
        connection.emit(false); runCurrent()
        assertEquals(1, calls)
        connection.emit(true); runCurrent()
        assertEquals(2, calls)
        connection.emit(true); runCurrent()
        assertEquals(2, calls)
        connection.emit(false); runCurrent()
        assertEquals(2, calls)
        firstVisit.cancelAndJoin()
        val nextResume = launch { reconcileResumedConnections(connection) { calls++ } }
        runCurrent()
        assertEquals(3, calls)
        nextResume.cancelAndJoin()
        connection.emit(true); runCurrent()
        assertEquals(3, calls, "Leaving the resumed surface removes its collector")
    }

    @Test fun `failed refresh keeps the collector available for a later reconnection`() = runTest {
        val connection = MutableSharedFlow<Boolean>(replay = 1)
        connection.emit(false)
        var calls = 0
        val task = launch { reconcileResumedConnections(connection) { calls++; error("HTTP unavailable") } }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(task.isActive)
        connection.emit(true); runCurrent()
        assertEquals(2, calls)
        task.cancelAndJoin()
    }

    @Test fun `disconnect and background cancel in flight work without overlap`() = runTest {
        val connection = MutableSharedFlow<Boolean>(replay = 1)
        connection.emit(true)
        var active = 0
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val task = launch {
            reconcileResumedConnections(connection) {
                calls++
                active++
                assertEquals(1, active)
                entered.complete(Unit)
                try { awaitCancellation() } finally { active-- }
            }
        }
        entered.await()
        connection.emit(false); runCurrent()
        assertEquals(0, active)
        connection.emit(true); runCurrent()
        assertEquals(2, calls)
        assertEquals(1, active)
        task.cancelAndJoin()
        assertEquals(0, active)
        assertEquals(0, connection.subscriptionCount.value)
    }
}
