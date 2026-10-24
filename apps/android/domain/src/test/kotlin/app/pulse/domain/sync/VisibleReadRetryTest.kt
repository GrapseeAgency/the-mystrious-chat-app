package app.pulse.domain.sync

import app.pulse.domain.model.PulseApiException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VisibleReadRetryTest {
    @Test fun `temporary outage can recover beyond the old three attempt cutoff`() = runTest {
        val waits = mutableListOf<Long>()
        var sends = 0
        acknowledgeVisibleRead({ true }, {
            sends++
            if (sends < 9) Result.failure(PulseApiException("SERVER", "busy", 503)) else Result.success(Unit)
        }, { waits += it })
        assertEquals(9, sends)
        assertEquals(listOf(600L, 1500L, 3000L, 6000L, 12000L, 24000L, 48000L, 60000L, 60000L), waits)
    }
    @Test fun `retry stops when viewport or message lifetime no longer qualifies`() = runTest {
        var visible = true
        var sends = 0
        acknowledgeVisibleRead({ visible }, {
            sends++; Result.failure(PulseApiException("NETWORK", "offline"))
        }, { wait -> if (wait != 600L) visible = false })
        assertEquals(1, sends)
    }
    @Test fun `refusals are not polled and retry-after is honored`() = runTest {
        for (status in listOf(400, 401, 403, 404, 410, 422)) {
            var sends = 0
            acknowledgeVisibleRead({ true }, { sends++; Result.failure(PulseApiException("UNKNOWN", "refused", status)) }, {})
            assertEquals(1, sends)
        }
        for (status in listOf(408, 409, 425, 429, 500, 503)) {
            assertNotNull(visibleReadRetryDelay(PulseApiException("UNKNOWN", "retry", status), 8))
        }
        assertEquals(120000L, visibleReadRetryDelay(PulseApiException("RATE_LIMITED", "wait", 429, 120), 1))
        assertEquals(60000L, visibleReadRetryDelay(IllegalStateException("transport"), Int.MAX_VALUE))
    }
    @Test fun `cancellation prevents dispatch and stops a retry pause`() = runTest {
        var sends = 0
        val job = launch {
            acknowledgeVisibleRead({ true }, { sends++; Result.failure(IllegalStateException("offline")) }, { wait ->
                if (wait != 600L) currentCoroutineContext().cancel()
            })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, sends)
        val canceled = launch {
            currentCoroutineContext().cancel()
            acknowledgeVisibleRead({ true }, { sends++; Result.success(Unit) }, {})
        }
        canceled.join()
        assertEquals(1, sends)
    }
}
