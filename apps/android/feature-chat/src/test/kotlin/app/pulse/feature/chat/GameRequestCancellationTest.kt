package app.pulse.feature.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRequestCancellationTest {
    @Test fun `ordinary errors return failure`() = runTest {
        assertTrue(gameRequest { error("Offline") }.isFailure)
    }
    @Test fun `cancellation is not an ordinary failed move`() = runTest {
        var returned = false
        val job = launch {
            gameRequest<Int> { throw CancellationException("Left room") }
            returned = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
    }
    @Test fun `callback ignoring cancellation cannot publish its reply`() = runTest {
        var published = false
        val job = launch {
            gameRequest { currentCoroutineContext().cancel(); "late reply" }
            published = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(published)
    }
    @Test fun `private access refusal is preserved for retiring the board rather than hidden as offline`() = runTest {
        val denial = app.pulse.domain.sync.PrivateGameUnavailableException("Membership revoked")
        val result = gameRequest<Int> { throw denial }
        assertTrue(result.exceptionOrNull() === denial)
    }

}
