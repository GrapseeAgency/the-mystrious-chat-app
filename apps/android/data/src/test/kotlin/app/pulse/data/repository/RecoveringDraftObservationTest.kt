package app.pulse.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveringDraftObservationTest {
    @Test fun laterRoomFailureDoesNotStopNewBuffersAndObservationResubscribes() = runTest {
        val buffers = MutableStateFlow(0)
        val disk = MutableStateFlow("old")
        var fail = false
        var subscriptions = 0
        var buffer: String? = null
        val seen = mutableListOf<String?>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            recoveringDraftObservation(buffers, database = { flow {
                subscriptions++
                disk.collect { if (fail) error("Room observer failed") else emit(it) }
            } }) { buffer ?: if (fail) error("query unavailable") else disk.value }.collect { seen.add(it) }
        }
        assertEquals("old", seen.last())
        fail = true; disk.value = "unreadable"; runCurrent()
        buffer = "typed during outage"; buffers.value++; runCurrent()
        assertEquals("typed during outage", seen.last())
        buffer = ""; buffers.value++; runCurrent()
        assertEquals("", seen.last())
        fail = false; disk.value = "recovered"; buffer = null
        advanceTimeBy(1_001); runCurrent()
        assertTrue(subscriptions >= 2)
        assertEquals("recovered", seen.last())
        observer.cancel()
    }

    @Test fun snapshotFailureDoesNotInventClearAndRecoversWithoutAnotherRoomEmission() = runTest {
        val buffers = MutableStateFlow(0)
        var broken = false
        val seen = mutableListOf<String?>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            recoveringDraftObservation(buffers, database = { MutableStateFlow(Unit) }) {
                if (broken) error("snapshot failed") else "retained"
            }.collect { seen.add(it) }
        }
        broken = true; buffers.value++; runCurrent()
        assertFalse(seen.contains(null))
        broken = false; val count = seen.size
        advanceTimeBy(5_001); runCurrent()
        assertTrue(seen.size > count)
        observer.cancel(); val cancelledCount = seen.size
        advanceTimeBy(10_000); runCurrent()
        assertEquals(cancelledCount, seen.size)
    }
}
