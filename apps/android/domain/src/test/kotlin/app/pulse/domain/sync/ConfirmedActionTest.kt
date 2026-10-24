package app.pulse.domain.sync

import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfirmedActionTest {
    @Test fun `refusal cannot persist a flag or invoke a success dismissal`() = runTest {
        var applied = false
        val failure = IllegalStateException("offline or refused")
        val result = confirmedAction({ Result.failure<Unit>(failure) }) { applied = true }
        assertSame(failure, result.exceptionOrNull())
        assertFalse(applied)
    }
    @Test fun `confirmed result is applied once with its actual payload`() = runTest {
        val applied = mutableListOf<String>()
        val result = confirmedAction({ Result.success("file.txt") }) { applied += it }
        assertEquals("file.txt", result.getOrThrow())
        assertEquals(listOf("file.txt"), applied)
    }
    @Test fun `adapter ignoring cancellation cannot persist or dismiss afterwards`() = runTest {
        var applied = false
        val job = launch {
            confirmedAction({ currentCoroutineContext().cancel(); Result.success(Unit) }) { applied = true }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(applied)
    }
    @Test fun `failed local apply cannot be reported as a completed action`() = runTest {
        val result = runCatching { confirmedAction({ Result.success(Unit) }) { error("storage unavailable") } }
        assertTrue(result.isFailure)
    }
    @Test fun `already cancelled caller never starts the mutation`() = runTest {
        var dispatched = false
        val job = launch {
            currentCoroutineContext().cancel()
            confirmedAction({ dispatched = true; Result.success(Unit) }) { error("Must not apply") }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(dispatched)
    }

}
