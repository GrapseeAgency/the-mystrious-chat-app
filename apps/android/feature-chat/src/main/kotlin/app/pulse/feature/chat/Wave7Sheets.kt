package app.pulse.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.protocol.CheckinResultDto
import app.pulse.protocol.EventCountsDto
import app.pulse.protocol.GroupEventDto
import app.pulse.protocol.HubTaskDto
import app.pulse.protocol.KanbanCardDto
import app.pulse.protocol.KanbanPageDto
import app.pulse.protocol.LeaderboardPageDto
import app.pulse.protocol.MarketListingDto
import app.pulse.protocol.PulseWave7Logic
import app.pulse.protocol.RemindersPageDto
import app.pulse.protocol.SwapPageDto
import app.pulse.protocol.WhiteboardPageDto
import app.pulse.protocol.WhiteboardStrokePostDto
import app.pulse.ui.PulsePalette
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Wave 7 collaboration sheets (F-RO-03…09). Poll transports are web parity:
 * kanban 1500 ms while open (kanban-sheet.tsx:65), whiteboard since-delta
 * 900 ms (whiteboard-sheet.tsx:66). Server error copy surfaces verbatim
 * through the room snackbar — the sheets never invent success.
 */

private val W7_COLORS = listOf("#22c55e", "#0ea5e9", "#f59e0b", "#ef4444", "#a855f7", "#e2e8f0")

// ── Red packet create (F-RO-02) ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedPacketSheet(
    onDismiss: () -> Unit,
    onSend: (total: Long, count: Int, note: String?) -> Unit,
) {
    var total by remember { mutableStateOf("50") }
    var count by remember { mutableStateOf("5") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Text("🧧 Red packet", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Whole PC only — every grab wins at least 1.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = total, onValueChange = { total = it.filter(Char::isDigit).take(5) },
                label = { Text("Total PC (1–10000)") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = count, onValueChange = { count = it.filter(Char::isDigit).take(2) },
                label = { Text("Grabs (1–50)") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(60) },
                label = { Text("Note (optional, ≤60)") }, modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val t = total.toLongOrNull() ?: 0
                    val c = count.toIntOrNull() ?: 0
                    when {
                        t !in 1..10_000 -> error = "total must be an integer between 1 and 10000 PC."
                        c !in 1..50 -> error = "count must be an integer between 1 and 50."
                        c > t -> error = "count must be ≤ total so every grab wins at least 1 PC."
                        else -> onSend(t, c, note.trim().ifBlank { null })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send red packet") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Tic-tac-toe create (F-RO-07) ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSheet(
    members: List<Pair<String, String>>, // id → name (viewer excluded by caller)
    onDismiss: () -> Unit,
    onCreate: (opponentId: String?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("⚔️ Tic-tac-toe", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Open challenges seat the first taker; direct invites lock the O seat.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onCreate(null) }, modifier = Modifier.fillMaxWidth()) { Text("Open challenge") }
            if (members.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Or invite someone", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.height(180.dp).verticalScroll(rememberScrollState())) {
                    members.forEach { (id, name) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onCreate(id) }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(26.dp).clip(CircleShape).background(PulsePalette.Emerald.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) { Text(name.take(1).uppercase(), fontSize = 12.sp, color = PulsePalette.Emerald) }
                            Spacer(Modifier.width(8.dp))
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ── Tournament create (F-RO-08) ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TournamentSheet(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("🏆 New tournament", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Season runs until someone finishes it. Match wins feed the standings (+25 XP).", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text("Season name (1–40)") }, modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (name.trim().isEmpty()) error = "name must be between 1 and 40 characters."
                    else onCreate(name.trim())
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start season") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Kanban board (F-RO-04) ───────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanSheet(
    conversationId: String,
    viewerId: String,
    isAdmin: Boolean,
    loadBoard: suspend () -> KanbanPageDto?,
    onAddCard: (title: String, column: String, assigneeId: String?) -> Unit,
    onMoveCard: (cardId: String, column: String, position: Long?) -> Unit,
    onDeleteCard: (cardId: String) -> Unit,
    prefillTitle: String?,
    onDismiss: () -> Unit,
) {
    var board by remember { mutableStateOf<KanbanPageDto?>(null) }
    var addColumn by remember { mutableStateOf<String?>(null) }
    var addTitle by remember { mutableStateOf(prefillTitle ?: "") }
    var ticking by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { addTitle = prefillTitle ?: addTitle }

    // 1500 ms poll while open (web parity), paused by ticking=false.
    LaunchedEffect(ticking) {
        while (ticking) {
            loadBoard()?.let { board = it }
            delay(1_500)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 14.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🗂️ Kanban", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { ticking = !ticking }) { Text(if (ticking) "Pause" else "Live") }
            }
            val cards = board?.cards ?: emptyList()
            PulseWave7Logic.KANBAN_COLUMNS.forEach { col ->
                val colCards = cards.filter { it.column == col }.sortedBy { it.position }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (col) {
                            "todo" -> "To do"
                            "doing" -> "Doing"
                            else -> "Done"
                        } + " · ${colCards.size}",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { addColumn = if (addColumn == col) null else col; addTitle = prefillTitle ?: "" }) { Text("+ Add") }
                }
                if (addColumn == col) {
                    OutlinedTextField(
                        value = addTitle, onValueChange = { addTitle = it.take(120) },
                        label = { Text("Task title") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = {
                            if (addTitle.isNotBlank()) {
                                onAddCard(addTitle.trim(), col, null)
                                addTitle = ""
                                addColumn = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add card") }
                }
                colCards.forEach { card ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(card.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val who = listOfNotNull(
                                card.assigneeName?.let { "→ $it" },
                                card.createdByName?.let { "by $it" },
                            ).joinToString(" · ")
                            if (who.isNotEmpty()) {
                                Text(who, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        // move controls — native-native equivalent of web drag/drop
                        if (col != "todo") {
                            Text("‹", Modifier.clickable { onMoveCard(card.id, PulseWave7Logic.KANBAN_COLUMNS[colIndex(col) - 1], null) }.padding(6.dp), fontSize = 18.sp)
                        }
                        if (col != "done") {
                            Text("›", Modifier.clickable { onMoveCard(card.id, PulseWave7Logic.KANBAN_COLUMNS[colIndex(col) + 1], null) }.padding(6.dp), fontSize = 18.sp)
                        }
                        if (card.createdById == viewerId || isAdmin) {
                            Text("✕", Modifier.clickable { onDeleteCard(card.id) }.padding(6.dp), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun colIndex(col: String): Int = PulseWave7Logic.KANBAN_COLUMNS.indexOf(col).coerceAtLeast(0)

// ── Whiteboard (F-RO-03) ─────────────────────────────────────────────────

/**
 * R2-C item 4 — the durable pending-stroke draft hooks (iOS
 * PulseWhiteboardDraft parity): the sheet writes through on every drawn
 * stroke, restores + dedupes on open, purges on the server flush verdict
 * and on the board-reset path. Backed by the per-conversation prefs store
 * (`whiteboard.draft:<conversationId>`), so strokes survive sheet close,
 * process death and crashes until the server verdicts them.
 */
class WhiteboardDraftHooks(
    val load: suspend () -> List<WhiteboardStrokePostDto>,
    val append: (WhiteboardStrokePostDto) -> Unit,
    val dropFirst: (Int) -> Unit,
    val dropLast: () -> Unit,
    val replaceAll: (List<WhiteboardStrokePostDto>) -> Unit,
    val clear: () -> Unit,
)

/** Triple(color,width,pts) → wire post (the draft store's shape). */
private fun Triple<String, Float, List<Pair<Float, Float>>>.toStrokePost() = WhiteboardStrokePostDto(
    color = first,
    width = second.toDouble(),
    points = third.map { listOf(it.first.toDouble(), it.second.toDouble()) },
)

private fun WhiteboardStrokePostDto.toStrokeTriple() =
    Triple(color, width.toFloat(), points.map { it[0].toFloat() to it[1].toFloat() })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhiteboardSheet(
    conversationId: String,
    viewerId: String,
    load: suspend (since: Long?) -> WhiteboardPageDto?,
    onStrokes: suspend (List<WhiteboardStrokePostDto>) -> Boolean,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    /** R2-C item 4 — the durable pending-stroke draft (required). */
    draft: WhiteboardDraftHooks,
) {
    var color by remember { mutableStateOf(W7_COLORS[0]) }
    var width by remember { mutableStateOf(3f) }
    var localStrokes by remember { mutableStateOf(listOf<Triple<String, Float, List<Pair<Float, Float>>>>()) } // color, width, pts (0..1)
    var remote by remember { mutableStateOf(WhiteboardPageDto()) }
    var confirmClear by remember { mutableStateOf(false) }
    var lastServerTime by remember { mutableStateOf(0L) }
    var resetAt by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        load(null)?.let { remote = it; lastServerTime = it.serverTime ?: 0; resetAt = it.resetAt ?: 0 }
        // R2-C item 4 — restore the durable draft on open, minus the strokes
        // the snapshot ALREADY carries (they landed before the crash; the
        // dedupe pass rewrites the store — iOS PulseWhiteboardDraft
        // .droppingSynced/.replaceAll parity).
        val pending = draft.load()
        if (pending.isNotEmpty()) {
            val restored = app.pulse.protocol.PulseWhiteboardDraftLogic.droppingSynced(pending, remote.strokes)
            if (restored.size != pending.size) draft.replaceAll(restored)
            if (restored.isNotEmpty()) {
                localStrokes = localStrokes + restored.map { it.toStrokeTriple() }
            }
        }
    }
    // since-delta poll at 900 ms (web whiteboard-sheet.tsx:66)
    LaunchedEffect(Unit) {
        while (true) {
            delay(900)
            load(lastServerTime)?.let { page ->
                val st = page.serverTime ?: 0L
                if (st > lastServerTime) lastServerTime = st
                page.resetAt?.let { r -> if (r > resetAt) { resetAt = r; remote = WhiteboardPageDto(); localStrokes = emptyList(); draft.clear() } }
                if (page.strokes.isNotEmpty()) {
                    remote = WhiteboardPageDto(
                        strokes = remote.strokes + page.strokes,
                        serverTime = st,
                        resetAt = page.resetAt,
                    )
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🖌️ Whiteboard", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { draft.dropLast(); onUndo() }) { Text("Undo") }
                TextButton(onClick = { if (confirmClear) onClear() else confirmClear = true }) {
                    Text(if (confirmClear) "Tap again to clear" else "Clear", color = if (confirmClear) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                W7_COLORS.forEach { c ->
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(c)))
                            .border(
                                width = if (color == c) 3.dp else 1.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = CircleShape,
                            )
                            .clickable { color = c },
                    )
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Slider(
                    value = width, onValueChange = { width = it }, valueRange = 0.5f..40f,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                )
            }
            Spacer(Modifier.height(8.dp))
            var currentPts by remember { mutableStateOf(listOf<Pair<Float, Float>>()) }
            val canvasSize = 320f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .pointerInput(color, width) {
                        detectTapGestures(onPress = { offset ->
                            currentPts = listOf(Pair(offset.x / size.width, offset.y / size.height))
                            try {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val p = event.changes.firstOrNull()?.position ?: break
                                        currentPts = currentPts + listOf(Pair(p.x / size.width, p.y / size.height))
                                        event.changes.forEach { it.consume() }
                                        if (!event.changes.any { it.pressed }) break
                                    }
                                }
                            } finally {
                                if (currentPts.size >= 2) {
                                    val stroke = Triple(color, width, currentPts.takeLast(500))
                                    localStrokes = localStrokes + listOf(stroke)
                                    // R2-C item 4 — draw-time write-through: the
                                    // stroke is durable BEFORE any sync attempt.
                                    draft.append(stroke.toStrokePost())
                                }
                                currentPts = emptyList()
                            }
                        })
                    },
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(320.dp)) {
                    val all = remote.strokes.map { s -> Triple(s.color, s.width.toFloat(), s.points.map { Pair(it[0].toFloat(), it[1].toFloat()) }) } + localStrokes
                    all.forEach { (c, w, pts) ->
                        if (pts.size >= 2) {
                            val paint = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(c))
                            for (i in 1 until pts.size) {
                                drawLine(
                                    paint,
                                    androidx.compose.ui.geometry.Offset(pts[i - 1].first * size.width, pts[i - 1].second * size.height),
                                    androidx.compose.ui.geometry.Offset(pts[i].first * size.width, pts[i].second * size.height),
                                    Stroke(width = w.dp.toPx()).width,
                                )
                            }
                        }
                    }
                    if (currentPts.size >= 2) {
                        for (i in 1 until currentPts.size) {
                            drawLine(
                                androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(color)),
                                androidx.compose.ui.geometry.Offset(currentPts[i - 1].first * size.width, currentPts[i - 1].second * size.height),
                                androidx.compose.ui.geometry.Offset(currentPts[i].first * size.width, currentPts[i].second * size.height),
                                Stroke(width = width.dp.toPx()).width,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Strokes sync live (~1 s). Coordinates are normalized 0..1 on the server.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // flush pending strokes in batches of ≤40 — a batch leaves the
            // durable draft only when the server verdicts the POST
            // (R2-C item 4: failures keep the strokes pending; the VM toast
            // stays honest and the next sheet open re-syncs them).
            val flushable = localStrokes
            if (flushable.isNotEmpty()) {
                LaunchedEffect(flushable.size) {
                    while (localStrokes.isNotEmpty()) {
                        val batch = localStrokes.take(40)
                        val accepted = onStrokes(batch.map { it.toStrokePost() })
                        if (accepted) draft.dropFirst(batch.size)
                        localStrokes = localStrokes.drop(batch.size)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Events (F-RO-05) ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsSheet(
    conversationId: String,
    viewerId: String,
    isAdmin: Boolean,
    load: suspend () -> List<GroupEventDto>?,
    onCreate: (title: String, startsAtIso: String, description: String?, location: String?) -> Unit,
    onRsvp: (eventId: String, status: String) -> Unit,
    onCheckin: (eventId: String) -> Unit,
    onDelete: (eventId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var events by remember { mutableStateOf<List<GroupEventDto>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { while (true) { load()?.let { events = it }; delay(4_000); tick++ } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📅 Events", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { creating = !creating }) { Text(if (creating) "Close" else "+ New") }
            }
            if (creating) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(120) }, label = { Text("Event title") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = dateText, onValueChange = { dateText = it.take(16) }, label = { Text("Date (YYYY-MM-DD HH:mm)") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(value = location, onValueChange = { location = it.take(200) }, label = { Text("Location (optional)") }, modifier = Modifier.fillMaxWidth())
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val parsed = parseEventDate(dateText)
                        if (title.isBlank()) error = "title must be 1-120 characters."
                        else if (parsed == null) error = "startsAt must be a parseable ISO date string."
                        else {
                            error = null
                            onCreate(title.trim(), parsed, null, location.trim().ifBlank { null })
                            title = ""; dateText = ""; location = ""; creating = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Schedule event") }
                Spacer(Modifier.height(10.dp))
            }
            if (events.isEmpty()) {
                Text("No events yet — schedule the first one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            events.forEach { e ->
                val counts = e.counts ?: EventCountsDto()
                val startsMs = runCatching { Instant.parse(e.startsAt).toEpochMilli() }.getOrDefault(0L)
                val inWindow = PulseWave7Logic.checkinWindowOpen(startsMs, System.currentTimeMillis())
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.createdById == viewerId || isAdmin) {
                            Text("✕", Modifier.clickable { onDelete(e.id) }.padding(4.dp), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    e.location?.takeIf { it.isNotBlank() }?.let {
                        Text("📍 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatEventTime(e.startsAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PulseWave7Logic.RSVP_STATUSES.forEach { s ->
                            val selected = e.myStatus == s
                            val label = when (s) {
                                "going" -> "Going"
                                "maybe" -> "Maybe"
                                else -> "Can't"
                            }
                            OutlinedButton(
                                onClick = { onRsvp(e.id, s) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selected) PulsePalette.Emerald.copy(alpha = 0.2f) else Color.Transparent,
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) { Text("$label ${countOf(counts, s)}", fontSize = 12.sp) }
                        }
                    }
                    if (e.myStatus != null && e.myStatus != "no" && inWindow) {
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = { onCheckin(e.id) }, modifier = Modifier.fillMaxWidth()) { Text("Check in · +15 XP") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun countOf(c: EventCountsDto, s: String): Int = when (s) {
    "going" -> c.going
    "maybe" -> c.maybe
    else -> c.no
}

/** Accepts "YYYY-MM-DD HH:mm" (local) or ISO strings → ISO-8601 UTC wire form. */
internal fun parseEventDate(text: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    runCatching {
        return Instant.parse(t.replace(" ", "T").let { if (it.endsWith("Z") || it.contains("+")) it else "$it:00Z" }).toString()
    }
    return runCatching {
        val d = LocalDateTime.parse(t.replace("T", " ").let { it }, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        d.atZone(ZoneId.systemDefault()).toInstant().toString()
    }.getOrElse {
        runCatching { LocalDate.parse(t).atTime(19, 0).atZone(ZoneId.systemDefault()).toInstant().toString() }.getOrNull()
    }
}

internal fun formatEventTime(iso: String?): String {
    val ms = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0)
    if (ms <= 0) return "time TBD"
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return d.format(DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm"))
}

// ── Reminders (F-RO-06) ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersSheet(
    viewerId: String,
    load: suspend () -> RemindersPageDto?,
    onCreate: (note: String, remindAtIso: String, anchoredMessageId: String?) -> Unit,
    onResolve: (id: String) -> Unit,
    onDelete: (id: String) -> Unit,
    // R7 item 4 — web REMINDER_JUMP_EVENT parity: rows anchored to a message
    // offer Jump (host dismisses the sheet + jumps/flash-engines there).
    onJump: (item: app.pulse.protocol.ReminderItemDto) -> Unit = {},
    anchoredMessageId: String?,
    onDismiss: () -> Unit,
) {
    var items by remember { mutableStateOf(listOf<app.pulse.protocol.ReminderItemDto>()) }
    var note by remember { mutableStateOf("") }
    var when_ by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { while (true) { load()?.let { items = it.items }; delay(5_000); tick++ } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Text("⏰ Reminders", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Fires locally even offline; resolves to the server when online.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(280) },
                label = { Text(if (anchoredMessageId != null) "Note (anchored to a message)" else "Note") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = when_,
                onValueChange = { when_ = it },
                label = { Text("When — \"in 30m\", \"tomorrow\", \"2026-01-20 09:00\"") },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    val zone = ZoneId.systemDefault()
                    val parsed = PulseWave7Logic.parseRelativeReminder(when_, now, zone)
                    val iso = parsed?.let {
                        java.time.Instant.ofEpochMilli(it.remindAtEpochMs).toString()
                    } ?: parseEventDate(when_)
                    val finalNote = parsed?.note ?: note.trim()
                    when {
                        iso == null -> error = "No sane time found — try \"in 30m\", \"tomorrow\" or an exact date."
                        finalNote.isEmpty() -> error = "Note can't be empty."
                        else -> {
                            error = null
                            onCreate(finalNote, iso, anchoredMessageId)
                            note = ""; when_ = ""
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set reminder") }
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) {
                Text("No reminders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items.forEach { r ->
                val due = r.firedAt == null && (runCatching { Instant.parse(r.remindAt).toEpochMilli() }.getOrDefault(Long.MAX_VALUE) <= System.currentTimeMillis())
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.note.ifBlank { "Reminder" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (due) FontWeight.SemiBold else FontWeight.Normal)
                        Text(
                            (if (due) "DUE NOW — " else "") + formatEventTime(r.remindAt) + (r.conversation.name.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            fontSize = 12.sp,
                            color = if (due) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (r.messageId != null && r.firedAt == null) {
                        // R7 item 4 — jump to the anchored message (web row tap
                        // parity, reminders-sheet.tsx jump()); the host closes
                        // the sheet and routes the jump.
                        Text(
                            "Jump",
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onJump(r) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            color = PulsePalette.Emerald,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                        )
                    }
                    if (r.firedAt == null) {
                        Text("✓", Modifier.clickable { onResolve(r.id) }.padding(6.dp), color = PulsePalette.Emerald)
                    }
                    Text("✕", Modifier.clickable { onDelete(r.id) }.padding(6.dp), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Leaderboard (F-RO-09) ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeaderboardSheet(
    loadRoom: suspend () -> LeaderboardPageDto?,
    loadGlobal: suspend () -> LeaderboardPageDto?,
    onDismiss: () -> Unit,
) {
    var scope by remember { mutableStateOf(true) } // true = this room, false = global
    var page by remember { mutableStateOf<LeaderboardPageDto?>(null) }
    LaunchedEffect(scope) { page = if (scope) loadRoom() else loadGlobal() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏅 Leaderboard", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope = !scope }) { Text(if (scope) "This room" else "Global") }
            }
            val rows = page?.rows ?: emptyList()
            if (rows.isEmpty()) Text("No standings yet — send messages, win games, join tournaments.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.forEachIndexed { i, r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("#${i + 1}", color = PulsePalette.Emerald, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
                    Text(r.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.tournamentPoints}p · ${r.gameWins}W · ${r.xp} XP", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
