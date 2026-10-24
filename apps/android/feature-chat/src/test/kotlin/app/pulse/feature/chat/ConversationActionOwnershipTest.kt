package app.pulse.feature.chat

import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.Conversation
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulsePrefsStore
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationActionOwnershipTest {
    private class Accounts {
        var epoch = "owner-a"
        data class Call(val epoch: String, val room: String, val reply: Continuation<Result<Unit>>)
        val calls = mutableListOf<Call>()
        val notebooks = mutableListOf<Pair<String, Continuation<Result<Conversation>>>>()
        @Suppress("UNCHECKED_CAST")
        fun repository(bound: String? = null): PulseRepository = Proxy.newProxyInstance(PulseRepository::class.java.classLoader,
            arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "boundToCurrentAccount" -> repository(epoch)
                method.name == "attachmentSessionKey" -> bound ?: epoch
                method.name == "getViewerId" -> bound ?: epoch
                method.name == "observeConversations" -> flowOf(emptyList<Conversation>())
                method.name == "observeDrafts" -> flowOf(emptyMap<String, String>())
                method.name == "observePresence" -> flowOf(emptySet<String>())
                method.name == "events" -> emptyFlow<PulseEvent>()
                method.name.startsWith("archive") || method.name.startsWith("markRead") || method.name.startsWith("setMutedUntil") -> {
                    calls += Call(requireNotNull(bound), args!![0] as String, args.last() as Continuation<Result<Unit>>)
                    COROUTINE_SUSPENDED
                }
                method.name.startsWith("createSelfChat") -> {
                    notebooks += requireNotNull(bound) to (args!!.last() as Continuation<Result<Conversation>>)
                    COROUTINE_SUSPENDED
                }
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "hashCode" -> System.identityHashCode(proxy)
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as PulseRepository
        val prefs = Proxy.newProxyInstance(PulsePrefsStore::class.java.classLoader, arrayOf(PulsePrefsStore::class.java)) { _, method, _ ->
            when (method.name) {
                "getChatsListFilter" -> flowOf("all")
                "getSpotlightRecents" -> flowOf(emptyList<String>())
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as PulsePrefsStore
    }
    @Test fun `partial batch retains failures and duplicate taps cannot dispatch overlapping writes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = Accounts(); val vm = ChatsViewModel(accounts.repository(), accounts.prefs)
        try {
            var failed: List<String>? = null
            vm.batchArchive(listOf("one", "two", "one"), true) { failed = it }
            vm.archive("one", false)
            runCurrent(); assertEquals(1, accounts.calls.size)
            accounts.calls[0].reply.resume(Result.success(Unit)); runCurrent()
            assertEquals("two", accounts.calls[1].room)
            accounts.calls[1].reply.resume(Result.failure(IllegalStateException("offline"))); runCurrent()
            assertEquals(listOf("two"), failed)
            assertTrue(vm.notice.value!!.isError)
            assertTrue(vm.notice.value!!.text.contains("Archived 1"))
            assertEquals(setOf("two", "new"), remainingBatchSelection(setOf("one", "two", "new"), setOf("one", "two"), failed!!))
        } finally { vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun `queued writes cannot retarget and retired completion cannot clear a successor gate`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = Accounts(); val vm = ChatsViewModel(accounts.repository(), accounts.prefs)
        try {
            var oldCallback = false
            vm.batchMute8h(listOf("shared", "tail")) { oldCallback = true }
            runCurrent(); assertEquals("owner-a", accounts.calls[0].epoch)
            accounts.epoch = "owner-b"
            vm.archive("shared", true); runCurrent()
            assertEquals("owner-b", accounts.calls[1].epoch)
            accounts.calls[0].reply.resume(Result.success(Unit)); runCurrent()
            assertFalse(oldCallback); assertEquals(2, accounts.calls.size)
            vm.archive("shared", false); runCurrent()
            assertEquals(2, accounts.calls.size)
            accounts.calls[1].reply.resume(Result.success(Unit)); runCurrent()
            vm.archive("fresh", true)
            accounts.epoch = "owner-c"
            runCurrent(); assertEquals(2, accounts.calls.size)
        } finally { vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun `cancellation ignoring replies cannot clear selection after the view model ends`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = Accounts(); val vm = ChatsViewModel(accounts.repository(), accounts.prefs)
        try {
            var completed = false
            vm.batchMarkRead(listOf("room")) { completed = true }
            runCurrent()
            vm.viewModelScope.cancel()
            accounts.calls.single().reply.resume(Result.success(Unit)); runCurrent()
            assertFalse(completed)
        } finally { vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
    @Test fun `notebook taps capture owner and old or cancelled replies cannot navigate or release a successor`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts = Accounts(); val vm = ChatsViewModel(accounts.repository(), accounts.prefs)
        val opened = mutableListOf<String>()
        fun notebook(owner: String) = Conversation("book-$owner", Conversation.Kind.DM, "Note to Self", isSelf = true, memberIds = listOf(owner))
        try {
            vm.createSelfChat { opened += it }; vm.createSelfChat { opened += it }
            runCurrent(); assertEquals(1, accounts.notebooks.size)
            vm.cancelSelfOpening()
            vm.createSelfChat { opened += it }; runCurrent()
            accounts.notebooks[0].second.resume(Result.success(notebook("owner-a"))); runCurrent()
            assertTrue(opened.isEmpty())
            vm.createSelfChat { opened += it }; runCurrent(); assertEquals(2, accounts.notebooks.size)
            accounts.epoch = "owner-b"
            vm.createSelfChat { opened += it }; runCurrent()
            accounts.notebooks[1].second.resume(Result.success(notebook("owner-a"))); runCurrent()
            assertTrue(opened.isEmpty())
            vm.createSelfChat { opened += it }; runCurrent(); assertEquals(3, accounts.notebooks.size)
            accounts.notebooks[2].second.resume(Result.success(notebook("owner-b"))); runCurrent()
            assertEquals(listOf("book-owner-b"), opened)
            vm.createSelfChat { opened += it }; accounts.epoch = "owner-c"; runCurrent()
            assertEquals(3, accounts.notebooks.size)
        } finally { vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

}
