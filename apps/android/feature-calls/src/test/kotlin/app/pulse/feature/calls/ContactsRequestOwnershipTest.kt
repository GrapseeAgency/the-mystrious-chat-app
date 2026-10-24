package app.pulse.feature.calls

import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsRequestOwnershipTest {
    private class Accounts {
        var epoch = "a"
        data class Read(val owner: String, val query: String, val reply: Continuation<Result<List<User>>>)
        data class Dm(val owner: String, val reply: Continuation<Result<Conversation>>)
        val reads = mutableListOf<Read>()
        val dms = mutableListOf<Dm>()
        @Suppress("UNCHECKED_CAST")
        fun repository(bound: String? = null): PulseRepository = Proxy.newProxyInstance(PulseRepository::class.java.classLoader,
            arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "attachmentSessionKey" -> bound ?: epoch
                method.name == "boundToCurrentAccount" -> repository(epoch)
                method.name == "observePresence" -> flowOf(emptySet<String>())
                method.name.startsWith("users") -> {
                    reads += Read(requireNotNull(bound), args!![0] as String, args.last() as Continuation<Result<List<User>>>); COROUTINE_SUSPENDED
                }
                method.name.startsWith("createDm") -> {
                    dms += Dm(requireNotNull(bound), args!!.last() as Continuation<Result<Conversation>>); COROUTINE_SUSPENDED
                }
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "hashCode" -> System.identityHashCode(proxy)
                else -> error("Unexpected call ${method.name}")
            }
        } as PulseRepository
    }
    private val peer = User("peer", "Peer", "peer")
    private val room = Conversation("room", Conversation.Kind.DM, "Peer")

    @Test fun `latest search wins cancelled old reads and failed refresh retains real rows`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = Accounts(); val model = ContactsViewModel(accounts.repository())
        try {
            runCurrent(); model.query("peer"); runCurrent()
            accounts.reads[1].reply.resume(Result.success(listOf(peer))); runCurrent()
            accounts.reads[0].reply.resume(Result.success(emptyList())); runCurrent()
            assertEquals(listOf(peer), model.users.value)
            model.refresh(); runCurrent()
            assertEquals("peer", accounts.reads[2].query)
            accounts.reads[2].reply.resume(Result.failure(Exception("offline"))); runCurrent()
            assertEquals(listOf(peer), model.users.value); assertNotNull(model.state.value.error)
            accounts.epoch = "b"; model.query("next"); runCurrent()
            assertTrue(model.users.value.isEmpty())
            accounts.reads[3].reply.resume(Result.success(emptyList())); runCurrent()
            assertEquals("b", accounts.reads[3].owner)
        } finally { model.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun `DM captures at tap blocks duplicate and old completion cannot navigate or clear newer busy`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = Accounts(); val model = ContactsViewModel(accounts.repository())
        try {
            runCurrent(); accounts.reads[0].reply.resume(Result.success(listOf(peer))); runCurrent()
            model.openDm(peer); model.openDm(peer); runCurrent()
            assertEquals(1, accounts.dms.size)
            accounts.epoch = "b"; model.openDm(peer); runCurrent()
            accounts.dms[0].reply.resume(Result.success(room)); runCurrent()
            assertNull(model.dmResult.value); assertTrue(model.state.value.dmOpening)
            accounts.dms[1].reply.resume(Result.success(room)); runCurrent()
            assertEquals(ContactsViewModel.DmResult.Ready("room"), model.dmResult.value)
            assertFalse(model.state.value.dmOpening)
            model.consumeDmResult()
            model.openDm(peer); accounts.epoch = "c"; runCurrent()
            assertEquals(listOf("a", "b"), accounts.dms.map { it.owner })
            assertNull(model.dmResult.value)
        } finally { model.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
}
