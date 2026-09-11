package app.pulse.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.core.media.PulseMedia
import app.pulse.core.time.PulseTime
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import coil.compose.AsyncImage

/**
 * Chat room — the native rebuild of the web conversation surface, now on the
 * Wave 1 messaging engine ([ChatRoomViewModel]): paginated timeline with day
 * separators, tick states (queued clock → sent ✓ → seen ✓✓), the full message
 * action surface, pinned banner, room search + jump, staged media sends and
 * the honest offline strip. Threads open [ThreadScreen] via onOpenThread.
 */
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
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val replyCounts by viewModel.replyCounts.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val jumpTarget by viewModel.jumpTarget.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    var draft by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<Message?>(null) }
    var forwardTarget by remember { mutableStateOf<Message?>(null) }
    var infoTarget by remember { mutableStateOf<Message?>(null) }
    var deleteTarget by remember { mutableStateOf<Message?>(null) }
    var lightboxTarget by remember { mutableStateOf<Message?>(null) }
    var pinsOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var wasEditing by remember { mutableStateOf(false) }
    var expandedFor by remember { mutableStateOf<String?>(null) }
    var scrolledFlash by remember { mutableStateOf<String?>(null) }

    // Timeline rows (asc) with day separators, then reversed for the
    // reverseLayout list — index 0 is the newest row, the anchor for tails.
    val rows = remember(messages) { buildTimelineRows(messages) }
    val rowsReversed = remember(rows) { rows.asReversed() }
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

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        RoomHeader(
            conversation = conversation,
            partnerTypingName = state.partnerTypingName,
            searchOpen = state.searchOpen,
            onBack = {
                if (state.searchOpen) viewModel.setSearchOpen(false) else onBack()
            },
            onToggleSearch = {
                if (state.searchOpen) viewModel.setSearchOpen(false) else viewModel.setSearchOpen(true)
            },
        )

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

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(rowsReversed, key = { it.key }) { row ->
                    when (row) {
                        is TimelineRow.Day -> DaySeparator(row.label)
                        is TimelineRow.Msg -> {
                            val message = row.message
                            MessageRow(
                                message = message,
                                conversation = conversation,
                                viewerId = viewerId,
                                partnerLastReadAt = state.partnerLastReadAt,
                                isLastMine = lastMineId == message.id,
                                flashing = state.flashMessageId == message.id,
                                replyCount = replyCounts[message.id] ?: 0,
                                downloading = state.downloadingFileId == message.id,
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
                                modifier = Modifier.animateItem(),
                            )
                        }
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
                            Icons.Outlined.Schedule,
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
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { viewModel.jumpTo(reply.id) },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(reply.authorName, style = MaterialTheme.typography.labelMedium, color = PulsePalette.Emerald, fontWeight = FontWeight.SemiBold)
                            Text(reply.body, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { viewModel.setReplyTo(null) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel reply", modifier = Modifier.size(16.dp))
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
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.InsertDriveFile, contentDescription = null, tint = PulsePalette.Amber, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Editing message", style = MaterialTheme.typography.labelMedium, color = PulsePalette.Amber, fontWeight = FontWeight.SemiBold)
                            Text(editing.body, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = viewModel::cancelEdit) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel edit", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // Offline honesty strip (spec row 7) — text queues, media won't.
        AnimatedVisibility(visible = state.connected == false) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        "Offline — messages will queue",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Composer
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(
                    onClick = {
                        if (state.staged == null && state.editing == null) attachOpen = true
                    },
                    modifier = Modifier.clip(CircleShape),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        viewModel.onDraftChanged(it)
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(PulsePalette.Emerald),
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    if (state.editing != null) "Edit your message" else "Message",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
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
                        if (state.editing == null) draft = "" // edit path clears on success
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
                        contentDescription = "Send",
                        tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        SnackbarHost(hostState = snackbar)
    }

    // ── sheets & dialogs ─────────────────────────────────────────────

    if (attachOpen) {
        AttachSheet(
            onDismiss = { attachOpen = false },
            onPhoto = {
                attachOpen = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onDocument = {
                attachOpen = false
                documentPicker.launch(PulseMedia.DOCUMENT_MIME_ARRAY)
            },
        )
    }

    actionTarget?.let { target ->
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
}

// ── timeline model ───────────────────────────────────────────────────

internal sealed interface TimelineRow {
    val key: String

    /** Calendar-day separator pill. */
    data class Day(val iso: String, val label: String) : TimelineRow {
        override val key: String get() = "day-$iso"
    }

    /** A river message (thread replies never reach this list). */
    data class Msg(val message: Message) : TimelineRow {
        override val key: String get() = message.id
    }
}

/** Asc rows with a centered day pill wherever the calendar date changes. */
internal fun buildTimelineRows(messages: List<Message>): List<TimelineRow> {
    val rows = mutableListOf<TimelineRow>()
    var lastIso: String? = null
    for (message in messages) {
        val t = PulseTime.parse(message.createdAt)
        val iso = t?.atZoneSameInstant(java.time.ZoneId.systemDefault())?.toLocalDate()?.toString()
        if (iso != null && iso != lastIso) {
            rows += TimelineRow.Day(iso, PulseTime.dayChip(message.createdAt))
            lastIso = iso
        }
        rows += TimelineRow.Msg(message)
    }
    return rows
}

@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PinnedBanner(pin: Message, onJump: () -> Unit, onOpenAll: () -> Unit) {
    Surface(
        color = PulsePalette.Amber.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onJump)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = "Pinned message",
                tint = PulsePalette.Amber,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pin.authorName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = PulsePalette.Amber,
                    maxLines = 1,
                )
                Text(
                    pin.body.ifBlank { if (pin.imagePath != null) "📷 Photo" else "Document — ${pin.fileName ?: "file"}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenAll, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "All pinned messages",
                    tint = PulsePalette.Amber,
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
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            BasicField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Search this conversation…",
                leading = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Close",
                    style = MaterialTheme.typography.labelMedium,
                    color = PulsePalette.Emerald,
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
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        PulseTime.dayChip(hit.createdAt) + " · " + PulseTime.clock(hit.createdAt),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    snippetAnnotated(
                                        hit.body.ifBlank { if (hit.imagePath != null) "📷 Photo" else "Document — ${hit.fileName ?: "file"}" },
                                        query,
                                    ),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Attach sheet — photo picker or system document (spec row 9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onDocument: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        SheetAction(Icons.Filled.Image, "Photo", onPhoto)
        SheetAction(Icons.Filled.InsertDriveFile, "Document", onDocument)
        Spacer(Modifier.height(28.dp))
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
    onSend: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
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
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.InsertDriveFile, contentDescription = "Document", tint = PulsePalette.Emerald, modifier = Modifier.size(24.dp))
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
                                color = PulsePalette.Emerald,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onRetry)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        staged.uploading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.6.dp, color = PulsePalette.Emerald)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Uploading…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        staged.uploadedPath != null -> {
                            val size = PulseMedia.humanFileSize(staged.fileSize)
                            Text(
                                if (size.isEmpty()) "Ready to send" else "Ready to send · $size",
                                style = MaterialTheme.typography.labelSmall,
                                color = PulsePalette.Emerald,
                            )
                        }
                    }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove attachment", modifier = Modifier.size(15.dp))
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = caption,
                    onValueChange = onCaptionChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(PulsePalette.Emerald),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (caption.isEmpty()) {
                                Text(
                                    "Add a caption…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (searchOpen) "Close search" else "Back")
            }
            PulseAvatar(
                name = conversation?.title ?: "…",
                colorHex = conversation?.accentColor,
                size = 40.dp,
                isGroup = conversation?.isGroupish == true,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation?.title ?: "…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (conversation != null && !conversation.isGroupish) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(14.dp))
                    }
                }
                AnimatedContentCompat(partnerTypingName != null) { typing ->
                    if (typing) {
                        Text(
                            "${partnerTypingName.orEmpty()} is typing",
                            style = MaterialTheme.typography.labelMedium,
                            color = PulsePalette.Emerald,
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Text(
                            when {
                                conversation == null -> ""
                                conversation.isGroupish -> "${conversation.memberNames.size} members"
                                else -> "online"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onClick = onToggleSearch) {
                Icon(
                    if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search in conversation",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    onLongPress: (() -> Unit)?,
    onQuoteClick: (String) -> Unit,
    onOpenImage: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenThread: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mine = message.authorId == viewerId
    val system = message.kind == Message.Kind.SYSTEM || message.authorId == "system"

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (system) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(message.body, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        // Group sender label above their first bubble run
        if (!mine && conversation?.isGroupish == true) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(PulsePalette.parse(message.senderColor) ?: PulsePalette.Teal))
                Spacer(Modifier.width(5.dp))
                Text(message.authorName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                // Tombstone — soft-deleted rows render the honest placeholder.
                message.isDeleted -> TombstoneBubble()
                message.imagePath != null -> MediaWithQuote(
                    quoteId = message.replyToId,
                    quoteBody = message.replyToBody,
                    quoteAuthor = message.replyToAuthor,
                    mine = mine,
                    onQuoteClick = onQuoteClick,
                    content = { ImageBubble(message = message, mine = mine, onOpen = onOpenImage) },
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
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }
        }

        // reactions + meta
        if (message.reactions.isNotEmpty()) {
            Row(
                Modifier.padding(start = if (mine) 0.dp else 4.dp, end = if (mine) 4.dp else 0.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                message.reactions
                    .groupBy { it.emoji }
                    .forEach { (emoji, list) ->
                        val popped by animateFloatAsState(1f, animationSpec = PulseMotion.bouncy(), label = "react")
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(emoji, fontSize = 12.sp)
                                if (list.size > 1) {
                                    Spacer(Modifier.width(3.dp))
                                    Text("${list.size}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
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
                        Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        tint = PulsePalette.Emerald,
                        modifier = Modifier
                            .size(12.dp)
                            .alpha(0.9f),
                    )
                    Text(
                        "$replyCount ${if (replyCount == 1) "reply" else "replies"}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PulsePalette.Emerald,
                    )
                }
            }
        }

        // Tick line on the LAST own message — queued clock → ✓ sent → ✓✓ seen.
        if (mine && isLastMine) {
            if (message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                // Queued in the outbox — a clock, never a false "Seen".
                Row(
                    Modifier.padding(end = 4.dp, top = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
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
                            Icons.Filled.Check,
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
                    .background(if (mine) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .clickable { onQuoteClick(quoteId) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Column {
                    Text(
                        quoteAuthor ?: "Reply",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mine) Color.White else PulsePalette.Emerald,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        quoteBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mine) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
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
    modifier: Modifier = Modifier,
) {
    val shape = if (mine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    }
    val background: Brush = if (mine) {
        Brush.linearGradient(listOf(PulsePalette.Emerald, PulsePalette.EmeraldDeep))
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
    }
    val contentColor = if (mine) Color.White else MaterialTheme.colorScheme.onSurface
    val flashAlpha by animateFloatAsState(
        targetValue = if (flashing) 1f else 0f,
        animationSpec = tween(220),
        label = "flashRing",
    )

    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = modifier
            .border(2.dp, PulsePalette.Amber.copy(alpha = flashAlpha), shape)
            .then(
                if (onLongPress != null) {
                    Modifier.combinedClickable(onClick = { }, onLongClick = onLongPress)
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
                        .background(if (mine) Color.White.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .clickable(enabled = onQuoteClick != null) { onQuoteClick?.invoke(quoteId) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Column {
                        Text(
                            message.replyToAuthor ?: "Reply",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (mine) Color.White else PulsePalette.Emerald,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            replyBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mine) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            when (message.kind) {
                Message.Kind.VOICE -> VoiceBubble(message, contentColor)
                Message.Kind.IMAGE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Image, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Photo", color = contentColor, style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(
                    message.body,
                    color = contentColor,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                    // Pending outbox bubble — clock marker replaces the clock text.
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = "Queued",
                        tint = if (mine) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(11.dp),
                    )
                } else {
                    Text(
                        PulseTime.clock(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mine) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (message.editedAt != null) {
                    Text(
                        "edited",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mine) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (message.viaAutomation) {
                    Text(
                        "Automation",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mine) Color.White.copy(alpha = 0.85f) else PulsePalette.Violet,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (message.pinnedAt != null) {
                    Icon(Icons.Filled.PushPin, contentDescription = "Pinned", tint = if (mine) Color.White.copy(alpha = 0.85f) else PulsePalette.Amber, modifier = Modifier.size(11.dp))
                }
            }
        }
    }
}

/** Soft-delete tombstone — "🚫 Message deleted" (spec §1.2), no actions. */
@Composable
internal fun TombstoneBubble() {
    val shape = RoundedCornerShape(14.dp)
    Surface(shape = shape, color = Color.Transparent) {
        Box(
            Modifier
                .background(
                    SolidColor(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                    shape,
                )
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Text(
                "\uD83D\uDEAB Message deleted",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Native waveform placeholder — deterministic bars from the message id. */
@Composable
private fun VoiceBubble(message: Message, contentColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.GraphicEq, contentDescription = "Voice message", tint = contentColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            val seed = remember(message.id) { message.id.hashCode() }
            repeat(16) { i ->
                val h = (4 + ((seed * (i + 7)) % 14)).dp
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
