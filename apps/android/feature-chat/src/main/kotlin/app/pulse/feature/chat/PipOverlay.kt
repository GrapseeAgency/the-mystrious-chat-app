package app.pulse.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.pulse.core.time.PulseTime
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.usecase.SendMessageUseCase
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette
import app.pulse.ui.pulseGlass
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
// R1-W2I — PiP pane overlay (F-PI-01..03). Mirror of the web
// src/components/chat/pip-chat.tsx (PipWindow + PipChat container) and
// pip-stack.tsx (collapsed pill stack):
//   · one expanded draggable card (≤ PIP_MAX_PANES live panes, oldest
//     auto-evicts — store ops above)
//   · drag repositions with a rubber-band frame clamp (top 64 / bottom
//     92 / margin 10 — web pip-store.ts reserves) and a magnetic settle
//     to the nearest horizontal edge + momentum fling (web settle())
//   · tap on the header opens the conversation in the main shell
//     (web pulse:open-conversation dispatch — here: the nav route)
//   · collapsed panes render as 48dp glass pills on the right edge:
//     avatar + unread badge (9+) + close X, tap → focusPane
//   · data = the SHARED repo caches (conversations + per-pane messages)
//     — web "no second socket, no extra polling" rule
// Mounted once at the app root, above the NavHost + dock (MainActivity).
// ─────────────────────────────────────────────────────────────

/** Result of one mini-pane composer send (honest toast/restore routing). */
sealed interface PipSendResult {
    /** Delivered row (server ack). */
    data object Delivered : PipSendResult
    /** Network-class failure → queued in the outbox, flushes when back online. */
    data object Queued : PipSendResult
    /** Hard failure (slow mode / validation / API) → restore the draft. */
    data class Failed(val message: String) : PipSendResult
}

/** Frame geometry reserves — web pip-store.ts constants, dp on Android. */
internal val PIP_MARGIN_X: Dp = 10.dp
internal val PIP_TOP_RESERVE: Dp = 64.dp
internal val PIP_BOTTOM_RESERVE: Dp = 92.dp
internal val PIP_STACK_PILL: Dp = 48.dp
internal val PIP_STACK_GAP: Dp = 8.dp
internal val PIP_PANE_MIN_W: Dp = 220.dp
internal val PIP_PANE_MAX_W: Dp = 276.dp
internal val PIP_PANE_MIN_H: Dp = 300.dp
internal val PIP_PANE_MAX_H: Dp = 400.dp

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PipOverlayViewModel @Inject constructor(
    private val repo: PulseRepository,
    private val sendUseCase: SendMessageUseCase,
    /** The process-singleton pane store (web usePipChat). */
    val pip: PulsePiPStore,
) : ViewModel() {

    /** Live pane state — the overlay renders from this only. */
    val state: StateFlow<PiPState> = pip.state

    /**
     * Shared conversation cache (Room, socket-refreshed by the repo's
     * debounced inbox refetch) — the pane cards read the same stream the
     * chats list does (web: the same TanStack caches the main room uses).
     */
    val conversations: StateFlow<List<Conversation>> = repo.observeConversations()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Per-pane message cache — feeds pane-local unread + the seen marker. */
    private val paneMessages: StateFlow<Map<String, List<Message>>> = state
        .map { s -> s.panes.map { it.conversationId }.distinct() }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(ids.map { id -> repo.observeMessages(id).map { id to it } }) { pairs ->
                    pairs.toMap()
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Pane-local unread per conversation — web usePaneUnread formula:
     * incoming (not mine, not pending, not deleted) messages that arrived
     * after the pane was last the focused window.
     */
    val unread: StateFlow<Map<String, Int>> = combine(state, paneMessages) { s, msgs ->
        val viewer = repo.viewerId
        s.panes.associate { p ->
            p.conversationId to msgs[p.conversationId].orEmpty().count { m ->
                m.authorId != viewer &&
                    !m.id.startsWith(TEMP_MESSAGE_PREFIX) &&
                    m.deletedAt == null &&
                    PulseTime.epochMs(m.createdAt) > p.lastSeenAt
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // Seed a freshly opened pane's message cache — web fetchQuery
        // limit=30 parity (one REST pull per conversation, fire-and-forget;
        // incoming socket rows still land via the shared envelope upsert).
        viewModelScope.launch {
            state
                .map { s -> s.panes.map { it.conversationId } }
                .distinctUntilChanged()
                .collect { ids -> ids.forEach(::seedOnce) }
        }
        // The expanded window is the reading surface — incoming history is
        // seen (web onSeen on message-list change; the store's 1s guard
        // caps the writes).
        viewModelScope.launch {
            paneMessages.collect { msgs ->
                val s = state.value
                val focused = s.conversationId ?: return@collect
                if (s.panes.none { it.conversationId == focused && !it.minimized }) return@collect
                if (msgs[focused].orEmpty().isNotEmpty()) {
                    pip.markSeen(focused, System.currentTimeMillis())
                }
            }
        }
    }

    private val seeded = mutableSetOf<String>()

    private fun seedOnce(conversationId: String) {
        if (!seeded.add(conversationId)) return
        viewModelScope.launch { runCatching { repo.refreshMessages(conversationId, limit = 30) } }
    }

    fun focusPane(conversationId: String) = pip.focusPane(conversationId)

    fun closePane(conversationId: String) = pip.closePane(conversationId)

    fun setPanePosition(conversationId: String, nx: Float, ny: Float) =
        pip.setPanePosition(conversationId, nx, ny)

    /**
     * The mini-pane composer rides the SAME send use case as the room —
     * optimistic echo + outbox queue on network-class failure (web pip send
     * hits the same POST /messages as the main composer).
     */
    fun sendFromPane(conversationId: String, body: String, onResult: (PipSendResult) -> Unit) {
        viewModelScope.launch {
            sendUseCase(conversationId, body)
                .onSuccess { receipt ->
                    if (receipt.message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                        onResult(PipSendResult.Queued)
                    } else {
                        onResult(PipSendResult.Delivered)
                    }
                }
                .onFailure { onResult(PipSendResult.Failed(it.message ?: "Could not send")) }
        }
    }
}

/**
 * The floating pane manager — mounted once in the shell Box. Renders the
 * focused card plus the compact pill stack for every collapsed pane. The
 * wrapper Box stays hit-test-transparent (no background, no pointerInput)
 * so only the panes themselves consume touches.
 */
@Composable
fun PipPaneOverlay(
    viewModel: PipOverlayViewModel,
    viewerId: String?,
    dark: Boolean,
    reducedMotion: Boolean,
    onOpenRoom: (conversationId: String) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val unread by viewModel.unread.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    // The Android dock (108dp + system nav) is taller than web's ~76px
    // capsule — the effective bottom reserve honors whichever is larger so
    // panes never collide with the dock (web bottom reserve = 92).
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomReserve = maxOf(PIP_BOTTOM_RESERVE, 108.dp + navBottom)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val frameW = maxWidth
        val frameH = maxHeight
        if (frameW < 120.dp || frameH < 260.dp) return@BoxWithConstraints

        // web geo: paneW clamp 220..276, paneH = frame.h * 0.52 clamp 300..400;
        // a degenerate frame (pane can't clear header + dock) → no window.
        val paneW = minOf(PIP_PANE_MAX_W, maxOf(PIP_PANE_MIN_W, frameW - 24.dp))
        val paneH = minOf(PIP_PANE_MAX_H, maxOf(PIP_PANE_MIN_H, frameH * 0.52f))
        val boxBottom = frameH - bottomReserve
        if (boxBottom - PIP_TOP_RESERVE < paneH + 24.dp) return@BoxWithConstraints

        val minX = PIP_MARGIN_X
        val maxX = frameW - PIP_MARGIN_X - paneW
        val minY = PIP_TOP_RESERVE
        val maxY = boxBottom - paneH

        val focused = state.panes.firstOrNull { it.conversationId == state.conversationId && !it.minimized }
        val stacked = state.panes.filter { it.conversationId != state.conversationId || it.minimized }

        // the band the pill stack occupies (right edge, above the dock) —
        // right-edge snaps keep the card clear of it (web stackBand)
        val stackBand: Pair<Dp, Dp>? = if (stacked.isEmpty()) {
            null
        } else {
            val bandBottom = frameH - (bottomReserve + 8.dp)
            val bandHeight = stacked.size * PIP_STACK_PILL + (stacked.size - 1) * PIP_STACK_GAP
            Pair(bandBottom - bandHeight, bandBottom)
        }

        focused?.let { pane ->
            val conv = conversations.firstOrNull { it.id == pane.conversationId }
            PipExpandedPane(
                pane = pane,
                conversation = conv,
                unreadCount = unread[pane.conversationId] ?: 0,
                dark = dark,
                reducedMotion = reducedMotion,
                paneW = paneW,
                paneH = paneH,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                frameW = frameW,
                stackBand = stackBand,
                onSettle = viewModel::setPanePosition,
                onOpen = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenRoom(pane.conversationId)
                },
                onMinimize = { viewModel.pip.minimize() },
                onClose = { viewModel.closePane(pane.conversationId) },
                onSend = { text ->
                    viewModel.sendFromPane(pane.conversationId, text) { result ->
                        when (result) {
                            is PipSendResult.Failed -> onNotice("Could not send from the mini chat")
                            // the pane has no queued bubble — the notice is the
                            // honest receipt for an outbox-queued mini send
                            PipSendResult.Queued -> onNotice("Message queued — sends when you're back online")
                            PipSendResult.Delivered -> Unit
                        }
                    }
                },
            )
        }

        if (stacked.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = PIP_MARGIN_X, bottom = bottomReserve + 8.dp),
                verticalArrangement = Arrangement.spacedBy(PIP_STACK_GAP),
                horizontalAlignment = Alignment.End,
            ) {
                stacked.forEach { pane ->
                    val conv = conversations.firstOrNull { it.id == pane.conversationId }
                    PipStackPill(
                        title = conv?.title ?: "Mini chat",
                        accentColor = conv?.accentColor,
                        isGroup = conv?.isGroupish == true,
                        unreadCount = unread[pane.conversationId] ?: 0,
                        dark = dark,
                        onExpand = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.focusPane(pane.conversationId)
                        },
                        onClose = { viewModel.closePane(pane.conversationId) },
                    )
                }
            }
        }
    }
}

/**
 * The single expanded (focused) pane — a draggable glass card. Tap the
 * header → open the conversation in the main shell; drag → reposition with
 * edge settle. Content is condensed: name + latest message preview + a
 * one-line composer through the room's own send path.
 */
@Composable
private fun PipExpandedPane(
    pane: PipPane,
    conversation: Conversation?,
    unreadCount: Int,
    dark: Boolean,
    reducedMotion: Boolean,
    paneW: Dp,
    paneH: Dp,
    minX: Dp,
    maxX: Dp,
    minY: Dp,
    maxY: Dp,
    frameW: Dp,
    stackBand: Pair<Dp, Dp>?,
    onSettle: (conversationId: String, nx: Float, ny: Float) -> Unit,
    onOpen: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var paneDraft by remember(pane.conversationId) { mutableStateOf("") }
    var dragging by remember { mutableStateOf(false) }
    var velocity by remember { mutableStateOf(Offset.Zero) }
    var lastDragMs by remember { mutableStateOf(0L) }
    val shape = RoundedCornerShape(16.dp)

    // normalized → px top-left, re-derived whenever the frame or the stored
    // position changes (web useLayoutEffect on frameKey). Seeded directly so
    // the card never flashes at the origin.
    val seed = with(density) {
        Offset(
            (minX + (maxX - minX) * pane.nx).toPx(),
            (minY + (maxY - minY) * pane.ny).toPx(),
        )
    }
    val position = remember(pane.conversationId, pane.nx, pane.ny, minX, maxX, minY, maxY) {
        Animatable(seed, Offset.VectorConverter)
    }

    fun settle() {
        val paneWpx = with(density) { paneW.toPx() }
        val paneHpx = with(density) { paneH.toPx() }
        val minXpx = with(density) { minX.toPx() }
        val maxXpx = with(density) { maxX.toPx() }
        val minYpx = with(density) { minY.toPx() }
        val maxYpx = with(density) { maxY.toPx() }
        val frameWpx = with(density) { frameW.toPx() }
        val bandTopPx = stackBand?.let { with(density) { it.first.toPx() } }
        val bandBottomPx = stackBand?.let { with(density) { it.second.toPx() } }

        // web settle(): momentum projection (0.14 px per px/s), magnetic snap
        // to the nearest horizontal edge, Y clamped inside the frame, and the
        // right-edge berth kept clear for the pill stack.
        val power = 0.14f
        val projX = position.value.x + velocity.x * power
        val projY = position.value.y + velocity.y * power
        val tx = if (projX + paneWpx / 2f < frameWpx / 2f) minXpx else maxXpx
        var ty = projY.coerceIn(minYpx, maxYpx)
        if (bandTopPx != null && bandBottomPx != null && tx == maxXpx &&
            ty < bandBottomPx && ty + paneHpx > bandTopPx
        ) {
            val above = bandTopPx - paneHpx - with(density) { 6.dp.toPx() }
            ty = if (above >= minYpx) above else (bandBottomPx + with(density) { 6.dp.toPx() }).coerceAtMost(maxYpx)
            ty = ty.coerceIn(minYpx, maxYpx)
        }
        val rangeX = max(1f, maxXpx - minXpx)
        val rangeY = max(1f, maxYpx - minYpx)
        val nx = ((tx - minXpx) / rangeX).coerceIn(0f, 1f)
        val ny = ((ty - minYpx) / rangeY).coerceIn(0f, 1f)
        velocity = Offset.Zero
        scope.launch {
            if (reducedMotion) {
                position.snapTo(Offset(tx, ty))
            } else {
                // web settleSpring: stiffness 340, damping 30 → dampingRatio ≈ 0.81
                position.animateTo(Offset(tx, ty), spring(stiffness = 340f, dampingRatio = 0.81f))
            }
            // persist AFTER landing so the remember seed never resets mid-flight
            onSettle(pane.conversationId, nx, ny)
        }
    }

    Box(
        Modifier
            .offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
            .size(paneW, paneH)
            .graphicsLayer {
                val s = if (dragging) 1.03f else 1f // web whileDrag scale 1.03
                scaleX = s
                scaleY = s
            }
            .shadow(elevation = if (dragging) 22.dp else 10.dp, shape = shape)
            .pulseGlass(dark, shape, deep = true),
    ) {
        Column(Modifier.fillMaxSize()) {
            // header — the drag handle + tap-to-open target
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { onOpen() }
                    .pointerInput(pane.conversationId) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                lastDragMs = 0L
                                velocity = Offset.Zero
                            },
                            onDragEnd = {
                                dragging = false
                                settle()
                            },
                            onDragCancel = {
                                dragging = false
                                settle()
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            val dt = (change.uptimeMillis - lastDragMs).coerceAtLeast(1L).toFloat()
                            velocity = if (lastDragMs == 0L) {
                                Offset.Zero
                            } else {
                                // smoothed px/s estimate for the settle fling
                                Offset(
                                    velocity.x + (dragAmount.x / dt * 1000f - velocity.x) * 0.5f,
                                    velocity.y + (dragAmount.y / dt * 1000f - velocity.y) * 0.5f,
                                )
                            }
                            lastDragMs = change.uptimeMillis
                            scope.launch { position.snapTo(position.value + dragAmount) }
                        }
                    }
                    .padding(start = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseAvatar(
                    name = conversation?.title ?: "Mini chat",
                    colorHex = conversation?.accentColor,
                    size = 30.dp,
                    isGroup = conversation?.isGroupish == true,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    conversation?.title ?: "Mini chat",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unreadCount > 0) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(PulsePalette.Emerald))
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onMinimize, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Minimize mini chat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close mini chat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)))

            // condensed body — latest message preview (web PipBubble subset)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (conversation == null) {
                    Text(
                        "Loading…",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val authorName = conversation.lastMessageAuthorName
                    if (!conversation.lastMessageMine && authorName != null) {
                        Text(
                            authorName,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = PulsePalette.Emerald,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        when {
                            conversation.lastMessageDeleted -> "deleted"
                            conversation.lastMessagePreview.isNullOrBlank() -> "No messages here yet — say hi from the mini chat."
                            else -> conversation.lastMessagePreview.orEmpty()
                        },
                        fontSize = 12.sp,
                        color = if (conversation.lastMessageDeleted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        lineHeight = 16.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // compact composer — one line through the room's own send path
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val canSend = paneDraft.isNotBlank()
                BasicTextField(
                    value = paneDraft,
                    onValueChange = { if (it.length <= 2000) paneDraft = it },
                    modifier = Modifier.weight(1f).heightIn(min = 34.dp),
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(PulsePalette.Emerald),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val text = paneDraft.trim()
                            if (text.isNotEmpty()) {
                                paneDraft = ""
                                onSend(text)
                            }
                        },
                    ),
                    maxLines = 3,
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(999.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            if (paneDraft.isEmpty()) {
                                Text(
                                    "Message…",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (canSend) PulsePalette.Emerald else PulsePalette.Emerald.copy(alpha = 0.4f))
                        .clickable(enabled = canSend) {
                            val text = paneDraft.trim()
                            if (text.isNotEmpty()) {
                                paneDraft = ""
                                onSend(text)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Send message",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Collapsed pane pill — web pip-stack.tsx PipStackPill: 48dp glass circle,
 * avatar, emerald unread badge (9+) top-left, close X top-right,
 * tap → focusPane (expand + demote the rest).
 */
@Composable
private fun PipStackPill(
    title: String,
    accentColor: String?,
    isGroup: Boolean,
    unreadCount: Int,
    dark: Boolean,
    onExpand: () -> Unit,
    onClose: () -> Unit,
) {
    Box {
        Box(
            Modifier
                .size(PIP_STACK_PILL)
                .pulseGlass(dark, CircleShape)
                .clickable { onExpand() },
            contentAlignment = Alignment.Center,
        ) {
            PulseAvatar(name = title, colorHex = accentColor, size = 36.dp, isGroup = isGroup)
        }
        if (unreadCount > 0) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-4).dp, y = (-4).dp)
                    .sizeIn(minWidth = 20.dp, minHeight = 20.dp)
                    .clip(CircleShape)
                    .background(PulsePalette.Emerald)
                    .border(
                        2.dp,
                        if (dark) Color(0xFF18181B) else Color.White,
                        CircleShape,
                    )
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unreadCount > 9) "9+" else "$unreadCount",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (dark) Color(0xFF27272A) else Color.White)
                .border(
                    1.dp,
                    if (dark) Color(0xFF3F3F46) else Color(0xFFE4E4E7),
                    CircleShape,
                )
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close $title mini chat",
                tint = if (dark) Color(0xFFD4D4D8) else Color(0xFF71717A),
                modifier = Modifier.size(11.dp),
            )
        }
    }
}
