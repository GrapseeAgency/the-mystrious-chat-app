package app.pulse.feature.hub

import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HubMutationGateTest {
    private fun repository(owner: () -> String): PulseRepository = Proxy.newProxyInstance(
        PulseRepository::class.java.classLoader, arrayOf(PulseRepository::class.java)
    ) { proxy, method, _ ->
        when (method.name) {
            "getViewerId" -> owner()
            "reminderDeliveryOwner" -> null
            "attachmentSessionKey" -> owner()
            "boundToCurrentAccount" -> proxy
            else -> error("Unexpected dependency: ${method.name}")
        }
    } as PulseRepository

    @Test fun `rapid economic actions share a gate acquired before dispatch`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = HubViewModel(repository { "owner" })
            val reply = CompletableDeferred<Result<String>>()
            var completions = 0
            val first = vm.mutateWallet(send = { reply.await() }, receive = { completions++ })!!
            assertTrue(vm.walletMutationBusy.value)
            assertNull(vm.mutateWallet(send = { Result.success("duplicate") }, receive = { completions++ }))
            reply.complete(Result.success("accepted")); first.join()
            assertEquals(1, completions)
            assertFalse(vm.walletMutationBusy.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `failed request releases the gate and owner replacement prevents dispatch`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var owner = "a"
            val vm = HubViewModel(repository { owner })
            var failed = false
            vm.mutateWallet<String>(send = { Result.failure(IllegalStateException("offline")) }, receive = { failed = it.isFailure })!!.join()
            assertTrue(failed); assertFalse(vm.walletMutationBusy.value)
            var sent = false
            val stale = vm.mutateWallet(send = { sent = true; Result.success("no") }, receive = { fail("stale callback") })!!
            owner = "b"
            stale.join()
            assertFalse(sent); assertFalse(vm.walletMutationBusy.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `market draft completion waits for confirmation and duplicate taps do not dispatch`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = HubViewModel(repository { "owner" })
            val reply = CompletableDeferred<String>()
            var sends = 0; var accepted = 0
            val completions = mutableListOf<Boolean>()
            vm.marketAction(operation = { sends++; reply.await() }, accepted = { accepted++ }, completed = { completions += it })
            assertTrue(vm.marketBusy.value)
            vm.marketAction(operation = { sends++; "duplicate" }, accepted = { fail("duplicate") }, completed = { completions += it })
            assertEquals(listOf(false), completions)
            runCurrent(); assertEquals(1, sends); assertEquals(0, accepted)
            reply.complete("confirmed"); advanceUntilIdle()
            assertEquals(1, accepted); assertEquals(listOf(false, true), completions)
            assertFalse(vm.marketBusy.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `failed listing never completes the draft as accepted`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = HubViewModel(repository { "owner" })
            var verdict: Boolean? = null
            vm.marketAction<String>(operation = { throw IllegalStateException("offline") }, accepted = { fail("false success") }, completed = { verdict = it })
            advanceUntilIdle()
            assertEquals(false, verdict); assertFalse(vm.marketBusy.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun `late purchase cannot mutate another session or keep the money gate locked`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var owner = "a"
            val vm = HubViewModel(repository { owner })
            val reply = CompletableDeferred<String>()
            vm.marketAction(spendsMoney = true, operation = { reply.await() }, accepted = { fail("foreign result") }, completed = { fail("foreign callback") })
            assertTrue(vm.marketBusy.value); assertTrue(vm.walletMutationBusy.value)
            runCurrent(); owner = "b"; reply.complete("old purchase"); advanceUntilIdle()
            assertFalse(vm.marketBusy.value); assertFalse(vm.walletMutationBusy.value)
        } finally { Dispatchers.resetMain() }
    }

}
