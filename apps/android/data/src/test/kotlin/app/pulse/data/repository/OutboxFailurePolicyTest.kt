package app.pulse.data.repository

import app.pulse.core.result.PulseResult
import app.pulse.domain.model.OutboxFailureClass
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxFailurePolicyTest {
    @Test fun `invalid and conflicting envelopes are retained as rejected instead of blocking retries forever`() {
        for (status in listOf(400, 403, 404, 409, 410, 413, 415, 422)) {
            assertEquals(OutboxFailureClass.DROP, PulseResult.fromHttp(status).copy(status = status).outboxFailureClass())
        }
    }
    @Test fun `authentication timeout rate limit and server failures remain retryable`() {
        for (status in listOf(401, 408, 425, 429, 500, 503)) {
            assertEquals(OutboxFailureClass.RETRY, PulseResult.fromHttp(status).copy(status = status).outboxFailureClass())
        }
    }
    @Test fun `only an explicit message endpoint slow mode verdict can be bypassed`() {
        val failure = PulseResult.Failure(PulseResult.Failure.Kind.RATE_LIMITED, status = 429, code = "slow_mode")
        assertEquals(OutboxFailureClass.ROOM_RETRY, failure.outboxFailureClass(messageEndpoint = true))
        assertEquals(OutboxFailureClass.RETRY, failure.outboxFailureClass()) // upload response
        assertEquals(OutboxFailureClass.RETRY, failure.copy(code = null).outboxFailureClass(messageEndpoint = true))
        assertEquals(OutboxFailureClass.RETRY, failure.copy(status = 503).outboxFailureClass(messageEndpoint = true))
    }

}
