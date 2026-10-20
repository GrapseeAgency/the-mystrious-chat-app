package app.pulse.data.repository

import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRepositoryRouterTest {
    private class Account(val owner: String) {
        val draft = MutableStateFlow<String?>("draft-$owner")
        var completed = 0
        var completeRequest: (() -> Unit)? = null
        @Suppress("UNCHECKED_CAST")
        val repo = Proxy.newProxyInstance(PulseRepository::class.java.classLoader,
            arrayOf(PulseRepository::class.java)) { _, method, args ->
            when {
                method.name == "observeDraft" -> draft
                method.name == "getViewerId" -> owner
                method.name == "equals" -> false
                method.name.startsWith("refreshConversations") -> {
                    val continuation = args!!.last() as Continuation<Result<Unit>>
                    completeRequest = { completed++; continuation.resume(Result.success(Unit)) }
                    COROUTINE_SUSPENDED
                }
                else -> null
            }
        } as PulseRepository
    }

    @Test fun observationsRebindAndIgnoreRetiredAccountUpdates() = runTest {
        val guest = Account("guest")
        val a = Account("a")
        val b = Account("b")
        val router = AccountRepositoryRouter(guest.repo, { if (it == "a") a.repo else b.repo }, { guest.repo })
        val seen = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            router.repository.observeDraft("same-room-id").toList(seen)
        }
        router.repository.start("a"); runCurrent()
        assertEquals("draft-a", seen.last())
        router.repository.start("b"); runCurrent()
        assertEquals("draft-b", seen.last())
        a.draft.value = "late private response"; runCurrent()
        assertFalse(seen.contains("late private response"))
        assertTrue(seen.contains(null))
    }

    @Test fun initialSnapshotReadsStoredValueRatherThanSyntheticNull() = runTest {
        val a = Account("a")
        val router = AccountRepositoryRouter(a.repo, { a.repo }, { a.repo })
        assertEquals("draft-a", router.repository.observeDraft("room").first())
    }

    @Test fun suspendedInvocationNeverChangesItsAccountReceiver() = runTest {
        val a = Account("a")
        val b = Account("b")
        val router = AccountRepositoryRouter(a.repo, { b.repo }, { b.repo })
        val request = async { router.repository.refreshConversations() }
        runCurrent()
        router.repository.start("b")
        a.completeRequest!!.invoke()
        assertTrue(request.await().isSuccess)
        assertEquals(1, a.completed)
        assertEquals(0, b.completed)
        assertEquals("b", router.repository.viewerId)
    }
}
