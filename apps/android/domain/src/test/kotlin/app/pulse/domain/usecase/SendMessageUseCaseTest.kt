package app.pulse.domain.usecase

import app.pulse.domain.model.Message
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Domain test — JVM only, no Android deps (CI runs these on every push). */
class SendMessageUseCaseTest {

    private class FakeRepo : PulseRepository {
        val sent = mutableListOf<Message>()
        var lastReplyToId: String? = null
        var lastParentId: String? = null
        override val viewerId: String? = "a"
        override fun start(userId: String) {}
        override fun observeConversations(query: String) =
            MutableStateFlow(emptyList<app.pulse.domain.model.Conversation>())
        override fun observeMessages(conversationId: String) = MutableStateFlow(emptyList<Message>())
        override fun observePresence() = MutableStateFlow(emptySet<String>())
        override fun events() = MutableSharedFlow<PulseEvent>()
        override fun observeConnected() = MutableStateFlow(false)
        override fun observeThreadMessages(rootId: String) = MutableStateFlow(emptyList<Message>())
        override fun observeDrafts() = MutableStateFlow(emptyMap<String, String>())
        override suspend fun refreshConversations(): Result<Unit> = Result.success(Unit)
        override suspend fun refreshMessages(conversationId: String, limit: Int): Result<Unit> = Result.success(Unit)
        override suspend fun me() = app.pulse.domain.model.User(id = "a", name = "n", handle = "n")
        override suspend fun sendMessage(
            conversationId: String,
            body: String,
            replyToId: String?,
            parentId: String?,
            topicId: String?,
        ): Result<Message> {
            val m = Message(
                id = "m1", conversationId = conversationId, authorId = "a", authorName = "n",
                kind = Message.Kind.TEXT, body = body, createdAt = "t", replyToId = replyToId,
                threadRootId = parentId,
            )
            sent += m
            lastReplyToId = replyToId
            lastParentId = parentId
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
        override suspend fun markUnread(conversationId: String, on: Boolean) = Result.success(Unit)
        override suspend fun setMutedUntil(conversationId: String, until: String?) = Result.success(Unit)
        override suspend fun createSelfChat() =
            Result.success(app.pulse.domain.model.Conversation(id = "c", kind = app.pulse.domain.model.Conversation.Kind.DM, title = "Note to Self"))
        override suspend fun stories() = Result.success(emptyList<app.pulse.domain.model.StoryCell>())
        override suspend fun folders() = Result.success(emptyList<app.pulse.domain.model.FolderSummary>())
        override suspend fun mentions() = Result.success(emptyList<app.pulse.domain.model.MentionItem>())
        override suspend fun searchMessages(query: String) = Result.success(emptyList<app.pulse.domain.model.MessageHit>())
        override suspend fun fullHistory(conversationId: String) = Result.success(emptyList<Message>())
        override suspend fun deleteMessage(messageId: String) = Result.success(Unit)
        override suspend fun exportChat(conversationId: String) = Result.success("export.txt")
        override suspend fun clearMyMessages(conversationId: String) = Result.success(0)
        override suspend fun block(userId: String) = Result.success(Unit)
        override suspend fun unblock(userId: String) = Result.success(Unit)
        override suspend fun report(userId: String, reason: String, details: String?) = Result.success(Unit)
        override suspend fun enqueueOutbox(entry: app.pulse.domain.model.OutboxEntry) = Result.success(Unit)
        override fun observeOutbox() = MutableStateFlow(emptyList<app.pulse.domain.model.OutboxEntry>())
        override suspend fun outboxPending() = emptyList<app.pulse.domain.model.OutboxEntry>()
        override suspend fun attemptOutboxSend(entry: app.pulse.domain.model.OutboxEntry) = Result.success(Message(
            id = "m1", conversationId = entry.conversationId, authorId = "a", authorName = "n",
            kind = Message.Kind.TEXT, body = entry.content, createdAt = "t",
        ))
        override suspend fun resolveOutboxDelivery(entry: app.pulse.domain.model.OutboxEntry, real: Message) {}
        override suspend fun dropOutboxEntry(clientId: String, reason: String) {}
        override suspend fun bumpOutboxAttempts(clientId: String) {}
        override suspend fun flushOutbox() = app.pulse.domain.model.FlushReport()
        override suspend fun saveDraft(conversationId: String, text: String) {}
        override suspend fun clearDraft(conversationId: String) {}
        override fun observeDraft(conversationId: String) = MutableStateFlow<String?>(null)

        // ── Wave 1 messaging surface (stubs — the send path is the subject here) ──
        override suspend fun editMessage(messageId: String, content: String) =
            Result.failure<Message>(UnsupportedOperationException())
        override suspend fun toggleMessagePin(messageId: String) =
            Result.failure<Message>(UnsupportedOperationException())
        override suspend fun toggleMessageSave(messageId: String) =
            Result.failure<Boolean>(UnsupportedOperationException())
        override suspend fun pinnedMessages(conversationId: String) = emptyList<Message>()
        override suspend fun loadThread(rootId: String) =
            Message(id = rootId, conversationId = "c1", authorId = "a", authorName = "n", kind = Message.Kind.TEXT, body = "root", createdAt = "t") to emptyList<Message>()
        override suspend fun messagesPage(conversationId: String, before: String?, limit: Int) =
            emptyList<Message>() to false
        override suspend fun searchInConversation(conversationId: String, query: String) = emptyList<Message>()
        override suspend fun uploadMedia(dataUrl: String) =
            Result.failure<String>(UnsupportedOperationException())
        override suspend fun forwardMessage(targetConversationId: String, source: Message) =
            Result.failure<Message>(UnsupportedOperationException())
        override suspend fun setServerDraft(conversationId: String, draft: String) {}
        override suspend fun conversationDetail(conversationId: String) =
            Result.failure<app.pulse.domain.model.Conversation>(UnsupportedOperationException())
        override suspend fun sendMediaMessage(
            conversationId: String,
            body: String,
            imagePath: String?,
            audioPath: String?,
            durationMs: Long?,
            filePath: String?,
            fileName: String?,
            fileSize: Long?,
            kind: String?,
            viewOnce: Boolean?,
            topicId: String?,
        ): Result<Message> {
            val m = Message(
                id = "m-media", conversationId = conversationId, authorId = "a", authorName = "n",
                kind = if (kind == "file") Message.Kind.FILE else Message.Kind.IMAGE,
                body = body, createdAt = "t", imagePath = imagePath, filePath = filePath,
                fileName = fileName, fileSize = fileSize,
            )
            sent += m
            return Result.success(m)
        }
        override suspend fun downloadMedia(filePath: String) = Result.success("/tmp/$filePath")
        override suspend fun threadReplyCounts(rootIds: List<String>) = emptyMap<String, Int>()

        // ── Wave 2 messaging depth (stubs — the send path is the subject here) ──
        override suspend fun refreshMessages(conversationId: String, topicId: String?): Result<Unit> =
            Result.success(Unit)
        override suspend fun transcribeMessage(messageId: String) =
            Result.failure<app.pulse.domain.model.TranscribeOutcome>(UnsupportedOperationException())
        override suspend fun markMessageViewed(messageId: String) {}
        override suspend fun createPoll(conversationId: String, question: String, options: List<String>) =
            Result.failure<Message>(UnsupportedOperationException())
        override suspend fun votePoll(pollId: String, optionId: String) =
            Result.failure<Message>(UnsupportedOperationException())
        override suspend fun closePoll(pollId: String) =
            Result.failure<Message>(UnsupportedOperationException())
        override suspend fun unfurlMessage(messageId: String) {}
        override suspend fun refreshSavedLibrary() =
            Result.success(emptyList<app.pulse.domain.model.SavedItem>())
        override fun observeSavedLibrary() =
            MutableStateFlow(emptyList<app.pulse.domain.model.SavedItem>())
        override suspend fun unsaveMessage(messageId: String) =
            Result.failure<Boolean>(UnsupportedOperationException())
        override suspend fun refreshTopics(conversationId: String) = Result.success(Unit)
        override fun observeTopics(conversationId: String) =
            MutableStateFlow(emptyList<app.pulse.domain.model.Topic>())
        override suspend fun createTopic(conversationId: String, name: String, emoji: String) =
            Result.failure<app.pulse.domain.model.Topic>(UnsupportedOperationException())
        override suspend fun deleteTopic(conversationId: String, topicId: String) = Result.success(Unit)

        // ── Wave 3 call surface (unused by these use-case tests) ──
        override fun observeCallLog() = MutableStateFlow(emptyList<app.pulse.domain.model.CallLogEntry>())
        override suspend fun refreshCallLog(): Result<List<app.pulse.domain.model.CallLogEntry>> =
            Result.success(emptyList())
        override suspend fun writeCallLog(entry: app.pulse.domain.model.CallLogEntry) = Result.success(Unit)
        override suspend fun flushCallLogQueue(): Result<Int> = Result.success(0)
        override suspend fun emitCall(signal: app.pulse.domain.model.CallSignalOut) {}
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

    @Test
    fun `thread reply passes parentId without touching replyToId`() = runTest {
        // Spec §1.1: a thread reply is an ordinary send whose parentId is the
        // thread ROOT. replyToId (inline quote) and parentId (thread) are
        // different axes — the use case must forward them independently.
        val repo = FakeRepo()
        val result = SendMessageUseCase(repo)(
            conversationId = "c1",
            body = "answering in thread",
            parentId = "root-1",
        )
        assertTrue(result.isSuccess)
        assertEquals("root-1", repo.lastParentId)
        assertNull(repo.lastReplyToId)
        assertEquals("root-1", result.getOrNull()?.threadRootId)
        assertNull(result.getOrNull()?.replyToId)
    }

    @Test
    fun `inline quote passes replyToId without touching parentId`() = runTest {
        val repo = FakeRepo()
        val result = SendMessageUseCase(repo)(
            conversationId = "c1",
            body = "quoting you",
            replyToId = "m42",
        )
        assertTrue(result.isSuccess)
        assertEquals("m42", repo.lastReplyToId)
        assertNull(repo.lastParentId)
    }
}
