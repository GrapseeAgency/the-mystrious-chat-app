package app.pulse.domain.sync

import app.pulse.domain.model.Message
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedContentDeliveryTest {
    private class Account {
        var owner = "first"
        val sends = mutableListOf<Continuation<Result<Message>>>()
        val destinations = mutableListOf<String>()
        val clientIds = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        val repo = Proxy.newProxyInstance(PulseRepository::class.java.classLoader, arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "attachmentSessionKey" -> owner
                method.name == "boundToCurrentAccount" -> proxy
                method.name.startsWith("queueSharedImage") -> {
                    destinations += args!![0] as String
                    clientIds += args[1] as String
                    sends += args.last() as Continuation<Result<Message>>; COROUTINE_SUSPENDED
                }
                else -> error("Unexpected call ${method.name}")
            }
        } as PulseRepository
    }
    private fun message() = Message(id = "local-test", conversationId = "room", authorId = "first", authorName = "First", kind = Message.Kind.IMAGE, body = "", createdAt = "2026-10-24T00:00:00Z")

    @Test fun `account switch during preparation prevents any queue write`() = runTest {
        val account = Account(); val delivery = SharedContentDelivery(account.repo)
        val prepared = CompletableDeferred<Result<String>>()
        val attempt = async { delivery.image("room") { prepared.await() } }
        runCurrent(); account.owner = "replacement"; prepared.complete(Result.success("photo")); runCurrent()
        assertTrue(attempt.isCancelled); assertTrue(account.sends.isEmpty())
    }
    @Test fun `one presentation cannot send concurrently to two destinations and retries reuse prepared bytes and identity`() = runTest {
        val account = Account(); val delivery = SharedContentDelivery(account.repo)
        var preparations = 0
        val first = async { delivery.image("room") { preparations++; Result.success("photo") } }
        runCurrent()
        assertTrue(delivery.image("other") { error("must not prepare concurrently") }.isFailure)
        account.sends.single().resume(Result.failure(IllegalStateException("disk busy")))
        assertTrue(first.await().isFailure)
        val retry = async { delivery.image("room") { error("must reuse prepared photo") } }; runCurrent()
        assertEquals(1, preparations)
        assertEquals(account.clientIds.first(), account.clientIds.last())
        account.sends.last().resume(Result.success(message())); assertTrue(retry.await().isSuccess)
        assertTrue(delivery.image("other") { error("already queued") }.isFailure)
        assertEquals(listOf("room", "room"), account.destinations)
    }
    @Test fun `cancelled share cannot queue a cancellation ignoring preparation result`() = runTest {
        val account = Account(); val delivery = SharedContentDelivery(account.repo)
        var prepared: Continuation<Result<String>>? = null
        val attempt = async { delivery.image("room") { suspendCoroutine { prepared = it } } }
        runCurrent(); attempt.cancel(); prepared!!.resume(Result.success("photo")); runCurrent()
        assertTrue(attempt.isCancelled); assertTrue(account.sends.isEmpty())
    }
    @Test fun `oversized shared text is refused without truncation or dispatch`() = runTest {
        val account = Account(); val delivery = SharedContentDelivery(account.repo)
        assertTrue(delivery.text("room", "x".repeat(2001)).isFailure)
        assertTrue(delivery.text("room", "   ").isFailure)
    }
}
