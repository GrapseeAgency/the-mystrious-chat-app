package app.pulse.android.mirror

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.model.ScheduledItem
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.AiRecapDto
import app.pulse.protocol.ReminderItemDto
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R69-a - the room tools sheets, converted 1:1 from the web sources:
 *   - MirrorRemindersSheet  <- src/components/chat/reminders-sheet.tsx
 *     (upcoming vs History groups, live countdown badge, per-row cancel =
 *     DELETE + resolve = PATCH, pending spinners, retry on load failure).
 *     The web sheet has NO create form - reminders are created from the
 *     message "Remind me" picker - so none is invented here.
 *   - MirrorScheduledSheet  <- chat-room.tsx ScheduledListDrawer
 *     (delayed sends with stamp + content rows, refused rows, per-row
 *     cancel, web-exact empty copy, pinned Close footer).
 *   - MirrorRecapSheet      <- chat-room.tsx R34-b AI recap card
 *     (real LLM recap via aiRecap on open, "Based on N messages", Copy
 *     pill, "Reading the room" spinner, honest error + retry).
 * Every button calls a real PulseRepository method. Zero mock data, zero
 * dead controls. Rows are NOT tappable: the web's tap-to-jump needs a
 * room-navigation callback this sheet signature does not carry.
 */

// zinc/amber/rose/violet dark-mode truths these sheets render (tailwind hex).
private object ToolsInk {
    val Zinc200 = Color(0xFFE4E4E7)
    val Zinc300 = Color(0xFFD4D4D8)
    val Zinc500 = Color(0xFF71717A)
    val Zinc700 = Color(0xFF3F3F46)
    val Zinc800 = Color(0xFF27272A)
    val Amber400 = Color(0xFFFBBF24)
    val Amber500 = Color(0xFFF59E0B)
    val Amber600 = Color(0xFFD97706)
    val Rose400 = Color(0xFFFB7185)
    val Rose500 = Color(0xFFEF4444)
    val Rose300Soft = Color(0xB3FDA4AF) // rose-300/70 - refused row body
    val Violet400 = Color(0xFFA78BFA)
}

private val TOOLS_HM: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.systemDefault())
private val TOOLS_DAY_MONTH: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneId.systemDefault())
private val TOOLS_WEEKDAY: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE", Locale.US).withZone(ZoneId.systemDefault())

private const val TOOL_OP_RESOLVE = "resolve"
private const val TOOL_OP_DELETE = "delete"

/** Tolerant parse (ISO first, epoch fallback) into the device zone. */
private fun toolsWhen(iso: String?): ZonedDateTime? =
    MirrorRoomInstant(iso ?: "")?.atZone(ZoneId.systemDefault())

/** web formatListStamp: today HH:mm, yesterday "Yesterday", else "Aug 3". */
private fun toolsListStamp(whenAt: ZonedDateTime, now: ZonedDateTime): String =
    when (whenAt.toLocalDate()) {
        now.toLocalDate() -> TOOLS_HM.format(whenAt)
        now.minusDays(1).toLocalDate() -> "Yesterday"
        else -> TOOLS_DAY_MONTH.format(whenAt)
    }

/** web formatReminderCountdown (reminders-sheet.tsx L73-90), en-US truth. */
private fun toolsCountdown(remindAt: ZonedDateTime, now: ZonedDateTime): String {
    val diffMs = Duration.between(now.toInstant(), remindAt.toInstant()).toMillis()
    if (diffMs <= 45_000L) return "due now"
    if (diffMs < 3_600_000L) {
        val mins = kotlin.math.round(diffMs / 60_000.0).toLong().coerceAtLeast(1L)
        return "in ${mins}m"
    }
    val hours = diffMs / 3_600_000L
    if (remindAt.toLocalDate() == now.plusDays(1).toLocalDate()) {
        return "tomorrow " + TOOLS_HM.format(remindAt)
    }
    if (hours < 24L) return "in ${hours}h"
    if (!remindAt.toInstant().isAfter(now.plusDays(7).toInstant())) {
        return TOOLS_WEEKDAY.format(remindAt) + " " + TOOLS_HM.format(remindAt)
    }
    return TOOLS_DAY_MONTH.format(remindAt) + ", " + TOOLS_HM.format(remindAt)
}

/** web reminderText: collapsed note, else collapsed snippet, else fallback. */
private fun toolsReminderText(item: ReminderItemDto): String {
    val note = item.note.replace(Regex("\\s+"), " ").trim()
    if (note.isNotEmpty()) return note
    val snippet = (item.snippet ?: "").replace(Regex("\\s+"), " ").trim()
    if (snippet.isNotEmpty()) return snippet
    return if (item.messageId == null) "Reminder" else "Message reminder"
}

// shared bits ================================================================

/** web LoaderCircle animate-spin, native rotation. */
@Composable
private fun ToolsSpinner(tint: Color, modifier: Modifier = Modifier) {
    val spin = rememberInfiniteTransition(label = "toolsSpinner")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
        ),
        label = "toolsSpinnerAngle",
    )
    MirrorLucideIcon("LLoaderCircle", tint = tint, modifier = modifier.graphicsLayer { rotationZ = angle })
}

/** web Skeleton (animate-pulse) row used by the loading states. */
@Composable
private fun ToolsSkeletonRow(widthFraction: Float = 1f, heightDp: Int = 56) {
    val pulse = rememberInfiniteTransition(label = "toolsSkeleton")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "toolsSkeletonAlpha",
    )
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(heightDp.dp)
            .graphicsLayer { alpha = pulseAlpha }
            .clip(RoundedCornerShape(12.dp))
            .background(MirrorArt.White7),
    )
}

/** Small glass retry pill (task-mandated retry affordance, sheet glass language). */
@Composable
private fun ToolsRetryPill(onRetry: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(MirrorArt.White7)
            .border(1.dp, MirrorArt.Hairline, CircleShape)
            .clickable(onClick = onRetry)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MirrorLucideIcon("LRefreshCw", tint = MirrorArt.Accent2, modifier = Modifier.size(12.dp))
        Text("Retry", color = MirrorArt.Accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Inline load-error row: icon + message + retry (no toast system in the mirror). */
@Composable
private fun ToolsLoadError(message: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MirrorLucideIcon("LCloudOff", tint = MirrorArt.Red, modifier = Modifier.size(16.dp))
        Text(message, color = MirrorArt.Faint, fontSize = 13.sp, modifier = Modifier.weight(1f))
        ToolsRetryPill(onRetry = onRetry)
    }
}

/**
 * 28dp circular action button (web cancel/resolve affordance): spinner while
 * this row's op is pending, pressed tint instead of the web hover tint, and
 * no ripple (web has none).
 */
@Composable
private fun ToolsIconButton(
    icon: String,
    iconTint: Color,
    pressedTint: Color,
    pending: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (pressed) pressedTint else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (pending) {
            ToolsSpinner(iconTint, Modifier.size(14.dp))
        } else {
            MirrorLucideIcon(icon, tint = iconTint, modifier = Modifier.size(14.dp))
        }
    }
}

// reminders (web reminders-sheet.tsx) ========================================

/**
 * The viewer's reminders sheet: upcoming rows with live countdown + cancel
 * (DELETE) + resolve (PATCH), collapsible History group of fired rows at
 * 60% opacity, skeleton loading, honest retry on load failure, web-exact
 * empty copy. viewerId/conversationId stay in the signature for call-site
 * symmetry with the web (the gateway scopes reminders to THIS identity).
 */
@Composable
internal fun MirrorRemindersSheet(
    repository: PulseRepository,
    viewerId: String,
    conversationId: String,
    onDismiss: () -> Unit,
) {
    // null = first load pending; empty = loaded and really empty
    var items by remember { mutableStateOf<List<ReminderItemDto>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    // per-row in-flight op: reminder id -> "resolve" | "delete" (web variables+isPending)
    var pendingOps by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var actionFailedOp by remember { mutableStateOf<String?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    val now = remember { ZonedDateTime.now() }

    // fetch on open (+ after every mutation settle, web onSettled invalidate)
    LaunchedEffect(reloadKey) {
        loadFailed = false
        actionFailedOp = null
        if (items == null) {
            // paint the last good snapshot instantly when one exists
            val cached = runCatching { repository.cachedReminders() }.getOrNull()
            if (cached != null && items == null) items = cached.items
        }
        runCatching { repository.reminders(false).getOrThrow() }
            .fold(
                onSuccess = { page ->
                    items = page.items
                    loadFailed = false
                },
                onFailure = {
                    // keep cached rows visible (stale-while-error); only a
                    // fully cold load becomes an honest error with retry
                    if (items == null) loadFailed = true
                },
            )
    }

    fun runAction(id: String, op: String) {
        if (pendingOps.containsKey(id)) return
        actionFailedOp = null
        pendingOps = pendingOps + (id to op)
        CoroutineScope(Dispatchers.IO).launch {
            val outcome = runCatching {
                if (op == TOOL_OP_RESOLVE) {
                    repository.resolveReminder(id).getOrThrow()
                } else {
                    repository.deleteReminder(id).getOrThrow()
                }
            }
            withContext(Dispatchers.Main) {
                pendingOps = pendingOps - id
                outcome.fold(
                    onSuccess = {
                        // web cancelReminder: optimistic removal, then refetch.
                        // resolve keeps the row; the refetch moves it to History.
                        if (op == TOOL_OP_DELETE) {
                            items = items?.filterNot { rem -> rem.id == id }
                        }
                        reloadKey++
                    },
                    onFailure = { actionFailedOp = op },
                )
            }
        }
    }

    val loaded = items
    val upcoming = loaded?.filter { it.firedAt == null } ?: emptyList()
    val history = loaded
        ?.filter { it.firedAt != null }
        ?.sortedByDescending { toolsWhen(it.remindAt)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE }
        ?: emptyList()
    val countLabel = if (upcoming.size == 1) "1 reminder" else "${upcoming.size} reminders"

    MirrorSheet(title = countLabel, onDismiss = onDismiss) {
        MirrorSheetScroll {
            if (loadFailed) {
                ToolsLoadError(message = "Could not load reminders", onRetry = { reloadKey++ })
            } else if (loaded == null) {
                Column(
                    Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolsSkeletonRow()
                    ToolsSkeletonRow(widthFraction = 0.8333f)
                }
            } else if (upcoming.isEmpty() && history.isEmpty()) {
                Text(
                    "No reminders yet - long-press a message and choose Remind me.",
                    color = ToolsInk.Zinc500,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (upcoming.isEmpty()) {
                        Text(
                            "Nothing upcoming.",
                            color = ToolsInk.Zinc500,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    } else {
                        for (item in upcoming) {
                            val rowOp = pendingOps[item.id]
                            ToolsReminderRow(
                                item = item,
                                fired = false,
                                resolvePending = rowOp == TOOL_OP_RESOLVE,
                                deletePending = rowOp == TOOL_OP_DELETE,
                                now = now,
                                onResolve = { runAction(item.id, TOOL_OP_RESOLVE) },
                                onDelete = { runAction(item.id, TOOL_OP_DELETE) },
                            )
                        }
                    }
                }
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { historyOpen = !historyOpen }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // web History glyph is not in the mirror icon set; the
                        // clock reads the same "past" language at this size
                        MirrorLucideIcon("LClock", tint = ToolsInk.Zinc500, modifier = Modifier.size(12.dp))
                        Text(
                            "History · ${history.size}".uppercase(Locale.US),
                            color = ToolsInk.Zinc500,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        MirrorLucideIcon(
                            "LChevronRight",
                            tint = ToolsInk.Zinc500,
                            modifier = Modifier
                                .size(12.dp)
                                .graphicsLayer { rotationZ = if (historyOpen) -90f else 90f },
                        )
                    }
                    if (historyOpen) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (item in history) {
                                ToolsReminderRow(
                                    item = item,
                                    fired = true,
                                    resolvePending = false,
                                    deletePending = false,
                                    now = now,
                                    onResolve = {},
                                    onDelete = {},
                                )
                            }
                        }
                    }
                }
                if (actionFailedOp != null) {
                    Text(
                        if (actionFailedOp == TOOL_OP_DELETE) "Could not cancel the reminder" else "Could not update the reminder",
                        color = MirrorArt.Red,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** One reminder row (web renderRow): icon circle, chat name, note, countdown. */
@Composable
private fun ToolsReminderRow(
    item: ReminderItemDto,
    fired: Boolean,
    resolvePending: Boolean,
    deletePending: Boolean,
    now: ZonedDateTime,
    onResolve: () -> Unit,
    onDelete: () -> Unit,
) {
    val whenAt = toolsWhen(item.remindAt)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (fired) 0.6f else 1f)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (fired) Color(0x1A71717A) else Color(0x1AF59E0B)),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon(
                if (fired) "LBellOff" else "LBell",
                tint = if (fired) ToolsInk.Zinc500 else ToolsInk.Amber600,
                modifier = Modifier.size(12.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.conversation.name,
                color = ToolsInk.Zinc200,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                toolsReminderText(item),
                color = ToolsInk.Zinc300,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!fired) {
            // malformed stamp must never become a blank badge (R67 lesson)
            if (whenAt != null) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color(0x1AF59E0B))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        toolsCountdown(whenAt, now),
                        color = ToolsInk.Amber400,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // task R69-a: resolve = PATCH mark-handled, own pending spinner
            ToolsIconButton(
                icon = "LCheck",
                iconTint = MirrorArt.Accent2,
                pressedTint = Color(0x29FFB86B),
                pending = resolvePending,
                onClick = onResolve,
            )
            // web cancel button: BellOff, rose hover tint
            ToolsIconButton(
                icon = "LBellOff",
                iconTint = ToolsInk.Zinc500,
                pressedTint = Color(0x1AEF4444),
                pending = deletePending,
                onClick = onDelete,
            )
        } else {
            Text(
                "fired",
                color = ToolsInk.Zinc500,
                fontSize = 10.sp,
            )
        }
    }
}

// scheduled sends (web ScheduledListDrawer) ==================================

/**
 * The conversation's pending delayed sends: count title, refused rows in
 * rose ("Not sent - blocked"), per-row cancel wired to cancelScheduled with
 * a spinner, web-exact empty copy, and the pinned Close footer. Refused rows
 * reappear after a cancel refetch exactly like the web (server truth wins).
 */
@Composable
internal fun MirrorScheduledSheet(
    repository: PulseRepository,
    conversationId: String,
    onDismiss: () -> Unit,
) {
    var items by remember { mutableStateOf<List<ScheduledItem>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var cancelling by remember { mutableStateOf<Set<String>>(emptySet()) }
    var cancelFailed by remember { mutableStateOf(false) }
    val now = remember { ZonedDateTime.now() }

    LaunchedEffect(reloadKey) {
        loadFailed = false
        cancelFailed = false
        runCatching { repository.scheduledMessages(conversationId).getOrThrow() }
            .fold(
                onSuccess = { page ->
                    items = page
                    loadFailed = false
                },
                onFailure = { if (items == null) loadFailed = true },
            )
    }

    fun cancel(id: String) {
        if (cancelling.contains(id)) return
        cancelFailed = false
        cancelling = cancelling + id
        CoroutineScope(Dispatchers.IO).launch {
            val outcome = runCatching { repository.cancelScheduled(id).getOrThrow() }
            withContext(Dispatchers.Main) {
                cancelling = cancelling - id
                outcome.fold(
                    onSuccess = {
                        // web cancelScheduled.onSuccess refetches; a refused
                        // row re-renders in its rose "blocked" skin
                        reloadKey++
                    },
                    onFailure = { cancelFailed = true },
                )
            }
        }
    }

    val loaded = items
    val count = loaded?.size ?: 0
    val countLabel = when {
        count == 0 -> "Nothing scheduled"
        count == 1 -> "1 scheduled message"
        else -> "$count scheduled messages"
    }

    MirrorSheet(title = countLabel, onDismiss = onDismiss) {
        MirrorSheetScroll {
            if (loadFailed) {
                ToolsLoadError(message = "Could not load scheduled messages", onRetry = { reloadKey++ })
            } else if (loaded == null) {
                Column(
                    Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolsSkeletonRow()
                    ToolsSkeletonRow()
                }
            } else if (loaded.isEmpty()) {
                Text(
                    "Draft a message and choose "Schedule message" - it sends itself later.",
                    color = ToolsInk.Zinc500,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            } else {
                Column(
                    Modifier.padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (item in loaded) {
                        ToolsScheduledRow(
                            item = item,
                            cancelPending = cancelling.contains(item.id),
                            now = now,
                            onCancel = { cancel(item.id) },
                        )
                    }
                }
            }
            if (cancelFailed) {
                Text(
                    "Could not cancel",
                    color = MirrorArt.Red,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ToolsInk.Zinc800)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Text("Close", color = ToolsInk.Zinc300, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One scheduled-send card (web ScheduledListDrawer li): stamp head + content. */
@Composable
private fun ToolsScheduledRow(
    item: ScheduledItem,
    cancelPending: Boolean,
    now: ZonedDateTime,
    onCancel: () -> Unit,
) {
    val refused = item.cancelledAtIso != null
    val whenAt = toolsWhen(item.scheduledAtIso)
    val stampLine = if (refused) {
        val stamp = whenAt?.let { toolsListStamp(it, now) } ?: ""
        "Not sent - blocked · was $stamp".uppercase(Locale.US)
    } else {
        val stamp = whenAt?.let { toolsListStamp(it, now) } ?: ""
        val time = whenAt?.let { TOOLS_HM.format(it) } ?: ""
        "$stamp · $time".uppercase(Locale.US)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (refused) Color(0x14EF4444) else Color(0x9927272A))
            .border(1.dp, if (refused) Color(0x40EF4444) else ToolsInk.Zinc700, RoundedCornerShape(16.dp))
            .padding(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // web Ban glyph is not in the mirror icon set; the X carries the
            // same "dead row" signal in rose
            MirrorLucideIcon(
                if (refused) "LX" else "LCalendarClock",
                tint = if (refused) ToolsInk.Rose500 else ToolsInk.Amber500,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stampLine,
                color = if (refused) ToolsInk.Rose400 else ToolsInk.Amber400,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.28.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ToolsIconButton(
                icon = "LX",
                iconTint = ToolsInk.Zinc500,
                pressedTint = Color(0x1AEF4444),
                pending = cancelPending,
                onClick = onCancel,
            )
        }
        Text(
            item.content.replace(Regex("\\s+"), " ").trim(),
            color = if (refused) ToolsInk.Rose300Soft else ToolsInk.Zinc300,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textDecoration = if (refused) TextDecoration.LineThrough else null,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// AI recap (web R34-b recap card) ============================================

/**
 * AI recap surface: calls the real LLM recap on open, shows the
 * "Reading the room" spinner, then the recap body with "Based on N
 * messages" and a Copy pill; failures render an honest error with retry.
 * (The web's <5 messages gate lives at the call site - it needs the live
 * message count this sheet does not carry; server errors surface here.)
 */
@Composable
internal fun MirrorRecapSheet(
    repository: PulseRepository,
    conversationId: String,
    onDismiss: () -> Unit,
) {
    var recap by remember { mutableStateOf<AiRecapDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    // on open: real recap (web recapMutation)
    LaunchedEffect(reloadKey) {
        loading = true
        failed = false
        runCatching { repository.aiRecap(conversationId).getOrThrow() }
            .fold(
                onSuccess = { dto ->
                    recap = dto
                    loading = false
                },
                onFailure = {
                    failed = true
                    loading = false
                },
            )
    }
    // the copied note clears itself like the web toast
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    MirrorSheet(title = "AI recap", onDismiss = onDismiss) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0x1A8B5CF6)),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LSparkles", tint = ToolsInk.Violet400, modifier = Modifier.size(16.dp))
            }
            val loadedHead = recap
            Text(
                when {
                    loading -> "Summarizing the latest messages"
                    loadedHead != null -> "Based on ${loadedHead.basedOn} messages"
                    else -> ""
                },
                color = MirrorArt.Faint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            val loadedForCopy = recap
            if (!loading && loadedForCopy != null) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(MirrorArt.White7)
                        .border(1.dp, MirrorArt.Hairline, CircleShape)
                        .clickable {
                            val text = loadedForCopy.recap
                            if (text.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(text))
                                copied = true
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text("Copy", color = MirrorArt.Accent2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (copied) {
            Text(
                "Recap copied",
                color = MirrorArt.Accent2,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        val loadedBody = recap
        when {
            loading -> {
                Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToolsSpinner(MirrorArt.Dim, Modifier.size(14.dp))
                    Text(
                        "Reading the room…",
                        color = MirrorArt.Dim,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            loadedBody != null -> {
                Text(
                    loadedBody.recap,
                    color = MirrorArt.TextSoft,
                    fontSize = 12.5.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            failed -> {
                ToolsLoadError(message = "Recap is unavailable right now", onRetry = { reloadKey++ })
            }
        }
    }
}
