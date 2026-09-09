package app.pulse.domain.usecase

import app.pulse.domain.model.Message
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Domain test — JVM only, no Android deps (CI runs these on every push). */
class SendMessageUseCaseTest {

    private class FakeRepo : PulseRepository {
        val sent = mutableListOf<Message>()
        override val viewerId: String? = "a"
        override fun start(userId: String) {}
        override fun observeConversations(query: String) =
            MutableStateFlow(emptyList<app.pulse.domain.model.Conversation>())
        override fun observeMessages(conversationId: String) = MutableStateFlow(emptyList<Message>())
        override fun observePresence() = MutableStateFlow(emptySet<String>())
        override fun events() = MutableSharedFlow<PulseEvent>()
        override suspend fun refreshConversations(): Result<Unit> = Result.success(Unit)
        override suspend fun refreshMessages(conversationId: String, limit: Int): Result<Unit> = Result.success(Unit)
        override suspend fun me() = app.pulse.domain.model.User(id = "a", name = "n", handle = "n")
        override suspend fun sendMessage(conversationId: String, body: String, replyToId: String?): Result<Message> {
            val m = Message(
                id = "m1", conversationId = conversationId, authorId = "a", authorName = "n",
                kind = Message.Kind.TEXT, body = body, createdAt = "t", replyToId = replyToId,
            )
            sent += m
            return Result.success(m)
        }
        override suspend fun markRead(conversationId: String) = Result.success(Unit)
        override suspend fun setTyping(conversationId: String, userName: String, typing: Boolean) {}
        override suspend fun react(messageId: String, emoji: String) = Result.success(Unit)
        override suspend fun users(query: String) = Result.success(emptyList<app.pulse.domain.model.User>())
        override suspend fun checkHandle(handle: String) =
            Result.success(app.pulse.domain.model.HandleCheck(available = true))
        override suspend fun lookupUserByName(name: String) = Result.success<app.pulse.domain.model.User?>(null)
        override suspend fun createIdentity(name: String, color: String?, username: String?) =
            Result.success(app.pulse.domain.model.User(id = "a", name = name, handle = "n"))
        override suspend fun createDm(otherUserId: String) =
            Result.success(app.pulse.domain.model.Conversation(id = "c", kind = app.pulse.domain.model.Conversation.Kind.DM, title = "x"))
        override suspend fun createGroup(name: String, memberIds: List<String>) =
            Result.success(app.pulse.domain.model.Conversation(id = "c", kind = app.pulse.domain.model.Conversation.Kind.GROUP, title = name))
        override suspend fun togglePin(conversationId: String, pinned: Boolean) = Result.success(Unit)
        override suspend fun setMuted(conversationId: String, muted: Boolean) = Result.success(Unit)
        override suspend fun archive(conversationId: String, archived: Boolean) = Result.success(Unit)
        override suspend fun block(userId: String) = Result.success(Unit)
        override suspend fun unblock(userId: String) = Result.success(Unit)
        override suspend fun report(userId: String, reason: String, details: String?) = Result.success(Unit)
    }

    @Test
    fun `trims and sends`() = runTest {
        val repo = FakeRepo()
        val result = SendMessageUseCase(repo)(conversationId = "c1", body = "  hello pulse  ")
        assertTrue(result.isSuccess)
        assertEquals("hello pulse", result.getOrNull()?.body)
        assertEquals(1, repo.sent.size)
    }

    @Test
    fun `rejects blank body`() = runTest {
        val result = SendMessageUseCase(FakeRepo())(conversationId = "c1", body = "   ")
        assertTrue(result.isFailure)
    }
}
