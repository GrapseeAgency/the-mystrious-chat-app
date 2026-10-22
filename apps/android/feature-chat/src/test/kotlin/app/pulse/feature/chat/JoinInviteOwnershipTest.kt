package app.pulse.feature.chat

import app.pulse.domain.model.InviteJoinOutcome
import app.pulse.domain.model.InvitePreview
import app.pulse.domain.model.PulseApiException
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JoinInviteOwnershipTest {
    private class Account {
        var viewer: String? = "owner"
        val previews = mutableListOf<Pair<String, Continuation<Result<InvitePreview>>>>()
        val joins = mutableListOf<Continuation<Result<InviteJoinOutcome>>>()
        @Suppress("UNCHECKED_CAST")
        val repo = Proxy.newProxyInstance(PulseRepository::class.java.classLoader, arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "boundToCurrentAccount" -> proxy
                method.name == "getViewerId" -> viewer
                method.name.startsWith("invitePreview") -> {
                    previews += (args!![0] as String) to (args.last() as Continuation<Result<InvitePreview>>)
                    COROUTINE_SUSPENDED
                }
                method.name.startsWith("joinInvite") -> {
                    joins += args!!.last() as Continuation<Result<InviteJoinOutcome>>
                    COROUTINE_SUSPENDED
                }
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "hashCode" -> System.identityHashCode(proxy)
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as PulseRepository
        fun preview(code: String, result: Result<InvitePreview>) {
            val index = previews.indexOfFirst { it.first == code }
            previews.removeAt(index).second.resume(result)
        }
    }
    private fun preview(code: String) = InvitePreview(code, "room-$code", "Group", 2, false)

    @Test fun `old preview and old disposal cannot replace a newer invite`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = JoinInviteViewModel(account.repo)
            val first = vm.bind("FIRST"); runCurrent()
            val next = vm.bind("NEXT"); runCurrent()
            account.preview("NEXT", Result.success(preview("NEXT"))); runCurrent()
            account.preview("FIRST", Result.success(preview("FIRST"))); runCurrent()
            vm.release(first)
            assertEquals("room-NEXT", vm.state.value.preview?.conversationId)
            vm.release(next)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `join is single flight and delivery can only be consumed once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = JoinInviteViewModel(account.repo)
            val lease = vm.bind("CODE"); runCurrent()
            account.preview("CODE", Result.success(preview("CODE"))); runCurrent()
            vm.join("CODE"); vm.join("CODE"); runCurrent()
            assertEquals(1, account.joins.size)
            account.joins.removeAt(0).resume(Result.success(InviteJoinOutcome("room-CODE", false))); runCurrent()
            val joined = vm.state.value.joined!!
            assertTrue(vm.consumeJoined("CODE", joined))
            assertFalse(vm.consumeJoined("CODE", joined))
            vm.release(lease)
            assertNull(vm.state.value.joined)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `dismissed join and account retirement cannot publish or start work`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = JoinInviteViewModel(account.repo)
            val lease = vm.bind("CODE"); runCurrent()
            account.preview("CODE", Result.success(preview("CODE"))); runCurrent()
            vm.join("CODE"); runCurrent()
            vm.release(lease)
            account.joins.removeAt(0).resume(Result.success(InviteJoinOutcome("room-CODE", false))); runCurrent()
            assertNull(vm.state.value.joined)
            val next = vm.bind("NEXT"); runCurrent()
            account.preview("NEXT", Result.success(preview("NEXT"))); runCurrent()
            account.viewer = "replacement"
            vm.join("NEXT"); runCurrent()
            assertTrue(account.joins.isEmpty())
            vm.release(next)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `offline preview offers retry instead of claiming the link is invalid`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val account = Account(); val vm = JoinInviteViewModel(account.repo)
            val lease = vm.bind("CODE"); runCurrent()
            account.preview("CODE", Result.failure(PulseApiException("NETWORK", "Offline"))); runCurrent()
            assertFalse(vm.state.value.loading)
            assertFalse(vm.state.value.invalid)
            assertNotNull(vm.state.value.error)
            vm.load("CODE"); runCurrent()
            account.preview("CODE", Result.failure(PulseApiException("NOT_FOUND", "Revoked", status = 404))); runCurrent()
            assertTrue(vm.state.value.invalid)
            vm.release(lease)
        } finally { Dispatchers.resetMain() }
    }
}
