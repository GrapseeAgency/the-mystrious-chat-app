package app.pulse.feature.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pulse.core.media.PulseMedia
import app.pulse.core.time.PulseTime
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.usecase.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One-shot room-level snackbar/toast notice (web toast parity). */
data class RoomNotice(val text: String, val isError: Boolean = false)

/** A media attachment staged above the composer (upload lifecycle lives here). */
data class StagedMedia(
    val kind: Kind,
    val localUri: String,
    val fileName: String?,
    val fileSize: Long?,
    val mime: String? = null,
    val uploading: Boolean = false,
    val uploadedPath: String? = null,
    val error: String? = null,
) {
    enum class Kind { IMAGE, FILE }
}

/** A downloaded file the UI must hand to the system (open or share). */
data class FileReady(val path: String, val mime: String, val share: Boolean)

/**
 * Chat room state holder — messages, live typing/presence, sends + reactions
 * (Wave 0 core) plus the Wave 1 messaging surface: pagination, ticks, edit/
 * delete/pin/save/forward/info, room search + jump, staged media sends,
 * offline honesty and the OutboxDropped notice channel.
 */
@HiltViewModel
class ChatRoomViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repo: PulseRepository,
    private val sendUseCase: SendMessageUseCase,
) : ViewModel() {

    val conversationId: String = savedStateHandle.get<String>("conversationId").orEmpty()

    /** Global-search / notification jump — the room scrolls + flashes on arrival. */
    private val initialJumpMessageId: String? = savedStateHandle.get<String>("jump")

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
        val replyTo: Message? = null,
        val partnerTypingName: String? = null,
        val partnerLastReadAt: Long? = null,
        // ── Wave 1 ─────────────────────────────────────────────
        val editing: Message? = null,
        val hasMore: Boolean = false,
        val loadingOlder: Boolean = false,
        val pins: List<Message> = emptyList(),
        val connected: Boolean? = null,
        val notice: RoomNotice? = null,
        val flashMessageId: String? = null,
        val searchOpen: Boolean = false,
        val searchResults: List<Message> = emptyList(),
        val searching: Boolean = false,
        val staged: StagedMedia? = null,
        val downloadingFileId: String? = null,
        val openedFile: FileReady? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Composer restore seed — local draft table first, server myDraft fallback. */
    private val _initialDraft = MutableStateFlow<String?>(null)
    val initialDraft: StateFlow<String?> = _initialDraft.asStateFlow()

    /** MAIN RIVER — thread replies (threadRootId != null) live on the ThreadScreen only. */
    val messages: StateFlow<List<Message>> = repo.observeMessages(conversationId)
        .map { rows -> rows.filter { it.threadRootId == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversation: StateFlow<Conversation?> = repo.observeConversations()
        .map { list -> list.firstOrNull { it.id == conversationId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every chat — the ForwardSheet target list (spec §1.1 forward = client re-POST). */
    val conversations: StateFlow<List<Conversation>> = repo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "N replies ↳" chip counts — one batched Room query per river change, live. */
    val replyCounts: StateFlow<Map<String, Int>> = messages
        .map { rows ->
            val parents = rows.filter { !it.id.startsWith(TEMP_MESSAGE_PREFIX) }.map { it.id }
            repo.threadReplyCounts(parents)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Jump target awaiting enough of the window to be loaded (search/pin/quote/global). */
    private val _jumpTarget = MutableStateFlow<String?>(null)
    val jumpTarget: StateFlow<String?> = _jumpTarget.asStateFlow()

    private var typingJob: Job? = null
    private var draftSaveJob: Job? = null
    private var readJob: Job? = null
    private var searchJob: Job? = null
    private var flashJob: Job? = null
    private var jumpExpansionJob: Job? = null

    init {
        viewModelScope.launch {
            // Wave 0 draft restore: the local draft table wins; when it is
            // empty fall back to the server-mirrored myDraft on the summary.
            val local = runCatching { repo.observeDraft(conversationId).firstOrNull() }.getOrNull()
            if (!local.isNullOrBlank()) {
                _initialDraft.value = local
            } else {
                val server = runCatching {
                    repo.observeConversations()
                        .map { list -> list.firstOrNull { it.id == conversationId }?.myDraft }
                        .firstOrNull { !it.isNullOrBlank() }
                }.getOrNull()
                if (!server.isNullOrBlank()) _initialDraft.value = server
            }
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            runCatching { repo.messagesPage(conversationId, before = null, limit = 200) }
                .onSuccess { (_, hasMore) ->
                    _state.value = _state.value.copy(loading = false, hasMore = hasMore)
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(loading = false, error = failure.message)
                }
            scheduleRead()
            loadPins()
            initialJumpMessageId?.takeIf { it.isNotBlank() }?.let { jumpTo(it) }
        }
        viewModelScope.launch {
            repo.observeConnected().collect { connected ->
                _state.value = _state.value.copy(connected = connected)
            }
        }
        viewModelScope.launch {
            repo.events().collect { event ->
                when (event) {
                    is PulseEvent.Typing -> if (event.conversationId == conversationId && event.userId != repo.viewerId) {
                        _state.value = if (event.isTyping) {
                            _state.value.copy(partnerTypingName = event.userName.ifBlank { "Someone" })
                        } else {
                            _state.value.copy(partnerTypingName = null)
                        }
                    }
                    is PulseEvent.MessageRead -> if (event.conversationId == conversationId) {
                        val at = PulseTime.parse(event.at)?.toInstant()?.toEpochMilli()
                        if (at != null) {
                            _state.value = _state.value.copy(
                                partnerLastReadAt = maxOf(_state.value.partnerLastReadAt ?: 0, at),
                            )
                        }
                    }
                    is PulseEvent.MessageReceived -> if (event.conversationId == conversationId) {
                        scheduleRead()
                    }
                    is PulseEvent.OutboxDropped -> notify("Message couldn't be delivered", isError = true)
                    else -> Unit
                }
            }
        }
    }

    // ── read receipts (spec row 3): debounced 600ms while the room is open ──

    private fun scheduleRead() {
        readJob?.cancel()
        readJob = viewModelScope.launch {
            delay(600)
            runCatching { repo.markRead(conversationId) }
        }
    }

    // ── drafts (Wave 0 core) + typing ───────────────────────────

    fun onDraftChanged(text: String) {
        if (_state.value.editing != null) return // composer is editing a row, not drafting
        val userName = viewerName()
        if (typingJob?.isActive != true) {
            viewModelScope.launch { repo.setTyping(conversationId, userName, true) }
        }
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(1_200)
            repo.setTyping(conversationId, userName, false)
            typingJob = null
        }
        // 600ms debounced local save; the repo mirrors every mutation to the
        // server draft endpoint (fire-and-forget, blank clears — spec row 6).
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(600)
            runCatching { repo.saveDraft(conversationId, text) }
                .onFailure { _state.value = _state.value.copy(error = it.message) }
        }
    }

    private fun viewerName(): String {
        val conv = conversation.value ?: return repo.viewerId ?: ""
        val idx = conv.memberIds.indexOf(repo.viewerId)
        return conv.memberNames.getOrNull(idx) ?: repo.viewerId ?: ""
    }

    fun setReplyTo(message: Message?) {
        _state.value = _state.value.copy(replyTo = message)
    }

    fun notify(text: String, isError: Boolean = false) {
        _state.value = _state.value.copy(notice = RoomNotice(text, isError))
    }

    fun consumeNotice() {
        if (_state.value.notice != null) _state.value = _state.value.copy(notice = null)
    }

    // ── send / edit ─────────────────────────────────────────────

    fun send(body: String) {
        val editing = _state.value.editing
        if (editing != null) {
            sendEdit(editing.id, body)
            return
        }
        val replyId = _state.value.replyTo?.id
        _state.value = _state.value.copy(replyTo = null)
        viewModelScope.launch {
            sendUseCase(conversationId, body, replyId)
                .onSuccess { message ->
                    if (message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                        // Network-class failure → queued in the outbox. The
                        // composer text has left for the queue — clear the
                        // draft and let the pending bubble + banner tell it.
                        runCatching { repo.clearDraft(conversationId) }
                    } else {
                        app.pulse.core.fx.PulseFx.fire(app.pulse.core.fx.PulseFx.BurstKind.BURST, count = 26)
                        repo.setTyping(conversationId, viewerName(), false)
                        runCatching { repo.clearDraft(conversationId) }
                    }
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(error = failure.message)
                }
        }
    }

    /** PATCH edit — online-only by design (spec row 4); failure is an honest toast. */
    private fun sendEdit(messageId: String, content: String) {
        viewModelScope.launch {
            repo.editMessage(messageId, content.trim())
                .onSuccess {
                    _state.value = _state.value.copy(editing = null)
                }
                .onFailure { failure ->
                    notify(failure.message ?: "Couldn't update the message", isError = true)
                }
        }
    }

    fun beginEdit(message: Message) {
        _state.value = _state.value.copy(editing = message, replyTo = null)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editing = null)
    }

    // ── pin / save / delete / forward ───────────────────────────

    fun loadPins() {
        viewModelScope.launch {
            runCatching { repo.pinnedMessages(conversationId) }
                .onSuccess { pins -> _state.value = _state.value.copy(pins = pins) }
        }
    }

    fun toggleMessagePin(messageId: String) {
        viewModelScope.launch {
            repo.toggleMessagePin(messageId)
                .onSuccess { message ->
                    notify(if (message.pinnedAt != null) "Message pinned" else "Message unpinned")
                    loadPins()
                }
                .onFailure { notify("Couldn't update the pin", isError = true) }
        }
    }

    fun toggleMessageSave(messageId: String) {
        viewModelScope.launch {
            repo.toggleMessageSave(messageId)
                .onSuccess { saved -> notify(if (saved) "Saved to library" else "Removed from library") }
                .onFailure { notify("Couldn't save the message", isError = true) }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            repo.deleteMessage(messageId)
                .onSuccess {
                    notify("Message deleted")
                    // The tombstone row arrives from the server truth — refetch
                    // the window so "🚫 Message deleted" renders immediately.
                    runCatching { repo.refreshMessages(conversationId) }
                }
                .onFailure { notify("Couldn't delete the message", isError = true) }
        }
    }

    fun forwardTo(source: Message, targetIds: List<String>) {
        if (targetIds.isEmpty()) return
        viewModelScope.launch {
            var done = 0
            for (target in targetIds) {
                if (runCatching { repo.forwardMessage(target, source) }.isSuccess) done += 1
            }
            if (done == 0) {
                notify("Couldn't forward — check your connection", isError = true)
            } else {
                notify("Forwarded to $done ${if (done == 1) "chat" else "chats"}")
            }
        }
    }

    fun react(messageId: String, emoji: String) {
        viewModelScope.launch { repo.react(messageId, emoji) }
    }

    fun retry() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { repo.messagesPage(conversationId, before = null, limit = 200) }
                .onSuccess { (_, hasMore) -> _state.value = _state.value.copy(loading = false, hasMore = hasMore) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
        }
    }

    // ── pagination (spec row 2): before= cursor pages of 40 ────

    /** Oldest REAL row (temps are echoes, never cursors). */
    private fun oldestLoaded(): Message? = messages.value.firstOrNull { !it.id.startsWith(TEMP_MESSAGE_PREFIX) }

    fun loadOlder() {
        val current = _state.value
        if (current.loadingOlder || !current.hasMore) return
        val cursor = oldestLoaded()?.createdAt ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingOlder = true)
            runCatching { repo.messagesPage(conversationId, before = cursor, limit = PAGE_SIZE) }
                .onSuccess { (rows, more) ->
                    _state.value = _state.value.copy(
                        loadingOlder = false,
                        hasMore = more && rows.isNotEmpty(),
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loadingOlder = false,
                        notice = RoomNotice("Couldn't load older messages", isError = true),
                    )
                }
        }
    }

    // ── jump + flash (search / pins / quotes / global hits) ────

    fun jumpTo(messageId: String) {
        if (messageId.isBlank()) return
        if (messages.value.any { it.id == messageId }) {
            flash(messageId)
        } else {
            _jumpTarget.value = messageId
        }
    }

    fun consumeJumpTarget() {
        _jumpTarget.value = null
    }

    /**
     * Bounded window expansion (web parity ≤14 rounds): page older until the
     * jump target surfaces or the history is honestly exhausted. Cancel-and-
     * replace: a new target (or a racing loadOlder) never strands the gate.
     */
    fun expandForJump(messageId: String) {
        jumpExpansionJob?.cancel()
        jumpExpansionJob = viewModelScope.launch {
            var found = false
            try {
                var rounds = 0
                while (rounds < JUMP_MAX_ROUNDS && _state.value.hasMore) {
                    if (messages.value.any { it.id == messageId }) {
                        found = true
                        break
                    }
                    val cursor = oldestLoaded()?.createdAt ?: break
                    _state.value = _state.value.copy(loadingOlder = true)
                    val pageResult = runCatching { repo.messagesPage(conversationId, before = cursor, limit = PAGE_SIZE) }
                    if (pageResult.isFailure) {
                        _state.value = _state.value.copy(loadingOlder = false)
                        break
                    }
                    val (rows, more) = pageResult.getOrThrow()
                    _state.value = _state.value.copy(loadingOlder = false, hasMore = more && rows.isNotEmpty())
                    if (messages.value.any { it.id == messageId }) {
                        found = true
                        break
                    }
                    rounds += 1
                }
            } finally {
                _state.value = _state.value.copy(loadingOlder = false)
            }
            if (found) {
                flash(messageId)
            } else {
                notify("Couldn't reach that message in this chat's history", isError = true)
            }
            _jumpTarget.value = null
        }
    }

    /** Amber ring pulse on one bubble (~1.5s), then it fades and clears. */
    fun flash(messageId: String) {
        flashJob?.cancel()
        _state.value = _state.value.copy(flashMessageId = messageId)
        flashJob = viewModelScope.launch {
            delay(1_600)
            if (_state.value.flashMessageId == messageId) {
                _state.value = _state.value.copy(flashMessageId = null)
            }
        }
    }

    // ── room search (server hits + local window, deduped by id) ──

    fun setSearchOpen(open: Boolean) {
        searchJob?.cancel()
        _state.value = _state.value.copy(
            searchOpen = open,
            searchResults = emptyList(),
            searching = false,
        )
    }

    fun onSearchQueryChanged(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 2) {
            _state.value = _state.value.copy(searchResults = emptyList(), searching = false)
            return
        }
        _state.value = _state.value.copy(searching = true)
        searchJob = viewModelScope.launch {
            delay(250)
            val serverHits = runCatching { repo.searchInConversation(conversationId, q) }
                .getOrDefault(emptyList())
            val localHits = messages.value.filter { it.body.contains(q, ignoreCase = true) }
            val merged = (serverHits + localHits)
                .distinctBy { it.id }
                .filter { !it.isDeleted }
                .sortedBy { it.createdAt }
            _state.value = _state.value.copy(searchResults = merged, searching = false)
        }
    }

    // ── staged media (spec row 9 — never queued, honest errors) ──

    fun onImagePicked(uri: Uri) {
        _state.value = _state.value.copy(
            staged = StagedMedia(kind = StagedMedia.Kind.IMAGE, localUri = uri.toString(), fileName = null, fileSize = null, uploading = true, error = null),
        )
        viewModelScope.launch {
            MediaSupport.imageToDataUrl(context, uri)
                .onSuccess { dataUrl -> uploadStaged(dataUrl, null) }
                .onFailure { stageFailed(it.message ?: "Couldn't prepare the photo") }
        }
    }

    fun onDocumentPicked(uri: Uri) {
        viewModelScope.launch {
            val prepared = MediaSupport.documentToDataUrl(context, uri)
            prepared
                .onSuccess { doc ->
                    _state.value = _state.value.copy(
                        staged = StagedMedia(
                            kind = StagedMedia.Kind.FILE,
                            localUri = uri.toString(),
                            fileName = doc.fileName,
                            fileSize = doc.sizeBytes,
                            mime = doc.mime,
                            uploading = true,
                            error = null,
                        ),
                    )
                    uploadStaged(doc.dataUrl, doc.fileName)
                }
                .onFailure { failure ->
                    notify(failure.message ?: "Couldn't attach the document", isError = true)
                }
        }
    }

    /** Upload (or re-upload) the staged attachment — one route, both kinds. */
    private fun uploadStaged(dataUrl: String, fileName: String?) {
        val staged = _state.value.staged ?: return
        _state.value = _state.value.copy(staged = staged.copy(uploading = true, error = null))
        viewModelScope.launch {
            repo.uploadMedia(dataUrl)
                .onSuccess { path ->
                    val current = _state.value.staged ?: return@launch
                    _state.value = _state.value.copy(
                        staged = current.copy(uploading = false, uploadedPath = path),
                    )
                }
                .onFailure { stageFailed(it.message ?: "Upload failed") }
        }
    }

    private fun stageFailed(message: String) {
        val current = _state.value.staged ?: return
        _state.value = _state.value.copy(staged = current.copy(uploading = false, error = message))
    }

    /** Retry re-runs the upload; an already-uploaded staged card skips it. */
    fun retryStaged() {
        val staged = _state.value.staged ?: return
        if (staged.uploadedPath != null) {
            _state.value = _state.value.copy(staged = staged.copy(error = null))
            return
        }
        viewModelScope.launch {
            when (staged.kind) {
                StagedMedia.Kind.IMAGE -> MediaSupport.imageToDataUrl(context, Uri.parse(staged.localUri))
                    .onSuccess { dataUrl -> uploadStaged(dataUrl, staged.fileName) }
                    .onFailure { stageFailed(it.message ?: "Upload failed") }
                StagedMedia.Kind.FILE -> MediaSupport.documentToDataUrl(context, Uri.parse(staged.localUri))
                    .onSuccess { doc ->
                        _state.value = _state.value.copy(
                            staged = (_state.value.staged ?: staged).copy(
                                fileName = doc.fileName,
                                fileSize = doc.sizeBytes,
                                mime = doc.mime,
                            ),
                        )
                        uploadStaged(doc.dataUrl, doc.fileName)
                    }
                    .onFailure { stageFailed(it.message ?: "Upload failed") }
            }
        }
    }

    fun cancelStaged() {
        _state.value = _state.value.copy(staged = null)
    }

    /** Send the staged attachment with its caption (≤500 — UI enforces). */
    fun sendStaged(caption: String) {
        val staged = _state.value.staged ?: return
        val path = staged.uploadedPath
        if (path == null) {
            stageFailed("Still uploading — hold on a second")
            return
        }
        viewModelScope.launch {
            val result = when (staged.kind) {
                StagedMedia.Kind.IMAGE -> repo.sendMediaMessage(
                    conversationId = conversationId,
                    body = caption.trim(),
                    imagePath = path,
                )
                StagedMedia.Kind.FILE -> repo.sendMediaMessage(
                    conversationId = conversationId,
                    body = caption.trim(),
                    filePath = path,
                    fileName = staged.fileName ?: path.substringAfterLast('/'),
                    fileSize = staged.fileSize,
                    kind = "file",
                )
            }
            result
                .onSuccess {
                    _state.value = _state.value.copy(staged = null)
                    app.pulse.core.fx.PulseFx.fire(app.pulse.core.fx.PulseFx.BurstKind.BURST, count = 26)
                }
                .onFailure { stageFailed(it.message ?: "Couldn't send — you appear to be offline") }
        }
    }

    // ── file download + open/share (FileProvider hand-off) ─────

    fun openFile(message: Message, share: Boolean) {
        val path = message.filePath ?: return
        if (_state.value.downloadingFileId != null) return
        _state.value = _state.value.copy(downloadingFileId = message.id)
        viewModelScope.launch {
            repo.downloadMedia(path)
                .onSuccess { absolutePath ->
                    _state.value = _state.value.copy(
                        downloadingFileId = null,
                        openedFile = FileReady(
                            path = absolutePath,
                            mime = PulseMedia.mimeForFileName(message.fileName ?: absolutePath.substringAfterLast('/')),
                            share = share,
                        ),
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(downloadingFileId = null)
                    notify("Couldn't download the file", isError = true)
                }
        }
    }

    fun consumeOpenedFile() {
        if (_state.value.openedFile != null) _state.value = _state.value.copy(openedFile = null)
    }

    companion object {
        /** Older-page size — spec §1.1 pagination cursor rhythm. */
        const val PAGE_SIZE = 40

        /** Bounded jump-window expansion — web parity (≤14 before= rounds). */
        const val JUMP_MAX_ROUNDS = 14
    }
}
