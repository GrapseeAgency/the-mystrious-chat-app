package app.pulse.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Forum
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import app.pulse.domain.model.Message
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Thread state holder — the parent (top-level) message plus its replies
 * (asc). Send rides the ordinary wire path with `parentId = rootId` (spec
 * §1.1: threads are ordinary sends, ONE level deep, never conflated with
 * replyToId); the optimistic echo lands in Room and streams back through
 * the thread flow. Thread replies are NEVER queued offline (spec §1.2).
 */
@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: PulseRepository,
    /** The ONE active voice player — room + thread share the singleton. */
    val voicePlayer: VoicePlayer,
) : ViewModel() {

    val conversationId: String = savedStateHandle.get<String>("conversationId").orEmpty()
    val rootId: String = savedStateHandle.get<String>("rootId").orEmpty()

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
        val connected: Boolean? = null,
        val notice: RoomNotice? = null,
        val downloadingFileId: String? = null,
        val openedFile: FileReady? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The thread root — rehydrated from Room, refreshed by loadThread. */
    val parent: StateFlow<Message?> = repo.observeMessages(conversationId)
        .map { rows -> rows.firstOrNull { it.id == rootId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Replies asc — live Room flow (realtime appends ride message:new upserts). */
    val replies: StateFlow<List<Message>> = repo.observeThreadMessages(rootId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            runCatching { repo.loadThread(rootId) }
                .onSuccess { _state.value = _state.value.copy(loading = false) }
                .onFailure { failure ->
                    _state.value = _state.value.copy(loading = false, error = failure.message)
                }
        }
        viewModelScope.launch {
            repo.observeConnected().collect { connected ->
                _state.value = _state.value.copy(connected = connected)
            }
        }
        viewModelScope.launch {
            repo.events().collect { event ->
                if (event is PulseEvent.OutboxDropped) {
                    _state.value = _state.value.copy(
                        notice = RoomNotice("Message couldn't be delivered", isError = true),
                    )
                }
            }
        }
    }

    /**
     * Thread reply — `parentId = rootId` on the ordinary send path. The repo
     * writes an optimistic echo (threadRootId set) that streams through the
     * replies flow; a NETWORK failure retracts it with an honest error (no
     * outbox row — spec: thread replies not queued).
     */
    fun send(body: String) {
        viewModelScope.launch {
            repo.sendMessage(conversationId, body.trim(), parentId = rootId)
                .onSuccess { PulseFx.fire(PulseFx.BurstKind.BURST, count = 26) }
                .onFailure { failure ->
                    _state.value = _state.value.copy(
                        notice = RoomNotice(failure.message ?: "Couldn't send — you appear to be offline", isError = true),
                    )
                }
        }
    }

    fun retry() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { repo.loadThread(rootId) }
                .onSuccess { _state.value = _state.value.copy(loading = false) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
        }
    }

    fun notify(text: String, isError: Boolean = false) {
        _state.value = _state.value.copy(notice = RoomNotice(text, isError))
    }

    fun consumeNotice() {
        if (_state.value.notice != null) _state.value = _state.value.copy(notice = null)
    }

    // ── file download + open/share (same hand-off as the room) ──

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

    /** Wave 2 view-once — fire-and-forget POST /viewed (reveal is instant). */
    fun consumeViewOnce(message: Message) {
        viewModelScope.launch { repo.markMessageViewed(message.id) }
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
 * ThreadScreen — back header, the parent bubble card, replies (asc, same
 * bubble renderer) and the composer. Opened from the "N replies ↳" chip or
 * "Reply in thread" (route room/{conversationId}/thread/{rootId}).
 */
@Composable
fun ThreadScreen(
    viewerId: String?,
    onBack: () -> Unit,
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    val parent by viewModel.parent.collectAsStateWithLifecycle()
    val replies by viewModel.replies.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    var draft by remember { mutableStateOf("") }
    var lightboxTarget by remember { mutableStateOf<Message?>(null) }
    var anchoredToLatest by remember { mutableStateOf(false) }

    // Stick to the newest reply as the thread grows (realtime appends);
    // the first non-empty emission jumps straight to the end.
    LaunchedEffect(replies.size) {
        if (replies.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size
        val nearBottom = lastVisible >= replies.size - 1
        if (!anchoredToLatest || nearBottom) {
            listState.scrollToItem(replies.lastIndex.coerceAtLeast(0))
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Icon(Icons.Filled.Forum, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Thread",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        parent?.authorName?.let { "Replies to $it" } ?: "…",
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
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(
                                    Icons.Filled.Forum,
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
                            ThreadContentBubble(
                                message = root,
                                viewerId = viewerId,
                                downloading = false,
                                voicePlayer = viewModel.voicePlayer,
                                onConsumeViewOnce = { target ->
                                    viewModel.consumeViewOnce(target)
                                    lightboxTarget = target
                                },
                                onTranscribe = viewModel::transcribeVoice,
                                onOpenImage = { lightboxTarget = root },
                                onOpenFile = { viewModel.openFile(root, share = false) },
                            )
                        }
                    }
                }
                items(replies, key = { it.id }) { reply ->
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = if (reply.authorId == viewerId) Alignment.End else Alignment.Start,
                    ) {
                        ThreadContentBubble(
                            message = reply,
                            viewerId = viewerId,
                            downloading = state.downloadingFileId == reply.id,
                            voicePlayer = viewModel.voicePlayer,
                            onConsumeViewOnce = { target ->
                                viewModel.consumeViewOnce(target)
                                lightboxTarget = target
                            },
                            onTranscribe = viewModel::transcribeVoice,
                            onOpenImage = { lightboxTarget = reply },
                            onOpenFile = { viewModel.openFile(reply, share = false) },
                        )
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

            // Thread replies never queue (spec §1.2) — the honesty strip only.
            androidx.compose.animation.AnimatedVisibility(visible = state.connected == false) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                ) {
                    Text(
                        "Offline — replies need a connection and won't queue",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // Composer — thread reply send (parentId rides the ordinary path).
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
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
                val canSend = draft.isNotBlank()
                IconButton(
                    onClick = {
                        if (!canSend) return@IconButton
                        viewModel.send(draft)
                        draft = ""
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
                        Icons.AutoMirrored.Filled.Send,
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

/** One thread row — same renderer family as the river (tombstone/media/text). */
@Composable
private fun ThreadContentBubble(
    message: Message,
    viewerId: String?,
    downloading: Boolean,
    voicePlayer: VoicePlayer,
    onConsumeViewOnce: (Message) -> Unit,
    onTranscribe: (String) -> Unit,
    onOpenImage: () -> Unit,
    onOpenFile: () -> Unit,
) {
    val mine = message.authorId == viewerId
    // Wave 2 view-once gating — same state machine as the room river.
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
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}
