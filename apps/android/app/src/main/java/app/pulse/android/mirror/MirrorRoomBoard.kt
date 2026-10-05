package app.pulse.android.mirror

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.EventRsvpDto
import app.pulse.protocol.GroupEventDto
import app.pulse.protocol.KanbanCardDto
import app.pulse.protocol.WhiteboardStrokePostDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// R69-b - the three web group-collaboration sheets, mirrored natively:
//   events-sheet.tsx     -> MirrorEventsSheet (schedule / RSVP / check-in / delete)
//   kanban-sheet.tsx     -> MirrorKanbanSheet (todo / doing / done board)
//   whiteboard-sheet.tsx -> MirrorWhiteboardSheet (shared drawing canvas)
// Every number below is REAL via PulseRepository (events / kanban / whiteboard
// gateway endpoints), polled on the web's exact cadences while the sheet is
// open. User-facing strings are copied verbatim from the web sheet sources.
// Icon note: MirrorIconPaths does not ship Trash2 / Undo2 / ChevronDown /
// CalendarPlus / PenLine glyphs, so the nearest shipped lucide twins are used
// (LX / LArrowLeft / LArrowDown / LCalendarDays / LPencilLine).
// Token note: the web sheets use the zinc + amber palette; the mirror maps it
// onto the artboard tokens per MirrorTheme (amber -> Accent / Accent2,
// rose -> Red, zinc text tiers -> Text / TextSoft / Dim / Faint).

private const val EVENTS_POLL_MS = 5_000L
private const val KANBAN_POLL_MS = 1_500L
private const val WHITEBOARD_POLL_MS = 900L
private const val WHITEBOARD_SYNC_OVERLAP_MS = 1_500L
private const val WHITEBOARD_POINTS_MAX = 500
private const val DELETE_CONFIRM_MS = 2_600L
private const val CHECKIN_OPEN_BEFORE_MS = 15 * 60 * 1000L
private const val CHECKIN_CLOSE_AFTER_MS = 2 * 60 * 60 * 1000L

private val KANBAN_COLUMNS = listOf("todo", "doing", "done")

/** Web STROKE_COLORS values, verbatim (the first entry is the art accent). */
private val STROKE_COLORS = listOf("#c9762b", "#f43f5e", "#f59e0b", "#8b5cf6", "#06b6d4", "#f4f4f5")

/** Web STROKE_WIDTHS, verbatim. */
private val STROKE_WIDTHS = listOf(2.0, 5.0, 10.0)

/** Web RSVP_CHOICES, verbatim labels. */
private val RSVP_CHOICES = listOf("going" to "Going", "maybe" to "Maybe", "no" to "Can't")

/** The sheet panel color (MirrorSheet body) - the web's ring-zinc-950 ring. */
private val SHEET_SURFACE = Color(0xF21C1610)

private val MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)

private fun asKanbanColumn(value: String): String =
    if (value == "doing" || value == "done") value else "todo"

private fun kanbanColumnLabel(column: String): String = when (column) {
    "doing" -> "Doing"
    "done" -> "Done"
    else -> "To do"
}

private fun parseIsoMs(iso: String?): Long {
    if (iso.isNullOrBlank()) return Long.MIN_VALUE
    return runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)
}

/** 'in 2h 15m' / 'Starting now' under a minute - future only (web countdownLabel). */
private fun countdownLabel(startsAtMs: Long, nowMs: Long): String {
    val diff = startsAtMs - nowMs
    if (diff < 60_000L) return "Starting now"
    val mins = (diff / 60_000L).toInt()
    if (mins < 60) return "in ${mins}m"
    val hours = mins / 60
    if (hours < 24) return if (mins % 60 > 0) "in ${hours}h ${mins % 60}m" else "in ${hours}h"
    val days = hours / 24
    return if (hours % 24 > 0) "in ${days}d ${hours % 24}h" else "in ${days}d"
}

/** '2d ago' relative chip for the collapsed Past section (web pastLabel). */
private fun pastLabel(startsAtMs: Long, nowMs: Long): String {
    val mins = ((nowMs - startsAtMs) / 60_000L).toInt().coerceAtLeast(0)
    if (mins < 1) return "just now"
    if (mins < 60) return "${mins}m ago"
    val hours = mins / 60
    if (hours < 24) return "${hours}h ago"
    return "${hours / 24}d ago"
}

/** Local `yyyy-MM-ddTHH:mm` for tomorrow 18:00 - the datetime default (web defaultStartLocal). */
private fun defaultStartLocal(): String = LocalDate.now()
    .plusDays(1)
    .atTime(18, 0)
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

/** Parses `yyyy-MM-ddTHH:mm` to epoch millis in the device zone; null when invalid. */
private fun parseLocalStart(value: String): Long? = runCatching {
    LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}.getOrNull()

/** #rrggbb hex from the whiteboard wire to a Compose color. */
private fun boardColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

private fun samePoints(a: List<List<Double>>, b: List<List<Double>>): Boolean {
    if (a.size != b.size) return false
    for (i in a.indices) {
        if (a[i].size < 2 || b[i].size < 2) return false
        if (abs(a[i][0] - b[i][0]) > 1e-9 || abs(a[i][1] - b[i][1]) > 1e-9) return false
    }
    return true
}

// shared small pieces ------------------------------------------------------------------------

/** Rotating LoaderCircle - the web's animate-spin lucide glyph. */
@Composable
private fun BoardSpinner(tint: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "boardSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
        ),
        label = "boardSpinAngle",
    )
    MirrorLucideIcon("LLoaderCircle", tint = tint, modifier = modifier.rotate(angle))
}

/** Header status dot: rose when offline, pulsing amber when live (web header dot). */
@Composable
private fun BoardStatusDot(offline: Boolean) {
    val transition = rememberInfiniteTransition(label = "boardDot")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2000
                1f at 0
                0.4f at 1000
                1f at 2000
            },
        ),
        label = "boardDotPulse",
    )
    Box(
        Modifier
            .size(6.dp)
            .graphicsLayer { alpha = if (offline) 1f else pulse }
            .clip(CircleShape)
            .background(if (offline) MirrorArt.Red else MirrorArt.Accent2),
    )
}

/** Header icon tile + status line shared by all three sheets (web header anatomy). */
@Composable
private fun BoardHeaderRow(icon: String, statusDot: @Composable () -> Unit, statusLine: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MirrorArt.Accent2.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon(icon, tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            statusDot()
            Text(statusLine, color = MirrorArt.Faint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 40dp form field pill (web h-10 rounded-xl bg-white/5 border-white/10). */
@Composable
private fun BoardFormTextField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    valueColor: Color = MirrorArt.Text,
) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MirrorArt.White7)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                placeholder,
                color = MirrorArt.Faint,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(
                color = valueColor,
                fontSize = if (mono) 12.5.sp else 13.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            ),
            cursorBrush = SolidColor(MirrorArt.Accent2),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// MirrorEventsSheet (web events-sheet.tsx) ===================================================

@Composable
internal fun MirrorEventsSheet(
    repository: PulseRepository,
    conversationId: String,
    viewerId: String,
    viewerIsAdmin: Boolean = false,
    onDismiss: () -> Unit,
) {
    var events by remember { mutableStateOf<List<GroupEventDto>?>(null) }
    var offline by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var title by remember { mutableStateOf("") }
    var startsAtLocal by remember { mutableStateOf(defaultStartLocal()) }
    var location by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var deleteBusy by remember { mutableStateOf(false) }
    var rsvpBusy by remember { mutableStateOf(false) }
    var checkinBusy by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var pastOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 5s live poll while open (web refetchInterval parity); first success clears loading.
    LaunchedEffect(conversationId) {
        while (isActive) {
            val page = runCatching { repository.events(conversationId) }.getOrNull()?.getOrNull()
            if (page == null) {
                offline = true
            } else {
                events = page.events
                offline = false
            }
            delay(EVENTS_POLL_MS)
        }
    }
    // countdown chips recompute every 30s (web TICK_MS).
    LaunchedEffect(Unit) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    // two-tap delete confirm disarms after 2.6s (web DELETE_CONFIRM_MS).
    LaunchedEffect(confirmDeleteId) {
        if (confirmDeleteId != null) {
            delay(DELETE_CONFIRM_MS)
            confirmDeleteId = null
        }
    }

    suspend fun refreshEvents() {
        val page = runCatching { repository.events(conversationId) }.getOrNull()?.getOrNull()
        if (page == null) {
            offline = true
        } else {
            events = page.events
            offline = false
        }
    }

    fun submitCreate() {
        val trimmed = title.trim()
        val startsAtMs = parseLocalStart(startsAtLocal)
        if (trimmed.isEmpty() || startsAtMs == null || creating) return
        creating = true
        notice = null
        val startsAtIso = Instant.ofEpochMilli(startsAtMs).toString()
        val locationText = location.trim()
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                repository.createEvent(conversationId, trimmed, startsAtIso, null, locationText)
            }.getOrNull()
            creating = false
            if (result?.isSuccess == true) {
                title = ""
                location = ""
                startsAtLocal = defaultStartLocal()
                refreshEvents()
            } else {
                notice = "Could not schedule the event."
            }
        }
    }

    fun handleDeleteTap(eventId: String) {
        if (confirmDeleteId != eventId) {
            confirmDeleteId = eventId
            return
        }
        confirmDeleteId = null
        if (deleteBusy) return
        deleteBusy = true
        notice = null
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { repository.deleteEvent(eventId) }.getOrNull()
            if (result?.isSuccess != true) notice = "Could not delete the event."
            deleteBusy = false
            refreshEvents()
        }
    }

    fun handleRsvp(event: GroupEventDto, status: String) {
        if (event.myStatus == status || rsvpBusy) return
        rsvpBusy = true
        notice = null
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { repository.rsvpEvent(event.id, status) }.getOrNull()
            if (result?.isSuccess != true) notice = "RSVP failed - try again."
            rsvpBusy = false
            refreshEvents()
        }
    }

    fun handleCheckIn(event: GroupEventDto) {
        if (checkinBusy) return
        checkinBusy = true
        notice = null
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { repository.checkinEvent(event.id) }.getOrNull()
            if (result?.isSuccess != true) notice = "Check-in failed - try again."
            checkinBusy = false
            refreshEvents()
        }
    }

    val list = events
    val upcoming = (list ?: emptyList()).filter { parseIsoMs(it.startsAt) >= nowMs }
    val past = (list ?: emptyList()).filter { parseIsoMs(it.startsAt) < nowMs }
    val statusLine = if (offline) "Reconnecting…" else "${upcoming.size} upcoming · live"

    MirrorSheet(title = "Events", onDismiss = onDismiss) {
        BoardHeaderRow(
            icon = "LCalendarDays",
            statusDot = { BoardStatusDot(offline) },
            statusLine = statusLine,
        )
        Spacer(Modifier.height(12.dp))
        MirrorSheetScroll {
            // create form (web form card)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MirrorArt.White7)
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BoardFormTextField(
                    value = title,
                    onValue = { title = it.take(120) },
                    placeholder = "Event title",
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoardFormTextField(
                        value = startsAtLocal,
                        onValue = { startsAtLocal = it },
                        placeholder = "",
                        modifier = Modifier.weight(1f),
                        mono = true,
                        valueColor = if (parseLocalStart(startsAtLocal) == null) MirrorArt.Red else MirrorArt.Text,
                    )
                    BoardFormTextField(
                        value = location,
                        onValue = { location = it.take(200) },
                        placeholder = "Location (optional)",
                        modifier = Modifier.weight(1f),
                    )
                }
                val canSubmit = !creating && title.trim().isNotEmpty() && parseLocalStart(startsAtLocal) != null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = if (canSubmit) 1f else 0.5f }
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirrorArt.Accent)
                        .clickable(enabled = canSubmit) { submitCreate() },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (creating) {
                        BoardSpinner(Color.White, Modifier.size(16.dp))
                    } else {
                        MirrorLucideIcon("LCalendarDays", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Schedule event", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            val currentNotice = notice
            if (currentNotice != null) {
                Text(
                    currentNotice,
                    color = MirrorArt.Red,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // upcoming (web section label)
            Text(
                "Upcoming",
                color = MirrorArt.Faint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (list == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BoardSpinner(MirrorArt.Accent2, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Loading events…",
                        color = MirrorArt.Faint,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                if (list.isEmpty()) {
                    MirrorEventsEmpty()
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (event in upcoming) {
                        MirrorEventRow(
                            event = event,
                            upcoming = true,
                            viewerId = viewerId,
                            nowMs = nowMs,
                            confirmDelete = confirmDeleteId == event.id,
                            deleteBusy = deleteBusy,
                            rsvpBusy = rsvpBusy,
                            checkinBusy = checkinBusy,
                            onDeleteTap = { handleDeleteTap(event.id) },
                            onRsvp = { status -> handleRsvp(event, status) },
                            onCheckIn = { handleCheckIn(event) },
                        )
                    }
                }
                if (upcoming.isEmpty() && list.isNotEmpty()) {
                    Text(
                        "Nothing upcoming - past events live below.",
                        color = MirrorArt.Faint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                }
                // past (collapsed by default, web Past toggle)
                if (past.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { pastOpen = !pastOpen }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MirrorLucideIcon(
                            "LArrowDown",
                            tint = MirrorArt.Faint,
                            modifier = Modifier
                                .size(14.dp)
                                .rotate(if (pastOpen) 180f else 0f),
                        )
                        Text(
                            "Past (${past.size})",
                            color = MirrorArt.Faint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                        )
                    }
                    if (pastOpen) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (event in past) {
                                MirrorEventRow(
                                    event = event,
                                    upcoming = false,
                                    viewerId = viewerId,
                                    nowMs = nowMs,
                                    confirmDelete = confirmDeleteId == event.id,
                                    deleteBusy = deleteBusy,
                                    rsvpBusy = rsvpBusy,
                                    checkinBusy = checkinBusy,
                                    onDeleteTap = { handleDeleteTap(event.id) },
                                    onRsvp = { status -> handleRsvp(event, status) },
                                    onCheckIn = { handleCheckIn(event) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dashed empty card (web "No events yet" block). */
@Composable
private fun MirrorEventsEmpty() {
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))
                drawRoundRect(
                    color = MirrorArt.Hairline,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                )
            }
            .padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MirrorArt.White7),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon("LCalendarDays", tint = MirrorArt.Faint, modifier = Modifier.size(20.dp))
        }
        Text(
            "No events yet - schedule the first one above.",
            color = MirrorArt.Faint,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
    }
}

/** One event row (web renderRow): date tile, title, countdown, location, RSVP, check-in. */
@Composable
private fun MirrorEventRow(
    event: GroupEventDto,
    upcoming: Boolean,
    viewerId: String,
    nowMs: Long,
    confirmDelete: Boolean,
    deleteBusy: Boolean,
    rsvpBusy: Boolean,
    checkinBusy: Boolean,
    onDeleteTap: () -> Unit,
    onRsvp: (String) -> Unit,
    onCheckIn: () -> Unit,
) {
    val startsAtMs = parseIsoMs(event.startsAt)
    val description = event.description
    val locationText = event.location
    val creatorName = event.createdByName
    val myCheckedInAt = event.rsvps.firstOrNull { it.userId == viewerId }?.checkedInAt
    val windowOpen = startsAtMs - CHECKIN_OPEN_BEFORE_MS <= nowMs && nowMs <= startsAtMs + CHECKIN_CLOSE_AFTER_MS
    val canCheckIn = windowOpen && event.myStatus == "going" && myCheckedInAt == null
    val showCheckinHint =
        !windowOpen && myCheckedInAt == null && event.myStatus == "going" && nowMs < startsAtMs - CHECKIN_OPEN_BEFORE_MS
    val hereCount = event.rsvps.count { it.checkedInAt != null }
    val going = event.rsvps.filter { it.status == "going" }
    val zoned = runCatching {
        Instant.ofEpochMilli(startsAtMs).atZone(ZoneId.systemDefault())
    }.getOrNull()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MirrorArt.White7)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // calendar-tile date badge (web DateTile)
            Column(
                Modifier
                    .width(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MirrorArt.White7)
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(12.dp))
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    zoned?.format(MONTH_FORMATTER)?.uppercase() ?: "",
                    color = MirrorArt.Accent2,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    zoned?.dayOfMonth?.toString() ?: "",
                    color = MirrorArt.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        event.title,
                        color = MirrorArt.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // two-tap delete, creator OR admin (web canDelete)
                    if (event.createdById == viewerId || viewerIsAdmin) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (confirmDelete) MirrorArt.Red else Color.Transparent)
                                .clickable(enabled = !deleteBusy, onClick = onDeleteTap),
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon(
                                "LX",
                                tint = if (confirmDelete) Color.White else MirrorArt.Faint,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val urgent = upcoming && startsAtMs - nowMs < 60_000L
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(
                                when {
                                    urgent -> MirrorArt.Accent
                                    upcoming -> MirrorArt.Accent.copy(alpha = 0.15f)
                                    else -> MirrorArt.White7
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            if (upcoming) countdownLabel(startsAtMs, nowMs) else pastLabel(startsAtMs, nowMs),
                            color = when {
                                urgent -> Color.White
                                upcoming -> MirrorArt.Accent2
                                else -> MirrorArt.Faint
                            },
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (!locationText.isNullOrBlank()) {
                        Row(
                            Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            MirrorLucideIcon("LLocation", tint = MirrorArt.Faint, modifier = Modifier.size(12.dp))
                            Text(
                                locationText,
                                color = MirrorArt.Dim,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (!description.isNullOrBlank()) {
                    Text(
                        description,
                        color = MirrorArt.Dim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (creatorName != null && creatorName.isNotBlank()) {
                    Text("by $creatorName", color = MirrorArt.Faint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // RSVP pills + attendance chips (web justify-between row)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (choice in RSVP_CHOICES) {
                    MirrorRsvpPill(
                        label = choice.second,
                        count = when (choice.first) {
                            "going" -> event.counts.going
                            "maybe" -> event.counts.maybe
                            else -> event.counts.no
                        },
                        selected = event.myStatus == choice.first,
                        disabled = rsvpBusy,
                        onClick = { onRsvp(choice.first) },
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hereCount > 0) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(MirrorArt.Accent2.copy(alpha = 0.10f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        MirrorLucideIcon("LUsersRound", tint = MirrorArt.Accent2, modifier = Modifier.size(12.dp))
                        Text(
                            "$hereCount here",
                            color = MirrorArt.Accent2,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                MirrorGoingStack(going)
            }
        }

        // attendance strip (web check-in window states)
        when {
            canCheckIn -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirrorArt.Accent2.copy(alpha = 0.15f))
                        .border(1.dp, MirrorArt.Accent2.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .clickable(enabled = !checkinBusy, onClick = onCheckIn),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (checkinBusy) {
                        BoardSpinner(MirrorArt.Accent2, Modifier.size(16.dp))
                    } else {
                        MirrorLucideIcon("LCheck", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Check in", color = MirrorArt.Accent2, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            myCheckedInAt != null -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirrorArt.Accent2.copy(alpha = 0.10f))
                        .border(1.dp, MirrorArt.Accent2.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MirrorLucideIcon("LBadgeCheck", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Checked in", color = MirrorArt.Accent2, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
            showCheckinHint -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    MirrorLucideIcon("LClock", tint = MirrorArt.Faint, modifier = Modifier.size(12.dp))
                    Text(
                        "Check-in opens 15 min before start",
                        color = MirrorArt.Faint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MirrorRsvpPill(
    label: String,
    count: Int,
    selected: Boolean,
    disabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (selected) MirrorArt.Accent else MirrorArt.White7)
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.White else MirrorArt.TextSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            count.toString(),
            color = if (selected) Color.White.copy(alpha = 0.9f) else MirrorArt.Faint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Initials avatar stack of the going members, up to 4 + '+n' (web GoingStack). */
@Composable
private fun MirrorGoingStack(going: List<EventRsvpDto>) {
    if (going.isEmpty()) return
    val shown = going.take(4)
    val extra = going.size - shown.size
    Row(verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { index, rsvp ->
            Box(
                Modifier
                    .offset(x = if (index == 0) 0.dp else (-6).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(MirrorArt.avatarGradient(null)))
                    .border(2.dp, SHEET_SURFACE, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    MirrorArt.initials(rsvp.name),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (rsvp.checkedInAt != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.Accent2)
                            .border(2.dp, SHEET_SURFACE, CircleShape),
                    )
                }
            }
        }
        if (extra > 0) {
            Box(
                Modifier
                    .offset(x = (-6).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3F3F46))
                    .border(2.dp, SHEET_SURFACE, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$extra",
                    color = MirrorArt.TextSoft,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// MirrorKanbanSheet (web kanban-sheet.tsx) ===================================================

@Composable
internal fun MirrorKanbanSheet(
    repository: PulseRepository,
    conversationId: String,
    viewerId: String,
    viewerIsAdmin: Boolean = false,
    onDismiss: () -> Unit,
) {
    var cards by remember { mutableStateOf<List<KanbanCardDto>?>(null) }
    var offline by remember { mutableStateOf(false) }
    var synced by remember { mutableStateOf(false) }
    var drafts by remember { mutableStateOf(mapOf("todo" to "", "doing" to "", "done" to "")) }
    var busyIds by remember { mutableStateOf(setOf<String>()) }
    var addingIn by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 1500ms live poll while open (web POLL_MS parity); first success clears Syncing.
    LaunchedEffect(conversationId) {
        while (isActive) {
            val page = runCatching { repository.kanbanBoard(conversationId) }.getOrNull()?.getOrNull()
            if (page == null) {
                offline = true
            } else {
                cards = page.cards
                synced = true
                offline = false
            }
            delay(KANBAN_POLL_MS)
        }
    }

    suspend fun refreshBoard() {
        val page = runCatching { repository.kanbanBoard(conversationId) }.getOrNull()?.getOrNull()
        if (page == null) {
            offline = true
        } else {
            cards = page.cards
            synced = true
            offline = false
        }
    }

    fun addCard(column: String) {
        val text = (drafts[column] ?: "").trim()
        if (text.isEmpty() || addingIn != null) return
        addingIn = column
        notice = null
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                repository.createKanbanCard(conversationId, text, column, null, null)
            }.getOrNull()
            if (result?.isSuccess == true) {
                drafts = drafts + (column to "")
            } else {
                notice = "Could not add the card."
            }
            addingIn = null
            refreshBoard()
        }
    }

    fun moveCard(card: KanbanCardDto, direction: Int) {
        val from = KANBAN_COLUMNS.indexOf(asKanbanColumn(card.column))
        val to = from + direction
        if (to < 0 || to >= KANBAN_COLUMNS.size || busyIds.contains(card.id)) return
        busyIds = busyIds + card.id
        notice = null
        CoroutineScope(Dispatchers.IO).launch {
            // move-to-end semantics: position null on column change = max+1 in the target (web PATCH)
            val result = runCatching {
                repository.updateKanbanCard(card.id, null, KANBAN_COLUMNS[to], null, false, null)
            }.getOrNull()
            if (result?.isSuccess != true) notice = "Could not move the card."
            busyIds = busyIds - card.id
            refreshBoard()
        }
    }

    fun removeCard(card: KanbanCardDto) {
        if (busyIds.contains(card.id)) return
        busyIds = busyIds + card.id
        notice = null
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { repository.deleteKanbanCard(card.id) }.getOrNull()
            if (result?.isSuccess != true) notice = "Could not delete the card."
            busyIds = busyIds - card.id
            refreshBoard()
        }
    }

    val list = cards ?: emptyList()
    val grouped: Map<String, List<KanbanCardDto>> = KANBAN_COLUMNS.associateWith { column ->
        list.filter { asKanbanColumn(it.column) == column }
            .sortedWith(compareBy({ it.position }, { it.createdAt ?: "" }, { it.id }))
    }
    val statusLine = when {
        offline -> "Reconnecting…"
        !synced -> "Syncing…"
        else -> "${list.size} ${if (list.size == 1) "card" else "cards"} · live"
    }

    MirrorSheet(title = "Board", onDismiss = onDismiss) {
        BoardHeaderRow(
            icon = "LSquareKanban",
            statusDot = {
                if (offline) {
                    MirrorLucideIcon("LCloudOff", tint = MirrorArt.Red, modifier = Modifier.size(12.dp))
                } else {
                    BoardStatusDot(offline = false)
                }
            },
            statusLine = statusLine,
        )
        Spacer(Modifier.height(12.dp))
        // three-column board (web grid-cols-3) with per-column vertical card scroll
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (column in KANBAN_COLUMNS) {
                MirrorKanbanColumn(
                    column = column,
                    columnIndex = KANBAN_COLUMNS.indexOf(column),
                    cards = grouped[column] ?: emptyList(),
                    draft = drafts[column] ?: "",
                    adding = addingIn == column,
                    busyIds = busyIds,
                    viewerId = viewerId,
                    onDraft = { value -> drafts = drafts + (column to value) },
                    onAdd = { addCard(column) },
                    onMove = { card, direction -> moveCard(card, direction) },
                    onDelete = { card -> removeCard(card) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val currentNotice = notice
        if (currentNotice != null) {
            Text(
                currentNotice,
                color = MirrorArt.Red,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
        Text(
            "Cards sync to everyone in this room within ~1.5s",
            color = MirrorArt.Faint,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

@Composable
private fun MirrorKanbanColumn(
    column: String,
    columnIndex: Int,
    cards: List<KanbanCardDto>,
    draft: String,
    adding: Boolean,
    busyIds: Set<String>,
    viewerId: String,
    onDraft: (String) -> Unit,
    onAdd: () -> Unit,
    onMove: (KanbanCardDto, Int) -> Unit,
    onDelete: (KanbanCardDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = column != "todo"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // tinted column header (web COLUMN_META pills)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (accent) MirrorArt.Accent2.copy(alpha = 0.10f) else MirrorArt.White7)
                .border(
                    1.dp,
                    if (accent) MirrorArt.Accent2.copy(alpha = 0.25f) else MirrorArt.Hairline,
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (accent) MirrorArt.Accent2 else MirrorArt.Dim),
            )
            Text(
                kanbanColumnLabel(column),
                color = if (accent) MirrorArt.Accent2 else MirrorArt.TextSoft,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                cards.size.toString(),
                color = MirrorArt.Dim,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x40000000))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        // cards (web max-h-64 min-h-[68px] scroll area)
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp, max = 256.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (card in cards) {
                MirrorKanbanCard(
                    card = card,
                    columnIndex = columnIndex,
                    done = column == "done",
                    busy = busyIds.contains(card.id),
                    viewerId = viewerId,
                    onMove = onMove,
                    onDelete = onDelete,
                )
            }
            if (cards.isEmpty()) {
                Text(
                    "No cards",
                    color = MirrorArt.Faint,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
            }
        }
        // add-card input (Enter submits, web maxLength 120)
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MirrorArt.White7)
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(8.dp))
                .padding(start = 8.dp, end = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draft.isEmpty()) {
                Text("Add card…", color = MirrorArt.Faint, fontSize = 11.sp, maxLines = 1)
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                singleLine = true,
                enabled = !adding,
                textStyle = TextStyle(color = MirrorArt.Text, fontSize = 11.sp),
                cursorBrush = SolidColor(MirrorArt.Accent2),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (adding) {
                BoardSpinner(MirrorArt.Accent2, Modifier.align(Alignment.CenterEnd).size(12.dp))
            }
        }
    }
}

@Composable
private fun MirrorKanbanCard(
    card: KanbanCardDto,
    columnIndex: Int,
    done: Boolean,
    busy: Boolean,
    viewerId: String,
    onMove: (KanbanCardDto, Int) -> Unit,
    onDelete: (KanbanCardDto) -> Unit,
) {
    val assigneeName = card.assigneeName
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (busy) 0.6f else 1f }
            .clip(RoundedCornerShape(12.dp))
            .background(if (done) MirrorArt.Accent2.copy(alpha = 0.05f) else MirrorArt.White7)
            .border(
                1.dp,
                if (done) MirrorArt.Accent2.copy(alpha = 0.20f) else MirrorArt.Hairline,
                RoundedCornerShape(12.dp),
            )
            .padding(8.dp),
    ) {
        Text(
            card.title,
            color = if (done) MirrorArt.Faint else MirrorArt.Text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (done) TextDecoration.LineThrough else null,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (card.assigneeId != null) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Brush.verticalGradient(MirrorArt.avatarGradient(null))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            MirrorArt.initials(assigneeName ?: "?"),
                            color = Color.White,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        assigneeName ?: "Unknown",
                        color = MirrorArt.Dim,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (columnIndex > 0) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !busy) { onMove(card, -1) },
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LArrowLeft", tint = MirrorArt.Dim, modifier = Modifier.size(12.dp))
                }
            }
            if (columnIndex < KANBAN_COLUMNS.size - 1) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !busy) { onMove(card, 1) },
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LArrowRight", tint = MirrorArt.Dim, modifier = Modifier.size(12.dp))
                }
            }
            if (card.createdById == viewerId || viewerIsAdmin) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !busy) { onDelete(card) },
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LX", tint = MirrorArt.Dim, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

// MirrorWhiteboardSheet (web whiteboard-sheet.tsx) ===========================================

/**
 * A committed stroke on the local board. Points are normalized [x, y] pairs
 * (0..1) exactly like the wire DTO, so server strokes are adopted as-is.
 */
private data class BoardStroke(
    val id: String,
    val userId: String,
    val color: String,
    val width: Double,
    val points: List<List<Double>>,
    val pending: Boolean,
)

@Composable
internal fun MirrorWhiteboardSheet(
    repository: PulseRepository,
    conversationId: String,
    viewerId: String,
    onDismiss: () -> Unit,
) {
    var strokes by remember { mutableStateOf<List<BoardStroke>>(emptyList()) }
    var since by remember { mutableStateOf<Long?>(null) }
    var resetAtSeen by remember { mutableStateOf<Long?>(null) }
    var haveResetMark by remember { mutableStateOf(false) }
    var synced by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(STROKE_COLORS[0]) }
    var width by remember { mutableStateOf(STROKE_WIDTHS[1]) }
    var undoBusy by remember { mutableStateOf(false) }
    var clearBusy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }
    var localSeq by remember { mutableStateOf(0) }
    var livePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    fun say(text: String, error: Boolean) {
        notice = text
        noticeIsError = error
    }

    // two-tap clear confirm disarms after 2.6s (web parity).
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(DELETE_CONFIRM_MS)
            confirmClear = false
        }
    }

    // delta poll: full snapshot first (since = null), then ?since= watermark
    // with the web's 1500ms overlap window; a changed resetAt wipes the board.
    LaunchedEffect(conversationId) {
        while (isActive) {
            val page = runCatching { repository.whiteboard(conversationId, since) }
                .getOrNull()
                ?.getOrNull()
            if (page == null) {
                offline = true
            } else {
                offline = false
                if (haveResetMark && page.resetAt != resetAtSeen) {
                    strokes = emptyList()
                }
                haveResetMark = true
                resetAtSeen = page.resetAt
                val serverTime = page.serverTime ?: System.currentTimeMillis()
                val baseline = serverTime - WHITEBOARD_SYNC_OVERLAP_MS
                val current = since
                since = if (current == null) baseline else maxOf(current, baseline)
                for (serverStroke in page.strokes) {
                    val flat = serverStroke.points
                        .filter { it.size >= 2 }
                        .map { listOf(it[0].coerceIn(0.0, 1.0), it[1].coerceIn(0.0, 1.0)) }
                    if (flat.isEmpty()) continue
                    if (strokes.any { it.id == serverStroke.id }) continue
                    // adopt my own pending copy when the poll echoes it before the POST response
                    val pendingIndex = strokes.indexOfFirst {
                        it.pending && it.userId == serverStroke.userId &&
                            it.color == serverStroke.color &&
                            abs(it.width - serverStroke.width) < 0.001 &&
                            samePoints(it.points, flat)
                    }
                    strokes = if (pendingIndex >= 0) {
                        val mine = strokes[pendingIndex]
                        strokes.toMutableList()
                            .also { it[pendingIndex] = mine.copy(id = serverStroke.id, pending = false) }
                    } else {
                        strokes + BoardStroke(
                            id = serverStroke.id,
                            userId = serverStroke.userId,
                            color = serverStroke.color,
                            width = serverStroke.width,
                            points = flat,
                            pending = false,
                        )
                    }
                }
                synced = true
            }
            delay(WHITEBOARD_POLL_MS)
        }
    }

    fun commitStroke(rawPoints: List<Offset>, canvasWidthPx: Float, canvasHeightPx: Float) {
        if (rawPoints.isEmpty()) return
        var pairs = rawPoints.map { listOf(it.x.toDouble(), it.y.toDouble()) }
        // a tap becomes a dot: nudge a second point so the stroke survives the
        // server's 2-point minimum and renders as a round cap (web parity)
        if (pairs.size < 2) {
            val first = pairs[0]
            val off = 1.2 / canvasWidthPx.coerceAtLeast(1f).toDouble()
            pairs = listOf(
                first,
                listOf((first[0] + off).coerceAtMost(1.0), (first[1] + off).coerceAtMost(1.0)),
            )
        }
        // respect the 500-point cap - downsample, always keep the last point
        if (pairs.size > WHITEBOARD_POINTS_MAX) {
            val keepEvery = (pairs.size + WHITEBOARD_POINTS_MAX - 1) / WHITEBOARD_POINTS_MAX
            val down = ArrayList<List<Double>>()
            var index = 0
            while (index < pairs.size) {
                down.add(pairs[index])
                index += keepEvery
            }
            val lastPoint = pairs[pairs.size - 1]
            if (down[down.size - 1] != lastPoint) down.add(lastPoint)
            pairs = down
        }
        val stroke = BoardStroke("pending:$localSeq", viewerId, color, width, pairs, true)
        localSeq += 1
        strokes = strokes + stroke
        CoroutineScope(Dispatchers.IO).launch {
            val posted = runCatching {
                repository.postWhiteboardStrokes(
                    conversationId,
                    listOf(
                        WhiteboardStrokePostDto(
                            color = stroke.color,
                            width = stroke.width,
                            points = stroke.points,
                        ),
                    ),
                )
            }.getOrNull()?.getOrNull()
            if (posted != null) {
                offline = false
                val serverId = posted.ids.firstOrNull()
                strokes = strokes.map { current ->
                    if (current.id == stroke.id) {
                        if (serverId != null) current.copy(id = serverId, pending = false)
                        else current.copy(pending = false)
                    } else {
                        current
                    }
                }
            } else {
                // never reached the server - remove the local ghost honestly (web parity)
                strokes = strokes.filterNot { current -> current.id == stroke.id }
                offline = true
                say("Stroke did not sync - check your connection.", true)
            }
        }
    }

    fun undoMine() {
        if (undoBusy) return
        if (strokes.any { it.pending }) {
            say("Hold on - still syncing your last stroke.", false)
            return
        }
        if (!strokes.any { it.userId == viewerId }) {
            say("Nothing of yours to undo yet.", false)
            return
        }
        undoBusy = true
        notice = null
        CoroutineScope(Dispatchers.IO).launch {
            val undone = runCatching { repository.undoWhiteboardStroke(conversationId) }
                .getOrNull()
                ?.getOrNull()
            if (undone != null) {
                offline = false
                val removed = undone.removedId
                if (removed != null) {
                    strokes = strokes.filterNot { current -> current.id == removed }
                } else {
                    say("Nothing of yours to undo yet.", false)
                }
            } else {
                offline = true
                say("Undo failed - check your connection.", true)
            }
            undoBusy = false
        }
    }

    fun clearBoard() {
        // two-tap confirm - a wipe is destructive for the whole room (web parity)
        if (!confirmClear) {
            confirmClear = true
            return
        }
        confirmClear = false
        if (clearBusy) return
        clearBusy = true
        notice = null
        CoroutineScope(Dispatchers.IO).launch {
            val cleared = runCatching { repository.clearWhiteboard(conversationId) }
                .getOrNull()
                ?.getOrNull()
            if (cleared != null) {
                strokes = emptyList()
                offline = false
                say("Board cleared for everyone.", false)
            } else {
                offline = true
                say("Could not clear the board - check your connection.", true)
            }
            clearBusy = false
        }
    }

    val statusLine = when {
        offline -> "Reconnecting…"
        !synced -> "Syncing…"
        else -> "${strokes.size} ${if (strokes.size == 1) "stroke" else "strokes"} · live"
    }

    MirrorSheet(title = "Whiteboard", onDismiss = onDismiss) {
        BoardHeaderRow(
            icon = "LPresentation",
            statusDot = { BoardStatusDot(offline) },
            statusLine = statusLine,
        )
        Spacer(Modifier.height(12.dp))
        // board: dot-grid backdrop + normalized-point strokes (web 22px radial grid)
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MirrorArt.Bg),
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        fun norm(o: Offset): Offset = Offset(
                            (o.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (o.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                        )
                        var active: MutableList<Offset>? = null
                        detectDragGestures(
                            onDragStart = { start ->
                                if (active == null) {
                                    val list = mutableListOf(norm(start))
                                    active = list
                                    livePoints = list.toList()
                                }
                            },
                            onDrag = { change, _ ->
                                val list = active ?: return@detectDragGestures
                                val p = norm(change.position)
                                val last = list.last()
                                val dx = p.x - last.x
                                val dy = p.y - last.y
                                val jitter = 1.2.dp.toPx()
                                if (dx * dx + dy * dy >= jitter * jitter) {
                                    list.add(p)
                                    livePoints = list.toList()
                                }
                            },
                            onDragEnd = {
                                val list = active
                                active = null
                                livePoints = emptyList()
                                if (list != null) {
                                    commitStroke(list.toList(), size.width.toFloat(), size.height.toFloat())
                                }
                            },
                            onDragCancel = {
                                val list = active
                                active = null
                                livePoints = emptyList()
                                if (list != null && list.size > 3) {
                                    commitStroke(list.toList(), size.width.toFloat(), size.height.toFloat())
                                }
                            },
                        )
                    },
            ) {
                val gridStep = 22.dp.toPx()
                val dotRadius = 1.dp.toPx() / 2f
                var gx = gridStep / 2f
                while (gx < size.width) {
                    var gy = gridStep / 2f
                    while (gy < size.height) {
                        drawCircle(Color(0x17FFFFFF), radius = dotRadius, center = Offset(gx, gy))
                        gy += gridStep
                    }
                    gx += gridStep
                }
                for (stroke in strokes) {
                    drawBoardStroke(stroke)
                }
                if (livePoints.isNotEmpty()) {
                    drawBoardStroke(
                        BoardStroke(
                            id = "live",
                            userId = viewerId,
                            color = color,
                            width = width,
                            points = livePoints.map { listOf(it.x.toDouble(), it.y.toDouble()) },
                            pending = true,
                        ),
                    )
                }
            }
            if (!synced) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BoardSpinner(MirrorArt.Accent2, Modifier.size(28.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Loading the board…",
                        color = MirrorArt.Faint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else if (strokes.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MirrorArt.White7),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LPencilLine", tint = MirrorArt.Faint, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        "The board is empty - draw something, everyone in this chat sees it live.",
                        color = MirrorArt.Faint,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                }
            }
        }
        // toolbar: colors / widths / undo / clear (web toolbar anatomy)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (hex in STROKE_COLORS) {
                        val selected = color == hex
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (selected) MirrorArt.Accent2.copy(alpha = 0.15f) else Color.Transparent)
                                .border(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) MirrorArt.Accent2 else MirrorArt.Hairline,
                                    CircleShape,
                                )
                                .clickable { color = hex },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(boardColor(hex)),
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(MirrorArt.Hairline),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (w in STROKE_WIDTHS) {
                        val selected = width == w
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (selected) MirrorArt.Accent2.copy(alpha = 0.15f) else Color.Transparent)
                                .border(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) MirrorArt.Accent2 else MirrorArt.Hairline,
                                    CircleShape,
                                )
                                .clickable { width = w },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size((w + 4).dp)
                                    .clip(CircleShape)
                                    .background(MirrorArt.Text),
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MirrorArt.White7)
                        .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                        .clickable(enabled = !undoBusy) { undoMine() },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MirrorLucideIcon("LArrowLeft", tint = MirrorArt.Text, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Undo", color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (confirmClear) MirrorArt.Red else MirrorArt.White7)
                        .border(
                            1.dp,
                            if (confirmClear) Color.Transparent else MirrorArt.Hairline,
                            RoundedCornerShape(16.dp),
                        )
                        .clickable(enabled = !clearBusy) { clearBoard() },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MirrorLucideIcon(
                        "LEraser",
                        tint = if (confirmClear) Color.White else MirrorArt.Red,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (confirmClear) "Tap again" else "Clear board",
                        color = if (confirmClear) Color.White else MirrorArt.Red,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            val currentNotice = notice
            if (currentNotice != null) {
                Text(
                    currentNotice,
                    color = if (noticeIsError) MirrorArt.Red else MirrorArt.TextSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Smooth quadratic polyline through segment midpoints (web traceSmooth). */
private fun DrawScope.drawBoardStroke(stroke: BoardStroke) {
    val pts = stroke.points.filter { it.size >= 2 }
    if (pts.isEmpty()) return
    val paintColor = boardColor(stroke.color)
    if (pts.size == 1) {
        // a lone point renders as a round dot (web drawLiveSegment parity)
        drawCircle(
            paintColor,
            radius = stroke.width.dp.toPx() / 2f,
            center = Offset(
                (pts[0][0] * size.width).toFloat(),
                (pts[0][1] * size.height).toFloat(),
            ),
        )
        return
    }
    val path = Path()
    path.moveTo((pts[0][0] * size.width).toFloat(), (pts[0][1] * size.height).toFloat())
    if (pts.size == 2) {
        path.lineTo((pts[1][0] * size.width).toFloat(), (pts[1][1] * size.height).toFloat())
    } else {
        for (i in 1 until pts.size - 1) {
            val xi = (pts[i][0] * size.width).toFloat()
            val yi = (pts[i][1] * size.height).toFloat()
            val xNext = (pts[i + 1][0] * size.width).toFloat()
            val yNext = (pts[i + 1][1] * size.height).toFloat()
            path.quadraticBezierTo(xi, yi, (xi + xNext) / 2f, (yi + yNext) / 2f)
        }
        val last = pts[pts.size - 1]
        path.lineTo((last[0] * size.width).toFloat(), (last[1] * size.height).toFloat())
    }
    drawPath(
        path,
        paintColor,
        style = Stroke(
            width = stroke.width.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}
