package app.pulse.feature.settings

import app.pulse.domain.model.ComposerDraftToken
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerManagerSnapshotTest {
    private class Account(val epoch: String, val owner: String = "same-user") {
        var text = "same text"
        var revision = 0L
        var clearCalls = 0
        val changes = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val repo: PulseRepository = Proxy.newProxyInstance(
            PulseRepository::class.java.classLoader, arrayOf(PulseRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getViewerId" -> owner
                "attachmentSessionKey" -> epoch
                "captureDraftToken" -> ComposerDraftToken(args!![0] as String, owner, revision)
                "clearDraftIfUnchanged" -> {
                    clearCalls++
                    val token = args!![0] as ComposerDraftToken
                    if (token.ownerId == owner && token.revision == revision && args[1] == text) {
                        text = ""; revision++; true
                    } else false
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                else -> error("Unexpected account call: ${method.name}")
            }
        } as PulseRepository
        fun edit(value: String) { text = value; revision++ }
    }

    private class Router(initial: Account) {
        var account = initial
        val bindings = MutableStateFlow(initial.repo)
        fun switch(to: Account) { account = to; bindings.value = to.repo }
        val repo: PulseRepository = Proxy.newProxyInstance(
            PulseRepository::class.java.classLoader, arrayOf(PulseRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getViewerId" -> account.owner
                "attachmentSessionKey" -> account.epoch
                "observeAccountRepository" -> bindings
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                else -> error("The manager must not dispatch through a mutable router: ${method.name}")
            }
        } as PulseRepository
    }

    @Test fun displayedRevisionRejectsSameTextABAThatRecapturingAtDeleteWouldConsume() = runTest {
        val account = Account("session-a"); val router = Router(account)
        val displayed = ComposerManagerOwner.capture(account.repo).draft("room", account.text)
        account.edit("different text")
        account.edit("same text")
        assertFalse(displayed.clearIfCurrent(router.repo))
        assertEquals("same text", account.text)
        assertEquals(0L, displayed.token?.revision)
        // Negative control for the old UI: its late recapture incorrectly authorizes this deletion.
        val recaptured = checkNotNull(account.repo.captureDraftToken("room"))
        assertTrue(account.repo.clearDraftIfUnchanged(recaptured, displayed.text))
    }

    @Test fun unchangedDisplayedRevisionCanBeClearedWithoutRecapture() = runTest {
        val account = Account("session-a"); val router = Router(account)
        val displayed = ComposerManagerOwner.capture(account.repo).draft("room", account.text)
        assertTrue(displayed.clearIfCurrent(router.repo))
        assertEquals("", account.text)
        assertFalse(displayed.clearIfCurrent(router.repo))
    }

    @Test fun sameUserAndMatchingTextOnAnotherAccountSessionCannotAuthorizeOldRows() = runTest {
        val old = Account("origin-a-session-1"); val replacement = Account("origin-b-session-1")
        val router = Router(old)
        val displayed = ComposerManagerOwner.capture(old.repo).draft("shared-room", old.text)
        router.switch(replacement)
        assertFalse(displayed.clearIfCurrent(router.repo))
        assertEquals(0, old.clearCalls)
        assertEquals(0, replacement.clearCalls)
        assertEquals("same text", replacement.text)
        // Returning to the same account under a new session also cannot reuse the old authority.
        router.switch(Account("origin-a-session-2"))
        assertFalse(displayed.clearIfCurrent(router.repo))
    }

    @Test fun switchAfterTapButBeforeCoroutineExecutionCannotRetargetDeletion() = runTest {
        val old = Account("old-session"); val next = Account("new-session")
        val router = Router(old)
        val displayed = ComposerManagerOwner.capture(old.repo).draft("room", old.text)
        val deletion = async { displayed.clearIfCurrent(router.repo) }
        router.switch(next)
        runCurrent()
        assertFalse(deletion.await())
        assertEquals(0, old.clearCalls)
        assertEquals(0, next.clearCalls)
    }

    @Test fun observationsCarryTheirProducerAndClearRowsBeforeTheNewAccountEmits() = runTest {
        val old = Account("old-session"); val next = Account("new-session")
        val router = Router(old)
        val seen = mutableListOf<List<ComposerManagerDraftSnapshot>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeComposerManagerRows(router.repo) { owner ->
                val source = if (owner.repository === old.repo) old else next
                source.changes.map { listOf(owner.draft("room", it)) }
            }.collect { seen.add(it) }
        }
        runCurrent()
        old.changes.emit("same text"); runCurrent()
        val oldRow = seen.last().single()
        assertSame(old.repo, oldRow.owner.repository)
        router.switch(next); runCurrent()
        assertTrue(seen.last().isEmpty())
        old.changes.emit("late retired text"); runCurrent()
        assertTrue(seen.last().isEmpty())
        next.changes.emit("same text"); runCurrent()
        val newRow = seen.last().single()
        assertSame(next.repo, newRow.owner.repository)
        assertFalse(oldRow.owner.isCurrent(router.repo))
        assertTrue(newRow.owner.isCurrent(router.repo))
    }
}
