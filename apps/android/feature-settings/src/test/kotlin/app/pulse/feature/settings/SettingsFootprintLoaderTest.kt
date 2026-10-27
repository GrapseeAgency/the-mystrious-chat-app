package app.pulse.feature.settings

import app.pulse.domain.model.UserStats
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsFootprintLoaderTest {
    private class Account {
        var owner = "first"
        var epoch = "first-session"
        val reads = mutableListOf<Continuation<Result<UserStats>>>()
        @Suppress("UNCHECKED_CAST")
        val repo = Proxy.newProxyInstance(PulseRepository::class.java.classLoader, arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "boundToCurrentAccount" -> proxy
                method.name == "getViewerId" -> owner
                method.name == "attachmentSessionKey" -> epoch
                method.name.startsWith("userStats") -> { reads += args!!.last() as Continuation<Result<UserStats>>; COROUTINE_SUSPENDED }
                else -> error("Unexpected call ${method.name}")
            }
        } as PulseRepository
    }
    @Test fun queuedRefreshCannotChooseAReplacementAccount() = runTest {
        val account = Account(); val loader = SettingsFootprintLoader(account.repo, backgroundScope)
        loader.refresh("first")
        account.owner = "second"; account.epoch = "replacement-session"
        runCurrent()
        assertTrue(account.reads.isEmpty()); assertNull(loader.state.value.stats)
        assertFalse(loader.state.value.loading)
    }
    @Test fun olderCancellationIgnoringResponseCannotReplaceNewerStats() = runTest {
        val account = Account(); val loader = SettingsFootprintLoader(account.repo, backgroundScope)
        loader.refresh("first"); runCurrent()
        loader.refresh("first"); runCurrent()
        account.reads[1].resume(Result.success(UserStats(messages = 22))); runCurrent()
        account.reads[0].resume(Result.success(UserStats(messages = 11))); runCurrent()
        assertEquals(22L, loader.state.value.stats?.messages)
        assertFalse(loader.state.value.loading); assertNull(loader.state.value.error)
    }
    @Test fun sameUserNewSessionCannotAcceptInFlightStatistics() = runTest {
        val account = Account(); val loader = SettingsFootprintLoader(account.repo, backgroundScope)
        loader.refresh("first"); runCurrent()
        account.epoch = "new-session"
        account.reads.single().resume(Result.success(UserStats(messages = 88))); runCurrent()
        assertNull(loader.state.value.stats); assertNotNull(loader.state.value.error)
        assertFalse(loader.state.value.loading)
    }
    @Test fun failureIsNotZeroAndMismatchedDisplayedViewerDoesNotDispatch() = runTest {
        val account = Account(); val loader = SettingsFootprintLoader(account.repo, backgroundScope)
        loader.refresh("other"); runCurrent(); assertTrue(account.reads.isEmpty())
        loader.refresh("first"); runCurrent()
        account.reads.single().resume(Result.failure(IllegalStateException("offline"))); runCurrent()
        assertNull(loader.state.value.stats); assertEquals("offline", loader.state.value.error)
        assertFalse(loader.state.value.loading)
        loader.reset(); assertNull(loader.state.value.error)
    }
}
