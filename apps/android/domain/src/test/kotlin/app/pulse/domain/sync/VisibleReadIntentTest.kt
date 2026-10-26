package app.pulse.domain.sync

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.repository.PulseRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VisibleReadIntentTest {
    private class Journal {
        val sent = mutableListOf<String>()
        var refreshes = 0
        val writes = mutableListOf<Continuation<Result<Unit>>>()
        @Suppress("UNCHECKED_CAST")
        val repo = Proxy.newProxyInstance(PulseRepository::class.java.classLoader, arrayOf(PulseRepository::class.java)) { proxy, method, args ->
            when {
                method.name == "boundToCurrentAccount" -> proxy
                method.name == "getViewerId" -> "viewer"
                method.name == "requestReadMembershipRefresh" -> { refreshes++; Unit }
                method.name.startsWith("markReadThrough") -> {
                    sent += args!![2] as String
                    writes += args.last() as Continuation<Result<Unit>>
                    COROUTINE_SUSPENDED
                }
                else -> error("Native visible reads must not reacquire asynchronously: ${method.name}")
            }
        } as PulseRepository
    }

    @Test fun `manual unread arriving during journal suspension cannot replace displayed read authority`() = runTest {
        val journal = Journal()
        var member = ConversationMember("viewer", "Viewer", readMembershipId = "member", readIntent = "original.member")
        val scope = VisibleReadScope(journal.repo, "room") { member }
        val first = async { scope.message("first").send() }
        runCurrent()
        assertEquals(listOf("original.member"), journal.sent)
        member = member.copy(readIntent = "after-manual.member")
        journal.writes[0].resume(Result.success(Unit))
        assertTrue(first.await().isSuccess)
        val later = async { scope.message("later").send() }
        runCurrent()
        assertEquals(listOf("original.member", "original.member"), journal.sent)
        journal.writes[1].resume(Result.success(Unit))
        assertTrue(later.await().isSuccess)
        val newVisit = VisibleReadScope(journal.repo, "room") { member }
        val fresh = async { newVisit.message("first").send() }
        runCurrent()
        assertEquals("after-manual.member", journal.sent.last())
        journal.writes[2].resume(Result.success(Unit))
        assertTrue(fresh.await().isSuccess)
    }

    @Test fun `missing or foreign displayed membership cannot fall back to a later database fence`() = runTest {
        val journal = Journal()
        var member: ConversationMember? = null
        val intent = VisibleReadScope(journal.repo, "room") { member }.message("message")
        assertTrue(intent.send().isFailure)
        member = ConversationMember("another-viewer", "Other", readMembershipId = "other", readIntent = "fresh.other")
        assertTrue(intent.send().isFailure)
        assertTrue(journal.sent.isEmpty())
        assertEquals(2, journal.refreshes, "Missing metadata repairs asynchronously without adopting its future authority")
        member = ConversationMember("viewer", "Viewer", readMembershipId = "member")
        val offline = async { intent.send() }
        runCurrent()
        assertEquals(listOf("offline.member"), journal.sent)
        journal.writes.single().resume(Result.success(Unit))
        assertTrue(offline.await().isSuccess)
    }

    @Test fun `distinct visits do not erase or inherit each others queued authority`() {
        val first = readObservationJournalKey("owner", "message", "first.member")
        val fresh = readObservationJournalKey("owner", "message", "after-manual.member")
        assertNotEquals(first, fresh)
        assertEquals(first, readObservationJournalKey("owner", "message", "first.member"))
        assertTrue(first.startsWith("read-journal:v1|owner|"))
        assertNotEquals(first, readObservationJournalKey("other", "message", "first.member"))
    }

    @Test fun `lost ACK and reconnect retry retain the original fence even after server intent changes`() = runTest {
        var revision = "before-manual-unread"
        var captures = 0
        val sent = mutableListOf<String>()
        val intent = VisibleReadIntent({ captures++; Result.success(revision) }, {
            sent += it
            if (sent.size == 1) Result.failure(IllegalStateException("ACK lost")) else Result.success(Unit)
        })
        assertTrue(intent.send().isFailure)
        revision = "after-manual-unread"
        assertTrue(intent.send().isSuccess)
        assertEquals(1, captures)
        assertEquals(listOf("before-manual-unread", "before-manual-unread"), sent)
    }
    @Test fun `failed preparation cannot dispatch and a genuinely new reader captures fresh intent`() = runTest {
        var attempts = 0; var writes = 0
        val reader = VisibleReadIntent({ if (++attempts == 1) Result.failure(Exception("offline")) else Result.success("fresh") }, {
            assertEquals("fresh", it); writes++; Result.success(Unit)
        })
        assertTrue(reader.send().isFailure); assertEquals(0,writes)
        assertTrue(reader.send().isSuccess); assertEquals(1,writes)
    }
    @Test fun `navigation after capture begins cannot drop the local journal write`() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var saved: String? = null
        val reader = VisibleReadIntent({
            started.complete(Unit)
            finish.await()
            Result.success("original.membership")
        }, { saved = it; Result.success(Unit) })
        val job = launch { reader.send() }
        started.await()
        job.cancel()
        finish.complete(Unit)
        job.join()
        assertEquals("original.membership", saved)
        assertTrue(job.isCancelled)
    }

    @Test fun `already cancelled work never starts an observation`() = runTest {
        var writes = 0
        val reader = VisibleReadIntent({ Result.success("fence") }, { writes++; Result.success(Unit) })
        val job = launch { reader.send() }
        job.cancel()
        runCurrent()
        assertEquals(0, writes)
    }
}
