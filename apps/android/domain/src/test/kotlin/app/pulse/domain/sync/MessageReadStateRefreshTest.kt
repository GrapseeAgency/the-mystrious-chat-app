package app.pulse.domain.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageReadStateRefreshTest {
    private class Gateway {
        var epoch = "first-owner"
        var rosters = 0
        val requests = mutableListOf<CompletableDeferred<Result<Boolean>>>()
        val revisions = MutableStateFlow(0L)
        fun observations() = messageReadStateRefreshFlow(
            owner = { epoch }, revisions = revisions,
            refresh = {
                val response = CompletableDeferred<Result<Boolean>>()
                requests += response
                response.await()
            },
            metadata = { rosters++ },
        )
    }

    @Test fun `bursts retain one trailing GET and fallback stops with the Info collector`() = runTest {
        val gateway = Gateway()
        val seen = mutableListOf<Boolean>()
        val job = backgroundScope.launch { gateway.observations().collect { seen += it } }
        runCurrent()
        assertEquals(1, gateway.requests.size, "Initial canonical request")
        repeat(10) { gateway.revisions.value++; runCurrent() }
        assertEquals(1, gateway.requests.size, "Hints must not cancel or overlap the in-flight request")
        gateway.requests[0].complete(Result.success(true))
        runCurrent()
        assertEquals(2, gateway.requests.size, "Only the latest hint trails the current request")
        gateway.requests[1].complete(Result.success(true))
        runCurrent()
        assertEquals(listOf(true, true), seen)
        assertEquals(2, gateway.requests.size, "No hidden buffered producer may retain another stale hint")
        advanceTimeBy(5_000); runCurrent()
        assertEquals(3, gateway.requests.size, "One fallback request at five seconds")
        gateway.requests[2].complete(Result.success(true))
        runCurrent()
        job.cancel(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, gateway.requests.size, "A disposed Info collector must not poll")
    }

    @Test fun `a retired owner cannot publish a suspended result or refresh the replacement roster`() = runTest {
        val gateway = Gateway()
        val seen = mutableListOf<Boolean>()
        val job = backgroundScope.launch { gateway.observations().collect { seen += it } }
        runCurrent()
        gateway.epoch = "replacement-owner"
        gateway.requests.single().complete(Result.success(false))
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(seen.isEmpty())
        assertEquals(0, gateway.rosters)
    }

    @Test fun `closing Info during a request retires its result and metadata work`() = runTest {
        val gateway = Gateway()
        val seen = mutableListOf<Boolean>()
        val job = backgroundScope.launch { gateway.observations().collect { seen += it } }
        runCurrent()
        job.cancel(); runCurrent()
        gateway.requests.single().complete(Result.success(true))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(seen.isEmpty())
        assertEquals(0, gateway.rosters)
        assertEquals(1, gateway.requests.size)
    }
}
