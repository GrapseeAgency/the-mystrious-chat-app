package app.pulse.domain.sync

import app.pulse.domain.model.Conversation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConversationNavigationTest {
    private fun row(id: String) = Conversation(id, Conversation.Kind.DM, "Same display name")
    @Test fun `repository snapshot opens a just-created row even before the UI receives it`() = runTest {
        val resolved = resolveConversationForOpen("created", listOf(row("other"), row("created"))) { error("Network not needed") }
        assertEquals("created", resolved.id)
    }
    @Test fun `missing local row loads exact detail instead of opening a same-title chat`() = runTest {
        val resolved = resolveConversationForOpen("created", listOf(row("other"))) {
            assertEquals("created", it); Result.success(row(it))
        }
        assertEquals("created", resolved.id)
        assertTrue(runCatching { resolveConversationForOpen("created", emptyList()) { Result.success(row("wrong")) } }.isFailure)
    }
    @Test fun `self uses its own endpoint and peer navigation validates exact membership`() = runTest {
        val self = row("notes").copy(isSelf = true, memberIds = listOf("me"))
        val direct = row("dm").copy(memberIds = listOf("peer", "me"))
        assertEquals(self, resolvePersonalConversation("me", "me", emptyList(),
            createSelf = { Result.success(self) }, createDirect = { error("Self must not call DM") }))
        assertEquals(direct, resolvePersonalConversation("me", "peer", emptyList(),
            createSelf = { error("Peer must not call self") }, createDirect = { assertEquals("peer", it); Result.success(direct) }))
        assertEquals(direct, resolvePersonalConversation("me", "peer", listOf(row("same-title"), direct),
            createSelf = { error("Cached") }, createDirect = { error("Cached") }))
        assertTrue(runCatching { resolvePersonalConversation("me", "peer", emptyList(),
            createSelf = { error("Wrong path") }, createDirect = { Result.success(direct.copy(memberIds = listOf("me", "other"))) }) }.isFailure)
    }

    @Test fun `refusal and cancellation cannot produce a navigation result`() = runTest {
        val refusal = IllegalStateException("not a member")
        assertSame(refusal, runCatching { resolveConversationForOpen("room", emptyList()) { Result.failure(refusal) } }.exceptionOrNull())
        var opened = false
        val job = launch {
            resolveConversationForOpen("room", emptyList()) { currentCoroutineContext().cancel(); Result.success(row("room")) }
            opened = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(opened)
    }
}
