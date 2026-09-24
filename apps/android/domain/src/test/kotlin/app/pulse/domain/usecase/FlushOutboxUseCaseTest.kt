package app.pulse.domain.usecase

import app.pulse.domain.model.FlushReport
// R1-W2F — per-conversation themes (F-FX-05) in the FakeRepo stubs.
import app.pulse.domain.model.ConvTheme
import app.pulse.domain.model.Message
import app.pulse.domain.model.OutboxDeliveryException
import app.pulse.domain.model.OutboxEntry
import app.pulse.domain.model.OutboxFailureClass
// R5-B — send receipt type (message + optional streak bump).
import app.pulse.domain.model.SendReceipt
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
    override suspend fun myRole(conversationId: String): Result<String?> = Result.success(null)
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

        // ── Wave 8 — session tokens + prefs + data manager stubs ──
        override val pulsePrefs =
            MutableStateFlow(app.pulse.protocol.WirePulsePrefs())
        override suspend fun updatePulsePrefs(patch: app.pulse.protocol.WirePulsePrefs) = Result.success(Unit)
        override suspend fun login(name: String) =
            Result.success(app.pulse.domain.model.User(id = "a", name = name, handle = "h"))
        override suspend fun clearSessionToken() {}
        override suspend fun discardOutboxEntry(clientId: String) {}
        override suspend fun clearOutbox() {}
        override suspend fun clearAllDrafts() {}
        override suspend fun createDm(otherUserId: String) = Result.success(app.pulse.domain.model.Conversation(id = "c", kind = app.pulse.domain.model.Conversation.Kind.DM, title = "t"))
        override suspend fun createGroup(name: String, memberIds: List<String>) = Result.success(app.pulse.domain.model.Conversation(id = "c", kind = app.pulse.domain.model.Conversation.Kind.GROUP, title = name))
        override suspend fun me() = null
        override suspend fun sendMessage(
            conversationId: String,
            body: String,
            replyToId: String?,
            parentId: String?,
            topicId: String?,
        ): Result<SendReceipt> =
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

        // ── Wave 6 social graph stubs (unused by these use-case tests) ──
        override suspend fun userProfile(userId: String) =
            Result.success(app.pulse.domain.model.UserProfile(id = userId, name = "n"))
        override suspend fun patchProfile(patch: app.pulse.domain.model.ProfilePatch) =
            Result.success(app.pulse.domain.model.UserProfile(id = "a", name = "n"))
        override suspend fun userStats(userId: String) = Result.success(app.pulse.domain.model.UserStats())
        override suspend fun safetyState(peerId: String) =
            Result.success(app.pulse.domain.model.SafetyState(peerId = peerId))
        override suspend fun verifyPeer(peerId: String) =
            Result.success(app.pulse.domain.model.SafetyState(peerId = peerId, verified = true))
        override suspend fun unverifyPeer(peerId: String) =
            Result.success(app.pulse.domain.model.SafetyState(peerId = peerId))
        override suspend fun blockState(userId: String) = Result.success(false)
        override suspend fun blockedAccounts() = Result.success(emptyList<app.pulse.domain.model.BlockedAccount>())
        override suspend fun myReportReasons(userId: String) = Result.success(emptyList<String>())
        override suspend fun invitePreview(code: String) =
            Result.success(app.pulse.domain.model.InvitePreview(code = code, conversationId = "c", name = "g", memberCount = 1, alreadyMember = false))
        override suspend fun joinInvite(code: String) =
            Result.success(app.pulse.domain.model.InviteJoinOutcome(conversationId = "c", alreadyMember = false))
        override suspend fun channels(mineOnly: Boolean) = Result.success(emptyList<app.pulse.domain.model.Channel>())
        override suspend fun createChannel(name: String, description: String?, photo: String?) =
            Result.success(app.pulse.domain.model.Channel(id = "c", name = name, isSubscribed = true))
        override suspend fun subscribeChannel(channelId: String) = Result.success(false)
        override suspend fun unsubscribeChannel(channelId: String) = Result.success(Unit)
        override suspend fun createFolder(name: String, emoji: String) =
            Result.success(app.pulse.domain.model.FolderSummary(id = "f", name = name, emoji = emoji))
        override suspend fun updateFolder(folderId: String, name: String?, emoji: String?, position: Int?) = Result.success(Unit)
        override suspend fun deleteFolder(folderId: String) = Result.success(Unit)
        override suspend fun setFolderConversations(folderId: String, conversationIds: List<String>) = Result.success(Unit)

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

        // ── REM-A stubs (group governance + scheduling + rich sends) ──
        override suspend fun groupMeta(conversationId: String) =
            Result.failure<app.pulse.domain.model.GroupMeta>(UnsupportedOperationException())
        override suspend fun renameGroup(conversationId: String, name: String) = Result.success(Unit)
        override suspend fun setGroupBroadcast(conversationId: String, broadcast: Boolean) = Result.success(Unit)
        override suspend fun setScreenPrivacy(conversationId: String, on: Boolean) = Result.success(Unit)
        override suspend fun addGroupMembers(conversationId: String, userIds: List<String>) =
            Result.success(emptyList<String>())
        override suspend fun setMemberRole(conversationId: String, userId: String, promote: Boolean) = Result.success(Unit)
        override suspend fun kickMember(conversationId: String, userId: String) = Result.success(Unit)
        override suspend fun leaveGroup(conversationId: String) =
            Result.success(app.pulse.domain.model.GroupLeave(remainingMembers = 0))
        override suspend fun createGroupInvite(conversationId: String, regenerate: Boolean) = Result.success("code")
        override suspend fun setDisappearingTtl(conversationId: String, ttlSeconds: Int) = Result.success(ttlSeconds)
        override suspend fun setSlowMode(conversationId: String, seconds: Int) = Result.success(seconds)
        override suspend fun scheduledMessages(conversationId: String) =
            Result.success(emptyList<app.pulse.domain.model.ScheduledItem>())
        override suspend fun scheduleMessage(conversationId: String, content: String, scheduledAtIso: String) =
            Result.failure<app.pulse.domain.model.ScheduledItem>(UnsupportedOperationException())
        override suspend fun cancelScheduled(scheduledId: String) = Result.success(Unit)
        override suspend fun sendRichMessage(
            conversationId: String,
            body: String,
            kind: String,
            payload: String?,
            anon: Boolean,
            replyToId: String?,
            parentId: String?,
            topicId: String?,
        ): Result<SendReceipt> = Result.failure(UnsupportedOperationException())

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

        // ── R1-W2F2 fix — the R1-W2A quick-phrase members this fake never
        // implemented; PulseRepository requires them, so the domain test
        // source set did not compile without these stubs. ──
        override suspend fun phrases() = Result.success(emptyList<app.pulse.domain.model.QuickPhrase>())
        override suspend fun addPhrase(text: String) =
            Result.failure<app.pulse.domain.model.QuickPhrase>(UnsupportedOperationException())
        override suspend fun deletePhrase(phraseId: String) = Result.success(Unit)

        // ── R1-W2F — F-MD-06/F-FX-05 (unused by the outbox policy) ──
        override suspend fun translateMessage(messageId: String): Result<String> =
            Result.failure(UnsupportedOperationException())
        override val convThemes: kotlinx.coroutines.flow.Flow<Map<String, ConvTheme>> =
            MutableStateFlow(emptyMap<String, ConvTheme>())
        override suspend fun setConvTheme(conversationId: String, theme: ConvTheme?) {}
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

        // Wave 7 — collaboration & hub stubs (fakes never exercise these paths)
        override suspend fun createRedPacket(conversationId: String, total: Long, count: Int, note: String?): Result<app.pulse.protocol.RedPacketCreateResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun redPacket(packetId: String): Result<app.pulse.protocol.RedPacketDetailDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun grabRedPacket(packetId: String): Result<app.pulse.protocol.RedPacketGrabResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun whiteboard(conversationId: String, since: Long?): Result<app.pulse.protocol.WhiteboardPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun postWhiteboardStrokes(conversationId: String, strokes: List<app.pulse.protocol.WhiteboardStrokePostDto>): Result<app.pulse.protocol.WhiteboardPostResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun undoWhiteboardStroke(conversationId: String): Result<app.pulse.protocol.WhiteboardUndoResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun clearWhiteboard(conversationId: String): Result<app.pulse.protocol.WhiteboardClearResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun kanbanBoard(conversationId: String): Result<app.pulse.protocol.KanbanPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun createKanbanCard(conversationId: String, title: String?, column: String?, assigneeId: String?, messageId: String?): Result<app.pulse.protocol.KanbanCardDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun updateKanbanCard(cardId: String, title: String?, column: String?, assigneeId: String?, clearAssignee: Boolean, position: Long?): Result<app.pulse.protocol.KanbanCardDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun deleteKanbanCard(cardId: String): Result<Unit> = Result.failure(IllegalStateException("fake"))
        override suspend fun events(conversationId: String): Result<app.pulse.protocol.EventsPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun createEvent(conversationId: String, title: String, startsAtIso: String, description: String?, location: String?): Result<app.pulse.protocol.GroupEventDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun deleteEvent(eventId: String): Result<Unit> = Result.failure(IllegalStateException("fake"))
        override suspend fun rsvpEvent(eventId: String, status: String): Result<app.pulse.protocol.RsvpResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun checkinEvent(eventId: String): Result<app.pulse.protocol.CheckinResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun reminders(dueOnly: Boolean): Result<app.pulse.protocol.RemindersPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun createReminder(conversationId: String, messageId: String?, note: String?, remindAtIso: String): Result<app.pulse.protocol.ReminderItemDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun resolveReminder(reminderId: String): Result<app.pulse.protocol.ReminderResolveDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun deleteReminder(reminderId: String): Result<Unit> = Result.failure(IllegalStateException("fake"))
        override suspend fun createGame(conversationId: String, opponentId: String?): Result<app.pulse.protocol.GameMatchCreateResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun games(conversationId: String): Result<app.pulse.protocol.GamesPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun game(matchId: String): Result<app.pulse.protocol.GameDetailDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun gameMove(matchId: String, cell: Int): Result<app.pulse.protocol.GameDetailDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun joinGame(matchId: String): Result<app.pulse.protocol.GameDetailDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun createTournament(conversationId: String, name: String): Result<app.pulse.protocol.TournamentCreateResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun tournaments(conversationId: String): Result<app.pulse.protocol.TournamentsPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun tournament(tournamentId: String): Result<app.pulse.protocol.TournamentSummaryDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun finishTournament(tournamentId: String): Result<app.pulse.protocol.TournamentSummaryDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun joinTournament(tournamentId: String): Result<app.pulse.protocol.TournamentJoinResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun leaderboard(conversationId: String?): Result<app.pulse.protocol.LeaderboardPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun wallet(): Result<app.pulse.protocol.WalletPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun checkinWallet(): Result<app.pulse.protocol.CheckinWalletResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun transferCoins(toUsername: String, amount: Long, note: String?): Result<app.pulse.protocol.TransferResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun swapRates(): Result<app.pulse.protocol.SwapPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun swap(direction: String, amount: Long): Result<app.pulse.protocol.SwapResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun hubTasks(): Result<app.pulse.protocol.HubTasksPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun createHubTask(title: String, status: String?): Result<app.pulse.protocol.HubTaskDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun updateHubTask(taskId: String, title: String?, status: String?): Result<app.pulse.protocol.HubTaskDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun deleteHubTask(taskId: String): Result<Unit> = Result.failure(IllegalStateException("fake"))
        override suspend fun market(): Result<app.pulse.protocol.MarketPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun createListing(title: String, description: String?, price: Long): Result<app.pulse.protocol.MarketListingDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun buyListing(listingId: String): Result<app.pulse.protocol.MarketBuyResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun hubLogs(limit: Int, kind: String?): Result<app.pulse.protocol.HubLogsPageDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun appInstallState(appId: String): Result<app.pulse.protocol.AppInstallStateDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun installApp(appId: String): Result<app.pulse.protocol.AppInstallResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun uninstallApp(appId: String): Result<app.pulse.protocol.AppInstallResultDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun appCommunity(appId: String): Result<app.pulse.protocol.AppCommunityDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun joinAppCommunity(appId: String): Result<app.pulse.protocol.AppCommunityDto> = Result.failure(IllegalStateException("fake"))
        override suspend fun cachedWallet(): app.pulse.protocol.WalletPageDto? = null
        override suspend fun cachedHubTasks(): app.pulse.protocol.HubTasksPageDto? = null
        override suspend fun cachedReminders(): app.pulse.protocol.RemindersPageDto? = null

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
