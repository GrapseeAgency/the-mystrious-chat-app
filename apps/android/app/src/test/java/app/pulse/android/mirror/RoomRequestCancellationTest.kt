package app.pulse.android.mirror

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RoomRequestCancellationTest {
    @Test fun `wrapped repository cancellation is not treated as an offline UI result`() = runTest {
        try {
            roomRequest<Unit> { Result.failure(CancellationException("closed sheet")) }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    @Test fun `ordinary repository exception preserves a failure verdict`() = runTest {
        val error = IllegalStateException("offline")
        val result = roomRequest<Unit> { throw error }
        assertTrue(result.isFailure)
        assertSame(error, result.exceptionOrNull())
    }
}
