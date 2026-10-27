package app.pulse.core.result

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadReplayPolicyTest {
    @Test fun requestLocalRefusalsPermitIndependentWorkWithoutAuthorizingRetirement() {
        for (status in listOf(400, 403, 404, 409, 410, 422)) {
            val failure = PulseResult.Failure(PulseResult.Failure.Kind.UNKNOWN, status = status)
            assertTrue(failure.allowsFollowingReadObservation())
            assertFalse(failure.retiresRead("owner", "room", "message", "original.member"))
        }
    }

    @Test fun globalFailuresDoNotFanOutThroughTheJournal() {
        for (status in listOf(null, 401, 408, 425, 429, 500, 503)) {
            assertFalse(PulseResult.Failure(PulseResult.Failure.Kind.NETWORK, status = status).allowsFollowingReadObservation())
        }
        assertFalse(PulseResult.Failure(PulseResult.Failure.Kind.AUTH, status = 403).allowsFollowingReadObservation())
        assertFalse(PulseResult.Failure(PulseResult.Failure.Kind.RATE_LIMITED, status = 409).allowsFollowingReadObservation())
    }
}
