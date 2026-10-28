package app.pulse.android.mirror

import androidx.lifecycle.repeatOnLifecycle

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.domain.model.PulseApiException
import app.pulse.domain.sync.threadRootAvailable
import app.pulse.domain.sync.threadAccessDenied
import app.pulse.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.pulse.domain.model.Message
import app.pulse.domain.model.PollInfo
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * R73 - the message-level surface the mirror was missing entirely (web
 * MessageActionMenu chat-room.tsx:6401-6573): the reaction strip, Reply,
 * Reply in thread, Copy text, Forward to chat, Save/Unsave, Convert to task,
 * Remind me, Pin/Unpin, Message info, Edit message and Delete for everyone -
 * every row riding the REAL repository routes. Also the forward/thread/poll/
 * sticker/location/who-reacted surfaces the web mounts around the room.
 */

/** web icon-ids.ts REACTION_IDS - the wire values + the glyph + the label. */
internal object MirrorReactions {
    data class Def(val id: String, val label: String, val glyph: String)

    val ALL = listOf(
        Def("heart", "Heart", "PHeartStraight"),
        Def("fire", "Fire", "LFlame"),
        Def("laugh", "Laugh", "PMaskHappy"),
        Def("wow", "Wow", "PEyes"),
        Def("sad", "Sad", "PSmileySad"),
        Def("celebrate", "Celebrate", "PConfetti"),
        Def("thumbsup", "Thumbs up", "PThumbsUp"),
    )

    fun glyphOf(id: String): String = ALL.firstOrNull { it.id == id }?.glyph ?: "PHeartStraight"
}

/**
 * The web GlassMenu action panel, glass-deep #1c1610/95, rounded 16, hairline
 * ring, w-260 - identical language to MirrorKebabMenu. Rows disabled exactly
 * where the web disables them (deleted/own/media rules, chat-room.tsx:6504+).
 */
@Composable
internal fun MirrorMessageActionPanel(
    message: Message,
    conversationId: String,
    viewerId: String,
    members: List<ConversationMember>,
    repository: PulseRepository,
    onReaction: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onThread: () -> Unit,
    onForward: () -> Unit,
    onRemind: () -> Unit,
    onInfo: () -> Unit,
    onDeleted: () -> Unit,
    onTaskCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val mine = message.authorId == viewerId
    val deleted = message.deletedAt != null
    val isTextKind = message.kind == Message.Kind.TEXT
    // live saved-library truth (web isSaved = saved list contains the id)
    val savedList by repository.observeSavedLibrary().collectAsState(initial = emptyList())
    val saved = savedList.any { it.message.id == message.id }
    // optimistic local overrides so the panel reacts on the same tap
    var savedLocal by remember(message.id) { mutableStateOf(saved) }
    LaunchedEffect(saved) { savedLocal = saved }
    var pinnedLocal by remember(message.id) { mutableStateOf(message.pinnedAt != null) }
    var pendingOp by remember(message.id) { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    val actionScope = rememberCoroutineScope()
    val panelSession = remember(repository, viewerId) { repository.attachmentSessionKey() }
    val panelLive = remember(message.id, viewerId) { java.util.concurrent.atomic.AtomicBoolean(true) }
    androidx.compose.runtime.DisposableEffect(panelLive) { onDispose { panelLive.set(false) } }
    fun requireActionOwner() {
        if (!panelLive.get() || repository.viewerId != viewerId || panelSession == null ||
            repository.attachmentSessionKey() != panelSession) throw CancellationException("Message action owner changed")
    }
    fun op(name: String, block: suspend () -> String?) {
        if (pendingOp != null) return
        pendingOp = name
        note = null
        actionScope.launch {
            try {
                requireActionOwner()
                val result = block()
                currentCoroutineContext().ensureActive()
                requireActionOwner()
                note = result
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                requireActionOwner()
                note = error.message ?: "This action could not be confirmed."
            } finally { pendingOp = null }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x33000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}
                .padding(vertical = 6.dp)
                .verticalScroll(rememberScrollState())
                .heightIn(max = 420.dp),
        ) {
            // reaction strip (web GlassMenuStrip of REACTION_IDS, whileTap 0.82)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (def in MirrorReactions.ALL) {
                    val active = message.reactions.any { app.pulse.protocol.reactionId(it.emoji) == def.id && it.userId == viewerId }
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (active) Color(0x26FFB23D) else Color.Transparent)
                            .border(
                                1.dp,
                                if (active) Color(0x73FFB23D) else Color.Transparent,
                                CircleShape,
                            )
                            .mirrorPressClick(onClick = {
                                onDismiss()
                                onReaction(def.id)
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (def.glyph.startsWith("P")) {
                            MirrorPhosphorIcon(def.glyph, tint = MirrorArt.Accent2, modifier = Modifier.size(20.dp))
                        } else {
                            MirrorLucideIcon(def.glyph, tint = MirrorArt.Accent2, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            if (deleted) {
                Text(
                    "This message was deleted.",
                    color = MirrorArt.Dim,
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            MirrorActionSeparator()
            MirrorActionRow(glyph = "LCornerDownRight", label = "Reply", enabled = !deleted) { onDismiss(); onReply() }
            if (message.threadRootId == null && !deleted) {
                MirrorActionRow(glyph = "LMessagesSquare", label = "Reply in thread") { onDismiss(); onThread() }
            }
            MirrorActionSeparator()
            MirrorActionRow(glyph = "LCopy", label = "Copy text", enabled = !deleted && (!message.viewOnce || mine)) {
                clipboard.setText(AnnotatedString(message.body))
                onDismiss()
            }
            MirrorActionRow(glyph = "LExternalLink", label = "Forward to chat", enabled = !deleted && !message.viewOnce) { onDismiss(); onForward() }
            if (!deleted) {
                MirrorActionRow(
                    glyph = "LStar",
                    label = if (savedLocal) "Unsave" else "Save message",
                    active = savedLocal,
                    enabled = pendingOp == null,
                ) {
                    op("save") {
                        val next = repository.setMessageSaved(message.id, !savedLocal).getOrThrow()
                        requireActionOwner()
                        savedLocal = next
                        null
                    }
                }
            }
            if (message.threadRootId == null && !deleted && isTextKind) {
                MirrorActionRow(
                    glyph = "LListTodo",
                    label = "Convert to task",
                    enabled = pendingOp == null,
                ) {
                    op("task") {
                        repository.createKanbanCard(
                            conversationId = conversationId,
                            title = message.body.take(80),
                            column = null,
                            assigneeId = null,
                            messageId = message.id,
                        ).getOrThrow()
                        requireActionOwner()
                        onTaskCreated("Task added to the board")
                        null
                    }
                }
            }
            if (!deleted) {
                MirrorActionRow(glyph = "LBell", label = "Remind me") { onDismiss(); onRemind() }
            }
            if (!deleted) {
                MirrorActionRow(
                    glyph = if (pinnedLocal) "LPinOff" else "LPin",
                    label = if (pinnedLocal) "Unpin" else "Pin",
                    enabled = pendingOp == null,
                ) {
                    op("pin") {
                        val next = if (pinnedLocal) {
                            repository.unpinMessage(message.id).getOrThrow()
                            false
                        } else repository.pinMessage(message.id).getOrThrow().pinnedAt != null
                        requireActionOwner()
                        pinnedLocal = next
                        null
                    }
                }
            }
            if (mine && !deleted) {
                MirrorActionRow(glyph = "LInfo", label = "Message info") { onDismiss(); onInfo() }
            }
            if (mine && !deleted && message.audioPath == null && message.filePath == null) {
                MirrorActionRow(glyph = "LPencilLine", label = "Edit message") { onDismiss(); onEdit() }
            }
            MirrorActionSeparator()
            // canDelete = sender only (web DELETE /api/messages/{id} requesterId gate)
            MirrorActionRow(
                glyph = "LTrash2",
                label = "Delete for everyone",
                destructive = true,
                enabled = mine && !deleted && pendingOp == null,
            ) {
                op("delete") {
                    repository.deleteMessage(message.id).getOrThrow()
                    requireActionOwner()
                    onDeleted()
                    null
                }
            }
            note?.let {
                Text(
                    it,
                    color = MirrorArt.TextSoft,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MirrorActionSeparator() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .height(1.dp)
            .background(MirrorArt.Hairline),
    )
}

@Composable
private fun MirrorActionRow(
    glyph: String,
    label: String,
    active: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        destructive -> MirrorArt.Red
        active -> MirrorArt.Accent2
        else -> MirrorArt.TextSoft
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MirrorLucideIcon(glyph, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            label,
            color = if (enabled) tint else MirrorArt.Faint,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/** web ForwardSheet chat-room.tsx:6324 - chat picker, forward = re-POST. */
@Composable
internal fun MirrorForwardSheet(
    repository: PulseRepository,
    source: Message,
    onDismiss: () -> Unit,
    onForwarded: (String) -> Unit,
) {
    val bound = remember(repository) { repository.boundToCurrentAccount() }
    val owner = remember(bound) { bound.viewerId }
    val epoch = remember(bound) { bound.attachmentSessionKey() }
    val actionScope = rememberCoroutineScope()
    val conversations by bound.observeConversations().collectAsStateWithLifecycle(initialValue = emptyList())
    var query by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    MirrorSheet(title = "Forward to", onDismiss = onDismiss) {
        MirrorSheetSearchField(query = query, onQuery = { query = it })
        Spacer(Modifier.height(8.dp))
        val targets = conversations.filter { it.title.contains(query, ignoreCase = true) }
        MirrorSheetScroll {
            for (convo in targets) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = busyId == null) {
                            busyId = convo.id
                            error = null
                            actionScope.launch {
                                try {
                                    currentCoroutineContext().ensureActive()
                                    if (owner == null || bound.viewerId != owner || bound.attachmentSessionKey() != epoch) throw CancellationException("Forward account changed")
                                    bound.forwardMessage(convo.id, source).getOrThrow()
                                    if (bound.viewerId != owner || bound.attachmentSessionKey() != epoch) throw CancellationException("Forward account changed")
                                    onForwarded(convo.title)
                                    onDismiss()
                                } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                                    error = failure.message ?: "Forward wasn't queued. Try again."
                                } finally { busyId = null }
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorAvatar(
                        name = convo.title,
                        color = convo.accentColor,
                        isGroup = convo.isGroupish,
                        groupId = convo.id,
                        online = false,
                        showPresence = false,
                        sizeDp = 34,
                        cornerDp = 17,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(convo.title, color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            convo.lastMessagePreview.orEmpty().ifBlank { "Conversation" },
                            color = MirrorArt.Dim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (busyId == convo.id) {
                        MirrorLucideIcon("LLoaderCircle", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
                    }
                }
            }
            error?.let {
                Text(it, color = MirrorArt.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** web ThreadSheet chat-room.tsx:6234 - parent + replies + reply composer. */
@Composable
internal fun MirrorThreadSheet(
    repository: PulseRepository,
    root: Message,
    viewerId: String,
    viewerName: String,
    memberNames: List<String>,
    members: List<app.pulse.domain.model.ConversationMember>,
    jumpMessageId: String? = null,
    readingEnabled: Boolean = false,
    onDismiss: () -> Unit,
) {
    val threadRepository = remember(repository, viewerId) { repository.boundToCurrentAccount() }
    val recoveryFlow = remember(threadRepository, root.id) { threadRepository.observeFailedThreadReplies(root.conversationId, root.id) }
    val failedReplies by recoveryFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val clipboard = LocalClipboardManager.current
    var recoveryBusy by remember(root.id) { mutableStateOf(false) }
    var discardConfirm by remember(root.id) { mutableStateOf<String?>(null) }
    var threadWindow by remember(threadRepository, root.id) { mutableStateOf<app.pulse.domain.sync.MessageWindow?>(null) }
    var viewportCenter by remember(threadRepository, root.id) { mutableStateOf<app.pulse.domain.sync.MessageHistoryCursor?>(null) }
    var followTail by remember(threadRepository, root.id) { mutableStateOf(true) }
    var navigationReady by remember(threadRepository, root.id) { mutableStateOf(false) }
    var pageDirection by remember(threadRepository, root.id) { mutableStateOf(0) }
    var pageTick by remember(threadRepository, root.id) { mutableStateOf(0) }
    val selectedIds = threadWindow?.messages?.map { it.id }
    val replyFlow = remember(threadRepository, root.id, selectedIds) { threadRepository.observeThreadWindow(root.id, selectedIds) }
    val rootFlow = remember(threadRepository, root.id) {
        kotlinx.coroutines.flow.combine(threadRepository.observeMessage(root.conversationId, root.id),
            threadRepository.observeThreadAccessDenied(root.id)) { row, refused ->
            if (refused) null else row
        }
    }
    val cachedReplies by replyFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentRoot by rootFlow.collectAsStateWithLifecycle(initialValue = null)
    var scopeUnread by remember(threadRepository, root.id) { mutableStateOf<Int?>(null) }
    var scopeSnapshot by remember(threadRepository, root.id) { mutableStateOf<app.pulse.domain.model.ReadScopeState?>(null) }
    LaunchedEffect(scopeSnapshot) {
        val snapshot = scopeSnapshot ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            scopeUnread = snapshot.unreadAt(now)
            val next = snapshot.unreadExpirations.map { it.expiresAtEpochMs }.filter { it > now }.minOrNull() ?: break
            kotlinx.coroutines.delay((next - now).coerceIn(1L, 30_000L))
        }
    }
    var scopeReadRevision by remember(threadRepository, root.id) { mutableStateOf(0) }
    var firstUnreadMessageId by remember(threadRepository, root.id) { mutableStateOf<String?>(null) }
    var scopeAnchorCaptured by remember(threadRepository, root.id) { mutableStateOf(false) }
    LaunchedEffect(threadRepository, root.id) {
        threadRepository.events().collect { event ->
            if (event is app.pulse.domain.repository.PulseEvent.MessageRead && event.conversationId == root.conversationId) scopeReadRevision++
            if (event is app.pulse.domain.repository.PulseEvent.MessageReceived && event.conversationId == root.conversationId) scopeReadRevision++
            if (event is app.pulse.domain.repository.PulseEvent.MessageDeleted && event.conversationId == root.conversationId) scopeReadRevision++
            if (event is app.pulse.domain.repository.PulseEvent.MessageUpdated && event.conversationId == root.conversationId) scopeReadRevision++
        }
    }
    val connectedFlow = remember(threadRepository) { threadRepository.observeConnected() }
    val connected by connectedFlow.collectAsStateWithLifecycle(initialValue = false)
    val readRevisionFlow = remember(threadRepository, root.conversationId) {
        threadRepository.observeReadStateRevision(root.conversationId)
    }
    val readRevision by readRevisionFlow.collectAsStateWithLifecycle(initialValue = 0L)
    val scope = rememberCoroutineScope()
    val unreadRefresh = remember(threadRepository, root.id) {
        app.pulse.domain.sync.CoalescedRefresh(scope) {
            val epoch = threadRepository.attachmentSessionKey()
            val result = threadRepository.readScopeState(root.conversationId, threadId = root.id)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (threadRepository.attachmentSessionKey() == epoch) {
                result.getOrNull()?.let { snapshot ->
                    scopeSnapshot = snapshot
                    if (!scopeAnchorCaptured) { scopeAnchorCaptured = true; firstUnreadMessageId = snapshot.firstUnreadMessageId }
                }
                if ((result.exceptionOrNull() as? app.pulse.domain.model.PulseApiException)?.status in setOf(400, 403, 404, 410)) {
                    scopeSnapshot = null; scopeUnread = null
                }
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(unreadRefresh) { onDispose { unreadRefresh.cancel() } }
    LaunchedEffect(unreadRefresh, connected, scopeReadRevision, readRevision) { unreadRefresh.request() }
    var draftToken by remember(threadRepository, root.id) { mutableStateOf<app.pulse.domain.model.ComposerDraftToken?>(null) }
    val composer = remember(threadRepository, root.id) {
        RoomComposerDraft { draftToken = threadRepository.stageThreadDraft(root.conversationId, root.id, it) }
    }
    var draft by composer
    var sending by remember(root.id) { mutableStateOf(false) }
    var loading by remember(root.id) { mutableStateOf(true) }
    var validated by remember(root.id) { mutableStateOf(false) }
    var fetchedReplies by remember(root.id) { mutableStateOf<List<Message>?>(null) }
    var denied by remember(root.id) { mutableStateOf(false) }
    var error by remember(root.id) { mutableStateOf<String?>(null) }
    var draftError by remember(root.id) { mutableStateOf<String?>(null) }
    var retry by remember(root.id) { mutableStateOf(0) }
    var now by remember(root.id) { mutableStateOf(System.currentTimeMillis()) }
    var jumpResolved by remember(root.id, jumpMessageId) { mutableStateOf(false) }
    var flashId by remember(root.id) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val revealLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var viewOnceGrant by remember(root.id) { mutableStateOf<Message?>(null) }
    var openingViewOnce by remember(root.id) { mutableStateOf(false) }
    fun openViewOnce(message: Message) {
        if (openingViewOnce || viewOnceGrant != null) return
        openingViewOnce = true
        scope.launch {
            try {
                threadRepository.consumeViewOnce(message.id).onSuccess {
                    if (!denied && threadRootAvailable(currentRoot, root.conversationId, root.id) &&
                        revealLifecycle.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) viewOnceGrant = it
                }.onFailure { error = it.message ?: "Couldn't open this photo" }
            } finally { openingViewOnce = false }
        }
    }
    viewOnceGrant?.let { app.pulse.feature.chat.ImageLightbox(message = it, onDismiss = { viewOnceGrant = null }) }
    LaunchedEffect(currentRoot, cachedReplies, denied, viewOnceGrant?.id) {
        val granted = viewOnceGrant
        if (granted != null && (denied || !threadRootAvailable(currentRoot, root.conversationId, root.id) ||
                (granted.id != root.id && cachedReplies.none { it.id == granted.id && !it.isDeleted }))) viewOnceGrant = null
    }
    LaunchedEffect(threadRepository, root.id) {
        threadRepository.observeThreadDraft(root.conversationId, root.id).collect { snapshot ->
            val currentToken = threadRepository.captureThreadDraftToken(root.conversationId, root.id)
            if (snapshot.revision == currentToken?.revision) {
                draftToken = currentToken
                draftError = if (snapshot.saveFailed) "Couldn't save this draft. Keep this sheet open and edit to retry." else null
                val restoredText = snapshot.text
                if (restoredText != null && draft.text != restoredText) {
                    composer.update(androidx.compose.ui.text.input.TextFieldValue(restoredText), notify = false)
                }
            }
        }
    }
    LaunchedEffect(root.id) {
        while (true) { now = System.currentTimeMillis(); delay(1_000) }
    }
    LaunchedEffect(scopeReadRevision) {
        delay(150)
        while (loading) delay(150)
        pageTick++
    }
    LaunchedEffect(threadRepository, root.id, connected, retry, revealLifecycle, pageTick) {
        revealLifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            unreadRefresh.request()
            loading = true
            error = null
            try {
                val old = threadWindow
                val direction = pageDirection
                pageDirection = 0
                val middle = old?.messages?.let { it.getOrNull(it.size / 2) }
                val anchor = if (!jumpResolved && direction == 0) jumpMessageId else null
                val page = threadRepository.threadWindow(root.conversationId, root.id, anchorId = anchor,
                    before = if (direction < 0) old?.older else null,
                    after = if (direction > 0) old?.newer else null,
                    center = if (direction == 0 && anchor == null && old != null && (!followTail || old.hasNewer))
                        viewportCenter ?: middle?.let { app.pulse.domain.sync.MessageHistoryCursor(it.id, it.createdAt) } else null)
                val fresh = checkNotNull(page.parent)
                val loadedReplies = page.messages
                currentCoroutineContext().ensureActive()
                threadWindow = page
                navigationReady = true
                if (page.cachedOnly) error = "Offline — showing saved replies"
                fetchedReplies = loadedReplies
                if (!threadRootAvailable(fresh, root.conversationId, root.id)) {
                    denied = true
                    error = "This thread is no longer available."
                } else {
                    denied = false
                    validated = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                denied = denied || threadAccessDenied((failure as? PulseApiException)?.status)
                navigationReady = false
                if ((failure as? PulseApiException)?.code in setOf("message_missing", "message_gone")) jumpResolved = true
                error = failure.message ?: "Couldn't load this thread. Tap to retry."
            } finally { loading = false }
        }
    }
    LaunchedEffect(threadRepository, root.id, revealLifecycle) {
        revealLifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) { delay(15_000); if (!loading) pageTick++ }
        }
    }
    val available = !denied && threadRootAvailable(currentRoot, root.conversationId, root.id, now)
    val replies = if (available) cachedReplies.filter { it.conversationId == root.conversationId &&
        it.threadRootId == root.id && (it.expiresAtEpochMs?.let { expiry -> expiry > now } != false) } else emptyList()
    val viewportReplies = androidx.compose.runtime.rememberUpdatedState(replies)
    LaunchedEffect(threadRepository, root.id, listState) {
        androidx.compose.runtime.snapshotFlow {
            val rows = viewportReplies.value
            val visible = listState.layoutInfo.visibleItemsInfo
            rows.firstOrNull { row -> visible.firstOrNull { item -> rows.any { it.id == item.key } }?.key == row.id } to
                visible.any { it.key == rows.lastOrNull()?.id }
        }.collect { (first, tail) ->
            viewportCenter = first?.takeUnless { it.id.startsWith("local_") }?.let { app.pulse.domain.sync.MessageHistoryCursor(it.id, it.createdAt) }
            followTail = tail
        }
    }
    val latestReadMembers = androidx.compose.runtime.rememberUpdatedState(members)
    val readScope = remember(threadRepository, root.conversationId, root.id) {
        app.pulse.domain.sync.VisibleReadScope(threadRepository, root.conversationId) {
            latestReadMembers.value.firstOrNull { it.id == viewerId }
        }
    }
    app.pulse.feature.chat.ThreadReadReceipts(
        ownerKey = threadRepository,
        enabled = readingEnabled && available && viewOnceGrant == null && discardConfirm == null,
        connected = connected,
        root = currentRoot,
        replies = replies,
        listState = listState,
        onRead = { readScope.message(it) },
        onActive = { app.pulse.android.notify.IncomingAttention.beginReading(root.conversationId) },
    )
    LaunchedEffect(jumpMessageId, replies, available, loading, fetchedReplies) {
        if (jumpResolved || jumpMessageId == null || !available || !navigationReady || loading) return@LaunchedEffect
        val index = threadJumpIndex(replies, root, jumpMessageId, System.currentTimeMillis())
        if (index != null) {
            androidx.compose.runtime.withFrameNanos { }
            listState.scrollToItem(index)
            flashId = jumpMessageId
            jumpResolved = true
        } else if (validated && !loading && (fetchedReplies?.let {
                threadJumpIndex(it, root, jumpMessageId, System.currentTimeMillis())
            } == null || cachedReplies.any { it.id == jumpMessageId && it.isDeleted })) {
            error = "That reply is no longer available in this thread."
            jumpResolved = true
        }
    }
    LaunchedEffect(flashId) { if (flashId != null) { delay(1_700); flashId = null } }
    discardConfirm?.let { clientId ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { discardConfirm = null },
            containerColor = MirrorArt.BubbleIn,
            titleContentColor = MirrorArt.Text,
            textContentColor = MirrorArt.TextSoft,
            title = { Text("Discard this failed reply?") },
            text = { Text("This removes the saved outgoing reply from this device. Your current draft is kept.") },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { discardConfirm = null }) { Text("Cancel", color = MirrorArt.TextSoft) } },
            confirmButton = { androidx.compose.material3.TextButton(enabled = !recoveryBusy, onClick = {
                if (!recoveryBusy) {
                    recoveryBusy = true
                    discardConfirm = null
                    scope.launch {
                        try {
                            threadRepository.discardFailedThreadReply(root.conversationId, root.id, clientId).getOrThrow()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Couldn't discard this saved reply"
                        } finally { recoveryBusy = false }
                    }
                }
            }) { Text("Discard", color = MirrorArt.Accent) } },
        )
    }
    MirrorSheet(title = scopeUnread?.takeIf { it > 0 }?.let { "Thread · $it unread" } ?: "Thread", onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 430.dp)) {
            draftError?.let { Text(it, color = MirrorArt.Accent, fontSize = 12.sp) }
            error?.let { text ->
                Text(text, color = MirrorArt.Accent, fontSize = 12.sp,
                    modifier = Modifier.clickable(enabled = !loading) { retry++ }.padding(vertical = 6.dp))
            }
            if (!available) {
                Text(if (loading) "Loading thread…" else "This thread is unavailable. Your draft is saved on this device.",
                    color = MirrorArt.Faint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
            } else {
                threadWindow?.let { window ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        androidx.compose.material3.TextButton(enabled = !loading && window.hasOlder,
                            onClick = { pageDirection = -1; pageTick++ }) { Text("Earlier replies", color = MirrorArt.Accent) }
                        androidx.compose.material3.TextButton(enabled = !loading && window.hasNewer,
                            onClick = { pageDirection = 1; pageTick++ }) { Text("Later replies", color = MirrorArt.Accent) }
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false).heightIn(max = 340.dp)) {
                    item(key = "thread-parent") {
                        currentRoot?.let { parent -> MirrorThreadTextBubble(parent, parent.authorId == viewerId, onViewOnce = { openViewOnce(parent) }) }
                    }
                    replies.forEach { reply ->
                        if (reply.id == firstUnreadMessageId) item(key = "thread-unread-divider") {
                            Text("Unread replies", color = MirrorArt.Accent, fontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        }
                        item(key = reply.id) {
                        Box(Modifier.fillMaxWidth().then(if (flashId == reply.id)
                            Modifier.border(1.dp, MirrorArt.Accent, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) else Modifier)) {
                            MirrorThreadTextBubble(reply, reply.authorId == viewerId, onViewOnce = { openViewOnce(reply) })
                        }
                    }
                    }
                    if (replies.isEmpty()) item {
                        Text(if (loading) "Loading replies…" else "No replies yet - start the thread", color = MirrorArt.Faint,
                            fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                    }
                }
            }
            if (failedReplies.isNotEmpty()) {
                Text("NOT SENT · SAVED ON THIS DEVICE", color = MirrorArt.Faint, fontSize = 10.sp)
                LazyColumn(Modifier.heightIn(max = 150.dp)) {
                    items(failedReplies, key = { "failed-" + it.clientId }) { entry ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(entry.content.ifBlank { "Saved attachment" }, color = MirrorArt.TextSoft, fontSize = 12.sp, maxLines = 3)
                            Text(entry.failureReason.orEmpty(), color = MirrorArt.Faint, fontSize = 11.sp, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Retry original", color = if (available) MirrorArt.Accent else MirrorArt.Faint, fontSize = 12.sp,
                                    modifier = Modifier.clickable(enabled = available && !recoveryBusy) {
                                        if (recoveryBusy || denied || !threadRootAvailable(currentRoot, root.conversationId, root.id)) return@clickable
                                        recoveryBusy = true
                                        scope.launch {
                                            try {
                                                threadRepository.retryThreadReply(root.conversationId, root.id, entry.clientId).getOrThrow()
                                                error = null
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                error = failure.message ?: "Couldn't retry this saved reply"
                                            } finally { recoveryBusy = false }
                                        }
                                    })
                                Text("Copy text", color = MirrorArt.Accent, fontSize = 12.sp,
                                    modifier = Modifier.clickable(enabled = entry.content.isNotEmpty()) { clipboard.setText(AnnotatedString(entry.content)) })
                                Text("Discard", color = MirrorArt.Accent, fontSize = 12.sp,
                                    modifier = Modifier.clickable(enabled = !recoveryBusy) { discardConfirm = entry.clientId })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clip(CircleShape).background(MirrorArt.White7)
                .border(1.dp, MirrorArt.Hairline, CircleShape)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).imePadding(),
                verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    value = draft,
                    onValueChange = { if (it.text.length <= SendMessageUseCase.MAX_LENGTH) draft = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = MirrorArt.Text, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
                    modifier = Modifier.weight(1f), maxLines = 3,
                )
                if (draft.text.isNotBlank()) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(MirrorArt.Accent)
                        .mirrorPressClick(enabled = !sending && available, onClick = {
                            if (sending || denied || !threadRootAvailable(currentRoot, root.conversationId, root.id)) return@mirrorPressClick
                            val body = draft.text
                            val revision = composer.revision
                            val token = draftToken
                            if (token == null) { error = "Draft storage isn't ready. Try again."; return@mirrorPressClick }
                            sending = true
                            scope.launch {
                                try {
                                    SendMessageUseCase(threadRepository)(root.conversationId, body, parentId = root.id, draftToken = token).getOrThrow()
                                    composer.clearIfUnchanged(revision, notify = false)
                                    error = null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    denied = denied || threadAccessDenied((failure as? PulseApiException)?.status)
                                    error = "Reply wasn't confirmed. Check Outbox for recovery before retrying."
                                } finally { sending = false }
                            }
                        }), contentAlignment = Alignment.Center) {
                        MirrorLucideIcon("LSendHorizontal", tint = Color(0xFF20150C), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MirrorThreadTextBubble(message: Message, mine: Boolean, onViewOnce: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 250.dp)
            .mirrorBubbleShape(mine = mine, deleted = message.isDeleted)
            .mirrorBubbleBackground(mine = mine, deleted = message.isDeleted)
            .padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(threadAuthorLabel(message), color = if (mine) MirrorArt.Ink else MirrorArt.TextSoft,
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
            val canOpen = message.viewOnce && !mine && !message.isDeleted && message.viewedAt == null && message.imagePath != null
            Text(if (canOpen) "View-once photo · Tap to open" else if (message.viewOnce && message.viewedAt != null) "Photo opened" else threadMessageLabel(message),
                color = if (mine) MirrorArt.Ink else MirrorArt.Text,
                fontSize = 13.sp, lineHeight = 17.sp,
                modifier = Modifier.clickable(enabled = canOpen, onClick = onViewOnce))
        }
    }
}

/** web PollBuilderSheet chat-room.tsx:6184 - question + 2..6 options. */
@Composable
internal fun MirrorPollBuilderSheet(
    repository: PulseRepository,
    conversationId: String,
    onDismiss: () -> Unit,
    onPosted: (String) -> Unit,
) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    MirrorSheet(title = "New poll", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MirrorSheetInput(value = question, placeholder = "Ask a question", max = 160, onValue = { question = it })
            for ((index, option) in options.withIndex()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) {
                        MirrorSheetInput(
                            value = option,
                            placeholder = if (index < 2) "Option ${index + 1} (required)" else "Option ${index + 1}",
                            max = 80,
                            onValue = { v -> options = options.toMutableList().also { it[index] = v } },
                        )
                    }
                    if (options.size > 2) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .mirrorPressClick(onClick = { options = options.filterIndexed { i, _ -> i != index } }),
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon("LX", tint = MirrorArt.Dim, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            if (options.size < 6) {
                Text(
                    "+ Add another option",
                    color = MirrorArt.Accent2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { options = options + "" }
                        .padding(vertical = 4.dp),
                )
            }
            error?.let { Text(it, color = MirrorArt.Red, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(MirrorArt.Accent)
                        .mirrorPressClick(enabled = !busy, onClick = {
                            val clean = options.map { it.trim() }.filter { it.isNotEmpty() }
                            if (question.isBlank() || clean.size < 2) {
                                error = "A poll needs a question and at least two options"
                            } else {
                                busy = true
                                CoroutineScope(Dispatchers.IO).launch {
                                    val result = runCatching {
                                        repository.createPoll(conversationId, question.trim(), clean).getOrThrow()
                                    }
                                    CoroutineScope(Dispatchers.Main).launch {
                                        busy = false
                                        result.onSuccess { onPosted("Poll posted") }
                                            .onFailure { error = "Could not post the poll" }
                                    }
                                }
                            }
                        })
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text("Post poll", color = Color(0xFF20150C), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * web sticker-picker.tsx - five packs of stamp tiles; a tap sends the REAL
 * kind:'sticker' row with the { emoji, pack } payload (chat-room.tsx:3227).
 */
@Composable
internal fun MirrorStickerSheet(
    onDismiss: () -> Unit,
    onPick: (emoji: String, pack: String) -> Unit,
) {
    val packs = listOf(
        "Signal" to listOf("bolt", "flame", "sparkles", "rocket", "target", "star"),
        "Celebrate" to listOf("trophy", "crown", "gift", "cake", "music", "heart"),
        "Create" to listOf("palette", "camera", "mic", "gamepad", "brain", "drama"),
        "Nature" to listOf("leaf", "moon", "drop", "planet", "coffee", "paw"),
        "Marks" to listOf("smile", "pin", "sun", "shield", "key", "thumbsup", "thumbsdown"),
    )
    val glyphs = mapOf(
        "bolt" to "LZap", "flame" to "LFlame", "sparkles" to "LSparkles", "rocket" to "LRocket",
        "target" to "LTarget", "star" to "LStar", "trophy" to "LTrophy", "crown" to "LCrown",
        "gift" to "LGift", "cake" to "LCake", "music" to "LMusic", "heart" to "LHeart",
        "palette" to "LPalette", "camera" to "LCamera", "mic" to "LMic", "gamepad" to "LGamepad",
        "brain" to "LBrain", "drama" to "LSticker", "leaf" to "LLeaf", "moon" to "LMoon",
        "drop" to "LDroplet", "planet" to "LPlanet", "coffee" to "LCoffee", "paw" to "LPaw",
        "smile" to "LSmile", "pin" to "LPin", "sun" to "LSun", "shield" to "LShieldCheck",
        "key" to "LKey", "thumbsup" to "LThumbsUp", "thumbsdown" to "LThumbsDown",
    )
    var pack by remember { mutableStateOf(packs.first().first) }
    MirrorSheet(title = "Stamps", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((name, _) in packs) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(if (pack == name) MirrorArt.ChipActive else MirrorArt.White7)
                            .border(1.dp, if (pack == name) MirrorArt.Hairline else Color.Transparent, CircleShape)
                            .mirrorPressClick(onClick = { pack = name })
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(name, color = if (pack == name) MirrorArt.Text else MirrorArt.Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            val items = packs.first { it.first == pack }.second
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in items.chunked(4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in row) {
                            Box(
                                Modifier
                                    .size(58.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MirrorArt.White7)
                                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(14.dp))
                                    .mirrorPressClick(onClick = { onPick(item, pack) }),
                                contentAlignment = Alignment.Center,
                            ) {
                                MirrorLucideIcon(glyphs[item] ?: "LStar", tint = MirrorArt.Accent2, modifier = Modifier.size(26.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * web LocationShareSheet - sends kind:'location' {lat,lng,label} from the
 * REAL device fix (LocationManager last-known across gps/network/passive),
 * never a canned coordinate.
 */
@Composable
internal fun MirrorLocationSheet(
    onDismiss: () -> Unit,
    onSend: (lat: Double, lng: Double, label: String) -> Unit,
) {
    var fix by remember { mutableStateOf<Location?>(null) }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val last = runCatching {
            lm?.allProviders?.mapNotNull { p ->
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    lm.getLastKnownLocation(p)
                } else {
                    null
                }
            }?.maxByOrNull { it.time }
        }.getOrNull()
        if (last != null) fix = last else error = "No location fix available - enable location and retry"
    }
    MirrorSheet(title = "Share location", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (fix != null) {
                Text(
                    "Lat %.5f  -  Lng %.5f".format(fix!!.latitude, fix!!.longitude),
                    color = MirrorArt.TextSoft,
                    fontSize = 13.sp,
                )
                MirrorSheetInput(value = label, placeholder = "Label this place (optional)", max = 80, onValue = { label = it })
            }
            error?.let { Text(it, color = MirrorArt.Red, fontSize = 12.sp) }
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(MirrorArt.Accent)
                    .mirrorPressClick(enabled = fix != null, onClick = {
                        onSend(fix!!.latitude, fix!!.longitude, label.trim())
                    })
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text("Send my location", color = Color(0xFF20150C), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Message info: exact current readers plus reaction groups from the live roster.
 * The bounded lazy list preserves the existing sheet size for large groups.
 */
@Composable
internal fun MirrorMessageInfoPanel(
    message: Message,
    members: List<ConversationMember>,
    viewerId: String?,
    onDismiss: () -> Unit,
) {
    val readers = app.pulse.domain.sync.messageInfoReaders(members, viewerId, message.authorId,
        app.pulse.core.time.PulseTime.epochMs(message.createdAt), message.readByMembershipIds)
    MirrorSheet(title = "Message info", onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            item {
                Text(
                    "${if (message.id.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX)) "Queued" else "Sent"} ${MirrorRoomStamp(message.createdAt)}",
                    color = MirrorArt.TextSoft, fontSize = 12.sp,
                )
                if (message.editedAt != null) Text("Edited", color = MirrorArt.Dim, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Text("Seen by · ${readers.seenBy.size}", color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (readers.seenBy.isEmpty()) Text("No read receipts yet", color = MirrorArt.Faint, fontSize = 12.sp)
            }
            items(readers.seenBy, key = { "seen-${it.id}" }) { MirrorInfoReceiptRow(it, confirmed = true) }
            item {
                Spacer(Modifier.height(10.dp))
                Text("No read confirmation · ${readers.unconfirmed.size}", color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (readers.unconfirmed.isEmpty()) Text(
                    if (readers.seenBy.isEmpty()) "No recipient information" else "Everyone has seen this message",
                    color = MirrorArt.Faint, fontSize = 12.sp,
                )
            }
            items(readers.unconfirmed, key = { "unknown-${it.id}" }) { MirrorInfoReceiptRow(it, confirmed = false) }
            item {
                Spacer(Modifier.height(10.dp))
                Text("Reactions", color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (message.reactions.isEmpty()) Text("No reactions yet", color = MirrorArt.Faint, fontSize = 12.sp)
            }
            for ((emoji, hits) in message.reactions.groupBy { it.emoji }) {
                item(key = "reaction-$emoji") {
                    val names = hits.mapNotNull { hit -> members.firstOrNull { it.id == hit.userId }?.name }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MirrorPhosphorIcon(MirrorReactions.glyphOf(emoji), tint = MirrorArt.Accent2, modifier = Modifier.size(18.dp))
                        Column {
                            Text("${names.size} reacted", color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(names.joinToString(", ").ifBlank { "Unknown" }, color = MirrorArt.Dim,
                                fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MirrorInfoReceiptRow(member: ConversationMember, confirmed: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(member.name, color = MirrorArt.Text, fontSize = 13.sp,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (confirmed) "Read confirmed" else "No read confirmation",
            color = if (confirmed) MirrorArt.Accent2 else MirrorArt.Dim, fontSize = 11.sp)
    }
}

/** Poll bubble (web poll renderer): question, bars, per-option tallies, vote. */
@Composable
internal fun MirrorPollBubble(
    poll: PollInfo,
    mine: Boolean,
    viewerId: String,
    onVote: (optionId: String) -> Unit,
) {
    val total = poll.options.maxOf { it.voteCount }.coerceAtLeast(0)
    val sum = poll.options.sumOf { it.voteCount }.coerceAtLeast(1)
    Column {
        Text(
            poll.question,
            color = if (mine) MirrorArt.Ink else MirrorArt.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        for (option in poll.options) {
            val picked = option.votedBy.contains(viewerId)
            val fraction = option.voteCount.toFloat() / sum.toFloat()
            Column(Modifier.padding(vertical = 3.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                picked -> if (mine) Color(0x26000000) else Color(0x26FF7A3D)
                                else -> if (mine) Color(0x0D000000) else Color(0x0FFFFFFF)
                            },
                        )
                        .mirrorPressClick(enabled = !picked, onClick = { onVote(option.id) })
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option.text,
                        color = if (mine) MirrorArt.Ink else MirrorArt.Text,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${option.voteCount}",
                        color = if (mine) MirrorArt.InkSoft else MirrorArt.Dim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(if (picked) MirrorArt.Accent else MirrorArt.Hairline),
                )
            }
        }
        Text(
            "$sum votes",
            color = if (mine) MirrorArt.InkSoft else MirrorArt.Faint,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
internal fun MirrorSheetSearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(CircleShape)
            .background(MirrorArt.White7)
            .border(1.dp, MirrorArt.Hairline, CircleShape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MirrorLucideIcon("LSearch", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
        Box(Modifier.weight(1f)) {
            if (query.isBlank()) {
                Text("Search chats", color = MirrorArt.Faint, fontSize = 13.sp)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = MirrorArt.Text, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
            )
        }
    }
}

@Composable
internal fun MirrorSheetInput(value: String, placeholder: String, max: Int, onValue: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MirrorArt.White7)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isBlank()) {
                Text(placeholder, color = MirrorArt.Faint, fontSize = 13.sp)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = { if (it.length <= max) onValue(it) },
                textStyle = androidx.compose.ui.text.TextStyle(color = MirrorArt.Text, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
                maxLines = 2,
            )
        }
    }
}
