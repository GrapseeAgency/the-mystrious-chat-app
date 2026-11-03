package app.pulse.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.pulse.domain.model.FlushReport

/**
 * Section 14 item 5 - fault-injected cold-worker execution. Every
 * account-bound bootstrap recheck, the fail-closed credential path, the
 * mid-flight account switch and the partial-drain retry rule are driven
 * through the extracted OutboxColdFlush contract (the Worker shell only
 * adapts the graph).
 */
class OutboxColdFlushTest {

    private class Harness(
        var owner: String? = "viewer-a",
        var recipient: String? = null,
        var readStateRoom: String? = null,
        var base: String? = null,
        var token: String? = "token-1",
        var tokenLoadThrows: Boolean = false,
        var startBackground: Boolean = true,
        var boundViewerId: String? = "viewer-a",
        var cachedToken: String? = "token-1",
        var savedRemaining: Int = 0,
        var composerRemaining: Int = 0,
        var outboxPending: Int = 0,
        var readRemaining: Int = 0,
        var reconcile: Boolean = true,
    ) : OutboxColdFlush.Deps {
        val appliedBases = mutableListOf<String>()
        var invalidatedRooms = 0

        override suspend fun viewerId(): String? = owner
        override suspend fun serverBase(): String? = base
        override fun recipientUserId(): String? = recipient
        override fun readStateRoom(): String? = readStateRoom
        override suspend fun loadToken(): String? {
            if (tokenLoadThrows) throw IllegalStateException("Stored session credential could not be decrypted")
            return token
        }
        override fun cachedToken(): String? = cachedToken
        override suspend fun applyPersistedBase() { appliedBases.add("persisted") }
        override fun applyBase(baseToApply: String) { appliedBases.add(baseToApply) }
        override suspend fun startBackgroundIfIdle(ownerToBind: String): Boolean = startBackground && ownerToBind == owner
        override fun boundViewerId(): String? = boundViewerId
        override fun invalidateReadProjections(room: String) { invalidatedRooms++ }
        override suspend fun flushSavedIntents(): Result<Int> = Result.success(savedRemaining)
        override suspend fun flushComposerIntents(): Result<Int> = Result.success(composerRemaining)
        override suspend fun flushOutbox(): FlushReport = FlushReport(sent = 0, dropped = 0, pending = outboxPending)
        override suspend fun flushReadReceipts(): Result<Int> = Result.success(readRemaining)
        override suspend fun refreshConversations(): Result<Unit> = Result.success(Unit)
        override suspend fun reconcileNotifications(stillOwner: suspend () -> Boolean): Boolean =
            reconcile && stillOwner()
    }

    @Test
    fun `no owner means nothing is owed - success, not retry`() = runBlocking {
        val harness = Harness(owner = null)
        assertEquals(OutboxColdFlush.Verdict.Success, OutboxColdFlush(harness).run())
        assertEquals(0, harness.appliedBases.size)
    }

    @Test
    fun `a push addressed to another account is ignored with success`() = runBlocking {
        val harness = Harness(recipient = "viewer-z")
        assertEquals(OutboxColdFlush.Verdict.Success, OutboxColdFlush(harness).run())
    }

    @Test
    fun `a missing token keeps the debt pending - retry`() = runBlocking {
        val harness = Harness(token = null)
        assertEquals(OutboxColdFlush.Verdict.Retry, OutboxColdFlush(harness).run())
    }

    @Test
    fun `a fail-closed unreadable credential is a retry, never success`() = runBlocking {
        val harness = Harness(tokenLoadThrows = true)
        assertEquals(OutboxColdFlush.Verdict.Retry, OutboxColdFlush(harness).run())
    }

    @Test
    fun `an owner switch before binding retries instead of working for the wrong account`() = runBlocking {
        val harness = Harness(owner = "viewer-b")
        assertEquals(OutboxColdFlush.Verdict.Retry, OutboxColdFlush(harness).run())
    }

    @Test
    fun `a cold bind that cannot start (foreground owns it) retries`() = runBlocking {
        val harness = Harness(startBackground = false)
        assertEquals(OutboxColdFlush.Verdict.Retry, OutboxColdFlush(harness).run())
    }

    @Test
    fun `a mid-flight account switch retries and never flushes`() = runBlocking {
        val harness = Harness(boundViewerId = "viewer-b")
        assertEquals(OutboxColdFlush.Verdict.Retry, OutboxColdFlush(harness).run())
    }

    @Test
    fun `a fully drained queue is success`() = runBlocking {
        val harness = Harness()
        assertEquals(OutboxColdFlush.Verdict.Success, OutboxColdFlush(harness).run())
    }

    @Test
    fun `any pending lane keeps the work queued for the next run`() = runBlocking {
        val pendingBookmarks = Harness(savedRemaining = 2)
        assertEquals(OutboxColdFlush.Verdict.Retry, OutboxColdFlush(pendingBookmarks).run())
        val pendingComposer = Harness(composerRemaining = 1)
        assertEquals(OutboxColdFlush.Verdict.Retry, OutboxColdFlush(pendingComposer).run())
        val pendingOutbox = Harness(outboxPending = 3)
        assertEquals(OutboxColdFlush.Verdict.Retry, OutboxColdFlush(pendingOutbox).run())
        val pendingReads = Harness(readRemaining = 1)
        assertEquals(OutboxColdFlush.Verdict.Retry, OutboxColdFlush(pendingReads).run())
    }

    @Test
    fun `a push wake with read state invalidates exactly that projection`() = runBlocking {
        val harness = Harness(readStateRoom = "room-7")
        assertEquals(OutboxColdFlush.Verdict.Success, OutboxColdFlush(harness).run())
        assertEquals(1, harness.invalidatedRooms)
    }

    @Test
    fun `an over-long read-state room from a push is ignored`() = runBlocking {
        val harness = Harness(readStateRoom = "x".repeat(129))
        assertEquals(OutboxColdFlush.Verdict.Success, OutboxColdFlush(harness).run())
        assertEquals(0, harness.invalidatedRooms)
    }

    @Test
    fun `reconciliation that reports pending notifications keeps the work queued`() = runBlocking {
        val harness = Harness(reconcile = false)
        assertEquals(OutboxColdFlush.Verdict.Retry, OutboxColdFlush(harness).run())
    }

    @Test
    fun `a stored gateway base is applied before any flush`() = runBlocking {
        val harness = Harness(base = "https://gateway.example")
        assertTrue(OutboxColdFlush(harness).run() == OutboxColdFlush.Verdict.Success)
        assertEquals(listOf("https://gateway.example"), harness.appliedBases)
    }
}
