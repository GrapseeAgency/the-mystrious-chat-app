package app.pulse.feature.settings

import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.BlockedAccount
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockedListOwnershipTest {
    private class Accounts {
        var epoch = "a"
        val reads = mutableListOf<Continuation<Result<List<BlockedAccount>>>>()
        val writes = mutableListOf<Continuation<Result<Unit>>>()
        val writeOwners = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        fun repository(bound: String? = null): PulseRepository = Proxy.newProxyInstance(PulseRepository::class.java.classLoader,
            arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "attachmentSessionKey" -> bound ?: epoch
                method.name == "boundToCurrentAccount" -> repository(epoch)
                method.name.startsWith("blockedAccounts") -> {
                    reads += args!!.last() as Continuation<Result<List<BlockedAccount>>>; COROUTINE_SUSPENDED
                }
                method.name.startsWith("unblock") -> {
                    writeOwners += requireNotNull(bound)
                    writes += args!!.last() as Continuation<Result<Unit>>; COROUTINE_SUSPENDED
                }
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "hashCode" -> System.identityHashCode(proxy)
                else -> error("Unexpected call ${method.name}")
            }
        } as PulseRepository
    }
    private val row = BlockedAccount("peer", "Peer")

    @Test fun `failed refresh and unblock retain rows and expose errors without duplicate dispatch`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = Accounts(); val model = BlockedListViewModel(accounts.repository())
        try {
            runCurrent(); accounts.reads[0].resume(Result.success(listOf(row))); runCurrent()
            model.refresh(); runCurrent(); accounts.reads[1].resume(Result.failure(Exception("offline"))); runCurrent()
            assertEquals(listOf(row), model.state.value.rows); assertTrue(model.state.value.error)
            model.unblock("peer"); model.unblock("peer"); runCurrent()
            assertEquals(1, accounts.writes.size)
            accounts.writes[0].resume(Result.failure(Exception("offline"))); runCurrent()
            assertEquals(listOf(row), model.state.value.rows); assertTrue(model.state.value.error)
            assertNull(model.state.value.busyId)
        } finally { model.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun `prewrite reads and old owner completions cannot undo confirmed removal or clear newer busy state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = Accounts(); val model = BlockedListViewModel(accounts.repository())
        try {
            runCurrent(); accounts.reads[0].resume(Result.success(listOf(row))); runCurrent()
            model.refresh(); runCurrent()
            model.unblock("peer"); runCurrent()
            accounts.writes[0].resume(Result.success(Unit)); runCurrent()
            accounts.reads[1].resume(Result.success(listOf(row))); runCurrent()
            assertTrue(model.state.value.rows.isEmpty())
            model.refresh(); runCurrent(); accounts.reads[2].resume(Result.success(listOf(row))); runCurrent()
            model.unblock("peer"); runCurrent()
            accounts.epoch = "b"; model.refresh(); runCurrent()
            assertTrue(model.state.value.rows.isEmpty())
            accounts.reads[3].resume(Result.success(listOf(row))); runCurrent()
            model.unblock("peer"); runCurrent()
            accounts.writes[1].resume(Result.success(Unit)); runCurrent()
            assertEquals("peer", model.state.value.busyId); assertEquals(listOf(row), model.state.value.rows)
            accounts.writes[2].resume(Result.success(Unit)); runCurrent()
            assertEquals(listOf("a", "a", "b"), accounts.writeOwners)
            assertTrue(model.state.value.rows.isEmpty()); assertNull(model.state.value.busyId)
        } finally { model.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
}
