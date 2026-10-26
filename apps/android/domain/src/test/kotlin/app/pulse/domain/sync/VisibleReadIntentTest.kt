package app.pulse.domain.sync

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VisibleReadIntentTest {
    @Test fun `distinct visits do not erase or inherit each others queued authority`() {
        val first = readObservationJournalKey("owner", "message", "first.member")
        val fresh = readObservationJournalKey("owner", "message", "after-manual.member")
        assertNotEquals(first, fresh)
        assertEquals(first, readObservationJournalKey("owner", "message", "first.member"))
        assertTrue(first.startsWith("read-journal:v1|owner|"))
        assertNotEquals(first, readObservationJournalKey("other", "message", "first.member"))
    }

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
    @Test fun `navigation after capture begins cannot drop the local journal write`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var saved: String? = null
        val reader = VisibleReadIntent({
            started.complete(Unit)
            finish.await()
            Result.success("original.membership")
        }, { saved = it; Result.success(Unit) })
        val job = launch { reader.send() }
        started.await()
        job.cancel()
        finish.complete(Unit)
        job.join()
        assertEquals("original.membership", saved)
        assertTrue(job.isCancelled)
    }

    @Test fun `already cancelled work never starts an observation`() = runTest {
        var writes = 0
        val reader = VisibleReadIntent({ Result.success("fence") }, { writes++; Result.success(Unit) })
        val job = launch { reader.send() }
        job.cancel()
        runCurrent()
        assertEquals(0, writes)
    }
}
