package app.pulse.feature.chat

import androidx.lifecycle.viewModelScope
import app.pulse.domain.model.FeedPage
import app.pulse.domain.model.SavedItem
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedLibraryRefreshTest {
    @Test fun missedHintsRepairTheFirstPageButDoNotMoveAnOlderPageAndAccessHintsFenceOldResponses() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val hints = MutableSharedFlow<PulseEvent>()
        val calls = mutableListOf<Pair<String?, Continuation<Result<FeedPage<SavedItem>>>>>()
        @Suppress("UNCHECKED_CAST")
        fun repository(): PulseRepository = Proxy.newProxyInstance(PulseRepository::class.java.classLoader,
            arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "boundToCurrentAccount" -> repository()
                method.name == "attachmentSessionKey" -> "owner"
                method.name == "getViewerId" -> "owner"
                method.name == "observeSavedSyncStatus" -> flowOf<String?>(null)
                method.name == "observeSavedPage" -> flowOf(emptyList<SavedItem>())
                method.name == "events" -> hints
                method.name.startsWith("savedLibraryPage") -> {
                    calls += (args!![0] as String?) to (args.last() as Continuation<Result<FeedPage<SavedItem>>>)
                    COROUTINE_SUSPENDED
                }
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "hashCode" -> System.identityHashCode(proxy)
                else -> error(method.name)
            }
        } as PulseRepository
        val vm = SavedLibraryViewModel(repository())
        fun answer(index: Int, next: String? = "older") {
            calls[index].second.resume(Result.success(FeedPage(emptyList(), next, next != null)))
        }
        try {
            runCurrent(); answer(0); runCurrent()
            vm.repairFirstPage(); runCurrent(); assertEquals(2, calls.size)
            answer(1); runCurrent()
            vm.page(older = true); runCurrent(); assertEquals("older", calls[2].first)
            answer(2, null); runCurrent()
            vm.repairFirstPage(); runCurrent(); assertEquals(3, calls.size)
            vm.refresh(); runCurrent()
            hints.emit(PulseEvent.PrivateFeedsChanged); runCurrent()
            assertEquals(5, calls.size)
            answer(3, "stale-next"); runCurrent()
            assertTrue(vm.state.value.loading)
            answer(4, null); runCurrent()
            assertFalse(vm.state.value.loading); assertFalse(vm.state.value.next)
            assertEquals("older", calls[4].first)
        } finally { vm.viewModelScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
}
