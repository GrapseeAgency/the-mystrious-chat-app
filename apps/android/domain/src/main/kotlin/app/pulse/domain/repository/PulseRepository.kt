package app.pulse.domain.repository

import app.pulse.domain.model.Conversation
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.CallSignalData
import app.pulse.domain.model.CallSignalOut
import app.pulse.domain.model.FlushReport
import app.pulse.domain.model.FolderSummary
import app.pulse.domain.model.HandleCheck
import app.pulse.domain.model.Message
import app.pulse.domain.model.MessageHit
import app.pulse.domain.model.MentionItem
import app.pulse.domain.model.OutboxEntry
import app.pulse.domain.model.SavedItem
import app.pulse.domain.model.StoryGroup
import app.pulse.domain.model.StoryItem
import app.pulse.domain.model.StoryViewer
import app.pulse.domain.model.Topic
import app.pulse.domain.model.TranscribeOutcome
import app.pulse.domain.model.User
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

    suspend fun setViewer(id: String?, name: String?, color: String? = null)
    suspend fun setFxMode(mode: String)
    suspend fun setDarkOverride(value: String)
    suspend fun setReducedMotion(value: Boolean)
    suspend fun setChatsListFilter(value: String)
    suspend fun setServerBase(value: String?)
    suspend fun setVoiceRate(value: Float)
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
    ): Result<Message>
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
}
