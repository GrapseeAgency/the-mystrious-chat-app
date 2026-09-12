package app.pulse.domain.usecase

import app.pulse.domain.model.FlushReport
import app.pulse.domain.model.Message
import app.pulse.domain.model.OutboxDeliveryException
import app.pulse.domain.model.OutboxEntry
import app.pulse.domain.model.OutboxFailureClass
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Domain policy tests for the offline outbox drain — web flushPulseOutbox
 * parity (src/lib/pulse-outbox.ts): FIFO order, stop-at-first-failure,
 * success swaps temp→real, 4xx drops, network failures bump attempts.
 */
class FlushOutboxUseCaseTest {

    /** Scripted outcome for one outbox clientId. */
    private sealed interface Script {
        data class Ok(val realMessageId: String) : Script
        data class Drop(val reason: String) : Script
        data class Retry(val reason: String) : Script
    }

    private class FakeRepo : PulseRepository {
        val queue = mutableListOf<OutboxEntry>()
        val scripts = linkedMapOf<String, Script>()
        val deliveryOrder = mutableListOf<String>()
        val resolved = mutableListOf<Pair<String, String>>() // clientId → real message id
        val dropped = mutableListOf<Pair<String, String>>() // clientId → reason
        val bumped = mutableListOf<String>()

        override val viewerId: String? = "viewer"
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
        override suspend fun users(query: String) = Result.success(emptyList<app.pulse.domain.model.User>())
        override suspend fun createIdentity(name: String, color: String?, username: String?) =
            Result.success(app.pulse.domain.model.User(id = "a", name = name, handle = "h"))
        override suspend fun checkHandle(handle: String) =
            Result.success(app.pulse.domain.model.HandleCheck(available = true))
        override suspend fun lookupUserByName(name: String) = Result.success<app.pulse.domain.model.User?>(null)
        override suspend fun createDm(otherUserId: String) = Result.success(app.pulse.domain.model.Conversation(id = "c", kind = app.pulse.domain.model.Conversation.Kind.DM, title = "t"))
        override suspend fun createGroup(name: String, memberIds: List<String>) = Result.success(app.pulse.domain.model.Conversation(id = "c", kind = app.pulse.domain.model.Conversation.Kind.GROUP, title = name))
        override suspend fun me() = null
        override suspend fun sendMessage(
            conversationId: String,
            body: String,
            replyToId: String?,
            parentId: String?,
            topicId: String?,
        ): Result<Message> =
            Result.failure(UnsupportedOperationException())
        override suspend fun markRead(conversationId: String) = Result.success(Unit)
        override suspend fun setTyping(conversationId: String, userName: String, typing: Boolean) {}
        override suspend fun react(messageId: String, emoji: String) = Result.success(Unit)
        override suspend fun togglePin(conversationId: String, pinned: Boolean) = Result.success(Unit)
        override suspend fun setMuted(conversationId: String, muted: Boolean) = Result.success(Unit)
        override suspend fun archive(conversationId: String, archived: Boolean) = Result.success(Unit)
        override suspend fun markUnread(conversationId: String, on: Boolean) = Result.success(Unit)
        override suspend fun setMutedUntil(conversationId: String, until: String?) = Result.success(Unit)
        override suspend fun createSelfChat() = Result.success(app.pulse.domain.model.Conversation(id = "c", kind = app.pulse.domain.model.Conversation.Kind.DM, title = "self"))
        override suspend fun stories() = Result.success(emptyList<app.pulse.domain.model.StoryGroup>())
        override suspend fun createStory(caption: String, background: String?, imagePath: String?) =
            Result.success(app.pulse.domain.model.StoryItem(id = "s-new", kind = if (imagePath != null) "image" else "text"))
        override suspend fun markStoryViewed(storyId: String) = Result.success(1)
        override suspend fun storyViewers(storyId: String) = Result.success(emptyList<app.pulse.domain.model.StoryViewer>())
        override suspend fun deleteStory(storyId: String) = Result.success(Unit)
        override suspend fun folders() = Result.success(emptyList<app.pulse.domain.model.FolderSummary>())
        override suspend fun mentions() = Result.success(emptyList<app.pulse.domain.model.MentionItem>())
        override suspend fun searchMessages(query: String) = Result.success(emptyList<app.pulse.domain.model.MessageHit>())
        override suspend fun fullHistory(conversationId: String) = Result.success(emptyList<Message>())
        override suspend fun deleteMessage(messageId: String) = Result.success(Unit)
        override suspend fun exportChat(conversationId: String) = Result.success("x.txt")
        override suspend fun clearMyMessages(conversationId: String) = Result.success(0)
        override suspend fun block(userId: String) = Result.success(Unit)
        override suspend fun unblock(userId: String) = Result.success(Unit)
        override suspend fun report(userId: String, reason: String, details: String?) = Result.success(Unit)

        // ── outbox mechanics (scripted) ─────────────────────────
        override suspend fun enqueueOutbox(entry: OutboxEntry): Result<Unit> {
            queue += entry
            return Result.success(Unit)
        }

        override fun observeOutbox() = MutableStateFlow(queue.toList())
        override suspend fun outboxPending(): List<OutboxEntry> = queue.toList()

        override suspend fun attemptOutboxSend(entry: OutboxEntry): Result<Message> {
            deliveryOrder += entry.clientId
            return when (val script = scripts[entry.clientId]) {
                is Script.Ok -> Result.success(realMessage(script.realMessageId, entry))
                is Script.Drop -> Result.failure(
                    OutboxDeliveryException(OutboxFailureClass.DROP, script.reason),
                )
                is Script.Retry -> Result.failure(
                    OutboxDeliveryException(OutboxFailureClass.RETRY, script.reason),
                )
                null -> Result.failure(OutboxDeliveryException(OutboxFailureClass.RETRY, "no script"))
            }
        }

        override suspend fun resolveOutboxDelivery(entry: OutboxEntry, real: Message) {
            resolved += entry.clientId to real.id
            queue.removeAll { it.clientId == entry.clientId }
        }

        override suspend fun dropOutboxEntry(clientId: String, reason: String) {
            dropped += clientId to reason
            queue.removeAll { it.clientId == clientId }
        }

        override suspend fun bumpOutboxAttempts(clientId: String) {
            bumped += clientId
        }

        override suspend fun flushOutbox(): FlushReport = FlushOutboxUseCase(this).invoke()
        override suspend fun saveDraft(conversationId: String, text: String) {}
        override suspend fun clearDraft(conversationId: String) {}
        override fun observeDraft(conversationId: String) = MutableStateFlow<String?>(null)

        // ── Wave 1 messaging surface (unused by the outbox policy) ──
        override suspend fun editMessage(messageId: String, content: String) =
            Result.failure<Message>(UnsupportedOperationException())
        override suspend fun toggleMessagePin(messageId: String) =
            Result.failure<Message>(UnsupportedOperationException())
        override suspend fun toggleMessageSave(messageId: String) =
            Result.failure<Boolean>(UnsupportedOperationException())
        override suspend fun pinnedMessages(conversationId: String) = emptyList<Message>()
        override suspend fun loadThread(rootId: String) =
            Message(id = rootId, conversationId = "c1", authorId = "viewer", authorName = "Viewer", kind = Message.Kind.TEXT, body = "root", createdAt = "t") to emptyList<Message>()
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
        ) = Result.failure<Message>(UnsupportedOperationException())
        override suspend fun downloadMedia(filePath: String) =
            Result.failure<String>(UnsupportedOperationException())
        override suspend fun threadReplyCounts(rootIds: List<String>) = emptyMap<String, Int>()

        // ── Wave 2 messaging depth (unused by the outbox policy) ──
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
        // Wave 5 voice rooms / stage / space — the fakes never emit; recorded only where a test needs it.
        override suspend fun emitVoiceJoin(conversationId: String, user: app.pulse.protocol.PulseVoiceUser) {}
        override suspend fun emitVoiceLeave(conversationId: String) {}
        override suspend fun emitVoicePtt(conversationId: String, userId: String, on: Boolean) {}
        override suspend fun emitVoiceChunk(conversationId: String, userId: String, seq: Long, data: String) {}
        override suspend fun emitVoiceTranscript(conversationId: String, userId: String, text: String) {}
        override suspend fun emitStageJoin(conversationId: String, user: app.pulse.protocol.PulseVoiceUser, asHost: Boolean) {}
        override suspend fun emitStageHand(conversationId: String, userId: String, raised: Boolean) {}
        override suspend fun emitStageApprove(conversationId: String, byUserId: String, targetUserId: String) {}
        override suspend fun emitStageMute(conversationId: String, byUserId: String, targetUserId: String) {}
        override suspend fun emitStageEnd(conversationId: String, byUserId: String) {}
        override suspend fun emitStageLeave(conversationId: String) {}
        override suspend fun emitSpaceJoin(conversationId: String, user: app.pulse.protocol.PulseVoiceUser) {}
        override suspend fun emitSpaceMove(conversationId: String, x: Double, y: Double) {}
        override suspend fun emitSpaceLeave(conversationId: String) {}
        override suspend fun transcribeVoice(conversationId: String, requesterId: String, audioBase64: String): Result<app.pulse.protocol.VoiceTranscriptResultDto> =
            Result.failure(IllegalStateException("fake repo never transcribes"))

        private fun realMessage(id: String, entry: OutboxEntry) = Message(
            id = id,
            conversationId = entry.conversationId,
            authorId = "viewer",
            authorName = "Viewer",
            kind = Message.Kind.TEXT,
            body = entry.content,
            createdAt = entry.createdAt,
        )
    }

    private fun entry(clientId: String, conversationId: String = "c1") = OutboxEntry(
        conversationId = conversationId,
        clientId = clientId,
        content = "msg-$clientId",
        createdAt = "2026-02-14T10:00:00Z",
    )

    @Test
    fun `drains FIFO and swaps temp for real on success`() = runTest {
        val repo = FakeRepo()
        listOf("a", "b", "c").forEach { clientId ->
            repo.scripts[clientId] = Script.Ok("real-$clientId")
            repo.enqueueOutbox(entry(clientId))
        }

        val report = FlushOutboxUseCase(repo)()

        assertEquals(listOf("a", "b", "c"), repo.deliveryOrder)
        assertEquals(
            listOf("a" to "real-a", "b" to "real-b", "c" to "real-c"),
            repo.resolved,
        )
        assertTrue(repo.dropped.isEmpty() && repo.bumped.isEmpty())
        assertEquals(3, report.sent)
        assertEquals(0, report.dropped)
        assertEquals(0, report.pending)
    }

    @Test
    fun `stops at the first retryable failure and keeps the entry`() = runTest {
        val repo = FakeRepo()
        repo.scripts["a"] = Script.Ok("real-a")
        repo.scripts["b"] = Script.Retry("airplane mode")
        repo.scripts["c"] = Script.Ok("real-c")
        listOf("a", "b", "c").forEach { clientId -> repo.enqueueOutbox(entry(clientId)) }

        val report = FlushOutboxUseCase(repo)()

        // "c" must NOT overtake "b" — the drain stops at the first failure.
        assertEquals(listOf("a", "b"), repo.deliveryOrder)
        assertEquals(listOf("b"), repo.bumped)
        assertTrue(repo.dropped.isEmpty())
        assertEquals(1, report.sent)
        assertEquals(0, report.dropped)
        assertEquals(2, report.pending) // b (kept) + c (never attempted)
    }

    @Test
    fun `drops 4xx entries with the reason and stops`() = runTest {
        val repo = FakeRepo()
        repo.scripts["bad"] = Script.Drop("message blocked in this chat")
        repo.scripts["next"] = Script.Ok("real-next")
        repo.enqueueOutbox(entry("bad"))
        repo.enqueueOutbox(entry("next"))

        val report = FlushOutboxUseCase(repo)()

        assertEquals(listOf("bad"), repo.deliveryOrder)
        assertEquals(listOf("bad" to "message blocked in this chat"), repo.dropped)
        assertTrue(repo.bumped.isEmpty())
        assertEquals(0, report.sent)
        assertEquals(1, report.dropped)
        assertEquals(1, report.pending)
    }

    @Test
    fun `network failure increments attempts on the held entry`() = runTest {
        val repo = FakeRepo()
        repo.scripts["held"] = Script.Retry("offline")
        repo.enqueueOutbox(entry("held"))

        val report = FlushOutboxUseCase(repo)()

        assertEquals(listOf("held"), repo.bumped)
        assertTrue(repo.queue.isNotEmpty(), "network-class failures keep the entry queued")
        assertEquals(0, report.sent)
        assertEquals(1, report.pending)
    }

    @Test
    fun `empty outbox drains to a zero report`() = runTest {
        val repo = FakeRepo()
        val report = FlushOutboxUseCase(repo)()
        assertEquals(FlushReport(sent = 0, dropped = 0, pending = 0), report)
    }
}
