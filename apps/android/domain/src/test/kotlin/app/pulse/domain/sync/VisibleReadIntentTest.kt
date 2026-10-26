package app.pulse.domain.sync

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VisibleReadIntentTest {
    @Test fun `lost ACK and reconnect retry retain the original fence even after server intent changes`() = runTest {
        var revision = "before-manual-unread"
        var captures = 0
        val sent = mutableListOf<String>()
        val intent = VisibleReadIntent({ captures++; Result.success(revision) }, {
            sent += it
            if (sent.size == 1) Result.failure(IllegalStateException("ACK lost")) else Result.success(Unit)
        })
        assertTrue(intent.send().isFailure)
        revision = "after-manual-unread"
        assertTrue(intent.send().isSuccess)
        assertEquals(1, captures)
        assertEquals(listOf("before-manual-unread", "before-manual-unread"), sent)
    }
    @Test fun `failed preparation cannot dispatch and a genuinely new reader captures fresh intent`() = runTest {
        var attempts = 0; var writes = 0
        val reader = VisibleReadIntent({ if (++attempts == 1) Result.failure(Exception("offline")) else Result.success("fresh") }, {
            assertEquals("fresh", it); writes++; Result.success(Unit)
        })
        assertTrue(reader.send().isFailure); assertEquals(0,writes)
        assertTrue(reader.send().isSuccess); assertEquals(1,writes)
    }
}
