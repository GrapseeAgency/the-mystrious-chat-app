package app.pulse.android.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.pulse.core.PulseEndpoints
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.model.Message
import app.pulse.domain.model.QuickPhrase
import app.pulse.domain.model.Topic
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.VoicePeerDto
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ROOM_STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private val ROOM_DAY: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())

/**
 * Tolerant room timestamp parse: ISO-8601 first (the REST shape), then a
 * pure-digit fallback as epoch seconds / epoch millis (the relay shape).
 * Without the fallback a relay-shaped stamp fails Instant.parse, which used
 * to blank its cluster stamp and day label and leave invisible dead boxes
 * between bubbles. Returns null only when nothing can be read.
 */
internal fun MirrorRoomInstant(iso: String): Instant? {
    if (iso.isBlank()) return null
    val asIso = runCatching { Instant.parse(iso) }.getOrNull()
    if (asIso != null) return asIso
    val trimmed = iso.trim()
    if (trimmed.isNotEmpty() && trimmed.all { it.isDigit() }) {
        val n = trimmed.toLongOrNull() ?: return null
        return if (trimmed.length >= 13) Instant.ofEpochMilli(n) else Instant.ofEpochSecond(n)
    }
    return null
}

/** web formatDayChip: Today / Yesterday / "3 Aug" (web pulse-utils parity). */
internal fun MirrorRoomDayLabel(iso: String): String {
    val d = MirrorRoomInstant(iso)?.atZone(ZoneId.systemDefault()) ?: return ""
    val today = java.time.ZonedDateTime.now(ZoneId.systemDefault()).toLocalDate()
    val day = d.toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> ROOM_DAY.format(d)
    }
}

internal fun MirrorRoomEpoch(iso: String): Long =
    MirrorRoomInstant(iso)?.toEpochMilli() ?: 0L

private fun MirrorSameDayIso(aIso: String, bIso: String): Boolean {
    val zone = ZoneId.systemDefault()
    val a = MirrorRoomInstant(aIso)?.atZone(zone)?.toLocalDate() ?: return false
    val b = MirrorRoomInstant(bIso)?.atZone(zone)?.toLocalDate() ?: return false
    return a == b
}

/** web CLUSTER_WINDOW_MS = 5 minutes (chat-room.tsx:277). */
private const val CLUSTER_WINDOW_MINUTES = 5L

private sealed interface RoomEntry {
    data class DayLabel(val label: String, val key: String) : RoomEntry
    data class Stamp(val iso: String, val key: String) : RoomEntry
    data class Msg(val message: Message, val head: Boolean) : RoomEntry
}

private fun buildRoomEntries(messages: List<Message>): List<RoomEntry> {
    data class Flat(val message: Message, val head: Boolean)
    val built = mutableListOf<Flat>()
    var prev: Message? = null
    for (m in messages) {
        val p = prev
        val head = p == null ||
            !MirrorSameDayIso(p.createdAt, m.createdAt) ||
            p.authorId != m.authorId ||
            MirrorGapMinutes(p.createdAt, m.createdAt) > CLUSTER_WINDOW_MINUTES
        built.add(Flat(m, head))
        prev = m
    }
    val out = mutableListOf<RoomEntry>()
    var dayIso: String? = null
    for ((i, e) in built.withIndex()) {
        if (dayIso == null || !MirrorSameDayIso(dayIso, e.message.createdAt)) {
            val label = MirrorRoomDayLabel(e.message.createdAt)
            // A blank label (timestamp that failed to parse) must NEVER become
            // an invisible fillMaxWidth box - 39dp of dead air between bubbles
            // (the dead band the user circled). Skip it; the next valid row
            // re-opens the day group on its own.
            if (label.isNotEmpty()) {
                out.add(RoomEntry.DayLabel(label, "day-${e.message.id}-$i"))
            }
            dayIso = e.message.createdAt
        }
        if (e.head) {
            // Same guard for the cluster stamp: a blank HH:mm means the iso
            // did not parse - an invisible 23dp box, part of the same dead band.
            val stamp = MirrorRoomTimeLabel(e.message.createdAt)
            if (stamp.isNotEmpty()) {
                out.add(RoomEntry.Stamp(e.message.createdAt, "stamp-${e.message.id}"))
            }
        }
        out.add(RoomEntry.Msg(e.message, e.head))
    }
    return out
}

/** Grouped reaction chips (emoji, count, iReacted) in first-seen order. */
private data class ReactionChip(val emoji: String, val count: Int, val iReacted: Boolean)

private fun groupReactions(message: Message, viewerId: String): List<ReactionChip> {
    if (message.reactions.isEmpty()) return emptyList()
    val order = mutableListOf<String>()
    val counts = LinkedHashMap<String, Int>()
    val mine = HashSet<String>()
    for (r in message.reactions) {
        if (!counts.containsKey(r.emoji)) {
            order.add(r.emoji)
            counts[r.emoji] = 0
        }
        counts[r.emoji] = counts[r.emoji]!! + 1
        if (r.userId == viewerId) mine.add(r.emoji)
    }
    return order.map { e -> ReactionChip(e, counts[e] ?: 0, e in mine) }
}

/**
 * R63 - the chat room as a 1:1 conversion of the web chat-room.tsx render:
 * art-scene canvas, 56dp header (back / 40dp avatar / title+subtitle / three
 * 44dp bare icons), px-3 list with day chips + centered per-cluster "HH:mm"
 * stamps, the R54-c bubble anatomy (24dp sender avatar column on group heads,
 * sender name INSIDE the bubble, 18px radius with the tight tail corner, max
 * 78% width, mentions + links + markdown runs, meta row with the read ticks,
 * overlapping reaction chips, read-by avatars under the last own message),
 * photo bubbles (Coil over {gateway}/api/uploads), the quick-phrase glass rail
 * (tap inserts, pencil manages server rows), and the art-input-pill composer
 * with the 44dp dark-glass FAB that becomes the send action.
 */
@Composable
internal fun MirrorRoom(
    title: String,
    color: String?,
    isGroup: Boolean,
    groupId: String,
    subtitle: String,
    typers: List<String>,
    messages: List<Message>,
    viewerId: String,
    viewerName: String,
    repository: PulseRepository,
    viewerColor: String?,
    memberNames: List<String>,
    members: List<ConversationMember>,
    phrases: List<QuickPhrase>,
    muted: Boolean,
    ttlSeconds: Int,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSendImage: (String) -> Unit,
    onSendDocument: (String, String) -> Unit,
    onToggleReaction: (String, String) -> Unit,
    onAddPhrase: (String) -> Unit,
    onDeletePhrase: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onCall: (video: Boolean) -> Unit,
    onRoomInfo: () -> Unit,
    onManageGroup: () -> Unit,
    onMuteChoice: (String?) -> Unit,
    onTtlChoice: (Int) -> Unit,
    onUnpinMessage: (String) -> Unit,
    fetchPinned: suspend () -> List<Message>,
    pipActive: Boolean,
    onTogglePip: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var composerFocused by remember { mutableStateOf(false) }
    var phraseManagerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var trayOpen by remember { mutableStateOf(false) }
    var roomSearchOpen by remember { mutableStateOf(false) }
    var pinnedOpen by remember { mutableStateOf(false) }
    var muteStripOpen by remember { mutableStateOf(false) }
    var ttlStripOpen by remember { mutableStateOf(false) }
    // R69 - the web Conversation menu full set (chat-room.tsx L4096-4454):
    // every row opens the same real surface the web opens.
    var remindersOpen by remember { mutableStateOf(false) }
    var scheduledOpen by remember { mutableStateOf(false) }
    var recapOpen by remember { mutableStateOf(false) }
    var eventsOpen by remember { mutableStateOf(false) }
    var kanbanOpen by remember { mutableStateOf(false) }
    var whiteboardOpen by remember { mutableStateOf(false) }
    var voiceOpen by remember { mutableStateOf(false) }
    var stageOpen by remember { mutableStateOf(false) }
    var spaceOpen by remember { mutableStateOf(false) }
    var tournamentOpen by remember { mutableStateOf(false) }
    var topicsVisible by remember { mutableStateOf(false) }
    var activeTopicId by remember { mutableStateOf<String?>(null) }
    // menu badges, fetched live each time the menu opens (web queries)
    var pinnedCount by remember { mutableStateOf(0) }
    var upcomingReminders by remember { mutableStateOf(0) }
    // live voice roster for the Voice room pill (web voice.inRoom / roster.length)
    var voiceRoster by remember { mutableStateOf(emptyList<VoicePeerDto>()) }
    val voiceInRoom = voiceRoster.any { it.userId == viewerId }

    LaunchedEffect(groupId) {
        repository.events().collect { event ->
            if (event is PulseEvent.VoiceRoster && event.payload.conversationId == groupId) {
                voiceRoster = event.payload.roster
            }
        }
    }
    LaunchedEffect(menuOpen) {
        if (menuOpen) {
            pinnedCount = fetchPinned().size
            repository.reminders(false).onSuccess { page ->
                val now = System.currentTimeMillis()
                upcomingReminders = page.items.count { item ->
                    item.firedAt == null && item.remindAt != null &&
                        runCatching { Instant.parse(item.remindAt).toEpochMilli() > now }
                            .getOrDefault(false)
                }
            }
        }
    }
    // topic rail data: real observeTopics + a server refetch per topic switch
    val topics by remember(groupId) { repository.observeTopics(groupId) }
        .collectAsState(initial = emptyList())
    LaunchedEffect(topicsVisible) {
        if (topicsVisible) runCatching { repository.refreshTopics(groupId) }
    }
    LaunchedEffect(activeTopicId) {
        runCatching { repository.refreshMessages(groupId, activeTopicId) }
    }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val entries = remember(messages) { buildRoomEntries(messages) }
    // R64 - the web typing label: DM = "typing…", groups roll names
    // (chat-room.tsx typerLabel verbatim) and it WINS over the subtitle.
    val typerLabel = when {
        typers.isEmpty() -> ""
        !isGroup -> "typing…"
        typers.size == 1 -> "${typers[0]} is typing…"
        typers.size == 2 -> "${typers[0]} and ${typers[1]} are typing…"
        else -> "${typers.size} people are typing…"
    }
    // R64 - typing pump: throttled to one emit per 1.5s while typing
    // (web signalTyping), cancelled on send / blur / draft clear.
    var lastTypingSentAt by remember { mutableStateOf(0L) }
    var typingActive by remember { mutableStateOf(false) }
    fun pumpTyping(text: String) {
        val now = System.currentTimeMillis()
        if (text.isNotBlank()) {
            if (!typingActive || now - lastTypingSentAt >= 1_500L) {
                typingActive = true
                lastTypingSentAt = now
                onTyping(true)
            }
        } else if (typingActive) {
            typingActive = false
            lastTypingSentAt = 0L
            onTyping(false)
        }
    }
    fun stopTyping() {
        if (typingActive) {
            typingActive = false
            lastTypingSentAt = 0L
            onTyping(false)
        }
    }

    LaunchedEffect(messages.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }
    // Leaving the room always cancels the typing signal (web unmount parity).
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { stopTyping() }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val dataUrl = mirrorUriToDataUrl(context, uri)
                if (dataUrl != null) onSendImage(dataUrl)
            }
        }
    }
    // R64 - attachments tray document pick: any file → base64 data URL → upload.
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val doc = mirrorUriToDocumentDataUrl(context, uri)
                if (doc != null) onSendDocument(doc.first, doc.second)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Header: min-h-14, bg #0d0906/70, hairline bottom, px-1.5, gap-0.5
        Column(Modifier.background(Color(0xB30D0906))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back: size-11 ghost, ChevronLeft size-6, text-soft
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LChevronLeft", tint = MirrorArt.TextSoft, modifier = Modifier.size(24.dp))
                }
                // ONE tap target: 40dp avatar + title + subtitle → room info
                Row(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .clickable(onClick = onRoomInfo)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorAvatar(
                        name = title,
                        color = color,
                        isGroup = isGroup,
                        groupId = groupId,
                        online = false,
                        showPresence = !isGroup,
                        sizeDp = 40,
                        cornerDp = 20,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            color = MirrorArt.Text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.2).sp,
                            lineHeight = 19.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // web: the typing label wins and renders italic accent-2
                        Text(
                            if (typerLabel.isNotEmpty()) typerLabel else subtitle,
                            color = if (typerLabel.isNotEmpty()) MirrorArt.Accent2 else MirrorArt.Dim,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            fontWeight = if (typerLabel.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
                            fontStyle = if (typerLabel.isNotEmpty()) FontStyle.Italic else FontStyle.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // EXACTLY three bare icons: video, audio, kebab (44dp ghosts, size-5)
                MirrorRoomHeaderIcon("LVideo") { onCall(true) }
                MirrorRoomHeaderIcon("LPhone") { onCall(false) }
                MirrorRoomHeaderIcon("LKebab") { menuOpen = !menuOpen }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MirrorArt.Hairline),
            )
        }

        // R69 - the web TopicBar (topic-bar.tsx): Zulip-style chip rail the
        // kebab Topics row toggles. General is the implicit whole-room chip.
        if (isGroup && topicsVisible) {
            MirrorTopicRail(
                topics = topics,
                activeTopicId = activeTopicId,
                onSelect = { topicId -> activeTopicId = topicId },
                onCreate = { name ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val created = runCatching { repository.createTopic(groupId, name).getOrNull() }.getOrNull()
                        if (created != null) {
                            runCatching { repository.refreshTopics(groupId) }
                            withContext(Dispatchers.Main) { activeTopicId = created.id }
                        }
                    }
                },
            )
        }

        // List viewport: px-3 pt-3 pb-2. The web paints the wallpaper veil on
        // the scroll container (chat-room.tsx:4538): top + bottom soft glows
        // and a 16px dot grid over the art-scene - the mirror draws the same
        // three layers behind the bubbles.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    // top glow: radial-gradient(ellipse 90% 34% at 50% -8%, rgba(245,158,11,0.055), transparent 62%)
                    sceneGlow(
                        core = Color(0x0EF59E0B),
                        cx = w / 2f,
                        cy = -0.08f * h,
                        rx = 0.90f * w,
                        ry = 0.34f * h,
                        fadeStop = 0.62f,
                    )
                    // bottom glow: radial-gradient(ellipse 110% 40% at 50% 110%, rgba(20,184,166,0.04), transparent 62%)
                    sceneGlow(
                        core = Color(0x0A14B8A6),
                        cx = w / 2f,
                        cy = 1.10f * h,
                        rx = 1.10f * w,
                        ry = 0.40f * h,
                        fadeStop = 0.62f,
                    )
                    // dot grid: radial-gradient(circle, rgba(255,255,255,0.055) 1px, transparent 1px) @ 16px
                    val step = 16.dp.toPx()
                    var gx = step / 2f
                    while (gx < w) {
                        var gy = step / 2f
                        while (gy < h) {
                            drawCircle(color = Color(0x0EFFFFFF), radius = 1f, center = Offset(gx, gy))
                            gy += step
                        }
                        gx += step
                    }
                },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp,
                top = 12.dp,
                end = 12.dp,
                bottom = 8.dp,
            ),
        ) {
            itemsIndexed(entries, key = { _, e ->
                when (e) {
                    is RoomEntry.DayLabel -> e.key
                    is RoomEntry.Stamp -> e.key
                    is RoomEntry.Msg -> e.message.id
                }
            }) { _, entry ->
                when (entry) {
                    is RoomEntry.DayLabel -> {
                        // day chip: my-3 centered, 11px medium tracking-wide faint
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(
                                entry.label,
                                color = MirrorArt.Faint,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.3.sp,
                            )
                        }
                    }
                    is RoomEntry.Stamp -> {
                        // R54-c: centered "08:16" above each cluster head, my-1
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                            Text(
                                MirrorRoomTimeLabel(entry.iso),
                                color = MirrorArt.Faint,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    is RoomEntry.Msg -> {
                        val mine = entry.message.authorId == viewerId
                        val lastOwnId = messages.lastOrNull { it.authorId == viewerId && it.deletedAt == null }?.id
                        val showReadBy = mine && isGroup &&
                            entry.message.id == lastOwnId && entry.message.deletedAt == null
                        val created = MirrorRoomEpoch(entry.message.createdAt)
                        val readers = members.filter {
                            it.id != viewerId && (it.lastReadAt ?: 0L) >= created
                        }
                        MirrorBubbleRow(
                            message = entry.message,
                            mine = mine,
                            head = entry.head,
                            isGroup = isGroup,
                            viewerId = viewerId,
                            viewerName = viewerName,
                            memberNames = memberNames,
                            othersCount = members.count { it.id != viewerId },
                            readers = readers,
                            showReadBy = showReadBy,
                            onToggleReaction = onToggleReaction,
                        )
                    }
                }
            }
            // R64 - the web typing indicator: 24dp avatar + art-bubble-in pill
            // whose borderRadius breathes 1.25rem → 0.875rem → 1.25rem (0.72s
            // loop) around three squash-and-stretch dots. Shows ONLY when
            // someone else is typing (web chat-room.tsx:4684).
            if (typers.isNotEmpty()) {
                item(key = "room-typing-bubble") {
                    Row(
                        Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val typerName = typers.first()
                        val typerColor = members.firstOrNull { it.name == typerName }?.color
                        MirrorAvatar(
                            name = typerName,
                            color = typerColor,
                            isGroup = false,
                            groupId = "",
                            online = false,
                            showPresence = false,
                            sizeDp = 24,
                            cornerDp = 12,
                        )
                        val breathe = rememberInfiniteTransition(label = "bubbleBreathe")
                        val radius by breathe.animateFloat(
                            initialValue = 20f,
                            targetValue = 20f,
                            animationSpec = infiniteRepeatable(
                                animation = keyframes {
                                    durationMillis = 720
                                    20f at 0
                                    14f at 360
                                    20f at 720
                                },
                            ),
                            label = "bubbleRadius",
                        )
                        Box(
                            Modifier
                                .graphicsLayer { alpha = 1f }
                                .clip(RoundedCornerShape(radius.dp))
                                .background(MirrorArt.BubbleIn)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            MirrorRoomTypingDots()
                        }
                    }
                }
            }
        }

        // F-MS-29 quick phrases rail: glass chips ABOVE the composer; tap
        // INSERTS into the draft; the 28dp pencil opens the manage sheet.
        if (phrases.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (phrase in phrases.take(6)) {
                    Box(
                        Modifier
                            .widthIn(max = 220.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.White7)
                            .border(1.dp, MirrorArt.Hairline, CircleShape)
                            .clickable { draft = if (draft.isBlank()) phrase.text else "$draft ${phrase.text}" }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            phrase.text,
                            color = MirrorArt.TextSoft,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MirrorArt.White7)
                        .border(1.dp, MirrorArt.Hairline, CircleShape)
                        .clickable { phraseManagerOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorPhosphorIcon("PEdit", tint = MirrorArt.Dim, modifier = Modifier.size(14.dp))
                }
            }
        }

        // R54-c composer row: ONE art-input-pill (paperclip / Type here /
        // camera) with the 44dp dark-glass art-fab OUTSIDE on the right.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(MirrorArt.White7)
                    .border(1.dp, MirrorArt.Hairline, CircleShape)
                    // web focus hairline: ring-2 ring-inset ring-accent/45 over
                    // the WHOLE pill (R64 fix - the old drawCircle painted a
                    // floating circle INSIDE the pill instead of the inset ring).
                    .drawBehind {
                        if (composerFocused) {
                            drawRoundRect(
                                color = Color(0x73FF7A3D), // accent /45
                                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // paperclip: size-9 circle, size-5 dim glyph - toggles the tray
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (trayOpen) MirrorArt.White10 else Color.Transparent)
                        .clickable { trayOpen = !trayOpen },
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LPaperclip", tint = if (trayOpen) MirrorArt.Text else MirrorArt.Dim, modifier = Modifier.size(20.dp))
                }
                Box(Modifier.weight(1f).padding(bottom = 8.dp)) {
                    if (draft.isBlank()) {
                        Text("Type here", color = MirrorArt.Faint, fontSize = 15.sp, lineHeight = 20.sp)
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            pumpTyping(it)
                        },
                        textStyle = TextStyle(
                            color = MirrorArt.Text,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                composerFocused = it.isFocused
                                if (!it.isFocused) stopTyping()
                            },
                        maxLines = 5,
                    )
                }
                // camera: size-9 circle, size-5 dim glyph - the artboard photo button
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LCamera", tint = MirrorArt.Dim, modifier = Modifier.size(20.dp))
                }
            }
            // art-fab: 44dp DARK GLASS always - plus (opens the tray) becomes
            // the send plane the moment the draft holds text; rotates 45°
            // while the tray is open (web parity).
            val hasText = draft.isNotBlank()
            Box(
                Modifier
                    .size(44.dp)
                    .graphicsLayer { rotationZ = if (!hasText && trayOpen) 45f else 0f }
                    .clip(CircleShape)
                    .background(MirrorArt.White7)
                    .border(1.dp, MirrorArt.Hairline, CircleShape)
                    .clickable {
                        if (hasText) {
                            stopTyping()
                            onSend(draft)
                            draft = ""
                            trayOpen = false
                        } else {
                            trayOpen = !trayOpen
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (hasText) {
                    MirrorLucideIcon("LSendHorizontal", tint = MirrorArt.Text, modifier = Modifier.size(20.dp))
                } else {
                    MirrorLucideIcon("LPlus", tint = MirrorArt.Text, modifier = Modifier.size(22.dp))
                }
            }
        }
    }

    // R64 - the attachments tray: web CREATE grid subset that is REAL here
    // (photo pick, document pick + upload, quick-phrase manager). No dead tiles.
    if (trayOpen) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(onClick = { trayOpen = false }),
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xF21C1610))
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(20.dp))
                    .padding(10.dp)
                    .clickable(enabled = false) {},
            ) {
                Text(
                    "CREATE",
                    color = MirrorArt.Faint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MirrorTrayTile("LImagePlus", "Photo", "Camera roll or gallery") {
                        trayOpen = false
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    MirrorTrayTile("LFile", "Document", "PDF, TXT, CSV, ZIP") {
                        trayOpen = false
                        pickDocument.launch("*/*")
                    }
                    MirrorTrayTile("LMessageCircle", "Quick phrase", "Save lines you send often") {
                        trayOpen = false
                        phraseManagerOpen = true
                    }
                }
            }
        }
    }

    // R64 - the room kebab: the web Conversation menu (chat-room.tsx R54-c)
    // with every row wired to a real action. Dark ember dropdown, scale-in.
    if (menuOpen) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(onClick = { menuOpen = false }),
        ) {
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 60.dp, end = 8.dp)
                    .widthIn(min = 224.dp)
                    // web: max-h-[min(72vh,520px)] overflow-y-auto - 18 rows
                    // must scroll, never clip
                    .heightIn(max = minOf(LocalConfiguration.current.screenHeightDp * 0.72f, 520f).dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xF21C1610))
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp)
                    .clickable(enabled = false) {},
            ) {
                MirrorRoomMenuItem("LInfo", "Room info") {
                    menuOpen = false
                    onRoomInfo()
                }
                MirrorRoomMenuItem("LSearch", "Search in conversation") {
                    menuOpen = false
                    roomSearchOpen = true
                }
                // relocated header bell - upcoming count rides along
                MirrorRoomMenuItem(
                    "LBell",
                    "Reminders",
                    pillText = if (upcomingReminders > 0) (if (upcomingReminders > 9) "9+" else upcomingReminders.toString()) else "",
                    pillStyle = "accent",
                ) {
                    menuOpen = false
                    remindersOpen = true
                }
                MirrorRoomMenuItem(
                    "LPin",
                    "Pinned messages",
                    iconRotate = 45f,
                    pillText = if (pinnedCount > 0) pinnedCount.toString() else "",
                    pillStyle = "neutral",
                ) {
                    menuOpen = false
                    pinnedOpen = true
                }
                // relocated header mic - the live voice room
                MirrorRoomMenuItem(
                    "LMic",
                    "Voice room",
                    labelColor = if (voiceInRoom) MirrorArt.Accent2 else MirrorArt.Text,
                    pillText = if (voiceInRoom) "${voiceRoster.size} live" else "",
                    pillStyle = "accentSoft",
                ) {
                    menuOpen = false
                    voiceOpen = true
                }
                // relocated header PiP - mini chat window
                MirrorRoomMenuItem("LPictureInPicture2", if (pipActive) "Close mini chat window" else "Mini chat window") {
                    menuOpen = false
                    onTogglePip()
                }
                if (isGroup) {
                    MirrorRoomMenuItem(
                        "LMessagesSquare",
                        "Topics",
                        pillText = if (topicsVisible) "Shown" else "Hidden",
                        pillStyle = if (topicsVisible) "accentSoft" else "neutral",
                    ) {
                        topicsVisible = !topicsVisible
                    }
                }
                MirrorRoomMenuItem("LCalendarClock", "Scheduled sends") {
                    menuOpen = false
                    scheduledOpen = true
                }
                if (muteStripOpen) {
                    MirrorMenuStrip("Mute for", listOf("8h", "1w", "Always")) { choice ->
                        muteStripOpen = false
                        menuOpen = false
                        onMuteChoice(when (choice) {
                            "8h" -> "8h"
                            "1w" -> "1w"
                            else -> "always"
                        })
                    }
                } else {
                    MirrorRoomMenuItem(
                        if (muted) "LVolumeX" else "LBellOff",
                        if (muted) "Unmute notifications" else "Mute notifications",
                    ) {
                        if (muted) {
                            menuOpen = false
                            onMuteChoice(null)
                        } else {
                            muteStripOpen = true
                        }
                    }
                }
                if (ttlStripOpen) {
                    MirrorMenuStrip("New messages vanish after", listOf("Off", "24h", "7d", "30d")) { choice ->
                        ttlStripOpen = false
                        menuOpen = false
                        onTtlChoice(when (choice) {
                            "24h" -> 86_400
                            "7d" -> 604_800
                            "30d" -> 2_592_000
                            else -> 0
                        })
                    }
                } else {
                    MirrorRoomMenuItem(
                        "LTimer",
                        "Disappearing messages",
                        iconColor = if (ttlSeconds > 0) MirrorArt.Accent2 else MirrorArt.Dim,
                        pillText = if (ttlSeconds > 0) {
                            when (ttlSeconds) { 86_400 -> "24h"; 604_800 -> "7d"; else -> "30d" }
                        } else "",
                        pillStyle = "accentUp",
                    ) {
                        ttlStripOpen = true
                    }
                }
                // R34-b: AI recap - real LLM summary of the recent chat
                MirrorRoomMenuItem("LSparkles", "Recap with AI") {
                    menuOpen = false
                    recapOpen = true
                }
                MirrorRoomMenuItem("LUserPlus", if (isGroup) "Manage group" else "Manage chat") {
                    menuOpen = false
                    // web parity: groups open the classic manager (webhooks,
                    // leaderboard, roles); DMs ride the info page
                    if (isGroup) onManageGroup() else onRoomInfo()
                }
                // room tools - the same surfaces the composer tray opens on the web
                MirrorRoomMenuLabel("Tools")
                MirrorRoomMenuItem("LCalendarDays", "Events") {
                    menuOpen = false
                    eventsOpen = true
                }
                MirrorRoomMenuItem("LPresentation", "Whiteboard") {
                    menuOpen = false
                    whiteboardOpen = true
                }
                MirrorRoomMenuItem("LSquareKanban", "Kanban") {
                    menuOpen = false
                    kanbanOpen = true
                }
                MirrorRoomMenuItem("LPodcast", "Stage") {
                    menuOpen = false
                    stageOpen = true
                }
                MirrorRoomMenuItem("LMap", "Space") {
                    menuOpen = false
                    spaceOpen = true
                }
                MirrorRoomMenuItem(
                    "LTrophy",
                    "Tournament",
                    enabled = isGroup,
                ) {
                    if (isGroup) {
                        menuOpen = false
                        tournamentOpen = true
                    }
                }
            }
        }
    }

    // R64 - search in conversation: real filter over the cached room messages.
    if (roomSearchOpen) {
        MirrorRoomSearchSheet(messages = messages, onDismiss = { roomSearchOpen = false })
    }
    // R64 - pinned messages: live server list with unpin.
    if (pinnedOpen) {
        MirrorPinnedSheet(
            fetchPinned = fetchPinned,
            onUnpin = onUnpinMessage,
            onDismiss = { pinnedOpen = false },
        )
    }

    // R69 - the Conversation menu surfaces: the same real sheets the web opens
    if (remindersOpen) {
        MirrorRemindersSheet(repository, viewerId, groupId) { remindersOpen = false }
    }
    if (scheduledOpen) {
        MirrorScheduledSheet(repository, groupId) { scheduledOpen = false }
    }
    if (recapOpen) {
        MirrorRecapSheet(repository, groupId) { recapOpen = false }
    }
    if (eventsOpen) {
        MirrorEventsSheet(repository, groupId, viewerId) { eventsOpen = false }
    }
    if (kanbanOpen) {
        MirrorKanbanSheet(repository, groupId, viewerId) { kanbanOpen = false }
    }
    if (whiteboardOpen) {
        MirrorWhiteboardSheet(repository, groupId, viewerId) { whiteboardOpen = false }
    }
    if (voiceOpen) {
        MirrorVoiceRoomSheet(repository, groupId, viewerId, viewerName, viewerColor) { voiceOpen = false }
    }
    if (stageOpen) {
        MirrorStageSheet(repository, groupId, viewerId, viewerName, viewerColor) { stageOpen = false }
    }
    if (spaceOpen) {
        MirrorSpaceSheet(repository, groupId, viewerId, viewerName, viewerColor) { spaceOpen = false }
    }
    if (tournamentOpen) {
        MirrorTournamentSheet(repository, groupId, viewerId) { tournamentOpen = false }
    }

    if (phraseManagerOpen) {
        MirrorPhraseManager(
            phrases = phrases,
            onAdd = onAddPhrase,
            onDelete = onDeletePhrase,
            onDismiss = { phraseManagerOpen = false },
        )
    }
}

/** One attachments-tray tile (web CREATE grid tile anatomy). */
@Composable
private fun MirrorTrayTile(icon: String, label: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier
            .widthIn(max = 108.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MirrorArt.White7)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MirrorLucideIcon(icon, tint = MirrorArt.Accent2, modifier = Modifier.size(18.dp))
        Column {
            Text(label, color = MirrorArt.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(hint, color = MirrorArt.Faint, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Web ROOM_MENU_LABEL: px-3 pt-2.5 pb-1 10px bold uppercase tracking-widest faint. */
@Composable
private fun MirrorRoomMenuLabel(text: String) {
    Text(
        text.uppercase(),
        color = MirrorArt.Faint,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
    )
}

/** Web trailing pill: rounded-full 10px bold, neutral white/8 or accent variants. */
@Composable
private fun MirrorRoomMenuPill(text: String, style: String) {
    val (bg, fg) = when (style) {
        "accent" -> MirrorArt.Accent to Color(0xFFFFFFFF)
        "accentSoft" -> MirrorArt.Accent2.copy(alpha = 0.2f) to MirrorArt.Accent2
        "accentUp" -> MirrorArt.Accent2.copy(alpha = 0.15f) to MirrorArt.Accent2
        else -> Color.White.copy(alpha = 0.08f) to MirrorArt.TextSoft
    }
    Text(
        if (style == "accentUp") text.uppercase() else text,
        color = fg,
        fontSize = if (style == "accent") 9.sp else 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Room kebab row (web ROOM_MENU_ITEM): 16dp accent-2 icon + 13.5px medium. */
@Composable
private fun MirrorRoomMenuItem(
    icon: String,
    label: String,
    iconRotate: Float = 0f,
    iconColor: Color = MirrorArt.Accent2,
    labelColor: Color = MirrorArt.Text,
    enabled: Boolean = true,
    pillText: String = "",
    pillStyle: String = "neutral",
    onClick: () -> Unit,
) {
    // web ROOM_MENU_ITEM active:bg-white/[0.10] - pressed tint, never a ripple
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MirrorLucideIcon(
            icon,
            tint = iconColor,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = iconRotate },
        )
        Text(
            label,
            color = labelColor,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (pillText.isNotEmpty() && enabled) {
            MirrorRoomMenuPill(pillText, pillStyle)
        }
    }
}

/** Inline choice strip inside the menu (web mute-for / ttl presets). */
@Composable
private fun MirrorMenuStrip(label: String, choices: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(
            label,
            color = MirrorArt.Faint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (choice in choices) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MirrorArt.White7)
                        .clickable { onPick(choice) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(choice, color = MirrorArt.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Search-in-conversation sheet: real filter over the room's cached messages. */
@Composable
private fun MirrorRoomSearchSheet(messages: List<Message>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val hits = remember(query, messages) {
        if (query.isBlank()) emptyList()
        else messages.filter { it.body.contains(query, ignoreCase = true) }.take(40)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .padding(10.dp)
                .clickable(enabled = false) {},
        ) {
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
                        Text("Search this conversation…", color = MirrorArt.Faint, fontSize = 14.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = MirrorArt.Text, fontSize = 14.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .padding(top = 8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (query.isNotBlank() && hits.isEmpty()) {
                    Text("No matches in this conversation", color = MirrorArt.Faint, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                }
                for (hit in hits) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(
                            hit.body,
                            color = MirrorArt.TextSoft,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            MirrorRoomStamp(hit.createdAt),
                            color = MirrorArt.Faint,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Pinned messages sheet: the live server pins with unpin actions. */
@Composable
private fun MirrorPinnedSheet(
    fetchPinned: suspend () -> List<Message>,
    onUnpin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pinned by remember { mutableStateOf<List<Message>?>(null) }
    LaunchedEffect(Unit) {
        pinned = runCatching { fetchPinned() }.getOrDefault(emptyList())
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .padding(10.dp)
                .clickable(enabled = false) {},
        ) {
            Text(
                "PINNED MESSAGES",
                color = MirrorArt.Faint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            val list = pinned
            when {
                list == null -> Text("Loading pins…", color = MirrorArt.Faint, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                list.isEmpty() -> Text("Nothing pinned yet", color = MirrorArt.Faint, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                else -> Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (message in list) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    message.body.ifBlank { "Photo" },
                                    color = MirrorArt.TextSoft,
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(MirrorRoomStamp(message.createdAt), color = MirrorArt.Faint, fontSize = 10.sp)
                            }
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable { onUnpin(message.id) },
                                contentAlignment = Alignment.Center,
                            ) {
                                MirrorLucideIcon("LPinOff", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Room typing dots: 6dp squash-and-stretch (web TypingDots verbatim). */
@Composable
private fun MirrorRoomTypingDots() {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 0..2) {
            val transition = rememberInfiniteTransition(label = "roomTyper$i")
            val y by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 920
                        0f at 0
                        -4f at 350
                        -4f at 550
                        0f at 800
                    },
                    initialStartOffset = StartOffset(i * 140),
                ),
                label = "roomTyperY$i",
            )
            val scaleY by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 920
                        1f at 0
                        1.55f at 350
                        1.35f at 550
                        0.7f at 800
                        1f at 920
                    },
                    initialStartOffset = StartOffset(i * 140),
                ),
                label = "roomTyperSY$i",
            )
            val scaleX by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 920
                        1f at 0
                        0.8f at 350
                        0.9f at 550
                        1.2f at 800
                        1f at 920
                    },
                    initialStartOffset = StartOffset(i * 140),
                ),
                label = "roomTyperSX$i",
            )
            val alpha by transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 0.45f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 920
                        0.45f at 0
                        1f at 350
                        0.95f at 550
                        0.5f at 800
                        0.45f at 920
                    },
                    initialStartOffset = StartOffset(i * 140),
                ),
                label = "roomTyperA$i",
            )
            Box(
                Modifier
                    .offset(y = y.dp)
                    .size(6.dp)
                    .graphicsLayer {
                        this.scaleY = scaleY
                        this.scaleX = scaleX
                        this.alpha = alpha
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
                    .clip(CircleShape)
                    .background(Color(0xFFA1A1AA)),
            )
        }
    }
}

private fun MirrorRoomStamp(iso: String): String =
    runCatching { ROOM_STAMP.format(Instant.parse(iso)) }.getOrDefault("")

@Composable
private fun MirrorRoomHeaderIcon(glyph: String, onClick: () -> Unit = {}) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MirrorLucideIcon(glyph, tint = MirrorArt.TextSoft, modifier = Modifier.size(20.dp))
    }
}

/** F-MS-29 manage sheet: the web popover's rows (list / X delete / add form). */
@Composable
private fun MirrorPhraseManager(
    phrases: List<QuickPhrase>,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .padding(10.dp)
                .clickable(enabled = false) {},
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "QUICK PHRASES",
                    color = MirrorArt.Faint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${phrases.size}/12",
                    color = MirrorArt.Faint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(Modifier.heightIn(max = 208.dp)) {
                for (phrase in phrases) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            phrase.text,
                            color = MirrorArt.TextSoft,
                            fontSize = 12.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { onDelete(phrase.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon("LX", tint = MirrorArt.Faint, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                if (phrases.isEmpty()) {
                    Text(
                        "No phrases yet - save the lines you send often, then tap them above the composer.",
                        color = MirrorArt.Faint,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(CircleShape)
                        .background(MirrorArt.White7)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isBlank()) {
                        Text("Add a phrase…", color = MirrorArt.Faint, fontSize = 12.5.sp)
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = TextStyle(color = MirrorArt.Text, fontSize = 12.5.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                    )
                }
                Box(
                    Modifier
                        .height(32.dp)
                        .clip(CircleShape)
                        .background(MirrorArt.Accent)
                        .clickable {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                onAdd(text)
                                draft = ""
                            }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Save", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** One message row - the full R54-c web anatomy. */
@Composable
private fun MirrorBubbleRow(
    message: Message,
    mine: Boolean,
    head: Boolean,
    isGroup: Boolean,
    viewerId: String,
    viewerName: String,
    memberNames: List<String>,
    othersCount: Int,
    readers: List<ConversationMember>,
    showReadBy: Boolean,
    onToggleReaction: (String, String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (head) 10.dp else 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // incoming group rows: 24dp avatar on the head (bottom-aligned with
        // pb-5), a 24dp+6dp spacer on continuation rows (web parity)
        if (!mine && isGroup) {
            if (head) {
                Box(Modifier.padding(start = 0.dp, end = 6.dp, bottom = 20.dp)) {
                    MirrorAvatar(
                        name = message.authorName,
                        color = message.senderColor,
                        isGroup = false,
                        groupId = "",
                        online = false,
                        showPresence = false,
                        sizeDp = 24,
                        cornerDp = 12,
                    )
                }
            } else {
                Spacer(Modifier.width(30.dp))
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val bubbleMax = maxWidth * 0.78f
            val reactionChips = groupReactions(message, viewerId)
            val mentionsMe = mirrorMentionsViewer(message.body, viewerName) && message.deletedAt == null
            val isImage = message.kind == Message.Kind.IMAGE && message.imagePath != null && message.deletedAt == null
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                // bubble block
                Column(
                    Modifier
                        .widthIn(max = bubbleMax)
                        .mirrorBubbleShape(mine, message.deletedAt != null)
                        .mirrorBubbleBackground(mine, message.deletedAt != null)
                        .then(if (mentionsMe) Modifier.border(2.dp, Color(0xB3FFAB5E), mirrorBubbleShapeOf(mine, deleted = false)) else Modifier)
                        .padding(
                            horizontal = if (isImage) 4.dp else 12.dp,
                            vertical = if (isImage) 4.dp else 8.dp,
                        ),
                ) {
                    if (message.deletedAt != null) {
                        Text(
                            "This message was deleted",
                            color = MirrorArt.Faint,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            fontStyle = FontStyle.Italic,
                        )
                    } else {
                        // R54-c: sender name is the FIRST LINE INSIDE the
                        // incoming group bubble
                        if (!mine && isGroup && head) {
                            Text(
                                message.authorName,
                                color = MirrorArt.TextSoft,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                        if (message.replyToBody != null || message.replyToAuthor != null) {
                            MirrorReplyQuote(
                                author = message.replyToAuthor.orEmpty().ifBlank { "Unknown" },
                                body = message.replyToBody.orEmpty().replace(Regex("\\s+"), " ").take(120),
                                mine = mine,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        if (isImage) {
                            AsyncImage(
                                model = PulseEndpoints.http("/api/uploads/${message.imagePath}"),
                                contentDescription = "Shared photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .widthIn(max = 240.dp)
                                    .heightIn(max = 300.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            if (message.body.isNotBlank()) {
                                Text(
                                    mirrorAnnotated(message.body, mine, memberNames),
                                    color = if (mine) MirrorArt.Ink else MirrorArt.Text,
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp, bottom = 2.dp),
                                )
                            }
                        } else {
                            Text(
                                mirrorAnnotated(message.body, mine, memberNames),
                                color = if (mine) MirrorArt.Ink else MirrorArt.Text,
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                            )
                        }
                        // meta row: mine && !deleted always renders (web 7795)
                        if (mine) {
                            val read = readers.isNotEmpty() || MirrorRoomEpoch(message.createdAt) <= 0
                            Row(
                                Modifier
                                    .align(Alignment.End)
                                    .padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (message.id.startsWith("temp-")) {
                                    MirrorLucideIcon("LClock", tint = MirrorArt.InkFaint, modifier = Modifier.size(12.dp))
                                } else if (read) {
                                    MirrorLucideIcon("LCheckCheck", tint = MirrorArt.Ink, modifier = Modifier.size(14.dp))
                                } else {
                                    MirrorLucideIcon("LCheck", tint = MirrorArt.InkFaint, modifier = Modifier.size(12.dp))
                                }
                            }
                        } else if (message.pinnedAt != null || message.editedAt != null || message.expiresAtEpochMs != null) {
                            Row(
                                Modifier.align(Alignment.End).padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (message.pinnedAt != null) {
                                    MirrorLucideIcon("LPin", tint = MirrorArt.Faint, modifier = Modifier.size(12.dp))
                                }
                                if (message.editedAt != null) {
                                    Text("edited", color = MirrorArt.Faint, fontSize = 10.sp, fontStyle = FontStyle.Italic)
                                }
                            }
                        }
                    }
                }
                // reactions: -mt-1.5 overlap, gap-1, chips hug the bubble side
                if (reactionChips.isNotEmpty() && message.deletedAt == null) {
                    Row(
                        Modifier
                            .offset(y = (-6).dp)
                            .padding(end = if (mine) 8.dp else 0.dp, start = if (!mine) 8.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (chip in reactionChips) {
                            Row(
                                Modifier
                                    .clip(CircleShape)
                                    .background(if (chip.iReacted) Color(0xE61A120B) else Color(0xCC1A120B))
                                    .border(
                                        1.dp,
                                        if (chip.iReacted) Color(0x73FF7A3D) else MirrorArt.Hairline,
                                        CircleShape,
                                    )
                                    .clickable { onToggleReaction(message.id, chip.emoji) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(chip.emoji, fontSize = 12.sp, lineHeight = 14.sp)
                                if (chip.count > 1) {
                                    Text(
                                        chip.count.toString(),
                                        color = if (chip.iReacted) MirrorArt.Accent2 else MirrorArt.TextSoft,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
                // read-by row under the LAST own group message
                if (showReadBy && readers.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = 2.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (readers.size >= othersCount) "Seen" else "Read by ${readers.size}",
                            color = MirrorArt.Faint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                            for (r in readers.take(5)) {
                                Box(
                                    Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFF292019))
                                        .padding(1.dp),
                                ) {
                                    MirrorAvatar(
                                        name = r.name,
                                        color = r.color,
                                        isGroup = false,
                                        groupId = "",
                                        online = false,
                                        showPresence = false,
                                        sizeDp = 14,
                                        cornerDp = 7,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Reply quote inside a bubble: 3dp LEFT accent strip, name + truncated body. */
@Composable
private fun MirrorReplyQuote(author: String, body: String, mine: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .background(if (mine) Color(0x0D000000) else Color(0x0FFFFFFF)),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxSize()
                .background(if (mine) Color(0x26000000) else MirrorArt.Accent),
        )
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                author,
                color = if (mine) MirrorArt.Ink else MirrorArt.Accent2,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 14.sp,
            )
            Text(
                body,
                color = if (mine) MirrorArt.InkSoft else MirrorArt.Dim,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Bubble shape+background: art-bubble-out/in radii (18/18/6/18 vs 18/18/18/6). */
private fun mirrorBubbleShapeOf(mine: Boolean, deleted: Boolean) =
    if (deleted) {
        RoundedCornerShape(16.dp)
    } else if (mine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    }

private fun Modifier.mirrorBubbleShape(mine: Boolean, deleted: Boolean): Modifier =
    this.clip(mirrorBubbleShapeOf(mine, deleted))

private fun Modifier.mirrorBubbleBackground(mine: Boolean, deleted: Boolean): Modifier =
    when {
        deleted -> this.drawBehind {
            drawRoundRect(
                color = MirrorArt.Hairline,
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                ),
                cornerRadius = CornerRadius(16.dp.toPx()),
            )
        }
        mine -> this.background(MirrorArt.BubbleOut)
        else -> this.background(MirrorArt.BubbleIn)
    }

/** Does the body mention the viewer (@Name, case-insensitive)? */
internal fun mirrorMentionsViewer(body: String, viewerName: String): Boolean {
    if (body.isBlank() || viewerName.isBlank()) return false
    var idx = body.indexOf('@')
    val needle = viewerName.trim().lowercase()
    while (idx >= 0) {
        val rest = body.substring(idx + 1).lowercase()
        if (rest.startsWith(needle)) {
            val end = idx + 1 + needle.length
            val next = if (end < body.length) body[end] else ' '
            if (!next.isLetterOrDigit()) return true
        }
        idx = body.indexOf('@', idx + 1)
    }
    return false
}

/** Longest-first member mention lookup. */
private fun mentionAt(body: String, start: Int, memberNames: List<String>): String? {
    val rest = body.substring(start)
    var best: String? = null
    for (name in memberNames) {
        val n = name.trim()
        if (n.isEmpty()) continue
        if (rest.length >= n.length && rest.regionMatches(0, n, 0, n.length, ignoreCase = true)) {
            val end = start + n.length
            val next = if (end < body.length) body[end] else ' '
            if (!next.isLetterOrDigit()) {
                if (best == null || n.length > best!!.length) best = n
            }
        }
    }
    return best
}

private val MIRROR_LINK = Regex("""https?://[^\s<]+|www\.[^\s<]+""")
private val MIRROR_FORMAT = Regex(
    "\\*\\*([^*\\n]+)\\*\\*" +          // 1 bold **x**
        "|__([^_\\n]+)__" +              // 2 underline __x__
        "|~~([^~\\n]+)~~" +              // 3 strike ~~x~~
        "|\\|\\|([^|\\n]+)\\|\\|" +      // 4 spoiler ||x||
        "|`([^`\\n]+)`" +                // 5 code `x`
        "|(?<![\\w*])\\*([^*\\n]+)\\*(?!\\*)" + // 6 italic *x*
        "|(?<![\\w_])_([^_\\n]+)_(?!_)", // 7 italic _x_
)

/**
 * web BubbleText: mention spans, links, bold/underline/strike/spoiler/code/
 * italic runs - 15sp, ink on own bubbles, art-text on incoming.
 */
internal fun mirrorAnnotated(content: String, mine: Boolean, memberNames: List<String>): AnnotatedString =
    buildAnnotatedString {
        val mentionBg = if (mine) Color(0x1A9A4E06) else Color(0x26FFAB5E)
        val mentionFg = if (mine) Color(0xFF9A4E06) else Color(0xFFFFAB5E)
        val linkFg = if (mine) Color(0xFF9A4E06) else Color(0xFFFFAB5E)
        val bodyColor = if (mine) MirrorArt.Ink else MirrorArt.Text
        val codeBg = if (mine) Color(0x12000000) else Color(0x4D000000)

        var i = 0
        while (i < content.length) {
            if (content[i] == '@') {
                val mention = mentionAt(content, i + 1, memberNames)
                if (mention != null) {
                    pushStyle(SpanStyle(background = mentionBg, color = mentionFg, fontWeight = FontWeight.SemiBold))
                    append("@")
                    append(content.substring(i + 1, i + 1 + mention.length))
                    pop()
                    i += 1 + mention.length
                    continue
                }
            }
            // format markers
            val rest = content.substring(i)
            val fmtMatch = MIRROR_FORMAT.find(rest)
            val linkMatch = MIRROR_LINK.find(rest)
            val earliest = listOfNotNull(
                fmtMatch?.range?.first?.let { it to ('f') },
                linkMatch?.range?.first?.let { it to ('l') },
            ).minByOrNull { it.first }
            when (earliest?.second) {
                'f' -> {
                    val m = fmtMatch!!
                    if (m.range.first > 0) appendPlainWithLinks(rest.substring(0, m.range.first), mine, bodyColor, linkFg)
                    val inner = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
                    when {
                        m.groupValues[1].isNotEmpty() -> {
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = bodyColor))
                            append(inner)
                            pop()
                        }
                        m.groupValues[2].isNotEmpty() -> {
                            pushStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = bodyColor))
                            append(inner)
                            pop()
                        }
                        m.groupValues[3].isNotEmpty() -> {
                            pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = bodyColor.copy(alpha = 0.8f)))
                            append(inner)
                            pop()
                        }
                        m.groupValues[4].isNotEmpty() -> {
                            pushStyle(SpanStyle(background = MirrorArt.White10, color = bodyColor.copy(alpha = 0.35f)))
                            append(inner)
                            pop()
                        }
                        m.groupValues[5].isNotEmpty() -> {
                            pushStyle(
                                SpanStyle(
                                    background = codeBg,
                                    color = bodyColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp,
                                ),
                            )
                            append(inner)
                            pop()
                        }
                        else -> {
                            pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = bodyColor))
                            append(inner)
                            pop()
                        }
                    }
                    i += m.range.last + 1
                }
                'l' -> {
                    val m = linkMatch!!
                    if (m.range.first > 0) appendPlainWithLinks(rest.substring(0, m.range.first), mine, bodyColor, linkFg)
                    pushStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = linkFg))
                    append(m.value)
                    pop()
                    i += m.value.length
                }
                else -> {
                    appendPlainWithLinks(rest, mine, bodyColor, linkFg)
                    i = content.length
                }
            }
        }
    }

private fun AnnotatedString.Builder.appendPlainWithLinks(text: String, mine: Boolean, bodyColor: Color, linkFg: Color) {
    var last = 0
    for (m in MIRROR_LINK.findAll(text)) {
        if (m.range.first > last) {
            pushStyle(SpanStyle(color = bodyColor))
            append(text.substring(last, m.range.first))
            pop()
        }
        pushStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = linkFg))
        append(m.value)
        pop()
        last = m.range.last + 1
    }
    if (last < text.length) {
        pushStyle(SpanStyle(color = bodyColor))
        append(text.substring(last))
        pop()
    }
}

/** Pick → downscale (max 1280px) → JPEG 82 → data URL (web compressImageToDataUrl parity). */
private suspend fun mirrorUriToDataUrl(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / (sample * 2) >= 1280) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return@runCatching null
        val scaled = if (maxOf(bitmap.width, bitmap.height) > 1280) {
            val scale = 1280f / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()
}

/**
 * R64 - document pick → base64 data URL + display name (attachments tray
 * Document tile). Reads ANY file the picker returns; the upload route is
 * mime-agnostic so PDF/TXT/CSV/ZIP all ride the same POST /api/uploads.
 */
private suspend fun mirrorUriToDocumentDataUrl(context: Context, uri: Uri): Pair<String, String>? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            if (bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) return@runCatching null
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = uri.lastPathSegment?.substringAfterLast('/')?.take(120) ?: "document"
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP) to name
        }.getOrNull()
    }

/** HH:mm cluster stamp (en-US h23 parity - "08:16"). */
internal fun MirrorRoomTimeLabel(iso: String): String {
    val parsed = MirrorRoomInstant(iso) ?: return ""
    return ROOM_STAMP.format(parsed)
}

/** Whole minutes between two ISO stamps (null-safe, gaps drive clusters). */
internal fun MirrorGapMinutes(prevIso: String?, nextIso: String?): Long {
    val a = prevIso?.let { MirrorRoomInstant(it) } ?: return Long.MAX_VALUE
    val b = nextIso?.let { MirrorRoomInstant(it) } ?: return Long.MAX_VALUE
    return kotlin.math.abs(java.time.Duration.between(a, b).toMinutes())
}

/**
 * R69 - the web TopicBar (topic-bar.tsx): Zulip-style topic chip rail.
 * "General" is the implicit whole-room chip (topicId null), real Topic rows
 * render as glass chips with a live filed-message count, and the "+" chip
 * opens a small inline composer wired to the real createTopic API.
 */
@Composable
private fun MirrorTopicRail(
    topics: List<Topic>,
    activeTopicId: String?,
    onSelect: (String?) -> Unit,
    onCreate: (String) -> Unit,
) {
    var createOpen by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    Column(Modifier.background(Color(0xCC18181B))) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MirrorTopicChip(
                label = "General",
                count = -1,
                active = activeTopicId == null,
                onClick = { onSelect(null) },
            )
            for (topic in topics) {
                MirrorTopicChip(
                    label = topic.name,
                    count = topic.messageCount,
                    active = topic.id == activeTopicId,
                    onClick = { onSelect(topic.id) },
                )
            }
            // "+" chip: the web's inline create composer
            Box(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(50))
                    .background(Color(0x9918181B))
                    .clickable { createOpen = !createOpen }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LPlus", tint = MirrorArt.TextSoft, modifier = Modifier.size(14.dp))
            }
        }
        if (createOpen) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MirrorArt.Text, fontSize = 12.5.sp),
                    cursorBrush = SolidColor(MirrorArt.Accent2),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MirrorArt.White7)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    decorationBox = { inner ->
                        if (nameDraft.isBlank()) {
                            Text("Topic name", color = MirrorArt.Faint, fontSize = 12.5.sp)
                        }
                        inner()
                    },
                )
                Text(
                    "Add",
                    color = MirrorArt.Accent2,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = nameDraft.isNotBlank()) {
                            onCreate(nameDraft.trim())
                            nameDraft = ""
                            createOpen = false
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MirrorArt.Hairline),
        )
    }
}

/** One topic chip (web h-9 rounded-full px-3 12.5px semibold, amber active). */
@Composable
private fun MirrorTopicChip(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (active) {
                    Modifier.background(Color(0xFFF59E0B))
                } else {
                    Modifier
                        .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(50))
                        .background(Color(0x9918181B))
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (active) Color(0xFFFFFFFF) else MirrorArt.TextSoft,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
        if (count > 0) {
            Text(
                count.toString(),
                color = if (active) Color(0xE6FFFFFF) else MirrorArt.Faint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
