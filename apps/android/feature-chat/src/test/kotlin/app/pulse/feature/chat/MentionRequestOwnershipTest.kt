package app.pulse.feature.chat

import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.MentionItem
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MentionRequestOwnershipTest {
    @Test fun oldOwnerAndSupersededRefreshCannotReplaceTheCurrentFeed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var owner = "first"
        val calls = mutableListOf<Pair<String, Continuation<Result<List<MentionItem>>>>>()
        @Suppress("UNCHECKED_CAST")
        fun repository(bound: String? = null): PulseRepository = Proxy.newProxyInstance(PulseRepository::class.java.classLoader,
            arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "boundToCurrentAccount" -> repository(owner)
                method.name == "attachmentSessionKey" -> bound ?: owner
                method.name.startsWith("mentions") -> {
                    calls += requireNotNull(bound) to (args!!.last() as Continuation<Result<List<MentionItem>>>)
                    COROUTINE_SUSPENDED
                }
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "hashCode" -> System.identityHashCode(proxy)
                else -> error(method.name)
            }
        } as PulseRepository
        val vm = MentionsViewModel(repository())
        try {
            runCurrent(); owner = "second"; vm.refresh(); runCurrent()
            calls[1].second.resume(Result.success(listOf(MentionItem("current", "room")))); runCurrent()
            calls[0].second.resume(Result.success(listOf(MentionItem("old", "room")))); runCurrent()
            assertEquals(listOf("current"), vm.state.value.items.map { it.messageId })
            vm.refresh(); runCurrent(); calls[2].second.resume(Result.failure(IllegalStateException("offline"))); runCurrent()
            assertTrue(vm.state.value.error)
        } finally { vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
}
