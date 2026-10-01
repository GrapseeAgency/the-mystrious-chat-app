package app.pulse.feature.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
// R6 — M5: the DM dead-end notice icon (web chat-room Ban).
// R1-W2I F-PI-03 — pop-out mini chat menu icon (web PictureInPicture2).
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.core.media.PulseMedia
import app.pulse.core.time.PulseTime
// R1-W2F — per-conversation themes (F-FX-05).
import app.pulse.domain.model.ConvTheme
import app.pulse.domain.model.CallKind
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.LinkPreviewInfo
import app.pulse.domain.model.Message
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import app.pulse.domain.model.Topic
import app.pulse.protocol.REACTION_DEFAULT
import app.pulse.protocol.TOPIC_ICON_DEFAULT
import app.pulse.protocol.TOPIC_ICON_IDS
import app.pulse.protocol.reactionId
import app.pulse.protocol.topicIconId
import app.pulse.ui.EmberGlassButton
import app.pulse.ui.EmberPalette
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulseIcons
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import app.pulse.ui.emberBackdrop
import app.pulse.ui.pulseReactionGlyph
import app.pulse.ui.pulseTopicGlyph
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Chat room — the native rebuild of the web conversation surface, now on the
 * Wave 1 messaging engine ([ChatRoomViewModel]): paginated timeline with day
 * separators, tick states (queued clock, sent tick, seen double tick), the full message
 * action surface, pinned banner, room search + jump, staged media sends and
 * the honest offline strip. Threads open [ThreadScreen] via onOpenThread.
 */

// R7 item 2 — web swipe-to-reply constants (chat-room.tsx:7261-7263, 7357-7361):
// dragConstraints ±64 with elastic 0.12, threshold toward > 28 → beginReply,
// hint opacity fades in between 4 → 28 (all in web CSS px ≈ dp here).
private val REPLY_DRAG_LIMIT = 64.dp
private val REPLY_DRAG_THRESHOLD = 28.dp
private val REPLY_HINT_START = 4.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomScreen(
    conversationId: String,
    viewerId: String?,
    /** Global-search / notification jump — room scrolls + flashes on arrival. */
    jumpMessageId: String? = null,
    onBack: () -> Unit,
    onOpenThread: (conversationId: String, rootId: String) -> Unit,
    viewModel: ChatRoomViewModel = hiltViewModel(),
    // Wave 5 voice rooms (authorized shared plumbing only — the same
    // MainActivity-level trigger idiom the calls surface uses):
    /** True while this conversation has a joined voice room (header tint). */
    voiceJoined: Boolean = false,
    /** Live roster count for the "Voice · N live" pill (joined room). */
    voiceLiveCount: Int = 0,
    /** Opens the voice-rooms overlay for this conversation. */
    onOpenVoiceRoom: () -> Unit = {},
    // R2-A item 6/7/8/9 — the room-info surface (GroupInfoScreen) hosts the
    // automations/webhooks managers, the screen-security toggles and the
    // photo edit; groups/channels only (web room-info-page parity).
    onOpenRoomInfo: (String) -> Unit = {},
    // R7 item 4 — reminder jump for OTHER rooms (web REMINDER_JUMP_EVENT):
    // navigates to room/{id}?jump={messageId} so that room auto-flashes.
    onJumpToRoom: (conversationId: String, messageId: String) -> Unit = { _, _ -> },
    // R8 Task 3-c — group calls (mesh WebRTC): the room reports activation
    // (id + display title → the shell-level session's probe/banner identity)
    // and dials NEW group calls through the ONE shell session. The 1:1
    // header buttons dial the same way through onCall — one live call.
    onRoomActivated: (String, String) -> Unit = { _, _ -> },
    onStartGroupCall: (CallKind, String) -> Unit = { _, _ -> },
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val replyCounts by viewModel.replyCounts.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val jumpTarget by viewModel.jumpTarget.collectAsStateWithLifecycle()
    val topics by viewModel.topics.collectAsStateWithLifecycle()
    val activeTopicId by viewModel.activeTopicId.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val recordMs by viewModel.recordMs.collectAsStateWithLifecycle()
    val recordAmps by viewModel.recordAmps.collectAsStateWithLifecycle()
    val sendingVoice by viewModel.sendingVoice.collectAsStateWithLifecycle()
    val transcribingIds by viewModel.transcribingIds.collectAsStateWithLifecycle()
    // Wave 8 — prefs-driven room rendering (bubble corners, density, wallpaper)
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val bubbleCorner = when (prefs.bubbleRadius) { "md" -> 14.dp; "pill" -> 26.dp; else -> 20.dp }
    val densityGap = if (prefs.density == "compact") 3.dp else 6.dp
    val channelRole by viewModel.channelRole.collectAsStateWithLifecycle()
    val safety by viewModel.safety.collectAsStateWithLifecycle()
    // R1-W2A — quick phrases (F-MS-29) + D34 verified badge state.
    val phrases by viewModel.phrases.collectAsStateWithLifecycle()
    val phrasesBusy by viewModel.phrasesBusy.collectAsStateWithLifecycle()
    val peerVerified by viewModel.peerVerified.collectAsStateWithLifecycle()
    // R1-W2F — translation (F-MD-06), location fix (F-MD-07), conv themes (F-FX-05).
    val translatingId by viewModel.translatingId.collectAsStateWithLifecycle()
    val translated by viewModel.translated.collectAsStateWithLifecycle()
    val locationFix by viewModel.locationFix.collectAsStateWithLifecycle()
    val convThemes by viewModel.convThemes.collectAsStateWithLifecycle()
    // R1-W2I — PiP pane focus (F-PI-03): drives the pop-out toggle in the room menu.
    val pipFocusedId by viewModel.pipFocusedConversationId.collectAsStateWithLifecycle()
    // R2-A item 5 — AI recap card state; item 8 — live group meta drives the veil.
    val recap by viewModel.recap.collectAsStateWithLifecycle()
    val recapLoading by viewModel.recapLoading.collectAsStateWithLifecycle()
    val groupMeta by viewModel.groupMeta.collectAsStateWithLifecycle()

    // R3-B item 3 — the scheduled sends flow now has a consumer (manager sheet
    // + composer chip); item 4 — the incognito arming; item 6 — task busy.
    val scheduledItems by viewModel.scheduled.collectAsStateWithLifecycle()
    val scheduledLoading by viewModel.scheduledLoading.collectAsStateWithLifecycle()
    // R6 — BE7 — upcoming-reminder count for the room-header badge.
    val remindersUpcoming by viewModel.remindersUpcoming.collectAsStateWithLifecycle()
    val anonNext by viewModel.anonNext.collectAsStateWithLifecycle()
    val taskPending by viewModel.taskPending.collectAsStateWithLifecycle()
    // R2-C item 5 — the R44 slow-mode countdown (armed by the 429 retryAfter;
    // the composer chip counts it down live and send/mic stay locked).
    val slowModeRemainingSec by viewModel.slowModeRemainingSec.collectAsStateWithLifecycle()
    val slowBlocked = slowModeRemainingSec > 0
    val screenPrivacyOn = groupMeta?.screenPrivacyEffective == true
    val roomTheme = convThemes[conversationId]
    val effectiveWallpaper = roomTheme?.wallpaper ?: prefs.wallpaper ?: "none"

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val listScope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    // R5-B ITEM 1 — emoji appends re-focus the composer through this handle.
    val composerFocus = remember { FocusRequester() }

    var draft by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<Message?>(null) }
    var forwardTarget by remember { mutableStateOf<Message?>(null) }
    var infoTarget by remember { mutableStateOf<Message?>(null) }
    var deleteTarget by remember { mutableStateOf<Message?>(null) }
    var lightboxTarget by remember { mutableStateOf<Message?>(null) }
    var pinsOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    // R4-B item 2 — the attach sheet's Effects submenu state (web
    // trayEffectsOpen parity): the four chips route into the SAME outcome
    // machine as the /effects slash (PulseSlash.Outcome.Effect → sendEffect).
    var attachEffectsOpen by remember { mutableStateOf(false) }
    var pollBuilderOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var wasEditing by remember { mutableStateOf(false) }
    var expandedFor by remember { mutableStateOf<String?>(null) }
    var scrolledFlash by remember { mutableStateOf<String?>(null) }
    // R1-W2A — reaction picker / who-reacted / stickers / slash help /
    // schedule / quick-phrases hosts (D27, F-MS-24, F-MS-22, F-MS-29).
    var reactionPickerTarget by remember { mutableStateOf<Message?>(null) }
    var whoReactedFor by remember { mutableStateOf<Pair<Message, String>?>(null) }
    var stickerOpen by remember { mutableStateOf(false) }
    var scheduleOpen by remember { mutableStateOf(false) }
    // R3-B item 3 — scheduled sends manager sheet.
    var scheduledOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    // R6 — M2: the quick-phrase manager now lives on the VM (viewModel.phrasesOpen)
    // so the rail's "manage" chip reaches it — the screen-local flag was a dead end.
    // R5-B ITEM 1 — composer emoji picker (draft-EDIT engine, distinct from stickers).
    // R1-W2F — location share sheet (F-MD-07) + theme picker (F-FX-05).
    var locationOpen by remember { mutableStateOf(false) }
    var locationDenied by remember { mutableStateOf(false) }
    var themeOpen by remember { mutableStateOf(false) }
    // R2-A item 5 — the composer draft staged for the /schedule armer.
    var scheduleDraft by remember { mutableStateOf<String?>(null) }
    // R2-A item 4 — the frozen pre-open read watermark (unread divider).
    var unreadAnchorMs by remember(conversationId) { mutableStateOf<Long?>(null) }

    // Wave 6 — broadcast channel lock (role from the server detail).
    val isChannel = conversation?.kind == Conversation.Kind.CHANNEL
    LaunchedEffect(isChannel, conversationId) {
        if (isChannel) viewModel.loadComposerLock()
    }
    val composerLocked = isChannel && channelRole != "admin"
    // R6 — M5: DM dead-end (web chat-room.tsx:5035-5044 `dmBlocked` from the
    // conversation detail): a block in EITHER direction between the pair.
    // The server still 403-enforces sends — this is the honest UX notice.
    val dmBlocked = conversation?.kind == Conversation.Kind.DM && groupMeta?.dmBlocked == true
    // Wave 6 — DM safety entry: the only non-viewer member of a DM.
    val dmPeerId = conversation
        ?.takeIf { it.kind == Conversation.Kind.DM }
        ?.memberIds?.firstOrNull { it != viewerId }

    // Timeline rows (asc) with day separators, then reversed for the
    // reverseLayout list — index 0 is the newest row, the anchor for tails.
    // R2-A item 4 — an "unread" divider row is inserted at the first OTHER
    // person's message after the frozen watermark (web chat-room.tsx:1398-1424).
    val rows = remember(messages, unreadAnchorMs, viewerId) {
        buildTimelineRows(messages, unreadAnchorMs, viewerId)
    }
    val rowsReversed = remember(rows) { rows.asReversed() }
    // R7 item 1 — cluster rhythm (web chat-room.tsx:7275-7278: cozy head
    // mt-2.5 / non-head mt-0.5, compact mt-1 / mt-px). The list spacing drops
    // to the web's subtle non-head gap; HEAD rows (and day/unread dividers)
    // carry the difference as top padding so only clustered rows tighten.
    val clusterBaseGap = if (prefs.density == "compact") 1.dp else 2.dp
    val clusterHeadGap = densityGap - clusterBaseGap
    val lastMineId = remember(messages, viewerId) {
        messages.lastOrNull { it.authorId == viewerId && !it.isDeleted }?.id
    }

    // Wave 0 draft restore — the VM seeds from the local draft table (or the
    // server myDraft fallback) exactly once; never stomp live typing.
    val initialDraft by viewModel.initialDraft.collectAsStateWithLifecycle()
    LaunchedEffect(initialDraft) {
        val seed = initialDraft
        if (!seed.isNullOrBlank() && draft.isBlank() && state.editing == null) draft = seed
    }

    // Edit mode drives the composer: prefill on begin, clear on success
    // (VM keeps the editing row on failure so the text survives retries).
    LaunchedEffect(state.editing) {
        val editing = state.editing
        if (editing != null) {
            draft = editing.body
            wasEditing = true
        } else if (wasEditing) {
            draft = ""
            wasEditing = false
        }
    }

    // Auto-scroll to the newest row when the tail grows (and near the tail).
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty() && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    // R2-A item 4 — freeze the viewer's pre-open read watermark from the FIRST
    // Room summary that lands (web chats-tab handlePress freezes it at tap
    // time): only when unreadCount > 0, else no divider. markRead on entry
    // zeroes the summary shortly after, so this runs exactly once.
    LaunchedEffect(conversation) {
        if (unreadAnchorMs != null) return@LaunchedEffect
        val conv = conversation ?: return@LaunchedEffect
        unreadAnchorMs = if (conv.unreadCount > 0) {
            conv.members.firstOrNull { it.id == viewerId }?.lastReadAt
        } else {
            null
        }
    }

    // R2-A item 4 — jump-to-latest tracking (web chat-room.tsx:1575-1612):
    // near-tail detection clears the missed counter; off-screen arrivals
    // accumulate into the pill badge.
    val nearTail by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    val missedCount = remember { mutableIntStateOf(0) }
    var lastSeenLen by remember { mutableStateOf(0) }
    LaunchedEffect(rows.size, nearTail) {
        val len = rows.size
        if (nearTail) {
            lastSeenLen = len
            missedCount.intValue = 0
        } else if (len > lastSeenLen) {
            missedCount.intValue += len - lastSeenLen
            lastSeenLen = len
        } else if (len < lastSeenLen) {
            // room switch / cache reset
            lastSeenLen = len
            missedCount.intValue = 0
        }
    }

    // R2-A item 8 — screen security: while EITHER flag is on, FLAG_SECURE
    // keeps the room out of screenshots + the task-switcher preview (the
    // Android analogue of the web blur engagement), and the message area
    // covers while the app is backgrounded (web privacyHidden parity).
    var privacyHidden by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity
    DisposableEffect(screenPrivacyOn) {
        val window = activity?.window
        if (screenPrivacyOn && window != null) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        onDispose {
            if (screenPrivacyOn && window != null) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, screenPrivacyOn) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> privacyHidden = true
                Lifecycle.Event.ON_RESUME -> {
                    privacyHidden = false
                    // Returning from room-info (privacy toggles/photo) re-reads
                    // the live flags so the veil + toggles stay honest.
                    viewModel.loadGroupMeta()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Load-older trigger — the reverseLayout list ends at the OLDEST rows;
    // the VM gates reentrancy and the hasMore/limit rules.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount - 1 - lastVisible
        }.collect { remaining ->
            if (remaining <= 6) viewModel.loadOlder()
        }
    }

    // Jump resolution: in-window targets are already flashing (VM flash());
    // out-of-window targets expand the window (bounded ≤14 before= rounds).
    LaunchedEffect(jumpTarget, rows) {
        val target = jumpTarget ?: return@LaunchedEffect
        if (rows.any { it is TimelineRow.Msg && it.message.id == target }) {
            viewModel.consumeJumpTarget()
            viewModel.flash(target)
        } else if (expandedFor != target) {
            expandedFor = target
            viewModel.expandForJump(target)
        }
    }

    // The flash ring also scrolls the bubble into view (search/pins/quotes).
    LaunchedEffect(state.flashMessageId, rows) {
        val id = state.flashMessageId ?: return@LaunchedEffect
        if (scrolledFlash == id) return@LaunchedEffect
        val index = rowsReversed.indexOfFirst { it is TimelineRow.Msg && it.message.id == id }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            scrolledFlash = id
        }
    }

    // One-shot room notices (edits, pins, saves, forward, OutboxDropped).
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice.text, withDismissAction = false)
        viewModel.consumeNotice()
    }

    // Downloaded file hand-off — system viewer or share sheet (spec row 9).
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

    // Attachment pickers — photo picker + system documents (spec row 9).
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::onImagePicked)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onDocumentPicked)
    }

    // ── D30 camera capture: CAMERA runtime gate → TakePicture(FileProvider) ──
    // The shot lands on a cacheDir/camera uri, then flows through the SAME
    // staged pipeline as the gallery pick (onImagePicked → ≤1280px JPEG q0.82
    // data-URL → /api/uploads → caption sheet). Denial is an INLINE explainer
    // (no crash, no dead end).
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    var cameraDenied by remember { mutableStateOf(false) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = captureUri
        captureUri = null
        if (saved && uri != null) viewModel.onImagePicked(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            cameraDenied = false
            captureUri?.let { cameraLauncher.launch(it) }
        } else {
            cameraDenied = true
        }
    }
    val onCameraCapture: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val uri = MediaSupport.newCameraCaptureUri(context)
        captureUri = uri
        if (granted) cameraLauncher.launch(uri) else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ── D31 hold-to-record: RECORD_AUDIO gate + honest inline explainer ──
    // Wave 2 mic gate — RECORD_AUDIO is requested at the UI layer; denial is
    // an inline explainer row above the composer (plus the one-shot notice)
    // pointing at Settings — the mic button itself stays usable, no crash.
    var micDenied by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            micDenied = false
            viewModel.startRecording()
        } else {
            micDenied = true
            viewModel.notify("Microphone access was denied — enable it in Settings", isError = true)
        }
    }
    val onStartVoiceHold: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val onVoiceHoldFinish: (Boolean) -> Unit = { cancelled ->
        if (cancelled) viewModel.cancelRecording() else viewModel.stopAndSend()
    }

    // ── R8 Task 3-c — group call dial gates (contacts-screen idiom) ──
    // RECORD_AUDIO is the hard gate for BOTH kinds (the honest mic-denied
    // card inside the engine is the second net); video also asks for CAMERA
    // in one prompt — camera denial degrades to a voice call in the engine,
    // never blocking the join. The dial rides the ONE shell-level session.
    var groupCallDial by remember { mutableStateOf<Pair<CallKind, String>?>(null) }
    val groupMicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val dial = groupCallDial
        groupCallDial = null
        if (granted && dial != null) onStartGroupCall(dial.first, dial.second)
    }
    val groupVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val dial = groupCallDial
        groupCallDial = null
        if (grants[Manifest.permission.RECORD_AUDIO] == true && dial != null) onStartGroupCall(dial.first, dial.second)
    }
    val dialGroupCall: (CallKind) -> Unit = { wanted ->
        val title = conversation?.title.orEmpty()
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        when {
            !micGranted -> {
                groupCallDial = wanted to title
                groupMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            wanted == CallKind.VIDEO &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED -> {
                groupCallDial = wanted to title
                groupVideoLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
            }
            else -> onStartGroupCall(wanted, title)
        }
    }

    // R8 Task 3-c — report room activation (id + display title) so the shell
    // session probes THIS conversation and banners carry the right title
    // (web openConversationId parity). Idempotent — the shell also sets the
    // bare id on room enter.
    LaunchedEffect(conversation?.id, conversation?.title) {
        if (conversation != null) onRoomActivated(conversationId, conversation?.title.orEmpty())
    }

    // ── R1-W2F F-MD-07 — ACCESS_COARSE_LOCATION runtime gate for pin share.
    // Mirrors the D30 camera gate: denial is an inline explainer INSIDE the
    // location sheet (no crash, no dead end).
    // NOTE for the manifest owner: AndroidManifest.xml needs
    //     <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    // — runtime-only; no background location is used (a static pin needs none).
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            locationDenied = false
            viewModel.requestLocationFix()
        } else {
            locationDenied = true
        }
    }
    val onShareLocation: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.requestLocationFix() else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /**
     * R2-A item 5 — one slash machine for BOTH the palette pick and the send
     * path (web applySlash at chat-room.tsx:296-502 + runPaletteCommand:3226):
     * text outcomes send, sheet outcomes open their REAL surface, /recap runs
     * the AI recap request.
     */
    fun runPaletteCommand(command: PulseSlash.SlashCommand) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        draft = ""
        viewModel.onDraftChanged("")
        when (val outcome = PulseSlash.applySlash(command.cmd)) {
            is PulseSlash.Outcome.Send -> viewModel.send(outcome.content)
            is PulseSlash.Outcome.Effect -> viewModel.sendEffect(outcome.effect, outcome.content)
            is PulseSlash.Outcome.Error -> viewModel.notify(outcome.message, isError = true)
            is PulseSlash.Outcome.Topic -> viewModel.createTopic(outcome.name, TOPIC_ICON_DEFAULT)
            is PulseSlash.Outcome.Remind -> viewModel.remindMe("")
            PulseSlash.Outcome.Recap -> viewModel.requestRecap()
            PulseSlash.Outcome.Help -> helpOpen = true
            is PulseSlash.Outcome.Sheet -> when (outcome.sheet) {
                "poll" -> pollBuilderOpen = true
                "schedule" -> {
                    scheduleDraft = ""
                    scheduleOpen = true
                }
                "sticker" -> stickerOpen = true
                "location" -> {
                    locationOpen = true
                    onShareLocation()
                }
                "whiteboard" -> viewModel.openWhiteboard()
                "redpacket" -> viewModel.openRedPacket()
                "kanban" -> viewModel.openKanban()
                "events" -> viewModel.openEvents()
                "game" -> viewModel.openGame()
                "tournament" -> if (viewModel.isGroup) viewModel.openTournament() else viewModel.notifySticky("Tournaments are for groups only")
                // /stage + /space open the live rooms overlay (voice/stage/space
                // share one engine-owned surface on Android).
                else -> onOpenVoiceRoom()
            }
        }
    }

    /** Composer send — leading-slash drafts run the command machine first. */
    fun sendCurrentDraft() {
        if (draft.isBlank()) return
        val outcome = PulseSlash.applySlash(draft)
        when (outcome) {
            is PulseSlash.Outcome.Send -> {
                viewModel.send(outcome.content)
                if (state.editing == null) draft = "" // edit path clears on success
            }
            is PulseSlash.Outcome.Effect -> {
                viewModel.sendEffect(outcome.effect, outcome.content)
                draft = ""
            }
            else -> runPaletteCommand(
                PulseSlash.SlashCommand(
                    cmd = "/" + draft.trim().drop(1).substringBefore(' ').lowercase(),
                    args = "",
                    help = "",
                ),
            )
        }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // Wave 2 topic rail — refresh on open + every 15s while the room is open
    // (web parity tick; after-send refreshes ride the VM).
    LaunchedEffect(conversationId) {
        viewModel.refreshTopics()
        viewModel.loadPhrases()
        // R3-B item 3 — load the pending scheduled sends once on open (the
        // manager + composer chip re-arm after every schedule/cancel via the VM).
        viewModel.loadScheduled()
        // R6 — BE7 — the header badge counts upcoming reminders once per open.
        viewModel.loadRemindersUpcoming()
        while (true) {
            delay(15_000)
            viewModel.refreshTopics()
        }
    }

    // D34 — DM peer verification state for the header badge (quiet fetch;
    // tapping the badge still opens the safety sheet via onOpenSafety).
    LaunchedEffect(dmPeerId) {
        dmPeerId?.let(viewModel::loadPeerVerification)
    }

    Column(
        Modifier
            .fillMaxSize()
            .emberBackdrop(withGlow = true)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        RoomHeader(
            conversation = conversation,
            partnerTypingName = state.partnerTypingName,
            searchOpen = state.searchOpen,
            voiceJoined = voiceJoined,
            voiceLiveCount = voiceLiveCount,
            onOpenVoiceRoom = onOpenVoiceRoom,
            onBack = {
                if (state.searchOpen) viewModel.setSearchOpen(false) else onBack()
            },
            onToggleSearch = {
                if (state.searchOpen) viewModel.setSearchOpen(false) else viewModel.setSearchOpen(true)
            },
            onOpenSafety = if (dmPeerId != null) {
                { viewModel.loadSafety(dmPeerId) }
            } else null,
            onOpenLeaderboard = if (conversation?.isGroupish == true) {
                { viewModel.openLeaderboard() }
            } else null,
            // R1-W2F F-FX-05 — the overflow menu's theme picker entry.
            onOpenTheme = { themeOpen = true },
            // R1-W2I F-PI-03 — the pop-out mini-chat toggle (web header
            // PictureInPicture2 button: focused pane → close, else open).
            pipActive = pipFocusedId == conversationId,
            onTogglePip = { viewModel.togglePipPane() },
            // D34 — DM peer verification badge state (null = unknown/loading).
            peerVerified = if (dmPeerId != null) peerVerified else null,
            // R2-A item 5 — the AI-recap header entry (web chat-room.tsx:4162).
            recapBusy = recapLoading,
            onRequestRecap = viewModel::requestRecap,
            // R2-C item 1 — room info for EVERY room kind: web's header menu
            // covers DMs too (chat-room.tsx:2153-2171 · room-info-page.tsx),
            // so the gate is only "the conversation is loaded". GroupInfoScreen
            // adapts itself for DMs (partner header, no members/invite/roles).
            onOpenRoomInfo = if (conversation != null) {
                { onOpenRoomInfo(conversationId) }
            } else null,
            // R3-B item 3 — the scheduled sends manager (overflow entry).
            onOpenScheduled = { scheduledOpen = true },
            scheduledCount = scheduledItems.count { it.cancelledAtIso == null },
            // R6 — BE7 — the reminders button (web header parity) + badge.
            onOpenReminders = viewModel::openReminders,
            remindersCount = remindersUpcoming,
            // R8 Task 3-c — GROUP call dials (web chat-room header Phone/Video
            // group buttons, chat-room.tsx:4026-4053): voice always, video
            // beside it; groups only, permission gates live in the screen.
            onStartGroupVoice = if (conversation?.isGroupish == true) {
                { dialGroupCall(CallKind.VOICE) }
            } else null,
            onStartGroupVideo = if (conversation?.isGroupish == true) {
                { dialGroupCall(CallKind.VIDEO) }
            } else null,
        )

        // Wave 2 topic rail — GROUP rooms only (DMs have nothing to file into).
        if (conversation?.isGroupish == true) {
            TopicBar(
                topics = topics,
                activeTopicId = activeTopicId,
                onSelect = viewModel::setActiveTopic,
                onCreate = viewModel::createTopic,
            )
        }

        // Newest pinned message strip (tap → jump+flash; pin icon → all pins).
        state.pins.lastOrNull()?.let { newestPin ->
            PinnedBanner(
                pin = newestPin,
                onJump = { viewModel.jumpTo(newestPin.id) },
                onOpenAll = { pinsOpen = true },
            )
        }

        // Expandable room search bar + hit overlay (server q= + local window).
        AnimatedVisibility(
            visible = state.searchOpen,
            enter = fadeIn() + scaleIn(initialScale = 0.97f, animationSpec = PulseMotion.soft()),
            exit = fadeOut() + scaleOut(targetScale = 0.97f, animationSpec = tween(120)),
        ) {
            RoomSearchBar(
                query = searchQuery,
                results = state.searchResults,
                searching = state.searching,
                onQueryChange = {
                    searchQuery = it
                    viewModel.onSearchQueryChanged(it)
                },
                onClose = {
                    searchQuery = ""
                    viewModel.setSearchOpen(false)
                },
                onOpenHit = { hit ->
                    searchQuery = ""
                    viewModel.setSearchOpen(false)
                    viewModel.jumpTo(hit.id)
                },
            )
        }

        Box(
            Modifier
                .weight(1f)
                .then(
                    // R1-W2F F-FX-05 — per-conversation override ?? global default
                    // (web effectiveConvWallpaper parity).
                    app.pulse.ui.PulseWallpaper.brush(effectiveWallpaper)
                        ?.let { brush -> Modifier.background(brush) }
                        ?: Modifier,
                )
                .then(
                    // R1-W2F F-FX-05 — the tint glow replaces the TOP gradient
                    // stop (web applyConvTint parity), visible even on `none`.
                    roomTheme?.tint?.let { tint -> convTintGlow(tint) }
                        ?.let { brush -> Modifier.background(brush) }
                        ?: Modifier,
                ),
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(clusterBaseGap),
            ) {
                items(rowsReversed, key = { it.key }) { row ->
                    when (row) {
                        is TimelineRow.Day -> DaySeparator(row.label, modifier = Modifier.padding(top = clusterHeadGap))
                        is TimelineRow.Unread -> UnreadDivider(modifier = Modifier.padding(top = clusterHeadGap))
                        is TimelineRow.Msg -> {
                            val message = row.message
                            MessageRow(
                                bubbleCornerDp = bubbleCorner,
                                message = message,
                                conversation = conversation,
                                viewerId = viewerId,
                                partnerLastReadAt = state.partnerLastReadAt,
                                isLastMine = lastMineId == message.id,
                                flashing = state.flashMessageId == message.id,
                                replyCount = replyCounts[message.id] ?: 0,
                                downloading = state.downloadingFileId == message.id,
                                voicePlayer = viewModel.voicePlayer,
                                transcribing = message.id in transcribingIds,
                                // R1-W2F F-MD-06 — per-message LLM translation state.
                                translating = translatingId == message.id,
                                translatedText = translated[message.id],
                                onTranscribe = viewModel::transcribeVoice,
                                onVotePoll = viewModel::votePoll,
                                onClosePoll = viewModel::closePoll,
                                onGameMove = { matchId, cell -> viewModel.gameMove(matchId, cell) },
                                onGameJoin = { matchId -> viewModel.joinGame(matchId) },
                                onGameLoad = { matchId -> viewModel.gameDetail(matchId) },
                                // R7 item 3 — rematch POSTs a fresh challenge.
                                onGameRematch = { match -> viewModel.rematch(match) },
                                onRedPacketLoad = { id -> viewModel.redPacketDetail(id) },
                                onRedPacketGrab = { id -> viewModel.grabRedPacket(id) },
                                onRedPacketOpen = { id -> viewModel.openRedPacketDetail(id) },
                                onTournamentLoad = { id -> viewModel.tournamentDetail(id) },
                                onTournamentJoin = { id -> viewModel.joinTournament(id) },
                                onTournamentFinish = { id -> viewModel.finishTournament(id) },
                                onConsumeViewOnce = { target ->
                                    // Reveal is instant — the POST is fire-and-forget (web parity).
                                    viewModel.consumeViewOnce(target)
                                    lightboxTarget = target
                                },
                                onLongPress = if (!message.isDeleted && !message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                                    {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        actionTarget = message
                                    }
                                } else {
                                    null
                                },
                                onQuoteClick = { quoteId -> viewModel.jumpTo(quoteId) },
                                onOpenImage = { lightboxTarget = message },
                                onOpenFile = { viewModel.openFile(message, share = false) },
                                onOpenThread = { onOpenThread(conversationId, message.id) },
                                // D27 — long-press a reaction chip → who-reacted sheet.
                                onWhoReacted = if (!message.isDeleted && !message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                                    { emoji -> whoReactedFor = message to emoji }
                                } else {
                                    null
                                },
                                // R6 — M6 — double-tap bubble toggles the heart reaction + heart burst
                                // burst (web chat-room.tsx:7265-7268 + :7389-7394:
                                // particles only when the tap ADDS the reaction).
                                onDoubleClick = if (!message.isDeleted && !message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                                    {
                                        val addsHeart = message.reactions.none {
                                            // Wire values are reaction ids now; legacy emoji rows
                                            // normalize through reactionId on the compare.
                                            reactionId(it.emoji) == REACTION_DEFAULT && it.userId == viewerId
                                        }
                                        if (addsHeart) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            app.pulse.core.fx.PulseFx.fire(
                                                app.pulse.core.fx.PulseFx.BurstKind.HEARTS,
                                                count = 28,
                                            )
                                        }
                                        viewModel.react(message.id, REACTION_DEFAULT)
                                    }
                                } else {
                                    null
                                },
                                head = row.head,
                                tail = row.tail,
                                // R7 item 2 — swipe the bubble toward the
                                // trailing edge to reply (web beginReply).
                                onReply = if (!message.isDeleted && !message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                                    {
                                        viewModel.setReplyTo(message)
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier
                                    .animateItem()
                                    .then(
                                        // R7 item 1 — head rows restore the
                                        // room's normal rhythm above the bubble.
                                        if (row.head) Modifier.padding(top = clusterHeadGap) else Modifier,
                                    ),
                            )
                        }
                    }
                }
            }

            // R7 item 1(c) — pinned day chip (web `sticky top-1`,
            // chat-room.tsx:4406-4417): the OLDEST visible day pins to the top
            // edge whenever its natural chip has scrolled away. LazyListScope's
            // stickyHeader pins to the reverseLayout START edge (the bottom) —
            // the wrong edge for web semantics — so the pin derives from the
            // visible window instead.
            val pinnedDayChip by remember(rowsReversed) {
                derivedStateOf {
                    var label: String? = null
                    for (info in listState.layoutInfo.visibleItemsInfo.sortedByDescending { it.index }) {
                        when (val row = rowsReversed.getOrNull(info.index)) {
                            is TimelineRow.Msg -> {
                                label = PulseTime.dayChip(row.message.createdAt)
                                break
                            }
                            is TimelineRow.Day -> {
                                label = null
                                break
                            }
                            else -> continue
                        }
                    }
                    label
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = pinnedDayChip != null,
                enter = fadeIn() + scaleIn(initialScale = 0.96f, animationSpec = PulseMotion.soft()),
                exit = fadeOut() + scaleOut(targetScale = 0.96f, animationSpec = tween(120)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp),
            ) {
                pinnedDayChip?.let { DaySeparator(it) }
            }

            // R2-A item 4 — jump-to-latest pill (web chat-room.tsx:4527-4565):
            // visible while scrolled away from the tail, badge = the number of
            // rows that landed off-screen; tap scrolls to the newest row.
            androidx.compose.animation.AnimatedVisibility(
                visible = !nearTail,
                enter = fadeIn() + scaleIn(initialScale = 0.85f, animationSpec = PulseMotion.snappy()),
                exit = fadeOut() + scaleOut(targetScale = 0.9f, animationSpec = tween(120)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Transparent,
                    shadowElevation = 6.dp,
                    modifier = Modifier.semantics {
                        contentDescription = if (missedCount.intValue > 0) {
                            "Jump to newest messages — ${missedCount.intValue} new"
                        } else {
                            "Jump to newest messages"
                        }
                    },
                ) {
                    // EMB: the jump pill rides the ember gradient.
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Brush.linearGradient(EmberPalette.Gradient))
                            .clickable {
                                missedCount.intValue = 0
                                listScope.launch { listState.animateScrollToItem(0) }
                            }
                            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("New messages", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1C1410))
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            PulseIcons.ChevronDown,
                            contentDescription = null,
                            tint = Color(0xFF1C1410),
                            modifier = Modifier.size(14.dp),
                        )
                        if (missedCount.intValue > 0) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (missedCount.intValue > 99) "99+" else "${missedCount.intValue}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF7A4E33),
                                )
                            }
                        }
                    }
                }
            }

            // R2-A item 8 — the veil over the message area while the app is
            // backgrounded (web screen-privacy-veil: covers ONLY the messages;
            // header + composer stay untouched).
            if (screenPrivacyOn && privacyHidden) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            PulseIcons.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Screen security is on",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Messages stay hidden until you return to Pulse.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            // Load failure — honest error card with retry (page fetch / offline).
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

            // Wave 0 offline core — pending outbox state is honest, neutral,
            // and self-clearing: it shows while a `local_` bubble is queued
            // and disappears the moment the flush swaps it for the real row.
            val hasPending = messages.any { it.id.startsWith(TEMP_MESSAGE_PREFIX) }
            androidx.compose.animation.AnimatedVisibility(
                visible = hasPending && state.error == null,
                enter = fadeIn() + scaleIn(initialScale = 0.9f, animationSpec = PulseMotion.soft()),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            PulseIcons.Clock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Queued — will send when online",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // Staged media card — upload lifecycle lives in the VM (never queued).
        AnimatedVisibility(
            visible = state.staged != null,
            enter = fadeIn() + scaleIn(initialScale = 0.96f, animationSpec = PulseMotion.soft()),
            exit = fadeOut() + scaleOut(targetScale = 0.96f, animationSpec = tween(120)),
        ) {
            state.staged?.let { staged ->
                StagedMediaCard(
                    staged = staged,
                    caption = caption,
                    onCaptionChange = { caption = it },
                    onViewOnceChange = viewModel::setStagedViewOnce,
                    onSend = {
                        viewModel.sendStaged(caption)
                        caption = ""
                    },
                    onRetry = viewModel::retryStaged,
                    onRemove = {
                        viewModel.cancelStaged()
                        caption = ""
                    },
                )
            }
        }

        // Reply quote above the composer (tap jumps to the quoted message).
        AnimatedVisibility(
            visible = state.replyTo != null,
            enter = fadeIn() + scaleIn(initialScale = 0.96f, animationSpec = PulseMotion.snappy()),
            exit = fadeOut() + scaleOut(targetScale = 0.96f, animationSpec = tween(120)),
        ) {
            state.replyTo?.let { reply ->
                Surface(
                    color = EmberPalette.CardFill,
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmberPalette.Hairline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { viewModel.jumpTo(reply.id) },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(PulseIcons.Reply, contentDescription = null, tint = EmberPalette.SenderName, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(reply.authorName, style = MaterialTheme.typography.labelMedium, color = EmberPalette.SenderName, fontWeight = FontWeight.SemiBold)
                            Text(reply.body, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.55f))
                        }
                        IconButton(onClick = { viewModel.setReplyTo(null) }) {
                            Icon(PulseIcons.X, contentDescription = "Cancel reply", modifier = Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }

        // Edit mode bar above the composer (PATCH on send, X cancels).
        AnimatedVisibility(
            visible = state.editing != null,
            enter = fadeIn() + scaleIn(initialScale = 0.96f, animationSpec = PulseMotion.snappy()),
            exit = fadeOut() + scaleOut(targetScale = 0.96f, animationSpec = tween(120)),
        ) {
            state.editing?.let { editing ->
                Surface(
                    color = EmberPalette.CardFill,
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmberPalette.Hairline),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(PulseIcons.Pencil, contentDescription = null, tint = EmberPalette.Amber, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Editing message", style = MaterialTheme.typography.labelMedium, color = EmberPalette.Amber, fontWeight = FontWeight.SemiBold)
                            Text(editing.body, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = 0.55f))
                        }
                        IconButton(onClick = viewModel::cancelEdit) {
                            Icon(PulseIcons.X, contentDescription = "Cancel edit", modifier = Modifier.size(16.dp), tint = Color.White.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }

        // Offline honesty strip (spec row 7) — text queues, media won't.
        AnimatedVisibility(visible = state.connected == false) {
            Surface(
                color = EmberPalette.CardFill,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        PulseIcons.Clock,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        "Offline — messages will queue",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
            }
        }

        // R2-A item 5 — AI recap card pinned above the composer (web
        // chat-room.tsx:4835-4900): loading spinner → summary with Copy, and
        // an auto-dismiss after 15 s so it never outstays its welcome.
        LaunchedEffect(recap) {
            if (recap != null) {
                delay(15_000)
                viewModel.consumeRecap()
            }
        }
        AnimatedVisibility(
            visible = recap != null || recapLoading,
            enter = fadeIn() + scaleIn(initialScale = 0.96f, animationSpec = PulseMotion.soft()),
            exit = fadeOut() + scaleOut(targetScale = 0.96f, animationSpec = tween(120)),
        ) {
            Surface(
                color = EmberPalette.CardFill,
                border = androidx.compose.foundation.BorderStroke(1.dp, EmberPalette.Hairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                PulseIcons.Sparkle,
                                contentDescription = null,
                                tint = EmberPalette.SenderName,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("AI recap", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                when {
                                    recapLoading -> "Summarizing the latest messages"
                                    recap != null -> "Based on ${recap?.basedOn ?: 0} messages"
                                    else -> ""
                                },
                                fontSize = 10.5.sp,
                                color = Color.White.copy(alpha = 0.45f),
                            )
                        }
                        if (recap != null && !recapLoading) {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(recap?.text.orEmpty()))
                                viewModel.notify("Recap copied")
                            }) {
                                Text("Copy", fontSize = 11.sp, color = EmberPalette.Amber, fontWeight = FontWeight.Bold)
                            }
                        }
                        IconButton(onClick = viewModel::consumeRecap) {
                            Icon(PulseIcons.X, contentDescription = "Dismiss recap", modifier = Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.55f))
                        }
                    }
                    if (recap != null && !recapLoading) {
                        Text(
                            recap?.text.orEmpty(),
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Reading the room…",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
            }
        }

        // R2-A item 5 — the '/'-command palette (web chat-room.tsx:5199):
        // drafts starting with '/' list the matched commands; a pick runs the
        // same outcome machine the web palette does.
        if (draft.startsWith("/") && state.editing == null) {
            SlashPalette(
                draft = draft,
                onPick = ::runPaletteCommand,
            )
        }

        // Wave 6 — @mention suggester above the composer (roster-filtered).
        val memberNames = conversation?.memberNames.orEmpty()
        val activeToken = draft.substringAfterLast(' ', "")
        val mentionQuery = activeToken.takeIf { it.startsWith("@") }?.drop(1)?.lowercase().orEmpty()
        val mentionSuggestions = if (mentionQuery.isEmpty() || state.editing != null) {
            emptyList()
        } else {
            memberNames.filter { it.lowercase().contains(mentionQuery) }.take(5)
        }
        if (mentionSuggestions.isNotEmpty()) {
            Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth()) {
                    mentionSuggestions.forEach { name ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val prefix = draft.dropLast(activeToken.length)
                                    draft = prefix + "@" + name.trim() + " "
                                    viewModel.onDraftChanged(draft)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PulseAvatar(name = name, colorHex = null, size = 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("@" + name.trim(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // ── R2-C item 5 — slow-mode countdown chip (web chat-room.tsx:4747-
        // 4768): appears the moment the server answers 429, counts the honest
        // wait down live (mm:ss), then collapses. Send + mic stay disabled
        // while it shows.
        if (slowBlocked) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    ),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            PulseIcons.Gauge,
                            contentDescription = null,
                            tint = PulsePalette.Emerald,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Slow mode — you can send again in " + slowCountdown(slowModeRemainingSec),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── R3-B item 3 — scheduled sends chip (web chat-room.tsx:4726-4741):
        // "next · N pending — tap to manage" while this room has pending rows.
        val nextScheduled = scheduledItems.filter { it.cancelledAtIso == null }.minByOrNull { PulseTime.epochMs(it.scheduledAtIso) }
        if (nextScheduled != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { scheduledOpen = true }
                        .semantics { contentDescription = "Manage pending scheduled messages" },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            PulseIcons.Clock,
                            contentDescription = null,
                            tint = PulsePalette.Amber,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            PulseTime.listStamp(nextScheduled.scheduledAtIso) + " · " +
                                scheduledItems.count { it.cancelledAtIso == null } + " pending — tap to manage",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── R3-B item 4 — the armed-incognito hint (web chat-room.tsx:4800-4825,
        // verbatim copy): tap the X to disarm before the next send.
        if (anonNext && conversation?.isGroupish == true && !recording) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = PulsePalette.Emerald.copy(alpha = 0.14f),
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            PulseIcons.EyeOff,
                            contentDescription = null,
                            tint = PulsePalette.Emerald,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Incognito on — next message hides your name",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = PulsePalette.Emerald,
                        )
                        IconButton(onClick = viewModel::toggleIncognito) {
                            Icon(
                                PulseIcons.X,
                                contentDescription = "Turn off incognito",
                                tint = PulsePalette.Emerald,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── R6 — M2: quick phrases rail (F-MS-29, web chat-room.tsx:5046-5060
        // parity): composer-adjacent chips; tap appends the phrase to the
        // draft (space-separated, then refocus — web insertQuickPhrase), the
        // tail chip opens the manage sheet. Hidden while locked/blocked/
        // recording/editing exactly like the web rail.
        if (!composerLocked && !dmBlocked && !recording && state.editing == null) {
            QuickPhrasesRail(
                phrases = phrases,
                onUse = { phrase ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    // Web insertQuickPhrase rule: empty → the phrase alone;
                    // trailing whitespace → direct concat; else space-separated.
                    draft = when {
                        draft.isEmpty() -> phrase
                        draft.endsWith(" ") -> draft + phrase
                        else -> "$draft $phrase"
                    }
                    viewModel.onDraftChanged(draft)
                    composerFocus.requestFocus()
                },
                onManage = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.openPhrases()
                },
            )
        }

        // ── R7 item 5 — topic filing pill (web chat-room.tsx:4663-4690) ──
        // While a topic is active and not editing: persistent emerald pill
        // ABOVE the composer — MessagesSquare icon, "Filing to #<name>", X
        // stops filing and returns to General (setActiveTopicId(null)).
        val activeTopic = topics.firstOrNull { it.id == activeTopicId }
        androidx.compose.animation.AnimatedVisibility(
            visible = activeTopic != null,
            enter = fadeIn() + androidx.compose.animation.expandVertically(animationSpec = PulseMotion.soft()),
            exit = fadeOut() + androidx.compose.animation.shrinkVertically(animationSpec = tween(120)),
        ) {
            activeTopic?.let { topic ->
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = PulsePalette.Emerald.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            PulseIcons.Hash,
                            contentDescription = null,
                            tint = PulsePalette.Emerald,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Filing to #${topic.name}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = PulsePalette.Emerald,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.setActiveTopic(null) }) {
                            Icon(
                                PulseIcons.X,
                                contentDescription = "Stop filing to this topic — back to General",
                                tint = PulsePalette.Emerald,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        // Composer — the text side swaps to the record bar while recording;
        // the right slot (HoldRecordSlot) is ALWAYS mounted so the hold
        // gesture survives. Wave 6 — broadcast channel lock: non-admins get
        // the glass notice (web parity); the server still 403s non-admin posts.
        // R6 — M5: a blocked DM pair gets the rose dead-end notice instead of
        // the composer (web chat-room.tsx:5035-5044) — mic/send are gone with
        // it; the server 403 stays the authoritative gate.
        if (dmBlocked) {
            Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PulseIcons.Ban, contentDescription = null, tint = PulsePalette.Rose)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "You can no longer message this account",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else if (composerLocked) {
            Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PulseIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text("Only admins can post", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else Surface(color = Color.Transparent) {
            // D31 hold-to-record — the RIGHT slot is ALWAYS mounted (same node
            // across idle → recording) so the press gesture survives the state
            // change: hold the mic to record, release to send, slide LEFT past
            // the threshold to cancel (bar shows "Release to cancel").
            var cancelArmed by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.weight(1f)) {
                    if (recording) {
                        RecordBarContent(
                            recordMs = recordMs,
                            amps = recordAmps,
                            sending = sendingVoice,
                            cancelArmed = cancelArmed,
                            onCancel = viewModel::cancelRecording,
                        )
                    } else {
                        // EMB composer: [incognito] [ pill: smile | field | camera ] [attach] [mic/send]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            // R3-B item 4 — the incognito arming toggle (web anonNext;
                            // GROUPS only — the server clamps anon off on DMs).
                            if (conversation?.isGroupish == true) {
                                IconButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.toggleIncognito()
                                    },
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .semantics {
                                            contentDescription = if (anonNext) {
                                                "Turn off incognito"
                                            } else {
                                                "Incognito — next send hides your name"
                                            }
                                            stateDescription = if (anonNext) "Armed" else "Off"
                                        },
                                ) {
                                    Icon(
                                        if (anonNext) PulseIcons.EyeOff else PulseIcons.Eye,
                                        contentDescription = null,
                                        tint = if (anonNext) EmberPalette.Online else Color.White.copy(alpha = 0.55f),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            // EMB: the 52dp glass pill — PillFill, white 8% border, radius 26.
                            Row(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp)
                                    .clip(RoundedCornerShape(26.dp))
                                    .background(EmberPalette.PillFill)
                                    .border(1.dp, EmberPalette.Hairline, RoundedCornerShape(26.dp))
                                    .padding(horizontal = 2.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // R5-B ITEM 1 - the smile button now hosts the
                                // stamp picker (R19-a retired the draft-append
                                // emoji popup; stamps are registry ids).
                                IconButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        stickerOpen = true
                                    },
                                    modifier = Modifier.clip(CircleShape),
                                ) {
                                    Icon(
                                        PulseIcons.Smile,
                                        contentDescription = "Open stamp picker",
                                        tint = Color.White.copy(alpha = 0.55f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                                BasicTextField(
                                    value = draft,
                                    onValueChange = {
                                        draft = it
                                        viewModel.onDraftChanged(it)
                                    },
                                    textStyle = TextStyle(fontSize = 15.sp, color = Color.White),
                                    cursorBrush = SolidColor(EmberPalette.Amber),
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(composerFocus)
                                        .padding(horizontal = 4.dp, vertical = 14.dp),
                                    decorationBox = { inner ->
                                        Box {
                                            if (draft.isEmpty()) {
                                                Text(
                                                    if (state.editing != null) "Edit your message" else "Type here",
                                                    fontSize = 15.sp,
                                                    color = Color.White.copy(alpha = 0.40f),
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                )
                                // D30 camera glyph — the EXISTING capture trigger.
                                IconButton(
                                    onClick = onCameraCapture,
                                    modifier = Modifier.clip(CircleShape),
                                ) {
                                    Icon(
                                        PulseIcons.Camera,
                                        contentDescription = "Take a photo",
                                        tint = Color.White.copy(alpha = 0.55f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                // EMB: the attach trigger as a 48dp dark-ink circle (same gate as before).
                if (!recording) {
                    IconButton(
                        onClick = {
                            if (state.staged == null && state.editing == null) attachOpen = true
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(EmberPalette.FabFill),
                    ) {
                        Icon(
                            PulseIcons.Plus,
                            contentDescription = "Attach",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                val canSend = draft.isNotBlank() && !slowBlocked
                val slotMic = !canSend && state.editing == null && state.staged == null && !sendingVoice && !recording
                HoldRecordSlot(
                    recording = recording,
                    sending = sendingVoice,
                    micVisible = slotMic,
                    canSend = canSend,
                    // R2-C item 5 — slow mode locks the mic (web disabled-mic parity).
                    enabled = !slowBlocked,
                    onRecordStart = onStartVoiceHold,
                    onRecordArm = { armed -> cancelArmed = armed },
                    onRecordFinish = { cancelled ->
                        cancelArmed = false
                        onVoiceHoldFinish(cancelled)
                    },
                    onSend = {
                        if (!canSend) return@HoldRecordSlot
                        sendCurrentDraft()
                    },
                )
            }
        }

        // ── D30/D31 permission-denied inline explainers (graceful, no crash) ──
        if (micDenied) {
            PermissionExplainer(
                message = "Microphone access is off — voice notes need it. Hold-to-record unlocks once it's on.",
                onOpenSettings = { openAppSettings(context) },
                onDismiss = { micDenied = false },
            )
        }
        if (cameraDenied) {
            PermissionExplainer(
                message = "Camera access is off — allow it to take photos for this chat.",
                onOpenSettings = { openAppSettings(context) },
                onDismiss = { cameraDenied = false },
            )
        }

        SnackbarHost(hostState = snackbar)
    }

    // ── sheets & dialogs ─────────────────────────────────────────────

    if (attachOpen) {
        AttachSheet(
            onDismiss = { attachOpen = false; attachEffectsOpen = false },
            onPhoto = {
                attachOpen = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onCamera = {
                attachOpen = false
                onCameraCapture()
            },
            onDocument = {
                attachOpen = false
                documentPicker.launch(PulseMedia.DOCUMENT_MIME_ARRAY)
            },
            // R1-W2F F-MD-07 — one-shot fix → confirm sheet → kind:"location" row.
            onLocation = {
                attachOpen = false
                locationOpen = true
                onShareLocation()
            },
            onPoll = {
                attachOpen = false
                pollBuilderOpen = true
            },
            onWhiteboard = { attachOpen = false; viewModel.openWhiteboard() },
            onRedPacket = { attachOpen = false; viewModel.openRedPacket() },
            onEvents = { attachOpen = false; viewModel.openEvents() },
            onGame = { attachOpen = false; viewModel.openGame() },
            onTournament = {
                attachOpen = false
                if (viewModel.isGroup) viewModel.openTournament() else viewModel.notifySticky("Tournaments are for groups only")
            },
            onKanban = { attachOpen = false; viewModel.openKanban() },
            // ── R4-B item 2 — Express rows ──
            effectsOpen = attachEffectsOpen,
            onToggleEffects = { attachEffectsOpen = !attachEffectsOpen },
            onPickEffect = { effect ->
                attachOpen = false
                attachEffectsOpen = false
                if (draft.isNotBlank()) {
                    // The /effects outcome machine, reused verbatim: a draft
                    // rides the effect-flagged send (sendCurrentDraft's
                    // Outcome.Effect branch).
                    viewModel.sendEffect(effect, draft)
                    draft = ""
                    viewModel.onDraftChanged("")
                } else {
                    // Empty draft → stage the command; the existing slash
                    // machine sends it on the next send tap (web arms the
                    // effect — Android stages it visibly, honest parity).
                    draft = "/effects $effect "
                    viewModel.onDraftChanged(draft)
                }
            },
            onStickers = {
                attachOpen = false
                attachEffectsOpen = false
                stickerOpen = true
            },
            onScheduleSend = {
                attachOpen = false
                attachEffectsOpen = false
                val text = draft.trim()
                if (text.isEmpty()) {
                    // Web Schedule tile copy verbatim (chat-room.tsx:2615).
                    viewModel.notify("Type the message first, then schedule it")
                } else {
                    scheduleDraft = text
                    scheduleOpen = true
                }
            },
            onScheduledSends = {
                attachOpen = false
                attachEffectsOpen = false
                scheduledOpen = true
            },
            // Group-gated like the composer toggle (web groupOnly parity):
            // null in DMs hides the row entirely. Both entries flip the SAME
            // VM anonNext state — one source of truth.
            onIncognito = if (conversation?.isGroupish == true) {
                {
                    attachOpen = false
                    attachEffectsOpen = false
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.toggleIncognito()
                }
            } else {
                null
            },
        )
    }

    // R2-A item 5 — the palette's sticker / schedule / help hosts (the
    // composables existed since R1-W2A; the palette pick now opens them with
    // REAL send/schedule paths).
    if (stickerOpen) {
        StickerPickerSheet(
            onDismiss = { stickerOpen = false },
            onPick = { emoji, pack ->
                stickerOpen = false
                viewModel.sendSticker(emoji, pack)
            },
        )
    }
    if (scheduleOpen) {
        ScheduleSheet(
            draft = scheduleDraft.orEmpty(),
            busy = false,
            onDismiss = { scheduleOpen = false },
            onSchedule = { iso ->
                scheduleOpen = false
                viewModel.scheduleSend(scheduleDraft.orEmpty(), iso)
                scheduleDraft = null
            },
        )
    }
    // R3-B item 3 — the scheduled sends manager (consumes the VM's
    // `scheduled` StateFlow; cancel rides the existing cancelScheduled).
    if (scheduledOpen) {
        ScheduledSendsSheet(
            items = scheduledItems,
            loading = scheduledLoading,
            onCancel = { scheduledId -> viewModel.cancelScheduled(scheduledId) },
            onDismiss = { scheduledOpen = false },
        )
    }
    if (helpOpen) {
        SlashHelpDialog(onDismiss = { helpOpen = false })
    }

    // R6 — M2: quick-phrase manager (F-MS-29). Add/delete ride the VM funcs
    // (repo.addPhrase/deletePhrase — GET/POST/DELETE /api/users/{id}/phrases);
    // the rail refreshes through the same VM state the composer chips render.
    if (viewModel.phrasesOpen) {
        PhrasesSheet(
            phrases = phrases,
            busy = phrasesBusy,
            onDismiss = { viewModel.closePhrases() },
            onAdd = viewModel::addPhrase,
            onDelete = viewModel::deletePhrase,
        )
    }

    // R1-W2F F-MD-07 — location confirm sheet (fix lives in the VM; dismissal
    // detaches any still-running one-shot listener).
    if (locationOpen) {
        LocationShareSheet(
            fix = locationFix,
            denied = locationDenied,
            onRetry = { onShareLocation() },
            onOpenSettings = { openAppSettings(context) },
            onConfirm = { lat, lng, label ->
                locationOpen = false
                viewModel.sendLocation(lat, lng, label)
            },
            onDismiss = {
                locationOpen = false
                viewModel.cancelLocationFix()
            },
        )
    }

    // R1-W2F F-FX-05 — per-conversation theme picker (wallpaper + tint).
    if (themeOpen) {
        ConvThemeSheet(
            current = roomTheme,
            globalWallpaper = prefs.wallpaper ?: "none",
            onPickWallpaper = { wallpaper -> viewModel.applyConvTheme(wallpaper, roomTheme?.tint) },
            onPickTint = { tint -> viewModel.applyConvTheme(roomTheme?.wallpaper ?: (prefs.wallpaper ?: "none"), tint) },
            onReset = { viewModel.clearConvTheme() },
            onDismiss = { themeOpen = false },
        )
    }

    if (pollBuilderOpen) {
        PollBuilderSheet(
            onDismiss = { pollBuilderOpen = false },
            onCreate = { question, options ->
                pollBuilderOpen = false
                viewModel.createPoll(question, options)
            },
        )
    }

    // ── Wave 7 sheet hosts ──────────────────────────────────────────
    if (viewModel.redPacketOpen) {
        RedPacketSheet(
            onDismiss = { viewModel.redPacketOpen = false },
            onSend = { total, count, note -> viewModel.createRedPacket(total, count, note) },
        )
    }
    if (viewModel.gameOpen) {
        GameSheet(
            members = viewModel.roomMembers().filter { it.first != viewerId },
            onDismiss = { viewModel.gameOpen = false },
            onCreate = { opponentId -> viewModel.createGame(opponentId) },
        )
    }
    if (viewModel.tournamentOpen) {
        TournamentSheet(
            onDismiss = { viewModel.tournamentOpen = false },
            onCreate = { name -> viewModel.createTournament(name) },
        )
    }
    if (viewModel.kanbanOpen) {
        KanbanSheet(
            conversationId = viewModel.conversationId,
            viewerId = viewerId ?: "",
            isAdmin = viewModel.isAdmin(),
            prefillTitle = viewModel.kanbanSourceTitle?.take(80), // audit D2 fix: was dead `let { null }`
            loadBoard = { viewModel.kanbanBoard(viewModel.conversationId) },
            onAddCard = { title, column, assigneeId -> viewModel.createKanbanCard(title, column, assigneeId) },
            onMoveCard = { cardId, column, position -> viewModel.moveKanbanCard(cardId, column, position) },
            onDeleteCard = { cardId -> viewModel.deleteKanbanCard(cardId) },
            onDismiss = { viewModel.kanbanOpen = false },
        )
    }
    if (viewModel.whiteboardOpen) {
        WhiteboardSheet(
            conversationId = viewModel.conversationId,
            viewerId = viewerId ?: "",
            load = { since -> viewModel.whiteboard(viewModel.conversationId, since) },
            onStrokes = { strokes -> viewModel.postWhiteboardStrokes(viewModel.conversationId, strokes) },
            onUndo = { viewModel.undoWhiteboard(viewModel.conversationId) },
            onClear = { viewModel.clearWhiteboard(viewModel.conversationId) },
            onDismiss = { viewModel.whiteboardOpen = false },
            // R2-C item 4 — the durable pending-stroke draft (survives close/death).
            draft = WhiteboardDraftHooks(
                load = { viewModel.whiteboardDraft(viewModel.conversationId) },
                append = { viewModel.appendWhiteboardDraft(viewModel.conversationId, it) },
                dropFirst = { viewModel.dropFirstWhiteboardDraft(viewModel.conversationId, it) },
                dropLast = { viewModel.dropLastWhiteboardDraft(viewModel.conversationId) },
                replaceAll = { viewModel.replaceAllWhiteboardDraft(viewModel.conversationId, it) },
                clear = { viewModel.clearWhiteboardDraft(viewModel.conversationId) },
            ),
        )
    }
    if (viewModel.eventsOpen) {
        EventsSheet(
            conversationId = viewModel.conversationId,
            viewerId = viewerId ?: "",
            isAdmin = viewModel.isAdmin(),
            load = { viewModel.events(viewModel.conversationId) },
            onCreate = { title, iso, desc, loc -> viewModel.createEvent(title, iso, desc, loc) },
            onRsvp = { eventId, status -> viewModel.rsvp(eventId, status) },
            onCheckin = { eventId -> viewModel.checkin(eventId) },
            onDelete = { eventId -> viewModel.deleteEvent(eventId) },
            onDismiss = { viewModel.eventsOpen = false },
        )
    }
    if (viewModel.remindersOpen) {
        RemindersSheet(
            viewerId = viewerId ?: "",
            load = { viewModel.reminders() },
            onCreate = { note, iso, anchor -> viewModel.createReminder(note, iso, anchor) },
            onResolve = { id -> viewModel.resolveReminder(id) },
            onDelete = { id -> viewModel.deleteReminder(id) },
            // R7 item 4 — web REMINDER_JUMP_EVENT parity (reminders-sheet.tsx
            // jump()): dismiss the sheet, then jump to the anchored message —
            // this room's engine (flash + bounded expansion) or another room
            // via the jump-payload nav route.
            onJump = { r ->
                viewModel.remindersOpen = false
                val mid = r.messageId
                when {
                    mid.isNullOrBlank() -> {}
                    r.conversationId == viewModel.conversationId -> viewModel.jumpTo(mid)
                    else -> onJumpToRoom(r.conversationId, mid)
                }
            },
            anchoredMessageId = viewModel.reminderAnchor.value,
            onDismiss = { viewModel.remindersOpen = false },
        )
    }
    if (viewModel.leaderboardOpen) {
        LeaderboardSheet(
            loadRoom = { viewModel.leaderboard(viewModel.conversationId) },
            loadGlobal = { viewModel.leaderboard(null) },
            onDismiss = { viewModel.leaderboardOpen = false },
        )
    }
    viewModel.redPacketDetailId?.let { packetId ->
        val detail = remember(packetId) { mutableStateOf<app.pulse.protocol.RedPacketDetailDto?>(null) }
        LaunchedEffect(packetId) { detail.value = viewModel.redPacketDetail(packetId) }
        detail.value?.let { d ->
            ModalBottomSheet(
                onDismissRequest = { viewModel.redPacketDetailId = null },
                sheetState = rememberModalBottomSheetState(),
            ) {
                RedPacketDetailSheetBody(detail = d)
            }
        }
    }

    actionTarget?.let { target ->
        // R3-B item 6 — web onSettled parity: the sheet stays open (row
        // spinning) while the kanban round-trip runs, then closes.
        var taskInFlight by remember { mutableStateOf(false) }
        LaunchedEffect(taskPending) {
            when {
                taskPending -> taskInFlight = true
                taskInFlight -> {
                    taskInFlight = false
                    actionTarget = null
                }
            }
        }
        MessageActionSheet(
            message = target,
            isMine = target.authorId == viewerId,
            canThread = target.threadRootId == null && !target.isDeleted,
            pinned = target.pinnedAt != null,
            onDismiss = { actionTarget = null },
            onReact = { emoji ->
                viewModel.react(target.id, emoji)
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                actionTarget = null
            },
            onReply = {
                viewModel.setReplyTo(target)
                actionTarget = null
            },
            onReplyInThread = {
                actionTarget = null
                onOpenThread(conversationId, target.id)
            },
            onEdit = {
                viewModel.beginEdit(target)
                actionTarget = null
            },
            onCopy = {
                clipboard.setText(AnnotatedString(target.body))
                viewModel.notify("Copied to clipboard")
                actionTarget = null
            },
            onTogglePin = {
                viewModel.toggleMessagePin(target.id)
                actionTarget = null
            },
            onToggleSave = {
                viewModel.toggleMessageSave(target.id)
                actionTarget = null
            },
            onForward = {
                forwardTarget = target
                actionTarget = null
            },
            onShare = target.filePath?.takeIf { it.isNotBlank() }?.let {
                { viewModel.openFile(target, share = true) }
            },
            onDelete = if (target.authorId == viewerId && !target.isDeleted) {
                { deleteTarget = target; actionTarget = null }
            } else {
                null
            },
            onInfo = if (target.authorId == viewerId) {
                { infoTarget = target; actionTarget = null }
            } else {
                null
            },
            onAddToBoard = {
                viewModel.addMessageToBoard(target.id, target.body)
                actionTarget = null
            },
            // R3-B item 6 — web "Convert to task": one-shot POST
            // { userId, messageId } → /api/conversations/{id}/kanban.
            onConvertToTask = if (target.kind == Message.Kind.TEXT && !target.isDeleted && target.threadRootId == null) {
                {
                    viewModel.convertMessageToTask(target.id)
                }
            } else {
                null
            },
            taskPending = taskPending,
            onRemindMe = {
                viewModel.remindMe(target.id)
                actionTarget = null
            },
            // R1-W2F F-MD-06 — Translate (text rows only; the server rejects
            // the rest with its honest copy anyway).
            onTranslate = if (target.kind == Message.Kind.TEXT && !target.isDeleted && target.body.isNotBlank()) {
                {
                    viewModel.translateMessage(target.id)
                    actionTarget = null
                }
            } else {
                null
            },
            alreadyTranslated = target.id in translated,
        )
    }

    forwardTarget?.let { source ->
        ForwardSheet(
            conversations = conversations,
            onDismiss = { forwardTarget = null },
            onSend = { targets ->
                viewModel.forwardTo(source, targets)
                forwardTarget = null
            },
        )
    }

    infoTarget?.let { target ->
        MessageInfoSheet(
            message = target,
            conversation = conversation,
            viewerId = viewerId,
            onDismiss = { infoTarget = null },
        )
    }

    // R6 — M1: the reaction chip tap/long-press already armed `whoReactedFor`
    // (D27); this host finally renders it. The toggle button rides the SAME
    // VM react() toggle path as the quick-reaction grid (iOS WhoReactedSheet
    // parity — the roster can toggle your own reaction from the list).
    whoReactedFor?.let { (reactedMessage, reactedEmoji) ->
        WhoReactedSheet(
            message = reactedMessage,
            conversation = conversation,
            emoji = reactedEmoji,
            viewerId = viewerId,
            onDismiss = { whoReactedFor = null },
            onToggle = { emoji ->
                viewModel.react(reactedMessage.id, emoji)
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                whoReactedFor = null
            },
        )
    }

    deleteTarget?.let { target ->
        DeleteMessageDialog(
            message = target,
            onConfirm = {
                viewModel.deleteMessage(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    lightboxTarget?.let { target ->
        ImageLightbox(message = target, onDismiss = { lightboxTarget = null })
    }

    if (pinsOpen) {
        PinsDialog(
            pins = state.pins,
            onJump = { pin ->
                pinsOpen = false
                viewModel.jumpTo(pin.id)
            },
            onDismiss = { pinsOpen = false },
        )
    }

    // Wave 6 — DM safety-number sheet (12×5 digits, settle-confirmed verify).
    safety?.let { current ->
        SafetyNumberSheetHost(safety = current, onDismiss = viewModel::closeSafety, onVerify = viewModel::verifySafety, onReset = viewModel::unverifySafety)
    }
}

// ── timeline model ───────────────────────────────────────────────────

internal sealed interface TimelineRow {
    val key: String

    /** Calendar-day separator pill. */
    data class Day(val iso: String, val label: String) : TimelineRow {
        override val key: String get() = "day-$iso"
    }

    /** R2-A item 4 — the unread divider (anchored at the first unread row). */
    object Unread : TimelineRow {
        override val key: String get() = "unread-divider"
    }

    /**
     * A river message (thread replies never reach this list).
     * R7 item 1 — [head] opens a visual cluster (sender label renders only
     * here), [tail] closes one (web chat-room.tsx:1376-1393 cluster parity).
     */
    data class Msg(
        val message: Message,
        val head: Boolean = true,
        val tail: Boolean = true,
    ) : TimelineRow {
        override val key: String get() = message.id
    }
}

/**
 * Asc rows with a centered day pill wherever the calendar date changes.
 * R2-A item 4 — [unreadAnchorMs] (the viewer's pre-open read watermark, null
 * = no divider) inserts an [TimelineRow.Unread] row before the first OTHER
 * person's non-deleted message newer than the watermark (web chat-room.tsx
 * buildTimeline unreadDividerPlaced parity).
 */
internal fun buildTimelineRows(
    messages: List<Message>,
    unreadAnchorMs: Long? = null,
    viewerId: String? = null,
): List<TimelineRow> {
    val rows = mutableListOf<TimelineRow>()
    var lastIso: String? = null
    var dividerPlaced = unreadAnchorMs == null
    // R7 item 1 — the pure clustering kernel (web chat-room.tsx:1376-1393):
    // head = sender-label row, tail = closes the run. Thread replies are
    // already excluded upstream (ChatRoomViewModel filters threadRootId !=
    // null — web `parentId !== null` filter parity), deleted rows stay.
    val clusterFlags = app.pulse.protocol.PulseClusterKernel.flags(
        messages.map { m ->
            app.pulse.protocol.PulseClusterKernel.Entry(
                id = m.id,
                senderId = m.authorId,
                createdAtMs = PulseTime.epochMs(m.createdAt),
                anon = m.anon,
                anonAlias = m.anonAlias,
            )
        },
    )
    for (message in messages) {
        val t = PulseTime.parse(message.createdAt)
        val iso = t?.atZoneSameInstant(java.time.ZoneId.systemDefault())?.toLocalDate()?.toString()
        if (iso != null && iso != lastIso) {
            rows += TimelineRow.Day(iso, PulseTime.dayChip(message.createdAt))
            lastIso = iso
        }
        if (!dividerPlaced &&
            message.authorId != viewerId &&
            !message.isDeleted &&
            unreadAnchorMs != null &&
            (PulseTime.parse(message.createdAt)?.toInstant()?.toEpochMilli() ?: 0L) > unreadAnchorMs
        ) {
            rows += TimelineRow.Unread
            dividerPlaced = true
        }
        val flags = clusterFlags[message.id]
        rows += TimelineRow.Msg(message, head = flags?.head ?: true, tail = flags?.tail ?: true)
    }
    return rows
}

@Composable
private fun DaySeparator(label: String, modifier: Modifier = Modifier) {
    // EMB: plain centered hairline text, no chip (spec item 6).
    Box(modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.40f),
        )
    }
}

/** R2-A item 4 — the unread divider (web chat-room.tsx UnreadDivider). */
@Composable
private fun UnreadDivider(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(EmberPalette.GlassBorder),
        )
        Text(
            "Unread messages",
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = EmberPalette.Signal,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(EmberPalette.GlassBorder),
        )
    }
}

@Composable
private fun PinnedBanner(pin: Message, onJump: () -> Unit, onOpenAll: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = EmberPalette.CardFill,
        border = androidx.compose.foundation.BorderStroke(1.dp, EmberPalette.Hairline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onJump)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                PulseIcons.Pin,
                contentDescription = "Pinned message",
                tint = EmberPalette.Amber,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pin.authorName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = EmberPalette.Amber,
                    maxLines = 1,
                )
                Text(
                    pin.body.ifBlank { if (pin.imagePath != null) "Photo" else "Document — ${pin.fileName ?: "file"}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenAll, modifier = Modifier.size(30.dp)) {
                Icon(
                    PulseIcons.Search,
                    contentDescription = "All pinned messages",
                    tint = EmberPalette.Amber,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Expandable room search — server hits + the loaded window, deduped upstream. */
@Composable
private fun RoomSearchBar(
    query: String,
    results: List<Message>,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onOpenHit: (Message) -> Unit,
) {
    Surface(
        color = EmberPalette.CardFill,
        border = androidx.compose.foundation.BorderStroke(1.dp, EmberPalette.Hairline),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            BasicField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Search this conversation…",
                leading = {
                    Icon(PulseIcons.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        query.trim().length < 2 -> "Keep typing to search…"
                        searching -> "Searching…"
                        results.isEmpty() -> "No matches"
                        else -> "${results.size} ${if (results.size == 1) "match" else "matches"}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Close",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmberPalette.Amber,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (results.isNotEmpty()) {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(results, key = { it.id }) { hit ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenHit(hit) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PulseAvatar(name = hit.authorName, colorHex = hit.senderColor, size = 32.dp)
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        hit.authorName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        PulseTime.dayChip(hit.createdAt) + " · " + PulseTime.clock(hit.createdAt),
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.40f),
                                    )
                                }
                                Text(
                                    snippetAnnotated(
                                        hit.body.ifBlank { if (hit.imagePath != null) "Photo" else "Document — ${hit.fileName ?: "file"}" },
                                        query,
                                    ),
                                    fontSize = 13.sp,
                                    color = Color.White.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Attach sheet — photo picker, camera capture (D30), system document or poll
 * builder (spec row 9 + Wave 2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onCamera: () -> Unit,
    onDocument: () -> Unit,
    // R1-W2F F-MD-07 — share a live pin (one-shot fix → confirm sheet).
    onLocation: () -> Unit,
    onPoll: () -> Unit,
    onWhiteboard: () -> Unit,
    onRedPacket: () -> Unit,
    onEvents: () -> Unit,
    onGame: () -> Unit,
    onTournament: () -> Unit,
    onKanban: () -> Unit,
    // R4-B item 2 — the web tray's Express group (chat-room.tsx:2752-2800).
    // Discoverability only: every row opens/toggles an EXISTING engine.
    effectsOpen: Boolean,
    onToggleEffects: () -> Unit,
    onPickEffect: (String) -> Unit,
    onStickers: () -> Unit,
    onScheduleSend: () -> Unit,
    onScheduledSends: () -> Unit,
    /** null in DMs — group-gated exactly like the composer incognito toggle. */
    onIncognito: (() -> Unit)?,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        SheetAction(PulseIcons.ImageIcon, "Photo", onPhoto)
        // D30 — take a full-resolution shot with the system camera app; the
        // file flows through the same ≤1280px JPEG upload path as "Photo".
        SheetAction(PulseIcons.Camera, "Camera", onCamera)
        SheetAction(PulseIcons.File, "Document", onDocument)
        // R1-W2F F-MD-07 — wire kind whitelist carries "location" with the
        // payload blob {lat,lng,label} (web/iOS parity).
        SheetAction(PulseIcons.MapPin, "Location", onLocation, tint = PulsePalette.Emerald)
        SheetAction(PulseIcons.Poll, "Poll", onPoll, tint = PulsePalette.Emerald)
        // ── Wave 7 palette (web chat-room.tsx:2519-2634 order) ──
        SheetAction(PulseIcons.Pencil, "Whiteboard", onWhiteboard)
        SheetAction(PulseIcons.Gift, "Red packet", onRedPacket)
        SheetAction(PulseIcons.Calendar, "Events", onEvents)
        SheetAction(PulseIcons.Gamepad, "Game", onGame)
        SheetAction(PulseIcons.Trophy, "Tournament", onTournament)
        SheetAction(PulseIcons.Columns, "Kanban", onKanban)

        // ── R4-B item 2 — Express (web tray group verbatim label) ──
        Text(
            "EXPRESS",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 2.dp),
        )
        // Effects submenu — the web tray's inline effect chips
        // (chat-room.tsx:4992-5025): confetti | lasers | echo | sparkles,
        // each routing into the SAME outcome machine as the /effects slash.
        if (effectsOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("confetti", "lasers", "echo", "sparkles").forEach { effect ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = PulsePalette.Violet.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PulsePalette.Violet.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Send with the $effect effect" },
                    ) {
                        Row(
                            Modifier
                                .clickable(onClick = { onPickEffect(effect) })
                                .padding(horizontal = 6.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                PulseIcons.Sparkle,
                                contentDescription = null,
                                tint = PulsePalette.Violet,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                effect,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PulsePalette.Violet,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        SheetAction(PulseIcons.Sparkle, "Effects", onToggleEffects, tint = PulsePalette.Violet)
        SheetAction(PulseIcons.Smile, "Stickers", onStickers)
        SheetAction(PulseIcons.PaperPlane, "Schedule send", onScheduleSend)
        SheetAction(PulseIcons.Calendar, "Scheduled sends", onScheduledSends)
        if (onIncognito != null) {
            SheetAction(PulseIcons.EyeOff, "Incognito", onIncognito, tint = PulsePalette.Emerald)
        }
        Spacer(Modifier.height(28.dp))
    }
}

// ── R1-W2F — F-FX-05 per-conversation theme picker ──────────────────────

/**
 * Web CONV_TINT_META swatch colors (the bg-*-500 Tailwind classes) — shared
 * by the picker chips and the room glow so they can never drift.
 */
private fun convTintSwatch(tint: String): Color = when (tint) {
    "emerald" -> Color(0xFF10B981)
    "rose" -> Color(0xFFF43F5E)
    "amber" -> Color(0xFFF59E0B)
    "violet" -> Color(0xFF8B5CF6)
    "teal" -> Color(0xFF14B8A6)
    else -> Color.Transparent
}

/**
 * Web applyConvTint parity — the tint REPLACES the top gradient stop over the
 * wallpaper (visible even on the `none` wallpaper); alphas mirror
 * CONV_TINT_META glow values (emerald/violet 0.17, rose/amber/teal 0.16).
 */
private fun convTintGlow(tint: String): Brush? {
    val color = when (tint) {
        "emerald" -> convTintSwatch(tint).copy(alpha = 0.17f)
        "rose" -> convTintSwatch(tint).copy(alpha = 0.16f)
        "amber" -> convTintSwatch(tint).copy(alpha = 0.16f)
        "violet" -> convTintSwatch(tint).copy(alpha = 0.17f)
        "teal" -> convTintSwatch(tint).copy(alpha = 0.16f)
        else -> return null
    }
    return Brush.verticalGradient(listOf(color, Color.Transparent))
}

private fun wallpaperLabel(token: String): String =
    app.pulse.ui.PulseWallpaper.TOKENS.firstOrNull { it.first == token }?.second ?: token

/**
 * R1-W2F F-FX-05 — per-conversation theme picker (web ConvThemePicker
 * parity, iMessage-style): the SAME wallpaper swatches the Wave-8 Appearance
 * picker renders (PulseWallpaper.TOKENS) + optional tint chips + "Reset to
 * default". Every tap commits through the prefs store (`chat.convThemes`,
 * LRU-capped at 48); the sheet stays open for live previewing like the web.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ConvThemeSheet(
    current: ConvTheme?,
    globalWallpaper: String,
    onPickWallpaper: (String) -> Unit,
    onPickTint: (String?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val effectiveWallpaper = current?.wallpaper ?: globalWallpaper
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PulseIcons.Palette, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Chat theme", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                if (current == null) {
                    "Following the Appearance default · ${wallpaperLabel(globalWallpaper)}"
                } else {
                    "Custom for this chat only"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "WALLPAPER",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                app.pulse.ui.PulseWallpaper.TOKENS.forEach { (id, name) ->
                    val selected = effectiveWallpaper == id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPickWallpaper(id) }
                            .padding(2.dp),
                    ) {
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    app.pulse.ui.PulseWallpaper.brush(id)
                                        ?: Brush.verticalGradient(
                                            listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface),
                                        ),
                                )
                                .then(
                                    if (selected) {
                                        Modifier.border(2.dp, PulsePalette.Emerald, RoundedCornerShape(12.dp))
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(PulseIcons.Check, contentDescription = "Selected", tint = PulsePalette.Emerald, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(name, fontSize = 10.sp, color = if (selected) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "TINT",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // "No tint" clear chip (web Ban-button parity).
                val noTint = current?.tint == null
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .then(if (noTint) Modifier.border(2.dp, PulsePalette.Emerald, CircleShape) else Modifier)
                        .clickable { onPickTint(null) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PulseIcons.X, contentDescription = "No tint", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                }
                ConvTheme.TINTS.forEach { tint ->
                    val selected = current?.tint == tint
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(convTintSwatch(tint))
                            .then(if (selected) Modifier.border(2.dp, PulsePalette.Emerald, CircleShape) else Modifier)
                            .clickable { onPickTint(tint) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(PulseIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
            if (current != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable(onClick = onReset)
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PulseIcons.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Reset to default",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * Staged attachment card — preview, caption, upload spinner or inline error
 * retry. Media is NEVER queued: send either delivers or surfaces the error.
 */
@Composable
private fun StagedMediaCard(
    staged: StagedMedia,
    caption: String,
    onCaptionChange: (String) -> Unit,
    onViewOnceChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        color = EmberPalette.CardFill,
        border = androidx.compose.foundation.BorderStroke(1.dp, EmberPalette.Hairline),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (staged.kind) {
                    StagedMedia.Kind.IMAGE -> AsyncImage(
                        model = staged.localUri,
                        contentDescription = "Attached photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    StagedMedia.Kind.FILE -> Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(PulseIcons.Paperclip, contentDescription = "Document", tint = EmberPalette.Amber, modifier = Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when (staged.kind) {
                            StagedMedia.Kind.IMAGE -> "Photo"
                            StagedMedia.Kind.FILE -> staged.fileName ?: "Document"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        staged.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                staged.error,
                                style = MaterialTheme.typography.labelSmall,
                                color = PulsePalette.Rose,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Retry",
                                style = MaterialTheme.typography.labelMedium,
                                color = EmberPalette.Amber,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onRetry)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        staged.uploading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.6.dp, color = EmberPalette.Amber)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Uploading…",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.45f),
                            )
                        }
                        staged.uploadedPath != null -> {
                            val size = PulseMedia.humanFileSize(staged.fileSize)
                            Text(
                                if (size.isEmpty()) "Ready to send" else "Ready to send · $size",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmberPalette.Online,
                            )
                        }
                    }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(PulseIcons.X, contentDescription = "Remove attachment", modifier = Modifier.size(15.dp), tint = Color.White.copy(alpha = 0.55f))
                }
            }
            // Wave 2 view-once send toggle — IMAGE kind only (the wire
            // requires imagePath when viewOnce===true). Spec §1 row 5: this
            // tray tile is a native ADD, deliberately absent on web.
            if (staged.kind == StagedMedia.Kind.IMAGE) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "View once",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Disappears after opening",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = staged.viewOnce, onCheckedChange = onViewOnceChange)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = caption,
                    onValueChange = onCaptionChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    cursorBrush = SolidColor(EmberPalette.Amber),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (caption.isEmpty()) {
                                Text(
                                    "Add a caption…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.40f),
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RoomHeader(
    conversation: Conversation?,
    partnerTypingName: String?,
    searchOpen: Boolean,
    voiceJoined: Boolean,
    voiceLiveCount: Int,
    onOpenVoiceRoom: () -> Unit,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenSafety: (() -> Unit)? = null,
    onOpenLeaderboard: (() -> Unit)? = null,
    // R1-W2F F-FX-05 — the room overflow menu (chat theme entry).
    onOpenTheme: () -> Unit = {},
    // R1-W2I F-PI-03 — the pop-out mini-chat toggle (web chat-room.tsx
    // header PictureInPicture2 button, aria "Open/Close mini chat window").
    pipActive: Boolean = false,
    onTogglePip: () -> Unit = {},
    // D34 — DM peer verification state (null = unknown/loading): emerald
    // badge when verified, amber dot only when unverified (web parity).
    peerVerified: Boolean? = null,
    // R2-A item 5 — the AI-recap header entry (web chat-room.tsx:4162-4166:
    // disabled while the LLM round-trip is in flight).
    recapBusy: Boolean = false,
    onRequestRecap: () -> Unit = {},
    // R2-A item 6/7/8/9 — room info (GroupInfoScreen); null on DMs.
    onOpenRoomInfo: (() -> Unit)? = null,
    // R3-B item 3 — the scheduled sends manager (overflow row + pending count).
    onOpenScheduled: () -> Unit = {},
    scheduledCount: Int = 0,
    // R6 — BE7 — the room reminders entry (web header button + badge).
    onOpenReminders: () -> Unit = {},
    remindersCount: Int = 0,
    // R8 Task 3-c — GROUP call dials (null = not a group / no shell session).
    onStartGroupVoice: (() -> Unit)? = null,
    onStartGroupVideo: (() -> Unit)? = null,
) {
    Surface(color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // EMB: glass chrome on the ember ground (same handlers as before).
            EmberGlassButton(
                icon = PulseIcons.ChevronLeft,
                label = if (searchOpen) "Close search" else "Back",
                onClick = onBack,
            )
            Spacer(Modifier.width(6.dp))
            PulseAvatar(
                name = conversation?.title ?: "…",
                colorHex = conversation?.accentColor,
                size = 36.dp,
                isGroup = conversation?.isGroupish == true,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation?.title ?: "…",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (conversation != null && !conversation.isGroupish) {
                        if (peerVerified == true) {
                            Spacer(Modifier.width(4.dp))
                            Icon(PulseIcons.BadgeCheck, contentDescription = "Verified", tint = EmberPalette.Online, modifier = Modifier.size(14.dp))
                        } else if (peerVerified == false) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(PulsePalette.Amber),
                            )
                        }
                    }
                }
                AnimatedContentCompat(partnerTypingName != null) { typing ->
                    if (typing) {
                        // EMB: the typing state breathes (animated alpha on the word).
                        val typingAlpha by rememberInfiniteTransition(label = "typingPulse").animateFloat(
                            initialValue = 0.35f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                            label = "typingAlpha",
                        )
                        Text(
                            "${partnerTypingName.orEmpty()} is typing",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.alpha(typingAlpha),
                        )
                    } else {
                        Text(
                            when {
                                conversation == null -> ""
                                conversation.isGroupish -> "${conversation.memberNames.size} members"
                                else -> "online"
                            },
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.55f),
                        )
                    }
                }
                // Wave 6 — DM safety-number entry (web chat-room ShieldCheck).
                if (onOpenSafety != null) {
                    IconButton(onClick = onOpenSafety, modifier = Modifier.size(28.dp)) {
                        Icon(
                            PulseIcons.Shield,
                            contentDescription = "Safety number",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            // Wave 5 voice room entry — tints live while this room has a
            // joined voice seat; the pill shows the live roster size.
            if (voiceJoined) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = EmberPalette.Online.copy(alpha = 0.14f),
                    contentColor = EmberPalette.Online,
                    modifier = Modifier.semantics { contentDescription = "Voice room live with $voiceLiveCount people" },
                ) {
                    Row(
                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(PulseIcons.Waveform, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Voice · $voiceLiveCount live", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.width(2.dp))
            }
            EmberGlassButton(
                icon = PulseIcons.Waveform,
                label = if (voiceJoined) "Open the live voice room" else "Open voice room",
                onClick = onOpenVoiceRoom,
                tint = if (voiceJoined) EmberPalette.Online else Color.White,
                modifier = Modifier.semantics {
                    stateDescription = if (voiceJoined) "In voice room" else "Not in voice room"
                },
            )
            // R8 Task 3-c — group voice/video call buttons (web chat-room
            // header parity, aria "Start group voice/video call").
            if (onStartGroupVoice != null) {
                EmberGlassButton(
                    icon = PulseIcons.Phone,
                    label = "Start group voice call",
                    onClick = onStartGroupVoice,
                )
            }
            if (onStartGroupVideo != null) {
                EmberGlassButton(
                    icon = PulseIcons.Video,
                    label = "Start group video call",
                    onClick = onStartGroupVideo,
                )
            }
            onOpenLeaderboard?.let {
                EmberGlassButton(
                    icon = PulseIcons.Star,
                    label = "Leaderboard",
                    onClick = it,
                )
            }
            EmberGlassButton(
                icon = if (searchOpen) PulseIcons.X else PulseIcons.Search,
                label = if (searchOpen) "Close search" else "Search in conversation",
                onClick = onToggleSearch,
            )
            // R6 — BE7 — reminders (web chat-room header BellRing/Schedule
            // parity): opens the existing RemindersSheet; the emerald badge
            // shows the upcoming (unfired) count when non-zero.
            Box {
                EmberGlassButton(
                    icon = PulseIcons.Bell,
                    label = if (remindersCount > 0) "Reminders — $remindersCount upcoming" else "Reminders",
                    onClick = onOpenReminders,
                )
                if (remindersCount > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 2.dp)
                            .clip(CircleShape)
                            .background(EmberPalette.Signal)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            if (remindersCount > 99) "99+" else "$remindersCount",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
            }
            // R1-W2F — the room overflow menu (new host; the header previously
            // had only icon buttons). F-FX-05's theme picker entry lives here;
            // future room actions slot in below.
            var roomMenuOpen by remember { mutableStateOf(false) }
            Box {
                EmberGlassButton(
                    icon = PulseIcons.KebabVertical,
                    label = "Room menu",
                    onClick = { roomMenuOpen = true },
                )
                DropdownMenu(expanded = roomMenuOpen, onDismissRequest = { roomMenuOpen = false }) {
                    // R2-A item 6/7/8/9 — room info (automations, webhooks,
                    // screen security, photo) — groups/channels only.
                    if (onOpenRoomInfo != null) {
                        DropdownMenuItem(
                            text = { Text("Room info") },
                            leadingIcon = {
                                Icon(PulseIcons.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                roomMenuOpen = false
                                onOpenRoomInfo()
                            },
                        )
                    }
                    // R2-A item 5 — AI recap (web header overflow parity).
                    DropdownMenuItem(
                        text = { Text(if (recapBusy) "Summarizing…" else "AI recap") },
                        leadingIcon = {
                            Icon(PulseIcons.Sparkle, contentDescription = null, modifier = Modifier.size(18.dp), tint = PulsePalette.Violet)
                        },
                        enabled = !recapBusy,
                        onClick = {
                            roomMenuOpen = false
                            onRequestRecap()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Chat theme") },
                        leadingIcon = {
                            Icon(PulseIcons.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            roomMenuOpen = false
                            onOpenTheme()
                        },
                    )
                    // R1-W2I F-PI-03 — pop-out mini chat (web chat-room.tsx
                    // :4023-4037: focused pane → close, else open this room).
                    DropdownMenuItem(
                        text = { Text(if (pipActive) "Close mini chat window" else "Open mini chat window") },
                        leadingIcon = {
                            Icon(
                                PulseIcons.PiP,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (pipActive) PulsePalette.Emerald else LocalContentColor.current,
                            )
                        },
                        onClick = {
                            roomMenuOpen = false
                            onTogglePip()
                        },
                    )
                    // R3-B item 3 — the scheduled sends manager (web tray row
                    // "Manage N pending scheduled messages", chat-room.tsx:8650).
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (scheduledCount > 0) {
                                    "Scheduled sends ($scheduledCount pending)"
                                } else {
                                    "Scheduled sends"
                                },
                            )
                        },
                        leadingIcon = {
                            Icon(
                                PulseIcons.Clock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (scheduledCount > 0) PulsePalette.Amber else LocalContentColor.current,
                            )
                        },
                        onClick = {
                            roomMenuOpen = false
                            onOpenScheduled()
                        },
                    )
                }
            }
        }
    }
}

/** Small crossfade helper (kept local; Compose Crossfade without key jank). */
@Composable
private fun AnimatedContentCompat(visible: Boolean, content: @Composable (Boolean) -> Unit) {
    androidx.compose.animation.Crossfade(
        targetState = visible,
        animationSpec = tween(180),
        label = "subtitle",
    ) { content(it) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Message,
    conversation: Conversation?,
    viewerId: String?,
    partnerLastReadAt: Long?,
    isLastMine: Boolean,
    flashing: Boolean,
    replyCount: Int,
    downloading: Boolean,
    voicePlayer: VoicePlayer,
    transcribing: Boolean,
    // R1-W2F F-MD-06 — inline LLM translation for the text bubble.
    translating: Boolean = false,
    translatedText: String? = null,
    onTranscribe: (String) -> Unit,
    onVotePoll: (String, String) -> Unit,
    onClosePoll: (String) -> Unit,
    onConsumeViewOnce: (Message) -> Unit,
    onLongPress: (() -> Unit)?,
    onQuoteClick: (String) -> Unit,
    onOpenImage: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenThread: () -> Unit,
    // ── Wave 7 rich-object hooks ──
    onGameMove: (String, Int) -> Unit = { _, _ -> },
    onGameJoin: (String) -> Unit = {},
    onGameLoad: suspend (String) -> app.pulse.protocol.GameDetailDto? = { null },
    // R7 item 3 — rematch on finished matches (web game-tictactoe-card.tsx).
    onGameRematch: suspend (app.pulse.protocol.GameMatchDto) -> Unit = {},
    onRedPacketLoad: suspend (String) -> app.pulse.protocol.RedPacketDetailDto? = { null },
    onRedPacketGrab: (String) -> Unit = {},
    onRedPacketOpen: (String) -> Unit = {},
    onTournamentLoad: suspend (String) -> app.pulse.protocol.TournamentSummaryDto? = { null },
    onTournamentJoin: (String) -> Unit = {},
    onTournamentFinish: (String) -> Unit = {},
    // D27 — long-press a reaction chip → who-reacted sheet (null = inert).
    onWhoReacted: ((String) -> Unit)? = null,
    // R6 — M6 — double-tap the bubble fires the heart quick reaction (null = inert).
    onDoubleClick: (() -> Unit)? = null,
    // R7 item 1 — cluster position (web chat-room.tsx:1376-1393): the sender
    // label renders ONLY on head rows; non-head rows tuck up tight.
    head: Boolean = true,
    tail: Boolean = true,
    // R7 item 2 — swipe-bubble-to-reply (web drag="x" onDragEnd toward > 28;
    // null = inert: deleted rows + queued local_ echoes keep web's gate).
    onReply: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    bubbleCornerDp: androidx.compose.ui.unit.Dp = 16.dp,
) {
    val mine = message.authorId == viewerId
    val system = message.kind == Message.Kind.SYSTEM || message.authorId == "system"

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (system) {
            // EMB: system rows are centered 11sp white 40% on the backdrop.
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    message.body,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.40f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            return@Column
        }

        // Group sender label above their first bubble run — incognito rows
        // (R3-B item 4) mask the real name behind the server alias with a
        // neutral zinc dot (web anonMasked parity, chat-room.tsx:7232-7235).
        // R7 item 1 — head-only (web chat-room.tsx:7313 `head && !deleted`).
        if (!mine && head && conversation?.isGroupish == true) {
            val anonMasked = message.anon && message.anonAlias != null
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (anonMasked) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                PulsePalette.parse(message.senderColor) ?: PulsePalette.Teal
                            },
                        ),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (anonMasked) message.anonAlias.orEmpty() else message.authorName,
                    fontSize = 12.sp,
                    color = EmberPalette.SenderName,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // ── R7 item 2 — swipe-bubble-to-reply (web chat-room.tsx:7248-7373) ──
        // Horizontal drag with a ±64dp clamp, spring snap-back on release,
        // reply when the toward-trailing-edge offset (toward = mine ? -x : x)
        // passes 28dp. The static reply-arrow hint chip fades 0→1 between 4
        // and 28 at the OUTER edge (left for others' bubbles, right for mine)
        // exactly like the web's absolute motion.span. The drag detector sits
        // OUTSIDE the bubble's combinedClickable so tap / long-press /
        // double-tap keep working; vertical list scrolling is untouched
        // (horizontal-only slop).
        val haptics = LocalHapticFeedback.current
        val dragX = remember(message.id) { Animatable(0f) }
        val dragScope = rememberCoroutineScope()
        val density = LocalDensity.current
        val dragLimitPx = with(density) { REPLY_DRAG_LIMIT.toPx() }
        val thresholdPx = with(density) { REPLY_DRAG_THRESHOLD.toPx() }
        val hintStartPx = with(density) { REPLY_HINT_START.toPx() }
        Box {
            if (onReply != null) {
                Box(
                    Modifier
                        .align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
                        .graphicsLayer {
                            val toward = if (mine) -dragX.value else dragX.value
                            alpha = ((toward - hintStartPx) / (thresholdPx - hintStartPx)).coerceIn(0f, 1f)
                        }
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, EmberPalette.Hairline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PulseIcons.Reply,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .graphicsLayer { translationX = dragX.value }
                    .then(
                        if (onReply != null) {
                            Modifier.pointerInput(message.id) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        val toward = if (mine) -dragX.value else dragX.value
                                        dragScope.launch {
                                            dragX.animateTo(
                                                0f,
                                                spring(stiffness = Spring.StiffnessMediumLow),
                                            )
                                        }
                                        if (toward > thresholdPx) {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onReply?.invoke()
                                        }
                                    },
                                    onDragCancel = {
                                        dragScope.launch {
                                            dragX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                        }
                                    },
                                ) { change, dragAmount ->
                                    change.consume()
                                    val next = (dragX.value + dragAmount).coerceIn(-dragLimitPx, dragLimitPx)
                                    dragScope.launch { dragX.snapTo(next) }
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
            // Wave 2 view-once gate (spec §1 row 6): only the RECEIVER is
            // gated — the sender always sees their own photo normally.
            val viewOncePhoto = message.viewOnce && message.imagePath != null
            val burned = viewOncePhoto && !mine && message.viewedAt != null
            val gated = viewOncePhoto && !mine && message.viewedAt == null && !message.isDeleted
            when {
                // Tombstone — soft-deleted rows render the honest placeholder.
                message.isDeleted -> TombstoneBubble()
                // Wave 7 — red packet carrier (payload {packetId,total,count,note}).
                message.kind == Message.Kind.RED_PACKET -> MediaWithQuote(
                    quoteId = message.replyToId,
                    quoteBody = message.replyToBody,
                    quoteAuthor = message.replyToAuthor,
                    mine = mine,
                    onQuoteClick = onQuoteClick,
                    content = {
                        val rp = app.pulse.protocol.PulseWave7Logic.redPacketPayload(message.payload)
                        RedPacketCard(
                            packetId = rp?.packetId ?: "",
                            total = rp?.total ?: 0,
                            count = rp?.count ?: 0,
                            note = rp?.note,
                            isMine = mine,
                            viewerId = viewerId ?: "",
                            load = { onRedPacketLoad(rp?.packetId ?: "") },
                            onGrab = { onRedPacketGrab(rp?.packetId ?: "") },
                            onOpenDetail = { onRedPacketOpen(rp?.packetId ?: "") },
                        )
                    },
                )
                // Wave 7 — tic-tac-toe carrier (payload {matchId, game}).
                message.kind == Message.Kind.GAME -> MediaWithQuote(
                    quoteId = message.replyToId,
                    quoteBody = message.replyToBody,
                    quoteAuthor = message.replyToAuthor,
                    mine = mine,
                    onQuoteClick = onQuoteClick,
                    content = {
                        val gp = app.pulse.protocol.PulseWave7Logic.gamePayload(message.payload)
                        TicTacToeCard(
                            matchId = gp?.matchId ?: "",
                            viewerId = viewerId ?: "",
                            initial = null,
                            load = { onGameLoad(gp?.matchId ?: "") },
                            onMove = { cell -> onGameMove(gp?.matchId ?: "", cell) },
                            onJoin = { onGameJoin(gp?.matchId ?: "") },
                            // R7 item 3 — the loaded match drives the rematch POST.
                            onRematch = { match -> onGameRematch(match) },
                        )
                    },
                )
                // Wave 7 — tournament carrier (payload {tournamentId, name, game}).
                message.kind == Message.Kind.TOURNAMENT -> MediaWithQuote(
                    quoteId = message.replyToId,
                    quoteBody = message.replyToBody,
                    quoteAuthor = message.replyToAuthor,
                    mine = mine,
                    onQuoteClick = onQuoteClick,
                    content = {
                        val tp = app.pulse.protocol.PulseWave7Logic.tournamentPayload(message.payload)
                        TournamentCard(
                            tournamentId = tp?.tournamentId ?: "",
                            name = tp?.name ?: "",
                            viewerId = viewerId ?: "",
                            isAdmin = message.authorId == viewerId,
                            load = { onTournamentLoad(tp?.tournamentId ?: "") },
                            onJoin = { onTournamentJoin(tp?.tournamentId ?: "") },
                            onFinish = { onTournamentFinish(tp?.tournamentId ?: "") },
                        )
                    },
                )
                // Live poll — PollCard replaces the body text entirely.
                message.poll != null || message.kind == Message.Kind.POLL -> MediaWithQuote(
                    quoteId = message.replyToId,
                    quoteBody = message.replyToBody,
                    quoteAuthor = message.replyToAuthor,
                    mine = mine,
                    onQuoteClick = onQuoteClick,
                    content = {
                        PollCard(
                            message = message,
                            viewerId = viewerId,
                            mine = mine,
                            onVote = onVotePoll,
                            onClose = onClosePoll,
                        )
                    },
                )
                // Burned (viewed) — NO image render path at all (anti-replay).
                burned -> MediaWithQuote(
                    quoteId = message.replyToId,
                    quoteBody = message.replyToBody,
                    quoteAuthor = message.replyToAuthor,
                    mine = mine,
                    onQuoteClick = onQuoteClick,
                    content = { BurnedPhotoBubble() },
                )
                // Gated (unopened) — blurred + overlay; tap consumes + reveals.
                gated -> MediaWithQuote(
                    quoteId = message.replyToId,
                    quoteBody = message.replyToBody,
                    quoteAuthor = message.replyToAuthor,
                    mine = mine,
                    onQuoteClick = onQuoteClick,
                    content = {
                        ViewOnceGateBubble(
                            imagePath = message.imagePath,
                            mine = mine,
                            onOpen = { onConsumeViewOnce(message) },
                        )
                    },
                )
                message.imagePath != null -> MediaWithQuote(
                    quoteId = message.replyToId,
                    quoteBody = message.replyToBody,
                    quoteAuthor = message.replyToAuthor,
                    mine = mine,
                    onQuoteClick = onQuoteClick,
                    content = { ImageBubble(message = message, mine = mine, onOpen = onOpenImage) },
                )
                // R1-W2F F-MD-07 — tappable pin row (payload {lat,lng,label});
                // tap fires ACTION_VIEW geo:lat,lng. Before the imagePath/file
                // gates so a pin never falls into their paths.
                message.kind == Message.Kind.LOCATION -> MediaWithQuote(
                    quoteId = message.replyToId,
                    quoteBody = message.replyToBody,
                    quoteAuthor = message.replyToAuthor,
                    mine = mine,
                    onQuoteClick = onQuoteClick,
                    content = { LocationPinBubble(message = message, mine = mine) },
                )
                message.filePath != null || message.kind == Message.Kind.FILE -> MediaWithQuote(
                    quoteId = message.replyToId,
                    quoteBody = message.replyToBody,
                    quoteAuthor = message.replyToAuthor,
                    mine = mine,
                    onQuoteClick = onQuoteClick,
                    content = {
                        FileBubble(message = message, mine = mine, downloading = downloading, onOpen = onOpenFile)
                    },
                )
                else -> Bubble(
                    message = message,
                    mine = mine,
                    flashing = flashing,
                    onLongPress = onLongPress,
                    onQuoteClick = onQuoteClick,
                    // R6 — M6 — the heart double-tap rides the text bubble.
                    onDoubleClick = onDoubleClick,
                    voicePlayer = voicePlayer,
                    onTranscribe = onTranscribe,
                    transcribing = transcribing,
                    // R1-W2F F-MD-06 — inline translation line under the body.
                    translating = translating,
                    translatedText = translatedText,
                    // R3-B item 2 — the roster that drives @mention highlight.
                    memberNames = conversation?.memberNames.orEmpty(),
                    bubbleCornerDp = bubbleCornerDp,
                    // R7 item 1 — the grouped-first bubble tucks its top corner.
                    head = head,
                )
            }
            }
        }

        // reactions + meta
        if (message.reactions.isNotEmpty()) {
            Row(
                Modifier.padding(start = if (mine) 0.dp else 4.dp, end = if (mine) 4.dp else 0.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                message.reactions
                    .groupBy { reactionId(it.emoji) }
                    .forEach { (id, list) ->
                        val popped by animateFloatAsState(1f, animationSpec = PulseMotion.bouncy(), label = "react")
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color.White.copy(alpha = 0.10f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, EmberPalette.Hairline),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .combinedClickable(
                                    onClick = { onWhoReacted?.invoke(id) },
                                    onLongClick = { onWhoReacted?.invoke(id) },
                                ),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    pulseReactionGlyph(id),
                                    contentDescription = null,
                                    tint = EmberPalette.Amber,
                                    modifier = Modifier.size(13.dp),
                                )
                                if (list.size > 1) {
                                    Text("${list.size}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.60f))
                                }
                            }
                        }
                    }
            }
        }

        // Thread chip — "N replies ↳" on parents (live counts, tap opens the thread).
        if (replyCount > 0 && !message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color.White.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(1.dp, EmberPalette.Hairline),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onOpenThread),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        PulseIcons.Reply,
                        contentDescription = null,
                        tint = EmberPalette.SenderName,
                        modifier = Modifier
                            .size(12.dp)
                            .alpha(0.9f),
                    )
                    Text(
                        "$replyCount ${if (replyCount == 1) "reply" else "replies"}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }
            }
        }

        // Tick line on the LAST own message: queued clock, sent tick, seen double tick.
        if (mine && isLastMine) {
            if (message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                // Queued in the outbox — a clock, never a false "Seen".
                Row(
                    Modifier.padding(end = 4.dp, top = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        PulseIcons.Clock,
                        contentDescription = "Queued",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            } else {
                val createdAtMs = PulseTime.epochMs(message.createdAt)
                val others = conversation?.members?.filter { it.id != viewerId }.orEmpty()
                val seen = createdAtMs > 0L && (
                    others.any { (it.lastReadAt ?: 0L) >= createdAtMs } ||
                        (partnerLastReadAt ?: 0L) >= createdAtMs
                    )
                Row(
                    Modifier.padding(end = 4.dp, top = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (seen) {
                        PulseCheckCheck(
                            tint = PulsePalette.Emerald,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            "Seen",
                            style = MaterialTheme.typography.labelSmall,
                            color = PulsePalette.Emerald,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Icon(
                            PulseIcons.Check,
                            contentDescription = "Sent",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            PulseTime.clock(message.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Quote chip rendered above media cards (images/files don't use Bubble). */
@Composable
private fun MediaWithQuote(
    quoteId: String?,
    quoteBody: String?,
    quoteAuthor: String?,
    mine: Boolean,
    onQuoteClick: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (quoteId != null && quoteBody != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable { onQuoteClick(quoteId) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Column {
                    Text(
                        quoteAuthor ?: "Reply",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmberPalette.SenderName,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        quoteBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(
    message: Message,
    mine: Boolean,
    flashing: Boolean,
    onLongPress: (() -> Unit)?,
    onQuoteClick: ((String) -> Unit)?,
    // R6 — M6: double-tap toggles the heart reaction (text bubbles; media bubbles keep their
    // own open-on-tap, matching the web interactive gate `!isImage && !isFile`).
    onDoubleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    voicePlayer: VoicePlayer? = null,
    onTranscribe: ((String) -> Unit)? = null,
    transcribing: Boolean = false,
    // R1-W2F F-MD-06 — per-message LLM translation render state.
    translating: Boolean = false,
    translatedText: String? = null,
    // R3-B item 2 — the room roster; @Name tokens render as mention chips.
    memberNames: List<String> = emptyList(),
    bubbleCornerDp: androidx.compose.ui.unit.Dp = 20.dp,
    // R7 item 1 — cluster head: the grouped-first bubble tucks its near-sender
    // top corner to 6dp (ember bubble shape, spec item 3).
    head: Boolean = true,
) {
    // EMB: 20dp bubble radius; the grouped-first row pulls the top corner on
    // its own side to 6dp (incoming top-left, outgoing top-right).
    val shape = if (mine) {
        RoundedCornerShape(
            topStart = bubbleCornerDp,
            topEnd = if (head) 6.dp else bubbleCornerDp,
            bottomStart = bubbleCornerDp,
            bottomEnd = bubbleCornerDp,
        )
    } else {
        RoundedCornerShape(
            topStart = if (head) 6.dp else bubbleCornerDp,
            topEnd = bubbleCornerDp,
            bottomStart = bubbleCornerDp,
            bottomEnd = bubbleCornerDp,
        )
    }
    // EMB: flat ink bubble fills (BubbleOut outgoing / BubbleIn incoming).
    val background: Brush = if (mine) {
        SolidColor(EmberPalette.BubbleOut)
    } else {
        SolidColor(EmberPalette.BubbleIn)
    }
    val contentColor = Color.White
    val flashAlpha by animateFloatAsState(
        targetValue = if (flashing) 1f else 0f,
        animationSpec = tween(220),
        label = "flashRing",
    )

    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = modifier
            // EMB: max width 78% of the timeline, content hugs its own side.
            .fillMaxWidth(0.78f)
            .wrapContentWidth(if (mine) Alignment.End else Alignment.Start)
            .border(2.dp, PulsePalette.Amber.copy(alpha = flashAlpha), shape)
            .then(
                // R6 — M6: double-tap toggles the heart quick reaction (web
                // chat-room.tsx:7265-7268) — long-press keeps opening the
                // action sheet, single tap stays inert exactly as before.
                if (onLongPress != null || onDoubleClick != null) {
                    Modifier.combinedClickable(
                        onClick = { },
                        onLongClick = onLongPress,
                        onDoubleClick = onDoubleClick,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            Modifier
                .background(background, shape)
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            // reply quote — tap jumps to the quoted message (when a jump
            // surface exists; thread bubbles render it read-only).
            val replyBody = message.replyToBody
            val quoteId = message.replyToId
            if (quoteId != null && replyBody != null) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .clickable(enabled = onQuoteClick != null) { onQuoteClick?.invoke(quoteId) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Column {
                        Text(
                            message.replyToAuthor ?: "Reply",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmberPalette.SenderName,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            replyBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            val isVoice = message.kind == Message.Kind.VOICE || message.audioPath != null
            if (isVoice && voicePlayer != null) {
                // Wave 2 interactive voice note (play/pause + waveform
                // progress + speed chip) with the transcript strip under it.
                VoiceBubble(
                    message = message,
                    contentColor = contentColor,
                    voicePlayer = voicePlayer,
                    onTranscribe = onTranscribe?.let { callback -> { callback(message.id) } },
                    transcribing = transcribing,
                )
            } else if (isVoice) {
                // Defensive: voice rows without a player (shouldn't happen —
                // both screens inject the singleton) render the static bars.
                VoiceBubbleStatic(message, contentColor)
            } else when (message.kind) {
                Message.Kind.IMAGE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(PulseIcons.ImageIcon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Photo", color = contentColor, style = MaterialTheme.typography.bodyMedium)
                }
                else -> Column {
                    // R3-B item 1/2/3 — rich body: markdown/spoilers/mention
                    // chips via FormattedMessageBody, with the web jumbo gate
                    // (chat-room.tsx:7213/7603-7604) first — pure-emoji short
                    // rows render oversized (34sp, 1.2 line) instead.
                    val jumbo = message.poll == null && MessageTextParser.isJumboEmoji(message.body)
                    if (jumbo) {
                        Text(
                            message.body,
                            color = contentColor,
                            fontSize = 34.sp,
                            lineHeight = 40.8.sp,
                        )
                    } else {
                        FormattedMessageBody(
                            body = message.body,
                            mine = mine,
                            contentColor = contentColor,
                            memberNames = memberNames,
                        )
                    }
                    // Wave 2 link preview (spec §1 row 8): absent → plain text
                    // until the link:preview envelope lands (loading/failure
                    // states are inherent); polls never carry one.
                    val preview = message.linkPreview
                    if (preview != null && message.poll == null) {
                        Spacer(Modifier.height(6.dp))
                        LinkPreviewCard(preview, mine = mine)
                    }
                }
            }

            // ── R1-W2F F-MD-06 — inline LLM translation (web TranslationLine
            // parity: italic secondary line under the bubble content; the
            // conversationId-independent map overwrites on re-translate).
            if (translating) {
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.5.dp,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "Translating…",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                }
            }
            if (!translatedText.isNullOrBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    translatedText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = contentColor.copy(alpha = 0.82f),
                )
            }

            Spacer(Modifier.height(4.dp))
            // EMB: the meta line sits bottom-end INSIDE the bubble, 10sp white 40%.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                    // Pending outbox bubble — clock marker replaces the clock text.
                    Icon(
                        PulseIcons.Clock,
                        contentDescription = "Queued",
                        tint = Color.White.copy(alpha = 0.40f),
                        modifier = Modifier.size(10.dp),
                    )
                } else {
                    Text(
                        PulseTime.clock(message.createdAt),
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.40f),
                    )
                }
                if (message.editedAt != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "edited",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.40f),
                    )
                }
                if (message.viaAutomation) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Automation",
                        fontSize = 10.sp,
                        color = EmberPalette.SenderName,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (message.pinnedAt != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(PulseIcons.Pin, contentDescription = "Pinned", tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(10.dp))
                }
            }
        }
    }
}

/** Soft-delete tombstone: plain italic "Message deleted" (web parity), no actions. */
@Composable
internal fun TombstoneBubble() {
    val shape = RoundedCornerShape(14.dp)
    Surface(shape = shape, color = Color.Transparent) {
        Box(
            Modifier
                .background(SolidColor(EmberPalette.BubbleIn), shape)
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Text(
                "Message deleted",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                ),
                color = Color.White.copy(alpha = 0.40f),
            )
        }
    }
}

/** Static waveform fallback — deterministic bars + duration (pre-Wave 2 look). */
@Composable
private fun VoiceBubbleStatic(message: Message, contentColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(PulseIcons.Waveform, contentDescription = "Voice message", tint = contentColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            // D31 — the exact web voiceBars LCG (same bars as web/iOS per id).
            val bars = remember(message.id) { PulseMedia.voiceBubbleBars(message.id, count = 16) }
            bars.forEach { v ->
                val h = (4 + (v - 28) / 72f * 14f).dp
                Box(
                    Modifier
                        .width(3.dp)
                        .size(width = 3.dp, height = h)
                        .clip(RoundedCornerShape(2.dp))
                        .background(contentColor.copy(alpha = 0.85f)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val seconds = (message.durationMs ?: 0) / 1000
        Text(
            if (seconds > 0) "${seconds}s" else "Voice",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.8f),
        )
    }
}

/**
 * Native waveform placeholder — deterministic bars from the message id, now
 * interactive (Wave 2): play/pause circle, 26 bars recolored by playback
 * progress, current/total time and the 1x→1.5x→2x speed chip (persisted pref,
 * chip hidden below API 23 where setPlaybackParams doesn't exist). The
 * transcript strip renders under the bubble row — text when present, a
 * "Transcribe" pill otherwise (never on optimistic local_ rows).
 */
@Composable
private fun VoiceBubble(
    message: Message,
    contentColor: Color,
    voicePlayer: VoicePlayer,
    onTranscribe: (() -> Unit)?,
    transcribing: Boolean,
) {
    val playback by voicePlayer.playback.collectAsStateWithLifecycle()
    val rate by voicePlayer.rate.collectAsStateWithLifecycle()
    val active = playback?.takeIf { it.messageId == message.id }
    val playing = active?.playing == true
    val loading = active?.loading == true
    val durationMs = active?.durationMs?.takeIf { it > 0 } ?: (message.durationMs ?: 0L)
    val positionMs = active?.positionMs ?: 0L
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    DisposableEffect(message.id) {
        onDispose { voicePlayer.releaseIfActive(message.id) }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // play / pause circle
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.18f))
                    .clickable { voicePlayer.toggle(message) },
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = contentColor)
                } else {
                    Icon(
                        if (playing) PulseIcons.Pause else PulseIcons.Play,
                        contentDescription = if (playing) "Pause" else "Play voice message",
                        tint = contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // 26 deterministic bars — full color up to the playhead, dim after.
            // D31 — the exact web voiceBars LCG: identical bars on web/iOS for
            // the same message id (the wire carries NO waveform; all three
            // surfaces derive it from the id deterministically).
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                val bars = remember(message.id) { PulseMedia.voiceBubbleBars(message.id) }
                bars.forEachIndexed { i, v ->
                    val h = (4 + (v - 28) / 72f * 14f).dp
                    val lit = durationMs > 0 && (i + 1) / 26f <= fraction
                    Box(
                        Modifier
                            .width(3.dp)
                            .size(width = 3.dp, height = h)
                            .clip(RoundedCornerShape(2.dp))
                            .background(contentColor.copy(alpha = if (lit) 1f else 0.4f)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (durationMs > 0) {
                    formatRecordTimer(positionMs) + " / " + formatRecordTimer(durationMs)
                } else {
                    "Voice"
                },
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.8f),
            )
            // Speed chip — API 23+ only (setPlaybackParams guard).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = contentColor.copy(alpha = 0.16f),
                    modifier = Modifier.clickable { voicePlayer.cycleRate() },
                ) {
                    Text(
                        if (rate % 1f == 0f) "${rate.toInt()}x" else "${rate}x",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // Transcript strip (spec §1 row 15).
        val transcript = message.transcript
        if (transcript != null) {
            Spacer(Modifier.height(7.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(contentColor.copy(alpha = 0.22f)))
            Spacer(Modifier.height(5.dp))
            Text(
                transcript,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = contentColor.copy(alpha = 0.88f),
            )
        } else if (onTranscribe != null && !message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
            Spacer(Modifier.height(7.dp))
            if (transcribing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.4.dp, color = contentColor.copy(alpha = 0.7f))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Transcribing…",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                    )
                }
            } else {
                Text(
                    "Transcribe",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor.copy(alpha = 0.85f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(contentColor.copy(alpha = 0.14f))
                        .clickable(onClick = onTranscribe)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** "m:ss" — record bar + voice bubble time labels. */
private fun formatRecordTimer(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

// ── D31 hold-to-record voice capture ────────────────────────────────────

/** R2-C item 5 — mm:ss countdown for the slow-mode composer chip. */
private fun slowCountdown(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}

/**
 * The composer's right slot — ALWAYS the same node across idle → recording
 * so a press gesture started on the mic survives the recording bar replacing
 * the composer (release then lands where the finger went down).
 *
 *   • idle, blank draft → MIC: press-and-hold starts recording (the gesture
 *     starts only when [micVisible]; it keeps running until release even
 *     though the slot has switched to its recording look).
 *   • recording → SEND arrow: release (no cancel drag) sends, sliding LEFT
 *     past ~96dp arms cancel ("Release to cancel" shows in the bar) and
 *     release discards. [onRecordArm] carries the armed state to the bar.
 *   • idle with text → plain send (tap).
 */
@Composable
private fun HoldRecordSlot(
    recording: Boolean,
    sending: Boolean,
    micVisible: Boolean,
    canSend: Boolean,
    onRecordStart: () -> Unit,
    onRecordArm: (Boolean) -> Unit,
    onRecordFinish: (Boolean) -> Unit,
    onSend: () -> Unit,
    /** R2-C item 5 — false while slow mode counts: gestures and taps dead. */
    enabled: Boolean = true,
) {
    val density = LocalDensity.current
    val cancelThresholdPx = remember(density) { with(density) { 96.dp.toPx() } }
    val currentMicVisible by rememberUpdatedState(micVisible)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentRecordStart by rememberUpdatedState(onRecordStart)
    val currentRecordArm by rememberUpdatedState(onRecordArm)
    val currentRecordFinish by rememberUpdatedState(onRecordFinish)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentMicVisible || !currentEnabled) return@awaitEachGesture
                    down.consume()
                    currentRecordStart()
                    var dragX = 0f
                    var armed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        dragX += change.positionChange().x
                        change.consume()
                        val nowArmed = dragX < -cancelThresholdPx
                        if (nowArmed != armed) {
                            armed = nowArmed
                            currentRecordArm(armed)
                        }
                    }
                    currentRecordFinish(armed)
                }
            }
            .clip(CircleShape)
            .background(
                when {
                    // EMB: active states ride the ember gradient; idle is dark ink.
                    recording -> Brush.linearGradient(EmberPalette.Gradient)
                    canSend -> Brush.linearGradient(EmberPalette.Gradient)
                    else -> SolidColor(EmberPalette.FabFill)
                },
                CircleShape,
            )
            .clickable(enabled = !recording && !micVisible && canSend && enabled, onClick = onSend),
    ) {
        when {
            recording && sending -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color.White,
            )
            recording -> Icon(
                PulseIcons.PaperPlane,
                contentDescription = "Release to send the voice note",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            micVisible -> Icon(
                PulseIcons.Mic,
                contentDescription = "Hold to record a voice note — slide left to cancel",
                tint = Color.White.copy(alpha = if (enabled) 0.85f else 0.35f),
                modifier = Modifier.size(20.dp),
            )
            else -> Icon(
                PulseIcons.PaperPlane,
                contentDescription = "Send",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The left section while recording: cancel button, pulsing red dot, m:ss timer,
 * LIVE waveform bars (one per 100ms amplitude sample, deterministic from the
 * mic readings — no random) and the release hint. Replaces only the composer
 * text; the right slot [HoldRecordSlot] stays mounted for the release.
 */
@Composable
private fun RecordBarContent(
    recordMs: Long,
    amps: List<Float>,
    sending: Boolean,
    cancelArmed: Boolean,
    onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onCancel,
            enabled = !sending,
            modifier = Modifier.clip(CircleShape),
        ) {
            Icon(PulseIcons.X, contentDescription = "Cancel recording", tint = PulsePalette.Rose)
        }
        Spacer(Modifier.width(4.dp))
        // Red pulsing dot — infinite alpha breathing.
        val pulse = rememberInfiniteTransition(label = "recordPulse")
        val dotAlpha by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
            label = "recordDot",
        )
        Box(
            Modifier
                .size(12.dp)
                .alpha(dotAlpha)
                .clip(CircleShape)
                .background(PulsePalette.Rose),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            formatRecordTimer(recordMs),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = PulsePalette.Rose,
        )
        Spacer(Modifier.width(10.dp))
        RecordWaveform(
            amps = amps,
            tint = PulsePalette.Rose,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        when {
            sending -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = PulsePalette.Emerald)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Sending…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            cancelArmed -> Text(
                "‹ Release to cancel",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = PulsePalette.Rose,
            )
            else -> Text(
                "Release to send",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Live hold-to-record waveform — one bar per amplitude sample (the VM keeps
 * the last [PulseMedia.RECORD_WAVEFORM_BARS]). Bar height is a pure function
 * of the recorded amplitude (4..24dp), nothing random.
 */
@Composable
private fun RecordWaveform(
    amps: List<Float>,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(PulseMedia.RECORD_WAVEFORM_BARS) { i ->
            val amp = amps.getOrNull(i) ?: 0.06f
            Box(
                Modifier
                    .size(width = 3.dp, height = (4 + amp * 20).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint.copy(alpha = 0.85f)),
            )
        }
    }
}

/**
 * D30/D31 permission-denied explainer — an honest inline row above the
 * composer with a jump to the app's Settings page. No crash, no dead end;
 * dismissible; the permission launchers clear the state on grant.
 */
@Composable
private fun PermissionExplainer(
    message: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                PulseIcons.Lock,
                contentDescription = null,
                tint = PulsePalette.Amber,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) {
                Text("Open Settings", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(PulseIcons.X, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** App-info settings page — the jump target for the permission explainers. */
private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Wave 2 live poll card (spec §1 rows 2/3/4): header, bold question,
 * single-choice option rows with animated %-fill bars, vote footer and the
 * creator-only End button. Own pick derives ONLY from poll.pickFor(viewerId)
 * — myOptionId is actor-relative on relays and null on history (proven bug).
 */
@Composable
internal fun PollCard(
    message: Message,
    viewerId: String?,
    mine: Boolean,
    onVote: ((pollId: String, optionId: String) -> Unit)?,
    onClose: ((pollId: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val poll = message.poll ?: return
    val closed = poll.closed
    val picked = poll.pickFor(viewerId)
    val total = maxOf(poll.totalVotes, poll.options.sumOf { it.voteCount })
    val shape = RoundedCornerShape(14.dp)

    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = modifier.widthIn(max = 300.dp),
    ) {
        Column(
            Modifier
                .background(
                    if (mine) {
                        Brush.linearGradient(listOf(PulsePalette.Emerald, PulsePalette.EmeraldDeep))
                    } else {
                        SolidColor(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                    },
                    shape,
                )
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    PulseIcons.Poll,
                    contentDescription = null,
                    tint = if (mine) Color.White else PulsePalette.Emerald,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (closed) "Poll · Final results" else "Live poll",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (mine) Color.White.copy(alpha = 0.85f) else PulsePalette.Emerald,
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                poll.question,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            poll.options.forEach { option ->
                val isPick = picked == option.id
                val targetFraction = if (total > 0) option.voteCount.toFloat() / total else 0f
                val fill by animateFloatAsState(
                    targetValue = targetFraction,
                    animationSpec = tween(350),
                    label = "pollFill-${option.id}",
                )
                val canVote = !closed && picked == null && onVote != null && !message.id.startsWith(TEMP_MESSAGE_PREFIX)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (isPick) {
                                (if (mine) Color.White.copy(alpha = 0.2f) else PulsePalette.Emerald.copy(alpha = 0.16f))
                            } else {
                                (if (mine) Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                            },
                        )
                        .then(if (canVote) Modifier.clickable { onVote?.invoke(poll.id, option.id) } else Modifier)
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                ) {
                    // animated %-fill bar behind the option label
                    Box(
                        Modifier
                            .matchParentSize()
                            .fillMaxWidth(fill)
                            .background((if (mine) Color.White else PulsePalette.Emerald).copy(alpha = 0.16f)),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            option.text,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isPick) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isPick) {
                            Icon(
                                PulseIcons.Check,
                                contentDescription = "Your pick",
                                tint = if (mine) Color.White else PulsePalette.Emerald,
                                modifier = Modifier
                                    .padding(start = 5.dp)
                                    .size(13.dp),
                            )
                        }
                        Text(
                            "${option.voteCount}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (mine) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        closed && total == 0 -> "Voting closed · no votes"
                        total == 0 -> "No votes yet"
                        closed -> "$total ${if (total == 1) "vote" else "votes"} · final"
                        else -> "$total ${if (total == 1) "vote" else "votes"} · tap an option to vote"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mine) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (mine && !closed && onClose != null && !message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                    Text(
                        "End poll",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (mine) Color.White else PulsePalette.Rose,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onClose(poll.id) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Wave 2 link preview card (spec §1 row 8) — thumbnail (≤120dp, clipped),
 * title, 2-line description, siteName/host + Link glyph; tap opens the URL
 * in the browser (custom tab not required for this wave).
 */
@Composable
private fun LinkPreviewCard(
    preview: LinkPreviewInfo,
    mine: Boolean,
) {
    val context = LocalContext.current
    val host = remember(preview.url) {
        runCatching { java.net.URI(preview.url).host }.getOrNull()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (mine) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .clickable {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(preview.url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
            .padding(8.dp),
    ) {
        preview.imageUrl?.takeIf { it.isNotBlank() }?.let { image ->
            AsyncImage(
                model = MediaSupport.anyMediaUrl(image),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            preview.title?.takeIf { it.isNotBlank() } ?: preview.url,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (mine) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        preview.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = if (mine) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                PulseIcons.Link,
                contentDescription = null,
                tint = if (mine) Color.White.copy(alpha = 0.7f) else PulsePalette.Emerald,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                preview.siteName?.takeIf { it.isNotBlank() } ?: host ?: preview.url,
                style = MaterialTheme.typography.labelSmall,
                color = if (mine) Color.White.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Wave 2 topic rail — GROUP rooms only (spec §1 row 9). General = WHOLE room
 * (activeTopicId == null, unfiltered); topic chips show {emoji} {name} plus a
 * capped count badge; the dashed trailing "+" chip unfolds the inline create
 * panel (name ≤32 + emoji row). Tap a chip → [onSelect].
 */
@Composable
private fun TopicBar(
    topics: List<Topic>,
    activeTopicId: String?,
    onSelect: (String?) -> Unit,
    onCreate: (name: String, emoji: String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TopicChip(
                    emoji = TOPIC_ICON_DEFAULT,
                    label = "General",
                    count = null,
                    active = activeTopicId == null,
                    onClick = {
                        creating = false
                        onSelect(null)
                    },
                )
                topics.forEach { topic ->
                    TopicChip(
                        emoji = topicIconId(topic.emoji),
                        label = topic.name,
                        count = topic.messageCount,
                        active = activeTopicId == topic.id,
                        onClick = {
                            creating = false
                            onSelect(topic.id)
                        },
                    )
                }
                // dashed "+" chip — opens the inline create panel
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .pulseDashedBorder(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), cornerRadius = 999.dp)
                        .clickable { creating = !creating }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (creating) {
                TopicCreatePanel(
                    onCreate = { name, emoji ->
                        creating = false
                        onCreate(name, emoji)
                    },
                    onCancel = { creating = false },
                )
            }
        }
    }
}

/**
 * Inline topic-create panel - name field (≤32 chars) + registry icon choice
 * row (icon-ids.ts TOPIC_ICON_IDS); glyphs render via [pulseTopicGlyph].
 */
private val TOPIC_ICON_PRESETS = TOPIC_ICON_IDS
@Composable
private fun TopicCreatePanel(
    onCreate: (name: String, emoji: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf(TOPIC_ICON_DEFAULT) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                BasicField(
                    value = name,
                    onValueChange = { if (it.length <= 32) name = it },
                    placeholder = "Topic name",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${name.length}/32",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Create",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (name.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else PulsePalette.Emerald,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = name.isNotBlank()) { onCreate(name.trim(), emoji) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                "Cancel",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TOPIC_ICON_PRESETS.forEach { id ->
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(if (id == emoji) EmberPalette.Amber.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable { emoji = id }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        pulseTopicGlyph(id),
                        contentDescription = null,
                        tint = if (id == emoji) EmberPalette.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/** One topic chip - registry icon + label + optional count badge (99+ cap). */
@Composable
private fun TopicChip(
    emoji: String,
    label: String,
    count: Int?,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) PulsePalette.Emerald.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, PulsePalette.Emerald.copy(alpha = 0.45f)) else null,
    ) {
        Row(
            Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                pulseTopicGlyph(emoji),
                contentDescription = null,
                tint = if (active) EmberPalette.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                color = if (active) PulsePalette.EmeraldDeep else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (count != null && count > 0) {
                Text(
                    if (count > 99) "99+" else "$count",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) PulsePalette.EmeraldDeep else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ── Wave 6 — DM safety-number sheet (web safety-sheet parity) ───────────

private fun safetyStamp(iso: String): String = runCatching {
    java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm")
        .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(iso))
}.getOrDefault("")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafetyNumberSheetHost(
    safety: ChatRoomViewModel.SafetyUi,
    onDismiss: () -> Unit,
    onVerify: () -> Unit,
    onReset: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    PulseIcons.Shield,
                    contentDescription = null,
                    tint = if (safety.state?.verified == true) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Encryption", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        if (safety.state?.verified == true) "Verified" else "Not verified",
                        fontSize = 12.sp,
                        color = if (safety.state?.verified == true) PulsePalette.Emerald else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            when {
                safety.busy && safety.state == null -> Row(Modifier.padding(vertical = 12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                safety.state != null -> {
                    val digits = safety.state.safetyNumber.split(' ').filter { it.isNotBlank() }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        digits.chunked(4).forEach { rowDigits ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowDigits.forEach { group ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            group,
                                            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                if (rowDigits.size == 1) Spacer(Modifier.weight(1f))
                                if (rowDigits.size == 2) Spacer(Modifier.weight(1f))
                                if (rowDigits.size == 3) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Compare these 60 digits with your contact in person. If they match, mark this contact as verified.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (safety.state.verified) {
                        Text(
                            "Verified · " + (safety.state.verifiedAtIso?.let { safetyStamp(it) } ?: ""),
                            color = PulsePalette.Emerald,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onReset, enabled = !safety.busy) { Text("Reset verification") }
                    } else {
                        Button(
                            onClick = onVerify,
                            enabled = !safety.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Mark as verified") }
                    }
                }
                else -> Text(
                    "Could not load the safety number — try again.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
