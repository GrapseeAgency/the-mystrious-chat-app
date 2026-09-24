package app.pulse.domain.repository

import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Automation
import app.pulse.domain.model.BlockedAccount
import app.pulse.domain.model.CallLogEntry
// R1-W2F — per-conversation themes (F-FX-05).
import app.pulse.domain.model.ConvTheme
import app.pulse.domain.model.CallSignalData
import app.pulse.domain.model.CallSignalOut
import app.pulse.domain.model.Channel
import app.pulse.domain.model.FlushReport
import app.pulse.domain.model.FolderSummary
import app.pulse.domain.model.GroupLeave
import app.pulse.domain.model.GroupMeta
import app.pulse.domain.model.HandleCheck
import app.pulse.domain.model.InviteJoinOutcome
import app.pulse.domain.model.InvitePreview
import app.pulse.domain.model.Message
import app.pulse.domain.model.MessageHit
import app.pulse.domain.model.MentionItem
import app.pulse.domain.model.OutboxEntry
import app.pulse.domain.model.ProfilePatch
import app.pulse.domain.model.QuickPhrase
import app.pulse.domain.model.SavedItem
import app.pulse.domain.model.SafetyState
import app.pulse.domain.model.ScheduledItem
import app.pulse.domain.model.SendReceipt
import app.pulse.domain.model.StoryGroup
import app.pulse.domain.model.StoryItem
import app.pulse.domain.model.StoryViewer
import app.pulse.domain.model.Topic
import app.pulse.domain.model.TranscribeOutcome
import app.pulse.domain.model.User
import app.pulse.domain.model.UserProfile
import app.pulse.domain.model.UserStats
import app.pulse.domain.model.Webhook
import app.pulse.protocol.AiRecapDto
import app.pulse.protocol.PulseVoiceUser
import app.pulse.protocol.AppCommunityDto
import app.pulse.protocol.AppInstallResultDto
import app.pulse.protocol.AppInstallStateDto
import app.pulse.protocol.CheckinResultDto
import app.pulse.protocol.CheckinWalletResultDto
import app.pulse.protocol.EventsPageDto
import app.pulse.protocol.GameDetailDto
import app.pulse.protocol.GameMatchCreateResultDto
import app.pulse.protocol.GamesPageDto
import app.pulse.protocol.GroupEventDto
import app.pulse.protocol.HubLogsPageDto
import app.pulse.protocol.HubTaskDto
import app.pulse.protocol.HubTasksPageDto
import app.pulse.protocol.KanbanCardDto
import app.pulse.protocol.KanbanPageDto
import app.pulse.protocol.LeaderboardPageDto
import app.pulse.protocol.MarketBuyResultDto
import app.pulse.protocol.MarketListingDto
import app.pulse.protocol.MarketPageDto
import app.pulse.protocol.ReminderItemDto
import app.pulse.protocol.ReminderResolveDto
import app.pulse.protocol.RemindersPageDto
import app.pulse.protocol.RedPacketCreateResultDto
import app.pulse.protocol.RedPacketDetailDto
import app.pulse.protocol.RedPacketGrabResultDto
import app.pulse.protocol.RsvpResultDto
import app.pulse.protocol.SpaceStatePayload
import app.pulse.protocol.SwapPageDto
import app.pulse.protocol.SwapResultDto
import app.pulse.protocol.StageEndedPayload
import app.pulse.protocol.StageStatePayload
import app.pulse.protocol.TournamentCreateResultDto
import app.pulse.protocol.TournamentJoinResultDto
import app.pulse.protocol.TournamentSummaryDto
import app.pulse.protocol.TournamentsPageDto
import app.pulse.protocol.TransferResultDto
import app.pulse.protocol.VoiceChunkPayload
import app.pulse.protocol.VoicePttPayload
import app.pulse.protocol.VoiceRosterPayload
import app.pulse.protocol.VoiceTranscriptPayload
import app.pulse.protocol.VoiceTranscriptResultDto
import app.pulse.protocol.WalletPageDto
import app.pulse.protocol.WhiteboardClearResultDto
import app.pulse.protocol.WhiteboardPageDto
import app.pulse.protocol.WhiteboardPostResultDto
import app.pulse.protocol.WhiteboardStrokePostDto
import app.pulse.protocol.WhiteboardUndoResultDto
import kotlinx.coroutines.flow.Flow

/** Live events pushed by the relay — the UDF event side of the repository. */
sealed interface PulseEvent {
    data class MessageReceived(val conversationId: String, val message: Message) : PulseEvent
    data class MessageDeleted(val conversationId: String, val messageId: String) : PulseEvent
    data class MessageRead(val conversationId: String, val userId: String, val at: String?) : PulseEvent
    data class Typing(
        val conversationId: String,
        val userId: String,
        val userName: String,
        val isTyping: Boolean,
    ) : PulseEvent

    // ── offline outbox (Wave 0) ─────────────────────────────────
    /** A send failed network-class and now waits in the outbox (`local_<clientId>` bubble). */
    data class OutboxQueued(val clientId: String, val conversationId: String) : PulseEvent
    /** A queued send hit a definitive 4xx verdict — the entry and its temp bubble are gone. */
    data class OutboxDropped(val clientId: String, val reason: String) : PulseEvent
    /** A queued send delivered — the temp row was swapped for the real message. */
    data class OutboxFlushed(val clientId: String, val message: Message) : PulseEvent

    // ── native 1:1 calls (Wave 3) ──────────────────────────────
    /** A call:* relay signal reached this device (offer/answer/ice/reject/cancel/hangup). */
    data class CallSignal(val signal: CallSignalData) : PulseEvent

    // ── voice rooms / stage / space (Wave 5) ──────────────────
    /** S→C voice:roster — wholesale roster replace (joiners + leavers). */
    data class VoiceRoster(val payload: VoiceRosterPayload) : PulseEvent
    /** S→C voice:ptt — push-to-talk latch (echoed to the sender too). */
    data class VoicePtt(val payload: VoicePttPayload) : PulseEvent
    /** S→C voice:chunk — one 250ms PCM frame from a peer (never from self). */
    data class VoiceChunk(val payload: VoiceChunkPayload) : PulseEvent
    /** S→C voice:transcript — an ephemeral live caption. */
    data class VoiceTranscript(val payload: VoiceTranscriptPayload) : PulseEvent
    /** S→C stage:state — full stage roster (room object rides as JsonElement). */
    data class StageState(val payload: StageStatePayload) : PulseEvent
    /** S→C stage:ended — the host closed the stage. */
    data class StageEnded(val payload: StageEndedPayload) : PulseEvent
    /** S→C space:state — full spatial board (room object rides as JsonElement). */
    data class SpaceState(val payload: SpaceStatePayload) : PulseEvent
}

/** Device-side preferences (DataStore on Android, UserDefaults on iOS). */
interface PulsePrefsStore {
    val viewerId: Flow<String?>
    val viewerName: Flow<String?>
    /** The avatar palette key chosen at onboarding ("emerald"…"cyan") — header avatar. */
    val viewerColor: Flow<String?>
    val fxMode: Flow<String>
    val darkOverride: Flow<String>
    val reducedMotion: Flow<Boolean>
    /** Persisted chats-list filter — "all" | "unread" | "groups" (web ChatsListFilter). */
    val chatsListFilter: Flow<String>
    /** Optional user-set gateway origin ("https://host") — null/empty = offline-first. */
    val serverBase: Flow<String?>
    /** Voice playback speed (1x/1.5x/2x) — Wave 2 spec §1 row 12, applied live + on (re)start. */
    val voiceRate: Flow<Float>
    /** Live-caption toggle for voice rooms (web parity key pulse-voice-captions). */
    val voiceCaptions: Flow<Boolean>

    // ── Wave 8 — LOCAL settings (web pulse.settings.v1 parity) ─────
    // These never ride the server prefs blob — the web keeps them in a
    // localStorage zustand store; native keeps them in DataStore.

    /** Haptic feedback master toggle (web hapticsOn, default true). */
    val hapticsOn: Flow<Boolean>
    /** Quiet hours — silence incoming pings/haptics inside the window. */
    val quietHoursOn: Flow<Boolean>
    /** Quiet window start 'HH:mm' 24h LOCAL time (default 22:00). */
    val quietStart: Flow<String>
    /** Quiet window end 'HH:mm' 24h LOCAL time — may be < start → overnight (default 07:00). */
    val quietEnd: Flow<String>

    suspend fun setHapticsOn(value: Boolean)
    suspend fun setQuietHoursOn(value: Boolean)
    suspend fun setQuietStart(value: String)
    suspend fun setQuietEnd(value: String)

    // ── R2-C — design language + spotlight + whiteboard draft ──────

    /**
     * R2-C item 3 — design-language selection. EXACTLY the web value strings
     * (src/lib/ui-theme.ts): "glass" | "kinetic" | "minimal" | "dynamic" |
     * "aero", persisted under the web key `pulse.uiTheme.v2`.
     */
    val uiTheme: Flow<String>
    suspend fun setUiTheme(id: String)

    /**
     * R2-C item 7 — spotlight recent searches (web spotlight.tsx:50-73
     * parity): last 5 queries, deduped case-insensitively, newest first,
     * stored under the web key `pulse.spotlight.recents.v1`.
     */
    val spotlightRecents: Flow<List<String>>
    suspend fun pushSpotlightRecent(query: String)
    suspend fun clearSpotlightRecents()

    /**
     * R4-B item 3 — navigation architecture (web nav-registry.ts parity):
     * one of the 8 phone-feasible PulseNavStyle ids, persisted under the
     * web's EXACT key `pulse.navStyle.v2`; unreadable values (junk, the 5
     * excluded ids) resolve to the capsule default on read.
     */
    val navStyle: Flow<app.pulse.protocol.PulseNavStyle>
    suspend fun setNavStyle(style: app.pulse.protocol.PulseNavStyle)

    /**
     * R2-C item 4 — the whiteboard PENDING-stroke draft (iOS
     * PulseWhiteboardDraft parity): per-conversation durable queue of
     * unsynced strokes — write-through on draw, restore on sheet open,
     * purge on the server verdict / board-reset path.
     */
    suspend fun whiteboardDraft(conversationId: String): List<app.pulse.protocol.WhiteboardStrokePostDto>
    suspend fun appendWhiteboardDraft(conversationId: String, stroke: app.pulse.protocol.WhiteboardStrokePostDto)
    suspend fun dropFirstWhiteboardDraft(conversationId: String, count: Int)
    suspend fun dropLastWhiteboardDraft(conversationId: String)
    suspend fun replaceAllWhiteboardDraft(conversationId: String, strokes: List<app.pulse.protocol.WhiteboardStrokePostDto>)
    suspend fun clearWhiteboardDraft(conversationId: String)

    suspend fun setViewer(id: String?, name: String?, color: String? = null)
    suspend fun setFxMode(mode: String)
    suspend fun setDarkOverride(value: String)
    suspend fun setReducedMotion(value: Boolean)
    suspend fun setChatsListFilter(value: String)
    suspend fun setServerBase(value: String?)
    suspend fun setVoiceRate(value: Float)
    suspend fun setVoiceCaptions(value: Boolean)
}

/** Contract every Pulse data source (remote-first, Room cache) must honor. */
interface PulseRepository {
    /** Bind the client to a viewer identity (REST userId + socket join). Idempotent per id. */
    fun start(userId: String)

    val viewerId: String?

    fun observeConversations(query: String = ""): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<Message>>
    fun observePresence(): Flow<Set<String>>
    fun events(): Flow<PulseEvent>

    /** Live relay connection truth — false = the offline banner tells the truth. */
    fun observeConnected(): Flow<Boolean>

    // ── Wave 8 — session tokens + user preferences ──────────────

    /**
     * Live user preferences (server blob merged over the canonical defaults,
     * kept in the local DataStore store; server value wins on fetch, toggles
     * apply locally FIRST). Tolerant decode — unknown/missing fields never
     * break the flow.
     */
    val pulsePrefs: Flow<app.pulse.protocol.WirePulsePrefs>

    /**
     * Optimistic prefs update: the local store applies the patch FIRST
     * (instant UI), then PATCH /api/settings runs. A failed PATCH keeps the
     * local change (honest offline parity with the web store) and returns a
     * failure so the surface can hint at it.
     */
    suspend fun updatePulsePrefs(patch: app.pulse.protocol.WirePulsePrefs): Result<Unit>

    /**
     * POST /api/users/login { name } — reclaim an existing identity and
     * ROTATE the session token (the old credential goes invalid). The
     * returned user mirrors the lookup path; the token persists in the
     * secure store before Result.success surfaces.
     */
    suspend fun login(name: String): Result<User>

    /**
     * Identity forget/switch — the stored session credential must not
     * outlive the identity it belongs to. Clears the encrypted token store;
     * requests after this ride header-less (optional-verify window).
     */
    suspend fun clearSessionToken()

    /** Data & Storage manager — drop ONE held outbox row (+ its temp bubble). */
    suspend fun discardOutboxEntry(clientId: String)

    /** Data & Storage manager — drop EVERY held outbox row. */
    suspend fun clearOutbox()

    /** Data & Storage manager — drop every composer draft at once. */
    suspend fun clearAllDrafts()

    /** Thread replies for one root (replies asc) — the ThreadScreen rehydration flow. */
    fun observeThreadMessages(rootId: String): Flow<List<Message>>

    /** Live per-conversation drafts (chats-list "Draft:" preview merge — local wins). */
    fun observeDrafts(): Flow<Map<String, String>>

    /** Pull the latest lists from the gateway into the local cache. */
    suspend fun refreshConversations(): Result<Unit>
    suspend fun refreshMessages(conversationId: String, limit: Int = 200): Result<Unit>

    /**
     * Topic-filtered refresh (Wave 2): fetches only the messages filed under
     * `topicId` (wire `&topicId=`) and upserts them. The unfiltered overload
     * above stays the General/whole-room path.
     */
    suspend fun refreshMessages(conversationId: String, topicId: String?): Result<Unit>

    suspend fun users(query: String = ""): Result<List<User>>
    suspend fun createIdentity(name: String, color: String?, username: String? = null): Result<User>
    /** Live @handle availability for the onboarding picker (web check-username). */
    suspend fun checkHandle(handle: String): Result<HandleCheck>
    /** Case-insensitive name lookup (onboarding "that's me — log in"); null when free. */
    suspend fun lookupUserByName(name: String): Result<User?>
    suspend fun createDm(otherUserId: String): Result<Conversation>
    suspend fun createGroup(name: String, memberIds: List<String>): Result<Conversation>

    suspend fun me(): User?
    suspend fun sendMessage(
        conversationId: String,
        body: String,
        replyToId: String? = null,
        /** Thread reply: the THREAD ROOT id — never conflated with replyToId (spec §1.1). */
        parentId: String? = null,
        /** Wave 2 topic filing — dropped on thread replies (spec §1 row 10). */
        topicId: String? = null,
    ): Result<SendReceipt>
    suspend fun markRead(conversationId: String): Result<Unit>
    suspend fun setTyping(conversationId: String, userName: String, typing: Boolean)
    suspend fun react(messageId: String, emoji: String): Result<Unit>
    suspend fun togglePin(conversationId: String, pinned: Boolean): Result<Unit>
    suspend fun setMuted(conversationId: String, muted: Boolean): Result<Unit>
    suspend fun archive(conversationId: String, archived: Boolean): Result<Unit>
    suspend fun block(userId: String): Result<Unit>
    suspend fun unblock(userId: String): Result<Unit>
    suspend fun report(userId: String, reason: String, details: String?): Result<Unit>

    // ── N10 home-page era (HOMEPAGE-SPEC §8/§14) ────────────────────

    /** PATCH /mark-unread {userId, on} — Telegram mark-as-unread dot. */
    suspend fun markUnread(conversationId: String, on: Boolean): Result<Unit>

    /** PATCH /mute {userId, until} — until: "8h" | "1w" | "always" | null. */
    suspend fun setMutedUntil(conversationId: String, until: String?): Result<Unit>

    /** POST /api/conversations/self {userId} — the viewer's Note to Self chat. */
    suspend fun createSelfChat(): Result<Conversation>

    /** GET /api/stories?requesterId= — full story groups (D2 expiry-filtered client-side; v8 cache offline). */
    suspend fun stories(): Result<List<StoryGroup>>

    /** POST /api/stories {requesterId, caption?, background?, imagePath?} → 201 fresh story (viewCount 0). */
    suspend fun createStory(caption: String, background: String?, imagePath: String?): Result<StoryItem>

    /** POST /api/stories/{id}/view {requesterId} → fresh viewCount (idempotent; owner short-circuits). */
    suspend fun markStoryViewed(storyId: String): Result<Int>

    /** GET /api/stories/{id}/view?requesterId= — owner-only viewers list, oldest first (403 otherwise). */
    suspend fun storyViewers(storyId: String): Result<List<StoryViewer>>

    /** DELETE /api/stories/{id}?requesterId= — owner-only removal ({ok:true} / 403 / 404). */
    suspend fun deleteStory(storyId: String): Result<Unit>

    /** GET /api/folders?userId= — failure degrades to All-only rail. */
    suspend fun folders(): Result<List<FolderSummary>>

    /** GET /api/mentions?userId= — failure yields an honest 0 count. */
    suspend fun mentions(): Result<List<MentionItem>>

    /** GET /api/search?userId=&q= — server message search (≥2 chars, caller debounces). */
    suspend fun searchMessages(query: String): Result<List<MessageHit>>

    /** Full paginated-ish history for export/clear (single bounded fetch). */
    suspend fun fullHistory(conversationId: String): Result<List<Message>>

    /** DELETE /api/messages/{id} {requesterId} — sender-gated soft delete. */
    suspend fun deleteMessage(messageId: String): Result<Unit>

    /** Export the room transcript to a .txt file in app cache — returns the file name. */
    suspend fun exportChat(conversationId: String): Result<String>

    /** Soft-delete MY OWN non-deleted messages sequentially — returns the cleared count. */
    suspend fun clearMyMessages(conversationId: String): Result<Int>

    // ── offline outbox + drafts (Wave 0 — web pulse-outbox/pulse-drafts parity) ──

    /** Persist an outgoing message for later delivery (FIFO, capped at 50). */
    suspend fun enqueueOutbox(entry: OutboxEntry): Result<Unit>

    /** Live outbox rows (oldest first) — pending-bubble state + worker triggering. */
    fun observeOutbox(): Flow<List<OutboxEntry>>

    /** FIFO snapshot (≤50 oldest) the drain policy walks. */
    suspend fun outboxPending(): List<OutboxEntry>

    /** ONE delivery attempt for an outbox entry — never mutates outbox state itself. */
    suspend fun attemptOutboxSend(entry: OutboxEntry): Result<Message>

    /** Delivery success: swap the temp `local_<clientId>` row for the real message + drop the outbox row. */
    suspend fun resolveOutboxDelivery(entry: OutboxEntry, real: Message)

    /** Definitive 4xx verdict: delete the outbox row + temp row (+ OutboxDropped event). */
    suspend fun dropOutboxEntry(clientId: String, reason: String)

    /** Retryable failure: attempts++ on the held entry. */
    suspend fun bumpOutboxAttempts(clientId: String)

    /** Full drain with the standard policy — every flush trigger funnels here. */
    suspend fun flushOutbox(): FlushReport

    /** Persist the composer draft (≤2000 chars; blank clears). */
    suspend fun saveDraft(conversationId: String, text: String)

    /** Drop the local composer draft (send delivered / composer cleared). */
    suspend fun clearDraft(conversationId: String)

    /** Live draft text for the composer restore (null when none). */
    fun observeDraft(conversationId: String): Flow<String?>

    // ── Wave 1 messaging surface (spec WAVE1 §1.1 — all routes exist on the wire) ──

    /** PATCH /api/messages/{id} {userId, content} — sender-only edit → fresh row (editedAt set). */
    suspend fun editMessage(messageId: String, content: String): Result<Message>

    /** POST /api/messages/{id}/pin {userId} — toggle → fresh row (pinnedAt/pinnedBy). */
    suspend fun toggleMessagePin(messageId: String): Result<Message>

    /** POST /api/messages/{id}/save {userId} — toggle → the new saved state. */
    suspend fun toggleMessageSave(messageId: String): Result<Boolean>

    /** GET /api/conversations/{id}/pinned?userId= — pinned rows, pinnedAt asc (also upserts Room). */
    suspend fun pinnedMessages(conversationId: String): List<Message>

    /** GET /api/messages/{id}/thread?userId= → (parent, replies asc) — also upserts Room. */
    suspend fun loadThread(rootId: String): Pair<Message, List<Message>>

    /**
     * GET /api/conversations/{id}/messages?limit=&before= → (rows asc, hasMore).
     * Older page of the timeline; rows are upserted into Room (pagination merge).
     */
    suspend fun messagesPage(conversationId: String, before: String?, limit: Int = 40): Pair<List<Message>, Boolean>

    /** GET /api/conversations/{id}/messages?limit=100&q= — in-conversation search (hits upserted). */
    suspend fun searchInConversation(conversationId: String, query: String): List<Message>

    /** POST /api/uploads {dataUrl} → the stored filePath (media send URLs ride it). */
    suspend fun uploadMedia(dataUrl: String): Result<String>

    /** Forward = re-POST the source's content + media fields into the target conversation. */
    suspend fun forwardMessage(targetConversationId: String, source: Message): Result<Message>

    /** PATCH /api/conversations/{id}/draft {userId, draft} — server mirror, fire-and-forget silent fail. */
    suspend fun setServerDraft(conversationId: String, draft: String)

    /** GET /api/conversations/{id}?userId= — detail incl. members (read watermarks); upserts Room. */
    suspend fun conversationDetail(conversationId: String): Result<Conversation>

    /**
     * Send a MEDIA message (image/file) — online-only, NEVER queued (spec §1.2:
     * media sends that fail surface an error, never an outbox row).
     * `kind` rides the wire whitelist ("file" for documents; null → server
     * default for images where `imagePath` presence is the signal).
     */
    suspend fun sendMediaMessage(
        conversationId: String,
        body: String,
        imagePath: String? = null,
        audioPath: String? = null,
        durationMs: Long? = null,
        filePath: String? = null,
        fileName: String? = null,
        fileSize: Long? = null,
        kind: String? = null,
        viewOnce: Boolean? = null,
        topicId: String? = null,
    ): Result<Message>

    /** GET /api/uploads/{file} → bytes saved under cacheDir/downloads — returns the absolute path. */
    suspend fun downloadMedia(filePath: String): Result<String>

    /** Reply counts for river parent bubbles ("N replies ↳") — one batched Room query. */
    suspend fun threadReplyCounts(rootIds: List<String>): Map<String, Int>

    // ── REM-A — group governance + scheduling + rich sends ─────────────

    /**
     * GET /api/conversations/{id}?userId= → the group meta subset (roles, TTL,
     * broadcast, slow mode, screen privacy, invite code) as live server truth.
     * Also upserts the Room conversation cache like [conversationDetail].
     */
    suspend fun groupMeta(conversationId: String): Result<GroupMeta>

    /** PATCH /api/conversations/{id} { name } — group rename (admin-only server-side). */
    suspend fun renameGroup(conversationId: String, name: String): Result<Unit>

    /** PATCH /api/conversations/{id} { broadcast } — toggle announcement mode (admin-only). */
    suspend fun setGroupBroadcast(conversationId: String, broadcast: Boolean): Result<Unit>

    /** PATCH /api/conversations/{id} { screenPrivacy } — any participant may toggle. */
    suspend fun setScreenPrivacy(conversationId: String, on: Boolean): Result<Unit>

    // ── R2-A — round-2 parity (automations · webhooks · recap · privacy · photo) ──

    /** PATCH /api/conversations/{id}/screen-privacy { userId, on } — R42 the per-VIEWER veil flag. */
    suspend fun setMyScreenPrivacy(conversationId: String, on: Boolean): Result<Unit>

    /** PATCH /api/conversations/{id} { photo } — admin-only; photo is an uploaded "/api/uploads/<file>" path. */
    suspend fun setGroupPhoto(conversationId: String, photoPath: String): Result<Unit>

    /** GET /api/conversations/{id}/automations?userId= — the room's keyword auto-reply rules. */
    suspend fun automations(conversationId: String): Result<List<Automation>>

    /** POST /api/conversations/{id}/automations { trigger, reply } — admin-only. */
    suspend fun createAutomation(conversationId: String, trigger: String, reply: String): Result<Automation>

    /** PATCH /api/automations/{id} { enabled } — admin-only optimistic toggle. */
    suspend fun setAutomationEnabled(automationId: String, enabled: Boolean): Result<Automation>

    /** PATCH /api/automations/{id} { trigger } — R41 rename-in-place (admin-only). */
    suspend fun setAutomationTrigger(automationId: String, trigger: String): Result<Automation>

    /** DELETE /api/automations/{id} { userId } — admin-only remove. */
    suspend fun deleteAutomation(automationId: String): Result<Unit>

    /** GET /api/webhooks?conversationId=&requesterId= — Discord-style incoming hooks. */
    suspend fun webhooks(conversationId: String): Result<List<Webhook>>

    /** POST /api/webhooks { conversationId, name, requesterId } — admin-only. */
    suspend fun createWebhook(conversationId: String, name: String): Result<Webhook>

    /** DELETE /api/webhooks/{token}?requesterId= — admin-only. */
    suspend fun deleteWebhook(token: String): Result<Unit>

    /**
     * POST /api/ai/recap { userId, conversationId } — LLM summary of the last
     * ~30 rows → { recap, basedOn, cached } (server caches the latest recap).
     */
    suspend fun aiRecap(conversationId: String): Result<AiRecapDto>

    /** POST /api/conversations/{id}/members { userIds[] } — admin-only add → the ids actually added. */
    suspend fun addGroupMembers(conversationId: String, userIds: List<String>): Result<List<String>>

    /** PATCH /api/conversations/{id}/members/{userId} { action } — promote/demote (admin-only). */
    suspend fun setMemberRole(conversationId: String, userId: String, promote: Boolean): Result<Unit>

    /** DELETE /api/conversations/{id}/members/{userId} — kick a non-admin member (admin-only). */
    suspend fun kickMember(conversationId: String, userId: String): Result<Unit>

    /** DELETE /api/conversations/{id}/members — LEAVE group (last-admin succession server-side). */
    suspend fun leaveGroup(conversationId: String): Result<GroupLeave>

    /** POST /api/conversations/{id}/invite — admin-only lazy create / regenerate → the code. */
    suspend fun createGroupInvite(conversationId: String, regenerate: Boolean): Result<String>

    /** PATCH /api/conversations/{id}/disappearing { ttlSeconds } — presets 0/86400/604800/2592000. */
    suspend fun setDisappearingTtl(conversationId: String, ttlSeconds: Int): Result<Int>

    /** PATCH /api/conversations/{id}/slow-mode { seconds } — admin-only; presets 0/5/10/30/60/300. */
    suspend fun setSlowMode(conversationId: String, seconds: Int): Result<Int>

    /** GET /api/conversations/{id}/scheduled?userId= — THIS viewer's pending delayed sends. */
    suspend fun scheduledMessages(conversationId: String): Result<List<ScheduledItem>>

    /** POST /api/conversations/{id}/scheduled { content, scheduledAt } — 30s..30d horizon server-side. */
    suspend fun scheduleMessage(conversationId: String, content: String, scheduledAtIso: String): Result<ScheduledItem>

    /** DELETE /api/scheduled/{id} { requesterId } — owner-only cancel. */
    suspend fun cancelScheduled(scheduledId: String): Result<Unit>

    /**
     * Rich TEXT-kind send — carries the incognito flag (group-only server-side)
     * and the sticker/effects payload blob (sticker {emoji,pack} · {effect}).
     * Optimistic echo + outbox semantics match [sendMessage].
     *
     * R3-B item 4 — [anonAliasPreview] stamps the OPTIMISTIC echo with the
     * deterministic "Adjective the Animal" alias (web anonAliasPreview parity,
     * chat-room.tsx:1685-1715) so the queued/displayed row already wears the
     * exact mask the server will store.
     *
     * R5 — returns the same [SendReceipt] (message + optional streak bump)
     * as the plain send.
     */
    suspend fun sendRichMessage(
        conversationId: String,
        body: String,
        kind: String = "text",
        payload: String? = null,
        anon: Boolean = false,
        anonAliasPreview: String? = null,
        replyToId: String? = null,
        parentId: String? = null,
        topicId: String? = null,
    ): Result<SendReceipt>

    // ── Wave 2 messaging depth (spec WAVE2 §0 — all routes exist on the wire) ──

    /**
     * POST /api/messages/{id}/transcribe {requesterId} — voice notes only.
     * On success the cached Room row is patched (transcript + transcribedAt)
     * so every surface observing it updates. cached=true = server ASR cache hit.
     */
    suspend fun transcribeMessage(messageId: String): Result<TranscribeOutcome>

    /**
     * POST /api/messages/{id}/viewed {userId} — consume a view-once photo.
     * The returned authoritative row is upserted (viewedAt stamp); other
     * members learn it via the message:viewed relay. Failures are logged
     * only — the gate tap can be retried.
     */
    suspend fun markMessageViewed(messageId: String)

    /** POST /api/conversations/{id}/poll {senderId, question, options[]} → fresh poll row (upserted). */
    suspend fun createPoll(conversationId: String, question: String, options: List<String>): Result<Message>

    /** POST /api/polls/{id}/vote {userId, optionId} → fresh tally row (upserted). */
    suspend fun votePoll(pollId: String, optionId: String): Result<Message>

    /** POST /api/polls/{id}/close {userId} — creator-only; freezes the tally (upserted). */
    suspend fun closePoll(pollId: String): Result<Message>

    /**
     * POST /api/messages/{id}/unfurl {userId} — fire-and-forget Open-Graph
     * fetch after OWN sends. A non-null returned row is upserted (linkPreview
     * attached); null (= nothing unfurled) and failures are ignored/logged.
     */
    suspend fun unfurlMessage(messageId: String)

    /**
     * GET /api/users/{id}/saved — refetch the saved library (newest-first,
     * server cap 100). Upserts the carried message rows + savedMessages rows
     * and PRUNES local rows the server no longer lists (server is truth).
     */
    suspend fun refreshSavedLibrary(): Result<List<SavedItem>>

    /** Live saved library from the Room cache (skips messages missing from cache gracefully). */
    fun observeSavedLibrary(): Flow<List<SavedItem>>

    /** POST /api/messages/{id}/save — reuse the toggle; unsaves (saved=false) also drops the local row. */
    suspend fun unsaveMessage(messageId: String): Result<Boolean>

    /** GET /api/conversations/{id}/topics?userId= — refresh the topic rail (prune not-in-response rows). */
    suspend fun refreshTopics(conversationId: String): Result<Unit>

    /** Live topic chips for one conversation (General is NOT a row — UI prepends it). */
    fun observeTopics(conversationId: String): Flow<List<Topic>>

    /** POST /api/conversations/{id}/topics {userId, name, emoji?} → 200 existing / 201 new; rail refreshes. */
    suspend fun createTopic(conversationId: String, name: String, emoji: String = "💬"): Result<Topic>

    /**
     * DELETE /api/topics/{id}?userId= — creator/admin-only. Returns success
     * with the deletion applied; the caller resets an active topic that got
     * deleted (messages fall back to General server-side).
     */
    suspend fun deleteTopic(conversationId: String, topicId: String): Result<Unit>

    // ── native 1:1 calls (Wave 3) ─────────────────────────────

    /** Live call history from the Room cache (offline-first mirror of GET /api/calls). */
    fun observeCallLog(): Flow<List<CallLogEntry>>

    /** GET /api/calls?userId= — refetch history (newest first, server cap 50); prunes rows the server no longer lists. */
    suspend fun refreshCallLog(): Result<List<CallLogEntry>>

    /**
     * POST /api/calls — SINGLE-WRITER: the caller writes the terminal row.
     * A network-class failure enqueues the exact payload locally (UNIQUE
     * dedupe) for the next flush; the row also lands in the local cache so
     * the list is instant.
     */
    suspend fun writeCallLog(entry: CallLogEntry): Result<Unit>

    /** FIFO drain of the queued single-writer rows; stops at the first network-class failure (outbox parity). */
    suspend fun flushCallLogQueue(): Result<Int>

    /** Emit one call:* signaling payload (offer/answer/ice/reject/cancel/hangup). */
    suspend fun emitCall(signal: CallSignalOut)

    // ── Wave 5 voice rooms / stage / space (all best-effort emits) ─────
    /** voice:join — registers this device's voice seat; re-emitted on reconnect. */
    suspend fun emitVoiceJoin(conversationId: String, user: PulseVoiceUser)
    suspend fun emitVoiceLeave(conversationId: String)
    suspend fun emitVoicePtt(conversationId: String, userId: String, on: Boolean)
    /** voice:chunk — 16kHz Int16LE base64, 4000-sample 250ms blocks, seq starts at 1. */
    suspend fun emitVoiceChunk(conversationId: String, userId: String, seq: Long, data: String)
    suspend fun emitVoiceTranscript(conversationId: String, userId: String, text: String)
    /** stage:join — asHost:true ONLY for the claim-host path (first joiner is server-assigned). */
    suspend fun emitStageJoin(conversationId: String, user: PulseVoiceUser, asHost: Boolean)
    suspend fun emitStageHand(conversationId: String, userId: String, raised: Boolean)
    suspend fun emitStageApprove(conversationId: String, byUserId: String, targetUserId: String)
    suspend fun emitStageMute(conversationId: String, byUserId: String, targetUserId: String)
    suspend fun emitStageEnd(conversationId: String, byUserId: String)
    suspend fun emitStageLeave(conversationId: String)
    suspend fun emitSpaceJoin(conversationId: String, user: PulseVoiceUser)
    suspend fun emitSpaceMove(conversationId: String, x: Double, y: Double)
    suspend fun emitSpaceLeave(conversationId: String)

    // ── Wave 6 — social graph & discovery (users / safety / blocks / invites / channels / folders) ──

    /** GET /api/users/{id} — the full profile row (scrubbed lastSeen honoured). */
    suspend fun userProfile(userId: String): Result<UserProfile>

    /** PATCH /api/users/{id} — only touched fields ride the body ('' clears where the wire allows). */
    suspend fun patchProfile(patch: ProfilePatch): Result<UserProfile>

    /** GET /api/users/{id}/stats — messages/reactions/photos/voiceNotes/chats/groups. */
    suspend fun userStats(userId: String): Result<UserStats>

    /** GET /api/users/{id}/safety?userId= — 12×5 digits + this viewer's verify stamp. */
    suspend fun safetyState(peerId: String): Result<SafetyState>

    /** POST /api/users/{id}/safety { userId } — settle-confirmed (NO optimistic lies). */
    suspend fun verifyPeer(peerId: String): Result<SafetyState>

    /** DELETE /api/users/{id}/safety?userId= — settle-confirmed reset. */
    suspend fun unverifyPeer(peerId: String): Result<SafetyState>

    /** GET /api/users/{id}/block?userId= — pair state (Block/Unblock label truth). */
    suspend fun blockState(userId: String): Result<Boolean>

    /** GET /api/users/{id}/blocks?userId= — the viewer's own list (newest first). */
    suspend fun blockedAccounts(): Result<List<BlockedAccount>>

    /** GET /api/users/{id}/report?userId= — THIS viewer's prior reason keys (hint). */
    suspend fun myReportReasons(userId: String): Result<List<String>>

    /** GET /api/invite/{code}?userId= — public preview (name + member count only). */
    suspend fun invitePreview(code: String): Result<InvitePreview>

    /** POST /api/invite/{code}/join { userId } — idempotent join. */
    suspend fun joinInvite(code: String): Result<InviteJoinOutcome>

    /** GET /api/channels?userId=[&mine=1] — broadcast channel directory. */
    suspend fun channels(mineOnly: Boolean): Result<List<Channel>>

    /** POST /api/channels { userId, name, description?, photo? } — creator becomes admin. */
    suspend fun createChannel(name: String, description: String?, photo: String?): Result<Channel>

    /** POST /api/channels/{id}/subscribe { userId } — returns already=true on a no-op. */
    suspend fun subscribeChannel(channelId: String): Result<Boolean>

    /** DELETE /api/channels/{id}/subscribe { userId } — last-admin leave is a 403. */
    suspend fun unsubscribeChannel(channelId: String): Result<Unit>

    /** POST /api/folders { userId, name, emoji? } → the fresh folder (position max+1). */
    suspend fun createFolder(name: String, emoji: String): Result<FolderSummary>

    /** PATCH /api/folders/{id} { name?, emoji?, position? } — only provided fields change. */
    suspend fun updateFolder(folderId: String, name: String?, emoji: String?, position: Int?): Result<Unit>

    /** DELETE /api/folders/{id} — membership rows cascade, chats stay in the list. */
    suspend fun deleteFolder(folderId: String): Result<Unit>

    /** GET /api/conversations/{id} — THIS viewer's participant role ('admin'/'member'/null). */
    suspend fun myRole(conversationId: String): Result<String?>

    /** PUT /api/folders/{id}/conversations { conversationIds[] } — FULL ordered replace. */
    suspend fun setFolderConversations(folderId: String, conversationIds: List<String>): Result<Unit>

    /**
     * POST /api/voice/transcribe {conversationId, requesterId, audioBase64} →
     * { transcript } — 4s WAV windows of the LOCAL transmit stream; 60s
     * per-request timeout; failures are honest silence (no retry).
     */
    suspend fun transcribeVoice(conversationId: String, requesterId: String, audioBase64: String): Result<VoiceTranscriptResultDto>

    // ── Wave 7 — collaboration & hub ─────────────────────────────

    /** POST /api/redpackets — atomic debit; the carrier message is upserted locally. */
    suspend fun createRedPacket(conversationId: String, total: Long, count: Int, note: String?): Result<RedPacketCreateResultDto>

    /** GET /api/redpackets/{id}?userId= — lazy refund on first read after expiry. */
    suspend fun redPacket(packetId: String): Result<RedPacketDetailDto>

    /** POST /api/redpackets/{id}/grab — atomic; grab requires network (honest fail offline). */
    suspend fun grabRedPacket(packetId: String): Result<RedPacketGrabResultDto>

    /** GET /api/conversations/{id}/whiteboard?since= — since=null → full snapshot. */
    suspend fun whiteboard(conversationId: String, since: Long?): Result<WhiteboardPageDto>

    /** POST strokes (≤40/call, 2..500 pts, 0..1 coords). */
    suspend fun postWhiteboardStrokes(conversationId: String, strokes: List<WhiteboardStrokePostDto>): Result<WhiteboardPostResultDto>

    /** Undo the caller's latest stroke. */
    suspend fun undoWhiteboardStroke(conversationId: String): Result<WhiteboardUndoResultDto>

    /** Clear the board (resetAt watermark). */
    suspend fun clearWhiteboard(conversationId: String): Result<WhiteboardClearResultDto>

    /** GET board — cards ordered column → position → createdAt. */
    suspend fun kanbanBoard(conversationId: String): Result<KanbanPageDto>

    /** POST card (or message→card when messageId is set). */
    suspend fun createKanbanCard(conversationId: String, title: String?, column: String?, assigneeId: String?, messageId: String?): Result<KanbanCardDto>

    /** PATCH card — move/rename/reassign (position null on column change = move-to-end). */
    suspend fun updateKanbanCard(cardId: String, title: String?, column: String?, assigneeId: String?, clearAssignee: Boolean, position: Long?): Result<KanbanCardDto>

    /** DELETE card — creator OR group admin. */
    suspend fun deleteKanbanCard(cardId: String): Result<Unit>

    /** GET events — upcoming asc then past desc, ≤50. */
    suspend fun events(conversationId: String): Result<EventsPageDto>

    /** POST event (creator does NOT auto-RSVP). */
    suspend fun createEvent(conversationId: String, title: String, startsAtIso: String, description: String?, location: String?): Result<GroupEventDto>

    /** DELETE event — creator OR group admin. */
    suspend fun deleteEvent(eventId: String): Result<Unit>

    /** RSVP going|maybe|no — upsert-move, no socket. */
    suspend fun rsvpEvent(eventId: String, status: String): Result<RsvpResultDto>

    /** Check-in — window startsAt−15 min…+2 h, +15 XP, idempotent. */
    suspend fun checkinEvent(eventId: String): Result<CheckinResultDto>

    /** GET /api/reminders[?due=1]. */
    suspend fun reminders(dueOnly: Boolean): Result<RemindersPageDto>

    /** POST reminder — remindAt ISO future ≤90 d; schedules the local notification. */
    suspend fun createReminder(conversationId: String, messageId: String?, note: String?, remindAtIso: String): Result<ReminderItemDto>

    /** PATCH reminder — owner-only resolve (due loop calls after the nudge). */
    suspend fun resolveReminder(reminderId: String): Result<ReminderResolveDto>

    /** DELETE reminder — owner-only cancel (also unschedules the local notification). */
    suspend fun deleteReminder(reminderId: String): Result<Unit>

    // ── R1-W2A — quick phrases (F-MS-29) ──────────────────────────────

    /** GET /api/users/{viewerId}/phrases — the composer quick-phrase rail (position asc). */
    suspend fun phrases(): Result<List<QuickPhrase>>

    /** POST /api/users/{viewerId}/phrases { text } — server caps 12 rows × 120 chars. */
    suspend fun addPhrase(text: String): Result<QuickPhrase>

    /** DELETE /api/users/{viewerId}/phrases?phraseId= — owner-guarded. */
    suspend fun deletePhrase(phraseId: String): Result<Unit>

    // ── R1-W2F — F-MD-06 translation ──────────────────────────────────

    /**
     * POST /api/messages/{id}/translate { userId, lang? } — the server's LLM
     * result is persisted per language (translate/route.ts) and the mapped
     * fresh row carries it; the translated TEXT for the default language
     * ("en") is returned. Re-translating the same message is a cheap server
     * cache hit; 4xx/502 copy is honest ("Deleted messages cannot be…").
     */
    suspend fun translateMessage(messageId: String): Result<String>

    // ── R1-W2F — F-FX-05 per-conversation themes (`chat.convThemes`) ──

    /**
     * Live per-conversation theme overrides, keyed by conversationId — the
     * device-side mirror of the web `chat.convThemes` prefs blob, LRU-capped
     * at [ConvTheme.MAX_MAP_ENTRIES]. Missing id = follows the global default.
     */
    val convThemes: Flow<Map<String, ConvTheme>>

    /**
     * Upsert ONE conversation's theme override (null = clear it — the room
     * falls back to the global Appearance default). Persists LOCALLY through
     * the same DataStore prefs mechanism as the Wave-8 rendering prefs.
     */
    suspend fun setConvTheme(conversationId: String, theme: ConvTheme?)

    /** POST /api/games — carrier message `kind=game` is upserted locally. */
    suspend fun createGame(conversationId: String, opponentId: String?): Result<GameMatchCreateResultDto>

    /** GET /api/games?conversationId= — newest 25. */
    suspend fun games(conversationId: String): Result<GamesPageDto>

    /** GET /api/games/{id}. */
    suspend fun game(matchId: String): Result<GameDetailDto>

    /** POST move {cell 0..8} — 409s surface verbatim. */
    suspend fun gameMove(matchId: String, cell: Int): Result<GameDetailDto>

    /** POST join — first-come O seat. */
    suspend fun joinGame(matchId: String): Result<GameDetailDto>

    /** POST /api/tournaments — carrier message `kind=tournament` is upserted locally. */
    suspend fun createTournament(conversationId: String, name: String): Result<TournamentCreateResultDto>

    /** GET /api/tournaments?conversationId= — newest 5. */
    suspend fun tournaments(conversationId: String): Result<TournamentsPageDto>

    /** GET /api/tournaments/{id} — standings. */
    suspend fun tournament(tournamentId: String): Result<TournamentSummaryDto>

    /** PATCH finish — creator/admin, idempotent. */
    suspend fun finishTournament(tournamentId: String): Result<TournamentSummaryDto>

    /** POST join — idempotent upsert. */
    suspend fun joinTournament(tournamentId: String): Result<TournamentJoinResultDto>

    /** GET /api/leaderboard — room-scoped when conversationId set, else global top 50. */
    suspend fun leaderboard(conversationId: String?): Result<LeaderboardPageDto>

    /** GET /api/hub/wallet — wallet + ledger (≤30). */
    suspend fun wallet(): Result<WalletPageDto>

    /** POST check-in — 409 'Already checked in today.' surfaces verbatim. */
    suspend fun checkinWallet(): Result<CheckinWalletResultDto>

    /** POST transfer — @handle, integer PC. */
    suspend fun transferCoins(toUsername: String, amount: Long, note: String?): Result<TransferResultDto>

    /** GET swap rates + real stats. */
    suspend fun swapRates(): Result<SwapPageDto>

    /** POST swap pc2gem|gem2pc. */
    suspend fun swap(direction: String, amount: Long): Result<SwapResultDto>

    /** GET personal tasks (doing → todo → done). */
    suspend fun hubTasks(): Result<HubTasksPageDto>

    /** POST task. */
    suspend fun createHubTask(title: String, status: String?): Result<HubTaskDto>

    /** PATCH task (owner-only). */
    suspend fun updateHubTask(taskId: String, title: String?, status: String?): Result<HubTaskDto>

    /** DELETE task (owner-only). */
    suspend fun deleteHubTask(taskId: String): Result<Unit>

    /** GET market (open + own listings ≤60). */
    suspend fun market(): Result<MarketPageDto>

    /** POST listing. */
    suspend fun createListing(title: String, description: String?, price: Long): Result<MarketListingDto>

    /** POST buy — atomic debit/credit; returns the fresh wallet. */
    suspend fun buyListing(listingId: String): Result<MarketBuyResultDto>

    /** GET /api/hub/logs — ≤80, optional kind filter. */
    suspend fun hubLogs(limit: Int, kind: String?): Result<HubLogsPageDto>

    /** GET install state for one app (fan-out drives My apps). */
    suspend fun appInstallState(appId: String): Result<AppInstallStateDto>

    /** POST install/connect — idempotent. */
    suspend fun installApp(appId: String): Result<AppInstallResultDto>

    /** DELETE install — hard remove. */
    suspend fun uninstallApp(appId: String): Result<AppInstallResultDto>

    /** GET app community — null conversation when none exists yet. */
    suspend fun appCommunity(appId: String): Result<AppCommunityDto>

    /** POST join community — auto-provisions the group; founder = admin. */
    suspend fun joinAppCommunity(appId: String): Result<AppCommunityDto>

    // ── Wave 7 offline caches (story_cache precedent) ────────────

    /** Last good wallet page from the local snapshot cache, or null. */
    suspend fun cachedWallet(): WalletPageDto?

    /** Last good hub tasks page from the local snapshot cache, or null. */
    suspend fun cachedHubTasks(): HubTasksPageDto?

    /** Last good reminders page from the local snapshot cache, or null. */
    suspend fun cachedReminders(): RemindersPageDto?
}
