package app.pulse.domain.sync

import app.pulse.domain.model.Conversation
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
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
        var calls = 0
        var rosters = 0
        var held: Continuation<Result<Boolean>>? = null
        var available = true
        val revisions = MutableStateFlow(0L)
        @Suppress("UNCHECKED_CAST")
        val repository = Proxy.newProxyInstance(PulseRepository::class.java.classLoader, arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "boundToCurrentAccount" -> proxy
                method.name == "attachmentSessionKey" -> epoch
                method.name == "observeReadStateRevision" -> revisions
                method.name.startsWith("refreshMessageReadState") -> {
                    assertEquals("room", args!![0]); assertEquals("message", args[1])
                    calls++
                    if (calls == 1) {
                        held = args.last() as Continuation<Result<Boolean>>
                        COROUTINE_SUSPENDED
                    } else Result.success(available)
                }
                method.name.startsWith("conversationDetail") -> {
                    rosters++
                    Result.failure<Conversation>(IllegalStateException("Offline roster"))
                }
                else -> error("Info must not write a read receipt or fetch history: ${method.name}")
            }
        } as PulseRepository
    }

    @Test fun `bursts retain one trailing GET and fallback stops with the Info collector`() = runTest {
        val gateway = Gateway()
        val seen = mutableListOf<Boolean>()
        val job = backgroundScope.launch { gateway.repository.messageReadStateRefreshes("room", "message").collect { seen += it } }
        runCurrent()
        assertEquals(1, gateway.calls)
        repeat(10) { gateway.revisions.value++; runCurrent() }
        assertEquals(1, gateway.calls, "Hints must not cancel or overlap the in-flight request")
        gateway.held!!.resume(Result.success(true))
        runCurrent()
        assertEquals(2, gateway.calls)
        assertEquals(listOf(true, true), seen)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(3, gateway.calls)
        job.cancel(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, gateway.calls)
    }

    @Test fun `a retired owner cannot publish a suspended result or refresh the replacement roster`() = runTest {
        val gateway = Gateway()
        val seen = mutableListOf<Boolean>()
        val job = backgroundScope.launch { gateway.repository.messageReadStateRefreshes("room", "message").collect { seen += it } }
        runCurrent()
        gateway.epoch = "replacement-owner"
        gateway.held!!.resume(Result.success(false))
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(seen.isEmpty())
        assertEquals(0, gateway.rosters)
    }
}
