package app.pulse.data.remote

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RetryAfterTest {
    @Test fun `retry headers accept seconds and server-relative date despite phone clock skew`() {
        assertEquals(120, retryAfterSeconds("120", null))
        assertEquals(120, retryAfterSeconds("Tue, 20 Oct 2026 12:02:00 GMT", "Tue, 20 Oct 2026 12:00:00 GMT", Instant.EPOCH))
        assertNull(retryAfterSeconds("broken", null))
        assertNull(retryAfterSeconds("-3", null))
    }
}
