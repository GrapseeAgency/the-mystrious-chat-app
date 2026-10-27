package app.pulse.feature.chat

import androidx.lifecycle.repeatOnLifecycle
import app.pulse.domain.model.PulseApiException
import app.pulse.domain.sync.threadRootAvailable
import app.pulse.domain.sync.threadAccessDenied
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import app.pulse.ui.PulseIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pulse.core.fx.PulseFx
import app.pulse.core.media.PulseMedia
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.sync.withReadScopeExpiry
import app.pulse.domain.usecase.SendMessageUseCase
import app.pulse.domain.repository.PulseRepository
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Thread state holder - the parent (top-level) message plus its replies
 * (asc). Send rides the ordinary wire path with `parentId = rootId` (spec
 * §1.1: threads are ordinary sends, ONE level deep, never conflated with
 * replyToId); the optimistic echo lands in Room and streams back through
 * the thread flow. Thread replies share the durable outbox with the main room.
 */
@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: PulseRepository,
    /** The ONE active voice player - room + thread share the singleton. */
    val voicePlayer: VoicePlayer,
) : ViewModel() {
    private val repo = repository.boundToCurrentAccount()

    val conversationId: String = savedStateHandle.get<String>("conversationId").orEmpty()
    val rootId: String = savedStateHandle.get<String>("rootId").orEmpty()
    val initialJumpMessageId: String? = savedStateHandle.get<String>("jumpMessageId")

    data class UiState(
        val loading: Boolean = false,
        val unavailable: Boolean = false,
        val draft: String = "",
        val sending: Boolean = false,
        val error: String? = null,
        val connected: Boolean? = null,
        val notice: RoomNotice? = null,
        val downloadingFileId: String? = null,
        val openedFile: FileReady? = null,
        val loadedReplyIds: Set<String>? = null,
        // Exact cached target is not a complete server reply inventory.
        val offlineJumpId: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The thread root - rehydrated from Room, refreshed by loadThread. */
    val parent: StateFlow<Message?> = combine(repo.observeMessages(conversationId),
        _state.map { it.unavailable }.distinctUntilChanged(), repo.observeThreadAccessDenied(rootId)) { rows, denied, persistedDenial ->
            if (denied || persistedDenial) null else rows.firstOrNull { threadRootAvailable(it, conversationId, rootId) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** R3-B item 2 - the room summary; its roster drives @mention highlight. */
    val conversation: StateFlow<Conversation?> = repo.observeConversations()
        .map { list -> list.firstOrNull { it.id == conversationId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Replies asc - live Room flow (realtime appends ride message:new upserts). */
    val replies: StateFlow<List<Message>> = combine(repo.observeThreadMessages(rootId), parent) { rows, root ->
        if (root == null || root.isDeleted) emptyList() else rows
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val failedReplies = repo.observeFailedThreadReplies(conversationId, rootId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val recoveryActions = mutableSetOf<String>()

    fun recoverReply(clientId: String, discard: Boolean) {
        if (!discard && (_state.value.unavailable || !threadRootAvailable(parent.value, conversationId, rootId))) return
        if (!recoveryActions.add(clientId)) return
        viewModelScope.launch {
            try {
                val result = if (discard) repo.discardFailedThreadReply(conversationId, rootId, clientId)
                    else repo.retryThreadReply(conversationId, rootId, clientId)
                result.onFailure { notify(it.message ?: "Couldn't update this saved reply", isError = true) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                notify(failure.message ?: "Couldn't update this saved reply", isError = true)
            } finally {
                recoveryActions.remove(clientId)
            }
        }
    }

    private val unreadScopeSnapshot = MutableStateFlow<app.pulse.domain.model.ReadScopeState?>(null)
    private var scopeAnchorCaptured = false
    private val firstUnread = MutableStateFlow<String?>(null)
    val firstUnreadMessageId: StateFlow<String?> = firstUnread.asStateFlow()
    val unreadCount: StateFlow<Int?> = unreadScopeSnapshot.withReadScopeExpiry()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val threadRepository = repo.boundToCurrentAccount()
    private val unreadRefresh = app.pulse.domain.sync.CoalescedRefresh(viewModelScope) {
        val epoch = threadRepository.attachmentSessionKey()
        val result = threadRepository.readScopeState(conversationId, threadId = rootId)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (threadRepository.attachmentSessionKey() == epoch) {
            result.getOrNull()?.let { next ->
                unreadScopeSnapshot.value = next
                if (!scopeAnchorCaptured) { scopeAnchorCaptured = true; firstUnread.value = next.firstUnreadMessageId }
            }
            if ((result.exceptionOrNull() as? app.pulse.domain.model.PulseApiException)?.status in setOf(400, 403, 404, 410)) {
                unreadScopeSnapshot.value = null
            }
        }
    }
    private fun refreshUnread() = unreadRefresh.request()

    private val threadRefresh = app.pulse.domain.sync.CoalescedRefresh(viewModelScope) { reloadThread() }

    private var composerRevision = 0L
    private var threadDraftToken: app.pulse.domain.model.ComposerDraftToken? = null

    init {
        viewModelScope.launch {
            repo.observeThreadDraft(conversationId, rootId).collect { snapshot ->
                val currentToken = repo.captureThreadDraftToken(conversationId, rootId)
                if (snapshot.revision == currentToken?.revision) {
                    threadDraftToken = currentToken
                    if (snapshot.saveFailed) notify("Couldn't save this draft. Keep this thread open and edit to retry.", isError = true)
                    val restoredText = snapshot.text
                    if (restoredText != null && restoredText != _state.value.draft) {
                        composerRevision++
                        _state.value = _state.value.copy(draft = restoredText)
                    }
                }
            }
        }
        retry()
        viewModelScope.launch {
            repo.observeReadStateRevision(conversationId).collect { refreshUnread() }
        }
        viewModelScope.launch {
            repo.observeConnected().collect { connected ->
                _state.value = _state.value.copy(connected = connected)
                if (connected) retry()
            }
        }
        viewModelScope.launch {
            repo.events().collect { event ->
                val changedRoom = when (event) {
                    is PulseEvent.MessageRead -> event.conversationId
                    is PulseEvent.MessageReceived -> event.conversationId
                    is PulseEvent.MessageUpdated -> event.conversationId
                    is PulseEvent.MessageDeleted -> event.conversationId
                    else -> null
                }
                if (changedRoom == conversationId) refreshUnread()
                if (event is PulseEvent.OutboxDropped && event.conversationId == conversationId && event.threadRootId == rootId) {
                    _state.value = _state.value.copy(
                        notice = RoomNotice(event.reason, isError = true),
                    )
                }
            }
        }
    }


    fun changeDraft(value: String) {
        if (value.length > SendMessageUseCase.MAX_LENGTH) {
            notify("Replies are limited to ${SendMessageUseCase.MAX_LENGTH} characters", isError = true)
            return
        }
        composerRevision++
        threadDraftToken = repo.stageThreadDraft(conversationId, rootId, value)
        _state.value = _state.value.copy(draft = value)
    }

    /** Clear only the submitted revision, after persistence/acknowledgement. */
    fun send() {
        val state = _state.value
        if (state.sending || state.unavailable || state.draft.isBlank() || !threadRootAvailable(parent.value, conversationId, rootId)) return
        val revision = composerRevision
        val token = threadDraftToken ?: return
        _state.value = state.copy(sending = true)
        viewModelScope.launch {
            try {
                val result = SendMessageUseCase(repo)(conversationId, state.draft, parentId = rootId, draftToken = token)
                result.onSuccess { receipt ->
                    if (composerRevision == revision) { composerRevision++; _state.value = _state.value.copy(draft = "") }
                    if (!receipt.message.id.startsWith(TEMP_MESSAGE_PREFIX)) PulseFx.fire(PulseFx.BurstKind.BURST, count = 26)
                }.onFailure { failure ->
                    _state.value = _state.value.copy(notice = RoomNotice(failure.message ?: "Reply was not queued; your draft is kept", isError = true))
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(notice = RoomNotice("Reply was not confirmed; check Outbox before resending", isError = true))
            } finally { _state.value = _state.value.copy(sending = false) }
        }
    }

    fun retry() {
        refreshUnread()
        threadRefresh.request()
    }

    private suspend fun reloadThread() {
        _state.value = _state.value.copy(loading = true, error = null, offlineJumpId = null)
        try {
            val (root, loadedReplies) = threadRepository.loadThread(rootId)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!threadRootAvailable(root, conversationId, rootId)) throw PulseApiException("NOT_FOUND", "This thread is no longer available", 410)
            val now = System.currentTimeMillis()
            val liveIds = loadedReplies.filter { it.conversationId == conversationId && it.threadRootId == rootId &&
                !it.isDeleted && (it.expiresAtEpochMs?.let { expiry -> expiry > now } != false) }
                .mapTo(mutableSetOf()) { it.id }
            _state.value = _state.value.copy(loading = false, unavailable = false, error = null, loadedReplyIds = liveIds, offlineJumpId = null)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val denied = threadAccessDenied((failure as? PulseApiException)?.status)
            val offlineTarget = if (initialJumpMessageId != null && app.pulse.domain.sync.mayUseOfflineThread(failure)) {
                try {
                    threadRepository.cachedThreadTarget(conversationId, rootId, initialJumpMessageId)?.second?.id
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (cacheFailure: Exception) {
                    null
                }
            } else null
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            _state.value = _state.value.copy(loading = false, error = failure.message, offlineJumpId = offlineTarget,
                unavailable = _state.value.unavailable || denied,
                openedFile = if (denied) null else _state.value.openedFile)
        }
    }

    private val readScope by lazy {
        app.pulse.domain.sync.VisibleReadScope(repo, conversationId) {
            conversation.value?.takeIf { it.id == conversationId }?.members?.firstOrNull { it.id == repo.viewerId }
        }
    }
    fun markVisibleRead(messageId: String): app.pulse.domain.sync.VisibleReadIntent =
        readScope.message(messageId)

    fun notify(text: String, isError: Boolean = false) {
        _state.value = _state.value.copy(notice = RoomNotice(text, isError))
    }

    fun consumeNotice() {
        if (_state.value.notice != null) _state.value = _state.value.copy(notice = null)
    }

    // file download + open/share (same hand-off as the room)

    fun openFile(message: Message, share: Boolean) {
        val path = message.filePath ?: return
        if (_state.value.unavailable || !threadRootAvailable(parent.value, conversationId, rootId) || message.isDeleted ||
            (message.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true)) return
        if (_state.value.downloadingFileId != null) return
        _state.value = _state.value.copy(downloadingFileId = message.id)
        viewModelScope.launch {
            repo.downloadMedia(path)
                .onSuccess { absolutePath ->
                    val current = if (message.id == rootId) parent.value else replies.value.firstOrNull { it.id == message.id }
                    if (_state.value.unavailable || !threadRootAvailable(parent.value, conversationId, rootId) || current == null || current.isDeleted ||
                        (current.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true)) {
                        _state.value = _state.value.copy(downloadingFileId = null)
                        return@onSuccess
                    }
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

    /** Render only after this request receives a one-time grant. */
    suspend fun consumeViewOnce(message: Message): Message? {
        if (message.conversationId != conversationId) return null
        val granted = repo.consumeViewOnce(message.id)
            .onFailure { notify(it.message ?: "Couldn't open this photo", isError = true) }.getOrNull()
        return granted.takeIf { !_state.value.unavailable && threadRootAvailable(parent.value, conversationId, rootId) }
    }

    /** Wave 2 transcript pill (thread parity with the room strip). */
    fun transcribeVoice(messageId: String) {
        if (messageId.startsWith(TEMP_MESSAGE_PREFIX)) return
        viewModelScope.launch {
            repo.transcribeMessage(messageId)
                .onFailure {
                    _state.value = _state.value.copy(
                        notice = RoomNotice("Transcription unavailable", isError = true),
                    )
                }
        }
    }
}

/**
 * ThreadScreen - back header, the parent bubble card, replies (asc, same
 * bubble renderer) and the composer. Opened from the "N replies ↳" chip or
 * "Reply in thread" (route room/{conversationId}/thread/{rootId}).
 */
@Composable
fun ThreadScreen(
    viewerId: String?,
    onBack: () -> Unit,
    viewModel: ThreadViewModel = hiltViewModel(),
    jumpMessageId: String? = viewModel.initialJumpMessageId,
    readingEnabled: Boolean = false, // host must grant foreground/unlocked reading eligibility
) {
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
    val firstUnreadMessageId by viewModel.firstUnreadMessageId.collectAsStateWithLifecycle()
    val cachedParent by viewModel.parent.collectAsStateWithLifecycle()
    val cachedReplies by viewModel.replies.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val failedReplies by viewModel.failedReplies.collectAsStateWithLifecycle()
    var discardConfirm by remember { mutableStateOf<String?>(null) }
    val revealScope = androidx.compose.runtime.rememberCoroutineScope()
    val revealLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(viewModel, revealLifecycle) {
        revealLifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            // A foreground return is a catch-up boundary even when relay state
            // stayed true. Queue a trailing load if another load is still busy.
            viewModel.retry()
        }
    }
    val clipboard = LocalClipboardManager.current
    discardConfirm?.let { clientId ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { discardConfirm = null },
            title = { Text("Discard this failed reply?") },
            text = { Text("This removes the saved outgoing reply from this device. Your current draft is kept.") },
            confirmButton = { TextButton(onClick = {
                viewModel.recoverReply(clientId, discard = true)
                discardConfirm = null
            }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discardConfirm = null }) { Text("Cancel") } },
        )
    }

    // Do not wait for a separate Room/StateFlow emission to hide refused content.
    val parent = cachedParent.takeUnless { state.unavailable }
    val replies = if (state.unavailable) emptyList() else cachedReplies
    // R3-B item 2 - the roster that drives @mention highlight in bubbles.
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val memberNames = conversation?.memberNames.orEmpty()

    val context = LocalContext.current
    val haptics = app.pulse.ui.rememberGatedHaptics()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    val draft = state.draft
    var lightboxTarget by remember { mutableStateOf<Message?>(null) }
    var openingViewOnce by remember(viewModel.rootId) { mutableStateOf(false) }
    fun openViewOnce(target: Message) {
        if (openingViewOnce || lightboxTarget != null) return
        openingViewOnce = true
        revealScope.launch {
            try {
                viewModel.consumeViewOnce(target)?.let {
                    if (revealLifecycle.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) lightboxTarget = it
                }
            } finally { openingViewOnce = false }
        }
    }
    ThreadReadReceipts(
        ownerKey = viewModel,
        enabled = readingEnabled && !state.unavailable && lightboxTarget == null && discardConfirm == null,
        connected = state.connected == true,
        root = parent,
        replies = replies,
        listState = listState,
        onRead = viewModel::markVisibleRead,
    )
    var anchoredToLatest by remember(viewModel.rootId) { mutableStateOf(false) }
    var jumpResolved by remember(viewModel.rootId, jumpMessageId) { mutableStateOf(false) }
    var flashId by remember(viewModel.rootId, jumpMessageId) { mutableStateOf<String?>(null) }
    val latestThread by rememberUpdatedState(parent to replies)

    // Navigation carries the reply ID all the way into the rendered thread.
    // Wait for Room -> Compose propagation, not merely the HTTP response.
    LaunchedEffect(jumpMessageId, jumpResolved, state.loadedReplyIds, state.offlineJumpId, state.unavailable, state.loading) {
        val target = jumpMessageId ?: return@LaunchedEffect
        val offline = state.offlineJumpId
        val confirmed = if (offline != null) {
            // Cache absence says nothing about any OTHER target. Never turn
            // this one-row fallback into a false "reply deleted" result.
            if (offline != target) return@LaunchedEffect
            setOf(target)
        } else state.loadedReplyIds ?: return@LaunchedEffect
        if (jumpResolved || state.unavailable || state.loading) return@LaunchedEffect
        anchoredToLatest = true
        if (target !in confirmed) {
            viewModel.notify("That reply is no longer available in this thread.", isError = true)
            jumpResolved = true
            return@LaunchedEffect
        }
        val rendered = kotlinx.coroutines.withTimeoutOrNull(3_000) {
            androidx.compose.runtime.snapshotFlow {
                val (root, rows) = latestThread
                root?.let { app.pulse.domain.sync.threadReplyTargetIndex(rows, it, target) }
            }.first { it != null }
        }
        if (rendered != null) {
            androidx.compose.runtime.withFrameNanos { }
            val (root, rows) = latestThread
            val index = root?.let { app.pulse.domain.sync.threadReplyTargetIndex(rows, it, target) }
            if (index != null) {
                listState.scrollToItem(index + 1) // parent header is item zero
                flashId = target
            } else viewModel.notify("That reply is no longer available.", isError = true)
        } else viewModel.notify("Couldn't display that reply. Try opening it again.", isError = true)
        jumpResolved = true
    }
    LaunchedEffect(flashId) {
        if (flashId != null) { kotlinx.coroutines.delay(1_700); flashId = null }
    }

    LaunchedEffect(parent, replies, lightboxTarget?.id) {
        val target = lightboxTarget
        if (target != null && (parent == null || parent?.isDeleted == true ||
                (parent?.id != target.id && replies.none { it.id == target.id && !it.isDeleted }))) {
            lightboxTarget = null
        }
    }

    // Stick to the newest reply as the thread grows (realtime appends);
    // the first non-empty emission jumps straight to the end.
    LaunchedEffect(replies.lastOrNull()?.id) {
        if (replies.isEmpty() || (jumpMessageId != null && !jumpResolved)) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible >= replies.size - 1
        if (!anchoredToLatest || nearBottom) {
            listState.scrollToItem(replies.size) // include the parent header
            anchoredToLatest = true
        }
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice.text, withDismissAction = false)
        viewModel.consumeNotice()
    }

    LaunchedEffect(state.openedFile) {
        val file = state.openedFile ?: return@LaunchedEffect
        runCatching {
            val intent = if (file.share) {
                MediaSupport.buildShareIntent(context, file.path, file.mime, null)
            } else {
                MediaSupport.buildOpenIntent(context, file.path, file.mime)
            }
            context.startActivity(intent)
        }
        viewModel.consumeOpenedFile()
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(PulseIcons.ChevronLeft, contentDescription = "Back")
                }
                Icon(PulseIcons.ChatBubble, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        unreadCount?.takeIf { it > 0 }?.let { "Thread · $it unread" } ?: "Thread",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        (parent?.integrationLabel ?: parent?.authorName)?.let { "Replies to $it" } ?: "…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (parent != null) item(key = "thread-root-heading") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(
                                    PulseIcons.ChatBubble,
                                    contentDescription = "Thread root",
                                    tint = PulsePalette.Emerald,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    "Thread root",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PulsePalette.Emerald,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                }
                item(key = "thread-parent") {
                    parent?.let { root ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    RoundedCornerShape(16.dp),
                                )
                                .padding(10.dp),
                            horizontalAlignment = if (root.authorId == viewerId) Alignment.End else Alignment.Start,
                        ) {
                            ThreadContentBubble(
                                message = root,
                                viewerId = viewerId,
                                downloading = false,
                                voicePlayer = viewModel.voicePlayer,
                                memberNames = memberNames,
                                onConsumeViewOnce = { target ->
                                    openViewOnce(target)
                                },
                                onTranscribe = viewModel::transcribeVoice,
                                onOpenImage = { lightboxTarget = root },
                                onOpenFile = { viewModel.openFile(root, share = false) },
                            )
                        }
                    }
                }
                replies.forEach { reply ->
                    if (reply.id == firstUnreadMessageId) item(key = "thread-unread-divider") { UnreadDivider() }
                    item(key = reply.id) {
                    Column(
                        Modifier.fillMaxWidth().then(if (flashId == reply.id)
                            Modifier.border(2.dp, PulsePalette.Amber, RoundedCornerShape(16.dp)) else Modifier),
                        horizontalAlignment = if (reply.authorId == viewerId) Alignment.End else Alignment.Start,
                    ) {
                        ThreadContentBubble(
                            message = reply,
                            viewerId = viewerId,
                            downloading = state.downloadingFileId == reply.id,
                            voicePlayer = viewModel.voicePlayer,
                            memberNames = memberNames,
                            onConsumeViewOnce = { target ->
                                openViewOnce(target)
                            },
                            onTranscribe = viewModel::transcribeVoice,
                            onOpenImage = { lightboxTarget = reply },
                            onOpenFile = { viewModel.openFile(reply, share = false) },
                        )
                    }
                }
                }
                items(failedReplies, key = { "failed-" + it.clientId }) { entry ->
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("Not sent · saved on this device", style = MaterialTheme.typography.labelMedium)
                        Text(entry.content.ifBlank { "Saved attachment" }, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        Text(entry.failureReason.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Row {
                            TextButton(onClick = { viewModel.recoverReply(entry.clientId, discard = false) },
                                enabled = !state.unavailable && threadRootAvailable(parent, viewModel.conversationId, viewModel.rootId)) { Text("Retry original") }
                            TextButton(enabled = entry.content.isNotEmpty(), onClick = { clipboard.setText(AnnotatedString(entry.content)) }) { Text("Copy") }
                            TextButton(onClick = { discardConfirm = entry.clientId }) { Text("Discard") }
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn() + scaleIn(initialScale = 0.9f, animationSpec = PulseMotion.soft()),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.error ?: "", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = viewModel::retry) { Text("Retry") }
                    }
                }
            }

            // Durable replies survive the connection dropping.
            androidx.compose.animation.AnimatedVisibility(visible = state.connected == false && !state.unavailable && parent != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                ) {
                    Text(
                        "Offline — replies are saved and sent when connected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }

        if (!state.loading && (parent == null || parent?.isDeleted == true)) {
            Text("This thread is unavailable. Your draft is kept.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        // Composer - thread reply send (parentId rides the ordinary path).
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = viewModel::changeDraft,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(PulsePalette.Emerald),
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text("Reply in thread", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(6.dp))
                val canSend = draft.isNotBlank() && !state.sending && !state.unavailable && threadRootAvailable(parent, viewModel.conversationId, viewModel.rootId)
                IconButton(
                    onClick = {
                        if (!canSend) return@IconButton
                        viewModel.send()
                        jumpResolved = true // an explicit send supersedes a suspended search jump
                        anchoredToLatest = false
                        flashId = null
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(44.dp)
                        .background(
                            if (canSend) {
                                Brush.linearGradient(listOf(PulsePalette.Emerald, PulsePalette.EmeraldDeep))
                            } else {
                                SolidColor(MaterialTheme.colorScheme.surfaceVariant)
                            },
                            CircleShape,
                        ),
                ) {
                    Icon(
                        PulseIcons.PaperPlane,
                        contentDescription = "Send reply",
                        tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        SnackbarHost(hostState = snackbar)
    }

    lightboxTarget?.let { target ->
        ImageLightbox(message = target, onDismiss = { lightboxTarget = null })
    }
}

/** One thread row - same renderer family as the river (tombstone/media/text). */
@Composable
private fun ThreadContentBubble(
    message: Message,
    viewerId: String?,
    downloading: Boolean,
    voicePlayer: VoicePlayer,
    memberNames: List<String> = emptyList(),
    onConsumeViewOnce: (Message) -> Unit,
    onTranscribe: (String) -> Unit,
    onOpenImage: () -> Unit,
    onOpenFile: () -> Unit,
) {
    val mine = message.authorId == viewerId
    message.integrationLabel?.let { origin ->
        Text(origin, style = MaterialTheme.typography.labelSmall, color = PulsePalette.Emerald, fontWeight = FontWeight.SemiBold)
    }
    // Wave 2 view-once gating - same state machine as the room river.
    val viewOncePhoto = message.viewOnce && message.imagePath != null
    val burned = viewOncePhoto && !mine && message.viewedAt != null
    val gated = viewOncePhoto && !mine && message.viewedAt == null && !message.isDeleted
    when {
        message.isDeleted -> TombstoneBubble()
        message.poll != null || message.kind == Message.Kind.POLL -> PollCard(
            message = message,
            viewerId = viewerId,
            mine = mine,
            onVote = null, // threads render polls read-only; votes live in the river
            onClose = null,
        )
        burned -> BurnedPhotoBubble()
        gated -> ViewOnceGateBubble(
            imagePath = message.imagePath,
            mine = mine,
            onOpen = { onConsumeViewOnce(message) },
        )
        message.imagePath != null -> ImageBubble(message = message, mine = mine, onOpen = onOpenImage)
        // R1-W2F F-MD-07 - pins render the same tappable row in threads
        // (threads reply on any row kind; the room render lives in the river).
        message.kind == Message.Kind.LOCATION -> LocationPinBubble(message = message, mine = mine)
        message.filePath != null || message.kind == Message.Kind.FILE -> FileBubble(
            message = message,
            mine = mine,
            downloading = downloading,
            onOpen = onOpenFile,
        )
        else -> Bubble(
            message = message,
            mine = mine,
            flashing = false,
            onLongPress = null,
            onQuoteClick = null,
            voicePlayer = voicePlayer,
            onTranscribe = onTranscribe,
            // R3-B item 2 - mentions highlight inside threads too.
            memberNames = memberNames,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}
