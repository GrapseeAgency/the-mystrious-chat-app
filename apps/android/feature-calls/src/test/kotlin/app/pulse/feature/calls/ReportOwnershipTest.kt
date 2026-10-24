package app.pulse.feature.calls

import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportOwnershipTest {
    private class Accounts {
        var epoch = "a"
        val feeds = mutableListOf<Continuation<Result<List<User>>>>()
        val replies = mutableListOf<Continuation<Result<Unit>>>()
        val owners = mutableListOf<String>()
        @Suppress("UNCHECKED_CAST")
        fun repository(bound: String? = null): PulseRepository = Proxy.newProxyInstance(PulseRepository::class.java.classLoader,
            arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "attachmentSessionKey" -> bound ?: epoch
                method.name == "boundToCurrentAccount" -> repository(epoch)
                method.name == "observePresence" -> flowOf(emptySet<String>())
                method.name == "events" -> emptyFlow<PulseEvent>()
                method.name.startsWith("users") -> {
                    feeds += args!!.last() as Continuation<Result<List<User>>>; COROUTINE_SUSPENDED
                }
                method.name.startsWith("report") -> {
                    owners += requireNotNull(bound)
                    replies += args!!.last() as Continuation<Result<Unit>>; COROUTINE_SUSPENDED
                }
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "hashCode" -> System.identityHashCode(proxy)
                else -> error("Unexpected call ${method.name}")
            }
        } as PulseRepository
    }

    @Test fun `report failure keeps form open and late owner acknowledgement cannot close a successor`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = Accounts(); val model = ContactsViewModel(accounts.repository())
        val peer = User(id = "peer", name = "Peer", handle = "peer")
        var dismissed = 0
        try {
            runCurrent(); accounts.feeds[0].resume(Result.success(emptyList())); runCurrent()
            model.report(peer, "spam", "evidence", "a") { dismissed++ }
            model.report(peer, "spam", "evidence", "a") { dismissed++ }
            runCurrent(); assertEquals(1, accounts.replies.size)
            accounts.replies[0].resume(Result.failure(Exception("offline"))); runCurrent()
            assertEquals(0, dismissed); assertNotNull(model.state.value.safetyError)
            assertTrue(model.state.value.safetyPending.isEmpty())
            model.report(peer, "spam", "evidence", "a") { dismissed++ }; runCurrent()
            accounts.epoch = "b"
            model.report(peer, "spam", "evidence", "a") { dismissed++ }; runCurrent()
            assertEquals(2, accounts.replies.size) // stale form cannot submit as B
            model.report(peer, "spam", "evidence", "b") { dismissed++ }; runCurrent()
            accounts.replies[1].resume(Result.success(Unit)); runCurrent()
            assertEquals(0, dismissed); assertEquals(setOf("peer"), model.state.value.safetyPending)
            accounts.replies[2].resume(Result.success(Unit)); runCurrent()
            assertEquals(1, dismissed); assertTrue(model.state.value.safetyPending.isEmpty())
            assertEquals(listOf("a", "a", "b"), accounts.owners)
        } finally { model.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
}
