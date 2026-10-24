package app.pulse.android.mirror

import app.pulse.domain.sync.visibleQuote
import app.pulse.domain.sync.MessageHistoryCursor
import kotlinx.coroutines.isActive

import app.pulse.domain.sync.reconcileMessageSelection
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.pulse.core.PulseEndpoints
import app.pulse.core.fx.PulseFx
import app.pulse.domain.model.ConvTheme
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.model.LocationPayload
import app.pulse.domain.model.Message
import app.pulse.domain.model.PulseApiException
import app.pulse.domain.model.QuickPhrase
import app.pulse.domain.model.Topic
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.EffectPayloadDto
import app.pulse.protocol.VoicePeerDto
import app.pulse.protocol.WirePulsePrefs
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

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

private fun groupReactions(message: Message, viewerId: String): List<ReactionChip> = message.reactions
    .distinctBy { app.pulse.protocol.reactionId(it.emoji) to it.userId }
    .groupBy { app.pulse.protocol.reactionId(it.emoji) }
    .map { (id, members) -> ReactionChip(id, members.size, members.any { it.userId == viewerId }) }

/**
 * R74 - submit-time slash text transforms. Web parseSlashResult text cases
 * (chat-room.tsx L445-540) + the reminder parser (reminders-sheet.tsx L130).
 * null = not a slash command, plain send. Sealed so every branch is explicit.
 */
internal sealed class MirrorSlashBody {
    data class Send(val content: String) : MirrorSlashBody()
    data class Error(val message: String) : MirrorSlashBody()
    data class Reminder(val note: String, val remindAtIso: String) : MirrorSlashBody()
}

/** web rollDice L310 - AdM spec, count 1..12, sides 2..1000. */
internal fun mirrorRollDice(spec: String): Pair<List<Int>, Int>? {
    val m = Regex("^(\\d{1,2})d(\\d{1,3})$", RegexOption.IGNORE_CASE).find(spec.trim()) ?: return null
    val count = m.groupValues[1].toInt().coerceIn(1, 12)
    val sides = m.groupValues[2].toInt().coerceIn(2, 1000)
    val rolls = List(count) { kotlin.random.Random.nextInt(sides) + 1 }
    return rolls to rolls.sum()
}

/** web durationToMs - s/m/h/d tokens for the relative reminder parser. */
private fun mirrorDurationToMs(n: Int, token: String): Long? = when (token) {
    "s" -> n * 1_000L
    "m" -> n * 60_000L
    "h" -> n * 3_600_000L
    "d" -> n * 86_400_000L
    else -> null
}

/**
 * web parseRelativeReminder (reminders-sheet.tsx L130): trailing
 * "in 30m" / "30m" / "next week" / "tomorrow" / "tonight" patterns;
 * tomorrow = 09:00 local, tonight = 20:00 local, next week = +7d.
 */
internal fun mirrorParseRelativeReminder(arg: String, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): Pair<String, String>? {
    val input = arg.trim().replace(Regex("\\s+"), " ")
    if (input.isEmpty()) return null
    data class Hit(val regex: Regex, val stripIn: Boolean, val at: (MatchResult) -> java.time.ZonedDateTime?)
    val patterns = listOf(
        Hit(Regex("\\s+in\\s+(\\d+)\\s*([smhd])$", RegexOption.IGNORE_CASE), false) { m ->
            val ms = mirrorDurationToMs(m.groupValues[1].toInt(), m.groupValues[2].lowercase())
            if (ms == null) null else now.plusSeconds(ms / 1000)
        },
        Hit(Regex("\\s+(\\d+)\\s*([smhd])$", RegexOption.IGNORE_CASE), true) { m ->
            val ms = mirrorDurationToMs(m.groupValues[1].toInt(), m.groupValues[2].lowercase())
            if (ms == null) null else now.plusSeconds(ms / 1000)
        },
        Hit(Regex("\\s+next\\s+week$", RegexOption.IGNORE_CASE), false) { now.plusSeconds(7L * 86_400) },
        Hit(Regex("\\s+tomorrow$", RegexOption.IGNORE_CASE), false) {
            now.plusDays(1).with(java.time.LocalTime.of(9, 0))
        },
        Hit(Regex("\\s+tonight$", RegexOption.IGNORE_CASE), false) {
            now.with(java.time.LocalTime.of(20, 0))
        },
    )
    for (hit in patterns) {
        val m = hit.regex.find(input) ?: continue
        val at = hit.at(m) ?: continue
        var note = input.substring(0, m.range.first).trim()
        if (hit.stripIn) note = note.replace(Regex("\\s+in$", RegexOption.IGNORE_CASE), "").trim()
        if (note.isEmpty() || note.equals("in", ignoreCase = true)) return null
        return note to at.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
    return null
}

/** The slash text-command dispatch used by sendNow (web L445-540). */
internal fun mirrorSlashSendBody(inputRaw: String): MirrorSlashBody? {
    val input = inputRaw.trim()
    val m = Regex("^/(\\w+)(?:\\s+([\\s\\S]+))?$").find(input) ?: return null
    val word = m.groupValues[1].lowercase()
    val arg = (m.groupValues[2] ?: "").trim()
    return when (word) {
        "me" -> {
            if (arg.isEmpty()) MirrorSlashBody.Error("Usage: /me waves hello")
            else MirrorSlashBody.Send("_" + arg.take(1998) + "_")
        }
        "shrug" -> MirrorSlashBody.Send("${arg}${if (arg.isNotEmpty()) " " else ""}¯\\_(ツ)_/¯")
        "tableflip" -> MirrorSlashBody.Send("${arg}${if (arg.isNotEmpty()) " " else ""}(╯°□°）╯︵ ┻━┻")
        "unflip" -> MirrorSlashBody.Send("┬┬ ノ( ゜-゜ノ${if (arg.isNotEmpty()) " $arg" else ""}")
        "roll" -> {
            if (arg.isEmpty()) {
                val roll = mirrorRollDice("1d6")
                MirrorSlashBody.Send("Rolled **1d6**: *${roll?.second ?: "?"}*")
            } else {
                val roll = mirrorRollDice(arg)
                if (roll == null) MirrorSlashBody.Error("Usage: /roll AdM - e.g. /roll 2d6")
                else MirrorSlashBody.Send("Rolled **${arg.lowercase()}**: ${roll.first.joinToString(" + ")} = *${roll.second}*")
            }
        }
        "remind" -> {
            val parsed = mirrorParseRelativeReminder(arg)
            if (parsed == null) {
                MirrorSlashBody.Error("Usage: /remind buy milk in 30m - try 30m, 2h, tomorrow, tonight, next week")
            } else {
                MirrorSlashBody.Reminder(parsed.first, parsed.second)
            }
        }
        // R21-a bot commands ride as a normal message - the server bot answers
        "math", "flip", "8ball", "rps", "dice", "time", "wallet" -> MirrorSlashBody.Send(input)
        else -> MirrorSlashBody.Error("Unknown command \"/$word\" - try /help")
    }
}

internal data class MirrorMediaSelection(
    val draft: app.pulse.domain.model.AttachmentDraft,
    val sessionKey: String,
    val draftRevision: Long? = null,
    val submitted: app.pulse.domain.model.AttachmentDraft? = null,
) {
    fun freeze(caption: String): MirrorMediaSelection =
        if (submitted != null) this else copy(submitted = draft.copy(caption = caption.trim()))

    fun withViewOnce(enabled: Boolean): MirrorMediaSelection =
        if (submitted != null || draft.kind != "image") this else copy(draft = draft.copy(viewOnce = enabled))
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
    onOpenLiveRoom: (app.pulse.feature.voice.VoiceRoomsEngine.RoomKind) -> Unit,
    readingEnabled: Boolean = false,
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
    ttlSeconds: Int?,
    isBroadcast: Boolean,
    /** stored group/channel photo (web renders it in the header avatar) */
    avatarPath: String? = null,
    /** R76 - web jumpToMessage (chat-room.tsx:1039): open anchored at one message. */
    jumpMessageId: String? = null,
    jumpRequestId: String? = null,
    onOpenReminder: (MirrorReminderTarget) -> Unit,
    onJumpConsumed: () -> Unit = {},
    onBack: () -> Unit,
    onAddPhrase: (String) -> Unit,
    onDeletePhrase: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onCall: (video: Boolean) -> Unit,
    onRoomInfo: () -> Unit,
    onManageGroup: () -> Unit,
    onMuteChoice: (String?) -> Unit,
    onTtlChoice: (Int) -> Unit,
    roomSettingsBusy: Boolean = false,
    onUnpinMessage: suspend (String) -> Result<Unit>,
    fetchPinned: suspend () -> List<Message>,
    pipActive: Boolean,
    onTogglePip: () -> Unit,
    /** web prefs.reducedMotion - gates every full-screen effect (chat-room L1471). */
    reducedMotion: Boolean = false,
) {
    // R73 - the composer draft is a TextFieldValue: the mention autocomplete
    // needs the caret position to replace the @token in place (web L2923-2961).
    var editing by remember(groupId) { mutableStateOf<Message?>(null) }
    var stagedDraftToken by remember(repository, groupId) { mutableStateOf<app.pulse.domain.model.ComposerDraftToken?>(null) }
    val composerDraft = remember(repository, groupId) {
        RoomComposerDraft { text ->
            if (editing == null) {
                repository.stageDraft(groupId, text)
                stagedDraftToken = repository.captureDraftToken(groupId)
            }
        }
    }
    var draftValue by composerDraft
    var viewOnceGrant by remember(groupId, viewerId) { mutableStateOf<Message?>(null) }
    var openingViewOnce by remember(groupId, viewerId) { mutableStateOf(false) }
    val roomActionsLive = remember(groupId) { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(roomActionsLive) { onDispose { roomActionsLive.set(false) } }
    var composerFocused by remember { mutableStateOf(false) }
    var phraseManagerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var trayOpen by remember { mutableStateOf(false) }
    var roomSearchOpen by remember { mutableStateOf(false) }
    var pinnedOpen by remember { mutableStateOf(false) }
    var muteStripOpen by remember { mutableStateOf(false) }
    var ttlStripOpen by remember { mutableStateOf(false) }
    // R73 - message-level state (web chat-room.tsx): reply bar, edit bar,
    // the long-press action panel and the surfaces it opens.
    var replyTo by remember(groupId) { mutableStateOf<Message?>(null) }
    var draftBeforeEdit by remember(groupId) { mutableStateOf<TextFieldValue?>(null) }
    var replyBeforeEdit by remember(groupId) { mutableStateOf<Message?>(null) }
    fun finishEditing() {
        editing = null
        draftValue = draftBeforeEdit ?: TextFieldValue("")
        replyTo = replyBeforeEdit
        draftBeforeEdit = null
        replyBeforeEdit = null
    }
    var actionFor by remember { mutableStateOf<Message?>(null) }
    var forwardFor by remember { mutableStateOf<Message?>(null) }
    var threadFor by remember { mutableStateOf<Message?>(null) }
    var threadJumpId by remember { mutableStateOf<String?>(null) }
    var infoFor by remember { mutableStateOf<Message?>(null) }
    var pollOpen by remember { mutableStateOf(false) }
    var stickerOpen by remember { mutableStateOf(false) }
    var locationOpen by remember { mutableStateOf(false) }
    val mediaSession = repository.attachmentSessionKey()
    var captionFor by remember(repository, groupId, viewerId, mediaSession) { mutableStateOf<MirrorMediaSelection?>(null) }
    var pendingPhoto by remember(repository, groupId, viewerId, mediaSession) { mutableStateOf<MirrorMediaSelection?>(null) }
    var pendingDocument by remember(repository, groupId, viewerId, mediaSession) { mutableStateOf<MirrorMediaSelection?>(null) }
    var uploadingImage by remember(repository, groupId, viewerId, mediaSession) { mutableStateOf(false) }
    var preparingAttachment by remember(repository, groupId, viewerId, mediaSession) { mutableStateOf(false) }
    var attachmentJob by remember(repository, groupId, viewerId, mediaSession) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(repository, groupId, viewerId, mediaSession) { onDispose { attachmentJob?.cancel() } }
    // R73 - slow mode: server 429 retryAfter drives the countdown chip
    var slowModeUntil by remember { mutableStateOf(0L) }
    var slowModeSecondsLeft by remember { mutableStateOf(0) }
    var sendError by remember { mutableStateOf<String?>(null) }
    // R73 - offline outbox pill (web pulse-outbox: queued sends count)
    val outboxRows by repository.observeOutbox().collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingOutbox = outboxRows.count { it.conversationId == groupId && it.failureReason == null }
    // R73 - scheduled sends chip above the composer (web scheduled chip)
    var scheduledCount by remember(repository, viewerId, groupId) { mutableStateOf(0) }
    var scheduledRefresh by remember { mutableStateOf(0) }
    LaunchedEffect(repository, viewerId, groupId, scheduledRefresh) {
        try {
            val page = repository.scheduledMessages(groupId).getOrThrow()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            scheduledCount = page.count { it.cancelledAtIso == null }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: Exception) {
            if (failure is app.pulse.domain.sync.PrivateScheduleUnavailableException) scheduledCount = 0
        }
    }
    // R73 - composer draft persistence (web pulse-drafts): restore once,
    // debounced 600ms save, clear on send (web saveDraft cadence).
    var draftRestored by remember(groupId) { mutableStateOf(false) }
    LaunchedEffect(groupId) {
        val initialRevision = composerDraft.revision
        val saved = runCatching { repository.observeDraft(groupId).firstOrNull() }.getOrNull()
        if (composerDraft.revision == initialRevision && !saved.isNullOrBlank()) draftValue = TextFieldValue(saved)
        draftRestored = true
    }
    LaunchedEffect(draftValue.text, draftRestored, editing?.id) {
        if (!draftRestored || editing != null) return@LaunchedEffect
        val text = draftValue.text
        val token = stagedDraftToken ?: return@LaunchedEffect
        if (text.isNotBlank()) delay(600)
        runCatching { repository.saveStagedDraft(groupId, text, token) }
    }
    // Keystrokes are staged synchronously by RoomComposerDraft. Do NOT stage on
    // disposal: a queued send may already own this text while its ACK is pending.
    // R69 - the web Conversation menu full set (chat-room.tsx L4096-4454):
    // every row opens the same real surface the web opens.
    var remindersOpen by remember { mutableStateOf(false) }
    var scheduledOpen by remember { mutableStateOf(false) }
    var recapOpen by remember { mutableStateOf(false) }
    var eventsOpen by remember { mutableStateOf(false) }
    var kanbanOpen by remember { mutableStateOf(false) }
    var whiteboardOpen by remember { mutableStateOf(false) }
    var tournamentOpen by remember { mutableStateOf(false) }
    var topicsVisible by remember { mutableStateOf(false) }
    var activeTopicId by remember(groupId) { mutableStateOf<String?>(null) }
    // R74 - the seven parity surfaces: slash palette arming, incognito,
    // red packet, effects queue, effects tray chooser, help sheet.
    var redPacketOpen by remember { mutableStateOf(false) }
    var trayEffectsOpen by remember { mutableStateOf(false) }
    var pendingEffect by remember(groupId) { mutableStateOf<MirrorMessageEffect?>(null) }
    var anonNext by remember(groupId) { mutableStateOf(false) }
    var slashHelpOpen by remember { mutableStateOf(false) }
    val gameOwner = repository.reminderDeliveryOwner()
    var gameBusy by remember(repository, groupId, viewerId, gameOwner) { mutableStateOf(false) }
    val gameScope = rememberCoroutineScope()
    var gameJob by remember(repository, groupId, viewerId, gameOwner) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(repository, groupId, viewerId, gameOwner) { onDispose { gameJob?.cancel() } }
    // full-screen message effects (confetti/lasers/echo/sparkles) - one
    // canvas, queue capped at 3, nonce per run (web L1460-1512). All per-room
    // state keys on groupId so a room switch never replays the old history.
    var activeEffect by remember { mutableStateOf<MirrorActiveEffect?>(null) }
    val effectQueue = remember { mutableStateListOf<MirrorActiveEffect>() }
    val effectNonce = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val seenEffectIds = remember(groupId, activeTopicId) { HashSet<String>() }
    // live bubble geometry for the effect origin (web querySelector data-mid)
    val bubbleRects: SnapshotStateMap<String, Rect> = androidx.compose.runtime.mutableStateMapOf()
    var effectsHostRect by remember { mutableStateOf(Rect.Zero) }
    // menu badges, fetched live each time the menu opens (web queries)
    var pinnedCount by remember { mutableStateOf(0) }
    var upcomingReminders by remember { mutableStateOf(0) }
    // R72 - channel truth (web broadcastLocked chat-room.tsx:2294): only
    // admins post in a broadcast room - the composer is replaced with the
    // locked pill and every input path is gated, not swallowed server-side.
    val viewerIsAdmin = members.any { it.id == viewerId && it.role == "admin" }
    val broadcastLocked = isGroup && isBroadcast && !viewerIsAdmin
    // R72 - DM safety number (web ShieldCheck row chat-room.tsx:4232)
    val dmPeerId = if (!isGroup) members.firstOrNull { it.id != viewerId }?.id else null
    var safetyVerified by remember { mutableStateOf<Boolean?>(null) }
    var safetyOpen by remember { mutableStateOf(false) }
    // R72 - schedule message (web /schedule palette entry + drawer)
    var scheduleOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen, dmPeerId) {
        if (menuOpen && !isGroup && dmPeerId != null) {
            safetyVerified = runCatching { repository.safetyState(dmPeerId).getOrNull() }
                .getOrNull()?.verified
        }
    }
    // live voice roster for the Voice room pill (web voice.inRoom / roster.length)
    var voiceRoster by remember { mutableStateOf(emptyList<VoicePeerDto>()) }
    val voiceInRoom = voiceRoster.any { it.userId == viewerId }

    // R93 - room-internal back refinement. The root contract (MirrorRoot.kt)
    // closes the whole room on back; that is only correct when NO in-room
    // panel is up. This handler composes AFTER the root one, so while the
    // room is visible it wins whenever a panel is open and pops the topmost
    // surface first (menu dropdown over everything, then the full-panel
    // stack, then the header strips). With nothing open it disables itself
    // and back falls through to the root handler, which closes the room -
    // WhatsApp's exact layering.
    val roomPanelOpen = menuOpen || trayOpen || roomSearchOpen || pinnedOpen ||
            muteStripOpen || ttlStripOpen || pollOpen || stickerOpen ||
            locationOpen || remindersOpen || scheduledOpen || recapOpen ||
            eventsOpen || kanbanOpen || whiteboardOpen ||
            tournamentOpen || redPacketOpen ||
            trayEffectsOpen || slashHelpOpen || safetyOpen || scheduleOpen ||
            phraseManagerOpen
    val readerLifecycle = LocalLifecycleOwner.current
    androidx.activity.compose.BackHandler(enabled = roomPanelOpen) {
        when {
            menuOpen -> menuOpen = false
            safetyOpen -> safetyOpen = false
            redPacketOpen -> redPacketOpen = false
            tournamentOpen -> tournamentOpen = false
            whiteboardOpen -> whiteboardOpen = false
            kanbanOpen -> kanbanOpen = false
            eventsOpen -> eventsOpen = false
            recapOpen -> recapOpen = false
            scheduledOpen -> scheduledOpen = false
            remindersOpen -> remindersOpen = false
            pollOpen -> pollOpen = false
            locationOpen -> locationOpen = false
            stickerOpen -> stickerOpen = false
            trayEffectsOpen -> trayEffectsOpen = false
            slashHelpOpen -> slashHelpOpen = false
            muteStripOpen -> muteStripOpen = false
            ttlStripOpen -> ttlStripOpen = false
            phraseManagerOpen -> phraseManagerOpen = false
            roomSearchOpen -> roomSearchOpen = false
            pinnedOpen -> pinnedOpen = false
            trayOpen -> trayOpen = false
            scheduleOpen -> scheduleOpen = false
        }
    }

    LaunchedEffect(groupId) {
        repository.events().collect { event ->
            if (event is PulseEvent.VoiceRoster && event.payload.conversationId == groupId) {
                voiceRoster = event.payload.roster
            }
        }
    }
    LaunchedEffect(menuOpen) {
        if (menuOpen) {
            try {
                pinnedCount = fetchPinned().size
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep last known count; the pinned sheet exposes Retry.
            }
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
        .collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(topicsVisible) {
        if (topicsVisible) runCatching { repository.refreshTopics(groupId) }
    }
    LaunchedEffect(groupId, activeTopicId, readerLifecycle) {
        readerLifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var firstAttempt = true
            repository.observeConnected().distinctUntilChanged().collectLatest { connected ->
                if (connected || firstAttempt) {
                    firstAttempt = false
                    repository.refreshMessages(groupId, activeTopicId)
                }
            }
        }
    }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var timelineNow by remember(groupId) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(messages, readerLifecycle) {
        readerLifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                timelineNow = now
                val expiry = messages.mapNotNull { it.expiresAtEpochMs }.filter { it > now }.minOrNull()
                    ?: kotlinx.coroutines.awaitCancellation()
                delay((expiry - now).coerceAtLeast(1L))
            }
        }
    }
    val visibleMessages = remember(messages, activeTopicId, timelineNow) { visibleRoomMessages(messages, activeTopicId, timelineNow) }
    val entries = remember(visibleMessages) { buildRoomEntries(visibleMessages) }
    val latestEntries = androidx.compose.runtime.rememberUpdatedState(entries)
    val readObscured = roomPanelOpen || actionFor != null || forwardFor != null || threadFor != null || infoFor != null ||
        menuOpen || trayOpen || roomSearchOpen || pinnedOpen || phraseManagerOpen || pollOpen || stickerOpen ||
        locationOpen || captionFor != null || remindersOpen || scheduledOpen || recapOpen || eventsOpen ||
        kanbanOpen || whiteboardOpen || tournamentOpen || redPacketOpen || trayEffectsOpen || slashHelpOpen ||
        safetyOpen || scheduleOpen || viewOnceGrant != null
    val readRows = androidx.compose.runtime.rememberUpdatedState(visibleMessages)
    val minimumReadPixels = with(LocalDensity.current) { 44.dp.roundToPx() }
    val readConnected by remember(repository) { repository.observeConnected() }
        .collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(repository, viewerId, groupId, readingEnabled, readObscured, readConnected, minimumReadPixels, readerLifecycle) {
        readerLifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!readingEnabled || readObscured) return@repeatOnLifecycle
            val releaseAttention = app.pulse.android.notify.IncomingAttention.beginReading(groupId)
            try {
                fun currentBoundary(): String? {
                    val layout = listState.layoutInfo
                    val visible = layout.visibleItemsInfo.filter {
                        app.pulse.domain.sync.hasReadViewportOverlap(it.offset, it.size, layout.viewportStartOffset, layout.viewportEndOffset, minimumReadPixels)
                    }.map { it.key }.toSet()
                    val now = System.currentTimeMillis()
                    return readRows.value.lastOrNull { it.id in visible && !it.isDeleted &&
                        !it.id.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX) &&
                        (it.expiresAtEpochMs?.let { expiry -> expiry > now } != false) }?.id
                }
                androidx.compose.runtime.snapshotFlow { currentBoundary() }.collectLatest { id ->
                    if (id != null) app.pulse.domain.sync.acknowledgeVisibleRead(
                        isCurrent = { currentBoundary() == id },
                        send = { repository.markReadThrough(groupId, id) },
                    )
                }
            } finally {
                releaseAttention()
            }
        }
    }
    val historyScope = rememberCoroutineScope()
    fun openViewOnce(message: Message) {
        if (openingViewOnce || viewOnceGrant != null) return
        openingViewOnce = true
        historyScope.launch {
            try {
                repository.consumeViewOnce(message.id).onSuccess {
                    if (roomActionsLive.get() && readerLifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewOnceGrant = it
                }.onFailure {
                    if (roomActionsLive.get()) sendError = it.message ?: "Couldn't open this photo"
                }
            } finally { openingViewOnce = false }
        }
    }
    LaunchedEffect(messages, viewOnceGrant?.id) {
        val granted = viewOnceGrant
        if (granted != null && messages.none { it.id == granted.id && !it.isDeleted }) viewOnceGrant = null
    }
    viewOnceGrant?.let { granted ->
        app.pulse.feature.chat.ImageLightbox(message = granted, onDismiss = { viewOnceGrant = null })
    }
    fun voteOnPoll(pollId: String, optionId: String) {
        val owner = repository.viewerId
        val epoch = repository.attachmentSessionKey()
        historyScope.launch {
            if (!roomActionsLive.get() || owner == null || repository.viewerId != owner || repository.attachmentSessionKey() != epoch) return@launch
            try {
                repository.votePoll(pollId, optionId).getOrThrow()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                if (roomActionsLive.get()) sendError = error.message ?: "Your vote was not confirmed."
            }
        }
    }

    fun toggleReaction(message: Message, emoji: String) {
        val owner = repository.viewerId ?: return
        val epoch = repository.attachmentSessionKey()
        val reaction = app.pulse.protocol.reactionId(emoji)
        val present = message.reactions.none { it.userId == owner && app.pulse.protocol.reactionId(it.emoji) == reaction }
        historyScope.launch {
            if (!roomActionsLive.get() || repository.viewerId != owner || repository.attachmentSessionKey() != epoch) return@launch
            try {
                repository.react(message.id, reaction, present).onFailure {
                    if (roomActionsLive.get()) sendError = it.message ?: "Couldn't update reaction"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (roomActionsLive.get()) sendError = "Couldn't update reaction. Try again."
            }
        }
    }
    var loadingHistory by remember(groupId) { mutableStateOf(false) }
    var historyHasMore by remember(groupId) { mutableStateOf(true) }
    var historyCursor by remember(groupId) { mutableStateOf<MessageHistoryCursor?>(null) }
    val historyRequestEpoch = remember(groupId) { java.util.concurrent.atomic.AtomicLong() }
    fun loadEarlierMessages() {
        if (loadingHistory || !historyHasMore || jumpMessageId != null) return
        val requestEpoch = historyRequestEpoch.incrementAndGet()
        loadingHistory = true
        val topic = activeTopicId
        historyScope.launch {
            try {
                val rows = repository.observeMessages(groupId).first()
                // Search/thread caches may contain isolated older rows. Only a
                // consumed response proves a contiguous history boundary.
                var cursor = historyCursor
                val known = rows.map { it.id }.toSet()
                repeat(5) {
                    ensureActive()
                    val page = repository.historyPage(groupId, cursor, 60)
                    ensureActive()
                    if (!roomActionsLive.get() || historyRequestEpoch.get() != requestEpoch) return@launch
                    val next = page.nextCursor
                    if (next == null) { historyHasMore = false; return@launch }
                    val previous = cursor
                    if (previous != null && !next.precedes(previous)) { sendError = "History returned a repeated page"; return@launch }
                    historyCursor = next
                    cursor = next
                    historyHasMore = page.hasMore
                    if (!page.hasMore || visibleRoomMessages(page.messages, topic).any { it.id !in known }) return@launch
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (roomActionsLive.get() && historyRequestEpoch.get() == requestEpoch) sendError = "Couldn't load earlier messages. Try again."
            } finally { if (historyRequestEpoch.get() == requestEpoch) loadingHistory = false }
        }
    }
    // R76 - jump-to-message (web jumpToMessage, chat-room.tsx:1039-1120):
    // page back through the real cache until the hit lands, scroll to it and
    // flash the bubble - the Spotlight / home search / reminder links all
    // anchor the room this way.
    var jumpFlashId by remember { mutableStateOf<String?>(null) }
    val jumpActive = jumpMessageId != null
    LaunchedEffect(jumpMessageId, jumpRequestId, groupId) {
        val target = jumpMessageId ?: return@LaunchedEffect
        historyRequestEpoch.incrementAndGet() // supersede an earlier manual page request
        loadingHistory = false
        jumpFlashId = null
        activeTopicId = null // a search/quote jump must not remain hidden by a topic
        try {
            var rawCursor: MessageHistoryCursor? = null
            // A cold cache can request the latest page directly, without a polling race
            // against refresh. Every subsequent request carries both timestamp and ID.
            for (round in 0 until 14) {
                ensureActive()
                val current = repository.observeMessages(groupId).first()
                if (current.any { it.id == target }) break
                val cursor = rawCursor
                val page = repository.historyPage(groupId, cursor, 60)
                ensureActive()
                if (!roomActionsLive.get()) return@LaunchedEffect
                val next = page.nextCursor
                if (next == null) { historyHasMore = false; break }
                if (cursor != null && !next.precedes(cursor)) throw IllegalStateException("History returned a repeated page")
                rawCursor = next
                val previousBoundary = historyCursor
                if (previousBoundary == null || next.precedes(previousBoundary)) {
                    historyCursor = next
                    historyHasMore = page.hasMore
                }
                if (!page.hasMore) break
            }
            val fresh = repository.observeMessages(groupId).first()
            val targetRow = fresh.firstOrNull { it.id == target }
            if (targetRow == null || targetRow.isDeleted || targetRow.expiresAtEpochMs?.let { it <= System.currentTimeMillis() } == true) {
                sendError = "That message is unavailable or outside the loaded history"
                return@LaunchedEffect
            }
            val parentId = targetRow.threadRootId
            if (parentId != null) {
                // A cached parent is not current permission or proof that the
                // selected reply still belongs to a live thread.
                val (root, loadedReplies) = app.pulse.domain.sync.resolveThreadJump(repository, groupId, parentId, target)
                ensureActive()
                if (!roomActionsLive.get()) return@LaunchedEffect
                if (!app.pulse.domain.sync.threadRootAvailable(root, groupId, parentId) ||
                    app.pulse.domain.sync.threadReplyTargetIndex(loadedReplies, root, target) == null) {
                    sendError = "That reply is no longer available in this thread."
                    return@LaunchedEffect
                }
                threadJumpId = target
                threadFor = root
                return@LaunchedEffect
            }
            // Room has committed, but Compose may still display the previous entries.
            // Wait for the target in the rendered model before converting its ID to an index.
            val rendered = kotlinx.coroutines.withTimeoutOrNull(3_000) {
                androidx.compose.runtime.snapshotFlow { latestEntries.value }.first { rows ->
                    rows.any { it is RoomEntry.Msg && it.message.id == target }
                }
            }
            if (rendered == null) {
                sendError = "Couldn't display that message. Try opening it again."
                return@LaunchedEffect
            }
            androidx.compose.runtime.withFrameNanos { }
            val idx = latestEntries.value.indexOfFirst { it is RoomEntry.Msg && it.message.id == target && !it.message.isDeleted }
            if (idx < 0) { sendError = "That message is no longer available"; return@LaunchedEffect }
            listState.animateScrollToItem(idx + 1) // history-control is the first list item
            jumpFlashId = target
            delay(1700)
            jumpFlashId = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (roomActionsLive.get()) sendError = "Couldn't open that message. Try again."
        } finally {
            if (kotlinx.coroutines.currentCoroutineContext().isActive && roomActionsLive.get()) onJumpConsumed()
        }
    }
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

    // R73 - slow-mode 429: the server body carries retryAfter seconds; the
    // chip counts down live (web slow-mode chip 500ms tick, chat-room L4949).
    LaunchedEffect(slowModeUntil) {
        while (slowModeUntil > System.currentTimeMillis()) {
            slowModeSecondsLeft = ((slowModeUntil - System.currentTimeMillis()) / 1000L).toInt() + 1
            delay(500)
        }
        slowModeSecondsLeft = 0
    }

    // R74 - effect trigger (web triggerEffectFor L1464): full-screen particle
    // companion from the composer position + the per-message canvas layer
    // anchored at the bubble, queue capped at 3, nonce per run.
    fun triggerEffectFor(messageId: String, effect: MirrorMessageEffect) {
        if (reducedMotion) return
        when (effect) {
            MirrorMessageEffect.CONFETTI -> PulseFx.fire(PulseFx.BurstKind.CONFETTI, 90)
            MirrorMessageEffect.SPARKLES -> PulseFx.fire(PulseFx.BurstKind.STARS, 90)
            else -> PulseFx.fire(PulseFx.BurstKind.BURST, 90)
        }
        val host = effectsHostRect
        val bubble = bubbleRects[messageId]
        val origin = if (host.width > 0f && host.height > 0f && bubble != null) {
            MirrorEffectOrigin(
                x = ((bubble.center.x - host.left) / host.width).coerceIn(0.03f, 0.97f),
                y = ((bubble.center.y - host.top) / host.height).coerceIn(0.03f, 0.97f),
            )
        } else {
            MirrorEffectOrigin(0.5f, 0.62f)
        }
        if (activeEffect == null) {
            effectNonce.longValue += 1L
            activeEffect = MirrorActiveEffect(effect, origin, effectNonce.longValue)
        } else if (effectQueue.size < 3) {
            effectQueue.add(MirrorActiveEffect(effect, origin, 0L))
        }
    }

    fun handleEffectDone() {
        activeEffect = null
        if (effectQueue.isNotEmpty()) {
            val next = effectQueue.removeAt(0)
            effectNonce.longValue += 1L
            activeEffect = MirrorActiveEffect(next.effect, next.origin, effectNonce.longValue)
        }
    }

    // R74 - fire payload effects for messages appended to the tail of the
    // cache (web L1519-1541): seed silently on first load, never replay
    // history, exactly-once per id.
    LaunchedEffect(visibleMessages) {
        if (visibleMessages.isEmpty()) return@LaunchedEffect
        val tailId = visibleMessages.last().id
        if (seenEffectIds.isEmpty()) {
            // first load or room switch - seed, never replay
            visibleMessages.forEach { seenEffectIds.add(it.id) }
            return@LaunchedEffect
        }
        if (!seenEffectIds.contains(tailId)) {
            for (m in visibleMessages.reversed()) {
                if (seenEffectIds.contains(m.id)) break
                seenEffectIds.add(m.id)
                val payload = m.payload?.let { raw ->
                    runCatching { Json.decodeFromString(EffectPayloadDto.serializer(), raw) }.getOrNull()
                }
                val effect = MirrorMessageEffect.parse(payload?.effect)
                if (payload != null && effect != null) {
                    triggerEffectFor(m.id, effect)
                }
            }
        }
        if (seenEffectIds.size > 400) {
            seenEffectIds.clear()
            visibleMessages.forEach { seenEffectIds.add(it.id) }
        }
    }

    // R73 - the REAL send path lives here (web submitDraft): edit-vs-send
    // fork, replyToId + topicId on the wire, draft clear, slow-mode 429
    // surfacing, typing stop. Zero silent failures.
    // R74 - armed effects ride kind:"text" + payload {effect} (web L1646);
    // incognito arms anon + the deterministic alias preview (web L1672).
    var sending by remember(groupId) { mutableStateOf(false) }
    fun sendNow() {
        val rawText = draftValue.text.trim()
        val submittedRevision = composerDraft.revision
        val sendTopicId = activeTopicId
        if (broadcastLocked || sending) return
        if (rawText.isEmpty()) return
        editing?.let { editTarget ->
            // Editing is not a new slash command, reminder, effect or outbox send.
            val epoch = repository.attachmentSessionKey() ?: return
            val owner = repository.viewerId ?: return
            stopTyping()
            sending = true
            historyScope.launch {
                try {
                    if (!roomActionsLive.get() || repository.viewerId != owner || repository.attachmentSessionKey() != epoch) return@launch
                    repository.editMessage(editTarget.id, rawText).getOrThrow()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!roomActionsLive.get() || repository.attachmentSessionKey() != epoch) return@launch
                    if (editing?.id == editTarget.id && composerDraft.revision == submittedRevision) finishEditing()
                    sendError = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
                    if (roomActionsLive.get() && repository.attachmentSessionKey() == epoch) {
                        sendError = error.message ?: "Couldn't update this message. Your edit is still here."
                    }
                } finally { sending = false }
            }
            return
        }
        if (slowModeUntil > System.currentTimeMillis() && editing == null) return
        // R74 - submit-time slash text transforms (web parseSlashResult text
        // cases + bot pass-through): /me /shrug /tableflip /unflip /roll send
        // transformed bodies, /remind creates a real reminder, unknown words
        // surface the /help affordance (web L530-540).
        val text: String = when (val slash = mirrorSlashSendBody(rawText)) {
            is MirrorSlashBody.Error -> {
                sendError = slash.message
                return
            }
            is MirrorSlashBody.Reminder -> {
                val parsed = slash
                stopTyping()
                sending = true
                CoroutineScope(Dispatchers.IO).launch {
                    val ok = runCatching {
                        repository.createReminder(
                            conversationId = groupId,
                            messageId = null,
                            note = parsed.note,
                            remindAtIso = parsed.remindAtIso,
                        ).getOrThrow()
                    }.isSuccess
                    CoroutineScope(Dispatchers.Main).launch completion@ {
                        if (!roomActionsLive.get()) return@completion
                        sending = false
                        if (ok) composerDraft.clearIfUnchanged(submittedRevision)
                        sendError = if (ok) "Reminder set" else "Could not set the reminder"
                    }
                }
                return
            }
            is MirrorSlashBody.Send -> slash.content
            null -> rawText
        }
        val draftToken = repository.captureDraftToken(groupId)
        val replyTarget = replyTo
        // R74 - web consumes the armed effect at submit time (chat-room L3137:
        // "const armedEffect = pendingEffect; if (!== null) setPendingEffect(null)")
        val effectTarget = pendingEffect
        if (effectTarget != null) pendingEffect = null
        val anonTarget = anonNext && isGroup && effectTarget == null
        stopTyping()
        sending = true
        CoroutineScope(Dispatchers.IO).launch {
            val outcome = runCatching {
                if (effectTarget != null) {
                    val payload = Json.encodeToString(
                        EffectPayloadDto.serializer(),
                        EffectPayloadDto(effect = effectTarget.wire),
                    )
                    repository.sendRichMessage(
                        conversationId = groupId,
                        body = text.take(2000),
                        kind = "text",
                        payload = payload,
                        topicId = sendTopicId,
                        replyToId = replyTarget?.id,
                        draftToken = draftToken,
                    ).getOrThrow()
                } else if (anonTarget) {
                    repository.sendRichMessage(
                        conversationId = groupId,
                        body = text.take(2000),
                        replyToId = replyTarget?.id,
                        draftToken = draftToken,
                        topicId = sendTopicId,
                        anon = true,
                        anonAliasPreview = mirrorAnonAliasPreview(viewerId, groupId),
                    ).getOrThrow()
                } else {
                    repository.sendMessage(
                        conversationId = groupId,
                        body = text,
                        replyToId = replyTarget?.id,
                        draftToken = draftToken,
                        topicId = sendTopicId,
                    ).getOrThrow()
                }
            }
            CoroutineScope(Dispatchers.Main).launch completion@ {
                if (!roomActionsLive.get()) return@completion
                sending = false
                outcome.onSuccess {
                    composerDraft.clearIfUnchanged(submittedRevision, notify = false)
                    if (replyTo?.id == replyTarget?.id) replyTo = null
                    sendError = null
                    // R74 - the effect arm is one-shot like the web pendingEffect
                    // (armed stays until a send consumes it - web L3103 keeps it
                    // armed across sends until dismissed; the chip shows it).
                    // R24-b - incognito IS one-shot (web L1751): disarm on success.
                    if (anonTarget) anonNext = false
                }.onFailure { failure ->
                    val api = failure as? PulseApiException
                    if (api?.retryAfter != null) {
                        slowModeUntil = System.currentTimeMillis() + api.retryAfter!! * 1000L
                    } else {
                        sendError = when {
                            api?.kind in setOf("VALIDATION", "FORBIDDEN", "NOT_FOUND") ->
                                "Not sent. Original message kept in Settings → Chat → Outbox."
                            else -> "Message failed to send"
                        }
                    }
                }
            }
        }
    }

    // R74 - /game palette entry (web createGame chat-room.tsx L1276): DM
    // challenges the peer directly, group creates an open challenge anyone
    // can claim. The created invite message rides the normal refresh.
    fun startTicTacToe() {
        if (gameBusy) return
        val opponentId = if (!isGroup) members.firstOrNull { it.id != viewerId }?.id else null
        if (!isGroup && opponentId == null) {
            sendError = "Participant details are unavailable. Reopen the chat before challenging them."
            return
        }
        gameBusy = true
        gameJob = gameScope.launch {
            try {
                roomRequest { repository.createGame(groupId, opponentId) }
                    .onSuccess { sendError = if (it.replayed) "Recovered the previous challenge" else "Tic-tac-toe challenge sent" }
                    .onFailure { sendError = (it.message ?: "Challenge was not confirmed.") + " Check the chat before retrying." }
            } finally { gameBusy = false }
        }
    }

    // R74 - the slash fast-path (web handleSlashSelect L3000+): the palette
    // and the tray Commands tile dispatch through the same parser branches.
    fun runSlashCommand(rawCmd: String) {
        val cmd = rawCmd.trim()
        // text-transform commands stage the arg into the draft (web L3356)
        fun stageWithArgs() {
            val args = draftValue.text.replace(Regex("^/\\S*\\s*"), "").trim()
            draftValue = TextFieldValue(if (args.isNotEmpty()) "$cmd $args" else "$cmd ")
        }
        when {
            cmd == "/poll" -> { draftValue = TextFieldValue(""); pollOpen = true }
            cmd == "/schedule" -> { draftValue = TextFieldValue(""); scheduleOpen = true }
            cmd == "/sticker" -> { draftValue = TextFieldValue(""); stickerOpen = true }
            cmd == "/location" -> { draftValue = TextFieldValue(""); locationOpen = true }
            cmd == "/whiteboard" -> { draftValue = TextFieldValue(""); whiteboardOpen = true }
            cmd == "/redpacket" -> { draftValue = TextFieldValue(""); redPacketOpen = true }
            cmd == "/kanban" -> { draftValue = TextFieldValue(""); kanbanOpen = true }
            cmd == "/events" -> { draftValue = TextFieldValue(""); eventsOpen = true }
            cmd == "/stage" -> { draftValue = TextFieldValue(""); onOpenLiveRoom(app.pulse.feature.voice.VoiceRoomsEngine.RoomKind.STAGE) }
            cmd == "/space" -> { draftValue = TextFieldValue(""); onOpenLiveRoom(app.pulse.feature.voice.VoiceRoomsEngine.RoomKind.SPACE) }
            cmd == "/recap" -> { draftValue = TextFieldValue(""); recapOpen = true }
            cmd == "/help" -> { draftValue = TextFieldValue(""); slashHelpOpen = true }
            cmd == "/game" -> { draftValue = TextFieldValue(""); startTicTacToe() }
            cmd == "/tournament" -> {
                draftValue = TextFieldValue("")
                if (!isGroup) {
                    sendError = "Tournaments are for groups only"
                } else {
                    tournamentOpen = true
                }
            }
            cmd == "/topic" -> {
                // /topic <name> - create the topic now and file the next send
                val name = draftValue.text.replace(Regex("^/\\S*\\s*"), "").trim()
                if (name.isEmpty()) {
                    sendError = "Usage: /topic Design"
                } else {
                    draftValue = TextFieldValue("")
                    CoroutineScope(Dispatchers.IO).launch {
                        val created = runCatching {
                            repository.createTopic(groupId, name).getOrThrow()
                        }.getOrNull()
                        CoroutineScope(Dispatchers.Main).launch {
                            if (created != null) {
                                activeTopicId = created.id
                                runCatching { repository.refreshTopics(groupId) }
                            } else {
                                sendError = "Could not create the topic"
                            }
                        }
                    }
                }
            }
            cmd.startsWith("/effects") -> {
                val effectWord = cmd.split(Regex("\\s+")).getOrNull(1)?.lowercase() ?: ""
                val effect = MirrorMessageEffect.parse(effectWord)
                if (effect == null) {
                    sendError = "Usage: /effects confetti|lasers|echo|sparkles [text]"
                } else {
                    val rest = draftValue.text.replace(Regex("^/\\S*\\s*"), "").trim()
                    draftValue = TextFieldValue("")
                    if (rest.isNotEmpty()) {
                        // content present - send immediately with the payload
                        pendingEffect = effect
                        draftValue = TextFieldValue(rest)
                        sendNow()
                    } else {
                        pendingEffect = effect
                        sendError = "${effect.wire} effect armed - type a message and send"
                    }
                }
            }
            else -> stageWithArgs()
        }
    }

    // R73 - voice notes (web startRecording chat-room.tsx:3550-3620): real
    // MediaRecorder AAC capture, live timer, discard below 600ms on cancel.
    val voiceScope = rememberCoroutineScope()
    var voiceSendJob by remember(groupId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var voiceOperationId by remember(groupId) { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var uploadedVoicePath by remember(groupId) { mutableStateOf<String?>(null) }
    var recording by remember(groupId) { mutableStateOf(false) }
    var recordSeconds by remember(groupId) { mutableStateOf(0) }
    var voiceBusy by remember(groupId) { mutableStateOf(false) }
    var recordStartAt by remember(groupId) { mutableStateOf(0L) }
    var recordedDurationMs by remember(groupId) { mutableStateOf(0L) }
    val recorderAudioToken = remember(groupId) { java.util.UUID.randomUUID().toString() }
    val recorderRef = remember(groupId) { mutableStateOf<MediaRecorder?>(null) }
    val recordFileRef = remember(groupId) { mutableStateOf<File?>(null) }
    var voiceTopic by remember(groupId) { mutableStateOf<String?>(null) }
    var permissionRequestRoom by remember { mutableStateOf<String?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (permissionRequestRoom != groupId) return@rememberLauncherForActivityResult
        permissionRequestRoom = null
        if (granted) {
            if (!readerLifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                sendError = "Return to this room to record a voice note"
                return@rememberLauncherForActivityResult
            }
            if (voiceBusy || recordFileRef.value != null) {
                sendError = "Send or discard the current voice note first"
                return@rememberLauncherForActivityResult
            }
            if (!app.pulse.core.audio.AudioOwnership.shared.acquireExclusive(recorderAudioToken)) {
                sendError = "Leave your live room/call or pause playback before recording"
                return@rememberLauncherForActivityResult
            }
            val context = context
            runCatching {
                val file = File(context.cacheDir, "voice-note-${System.currentTimeMillis()}.m4a")
                recordFileRef.value = file
                val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else {
                    @Suppress("DEPRECATION") MediaRecorder()
                }
                recorderRef.value = recorder
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(96_000)
                recorder.setAudioSamplingRate(44_100)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
                recorderRef.value = recorder
                recordFileRef.value = file
                recording = true
                recordedDurationMs = 0L
                voiceOperationId = java.util.UUID.randomUUID().toString()
                voiceTopic = activeTopicId
                uploadedVoicePath = null
                recordStartAt = android.os.SystemClock.elapsedRealtime()
                recordSeconds = 0
            }.onFailure {
                runCatching { recorderRef.value?.release() }
                recorderRef.value = null
                app.pulse.core.audio.AudioOwnership.shared.release(recorderAudioToken)
                recordFileRef.value?.delete()
                recordFileRef.value = null
                sendError = "Microphone unavailable"
            }
        } else {
            sendError = "Microphone permission needed for voice notes"
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            delay(200)
            recordSeconds = ((android.os.SystemClock.elapsedRealtime() - recordStartAt) / 1000L).toInt()
        }
    }
    fun cancelRecording() {
        runCatching { recorderRef.value?.stop() }
        runCatching { recorderRef.value?.release() }
        recorderRef.value = null
        app.pulse.core.audio.AudioOwnership.shared.release(recorderAudioToken)
        recordFileRef.value?.delete()
        recordFileRef.value = null
        recording = false
        recordSeconds = 0
        recordedDurationMs = 0L
        uploadedVoicePath = null
    }
    fun finishRecording(): File? {
        val file = recordFileRef.value ?: return null
        val duration = recordedDurationMs.takeIf { it > 0L }
            ?: (android.os.SystemClock.elapsedRealtime() - recordStartAt).coerceAtLeast(0L)
        if (duration < 600L) { cancelRecording(); return null }
        val recorder = recorderRef.value
        val result = finalizeVoiceNote(file, { recorder?.stop() }, { recorder?.release() })
        recorderRef.value = null
        app.pulse.core.audio.AudioOwnership.shared.release(recorderAudioToken)
        recording = false
        if (result.isFailure) {
            recordFileRef.value = null
            sendError = "Recording could not be finalized. Please record again."
            return null
        }
        recordedDurationMs = duration
        return file
    }
    fun sendRecording() {
        if (voiceBusy) return
        val file = finishRecording() ?: return
        val operationId = voiceOperationId
        val duration = recordedDurationMs
        voiceBusy = true
        voiceSendJob = voiceScope.launch {
            try {
                val uploaded = uploadedVoicePath ?: run {
                    val dataUrl = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        "data:audio/mp4;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    }
                    repository.uploadMedia(dataUrl).getOrThrow().also { uploadedVoicePath = it }
                }
                ensureActive()
                repository.sendMediaMessage(conversationId = groupId, body = "",
                    audioPath = uploaded, durationMs = duration, clientId = operationId, topicId = voiceTopic).getOrThrow()
                ensureActive()
                cancelRecording()
                sendError = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                sendError = "Voice note not confirmed. Send again to retry, or discard."
            } finally { voiceBusy = false }
        }
    }
    DisposableEffect(readerLifecycle, groupId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && recording) {
                if (finishRecording() != null) {
                    sendError = "Recording stopped in the background. Send or discard it."
                }
            }
        }
        readerLifecycle.lifecycle.addObserver(observer)
        onDispose {
            readerLifecycle.lifecycle.removeObserver(observer)
            voiceSendJob?.cancel()
            cancelRecording() // scoped upload is canceled; recording belongs to this room
        }
    }

    var previousEntryCount by remember(groupId, activeTopicId) { mutableStateOf(0) }
    var previousTailId by remember(groupId, activeTopicId) { mutableStateOf<String?>(null) }
    LaunchedEffect(visibleMessages.lastOrNull()?.id, entries.size, activeTopicId) {
        val tail = visibleMessages.lastOrNull()
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (shouldFollowMessageTail(
                previousEntryCount, lastVisible, previousTailId, tail?.id,
                tail?.authorId == viewerId, jumpActive,
            ) && entries.isNotEmpty()
        ) listState.animateScrollToItem(entries.size)
        previousEntryCount = entries.size + 1
        previousTailId = tail?.id
    }
    // Leaving the room always cancels the typing signal (web unmount parity).
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { stopTyping() }
    }

    // Mounted room media uses the same bounded preparation and private-file
    // journal as the other native entry points. Picker intent owns the account,
    // topic, reply and anonymity; callback time must not choose a new owner.
    fun ownsMedia(selection: MirrorMediaSelection): Boolean = roomActionsLive.get() &&
        selection.sessionKey == mediaSession && repository.attachmentSessionKey() == selection.sessionKey
    fun newMediaSelection(kind: String): MirrorMediaSelection? {
        if (uploadingImage || preparingAttachment || pendingPhoto != null || pendingDocument != null || captionFor != null) return null
        if (broadcastLocked || editing != null || recording || voiceBusy) {
            sendError = "Finish the current action before adding an attachment."
            return null
        }
        val epoch = mediaSession ?: return null
        if (repository.attachmentSessionKey() != epoch) return null
        if (replyTo?.id?.startsWith(app.pulse.domain.model.TEMP_MESSAGE_PREFIX) == true) {
            sendError = "Wait for the original message to send before replying to it."
            return null
        }
        return MirrorMediaSelection(app.pulse.domain.model.AttachmentDraft(
            selectionId = java.util.UUID.randomUUID().toString(), conversationId = groupId, kind = kind, dataUrl = "",
            caption = if (kind == "file") draftValue.text else "", topicId = activeTopicId,
            replyToId = replyTo?.id, anon = isGroup && anonNext), epoch,
            draftRevision = if (kind == "file") composerDraft.revision else null)
    }
    fun prepareMedia(uri: Uri?, selection: MirrorMediaSelection?) {
        if (uri == null) return
        if (uri.scheme != "content") {
            sendError = "Choose an attachment through a content provider in the system picker."
            return
        }
        if (selection == null || !ownsMedia(selection)) {
            sendError = "Selection was interrupted. Choose the attachment again in this account."
            return
        }
        preparingAttachment = true
        attachmentJob = historyScope.launch {
            try {
                val prepared = if (selection.draft.kind == "image") {
                    selection.draft.copy(dataUrl = app.pulse.feature.chat.MediaSupport.imageToDataUrl(context, uri).getOrThrow())
                } else {
                    val document = app.pulse.feature.chat.MediaSupport.documentToDataUrl(context, uri).getOrThrow()
                    selection.draft.copy(dataUrl = document.dataUrl, fileName = document.fileName, fileSize = document.sizeBytes)
                }
                ensureActive()
                if (ownsMedia(selection)) { captionFor = selection.copy(draft = prepared); sendError = null }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: Exception) {
                if (ownsMedia(selection)) sendError = failure.message ?: "Could not prepare the attachment. Your text is kept."
            } finally { if (ownsMedia(selection)) preparingAttachment = false }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val selection = pendingPhoto; pendingPhoto = null
        prepareMedia(uri, selection)
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val selection = pendingDocument; pendingDocument = null
        prepareMedia(uri, selection)
    }
    fun sendStagedAttachment(selection: MirrorMediaSelection, caption: String) {
        if (uploadingImage || !ownsMedia(selection)) return
        // Freeze on the first Send. A retry after an ambiguous local result must
        // not silently change the envelope under the same client ID.
        val frozen = selection.freeze(caption)
        val submitted = checkNotNull(frozen.submitted)
        captionFor = frozen
        uploadingImage = true
        attachmentJob = historyScope.launch {
            try {
                if (!ownsMedia(selection)) return@launch
                repository.queuePreparedAttachment(submitted, selection.sessionKey).getOrThrow()
                ensureActive()
                if (ownsMedia(selection) && captionFor?.draft?.selectionId == submitted.selectionId) {
                    selection.draftRevision?.let { composerDraft.clearIfUnchanged(it) }
                    if (replyTo?.id == submitted.replyToId) replyTo = null
                    captionFor = null; sendError = null
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: Exception) {
                if (ownsMedia(selection)) sendError = failure.message ?: "Could not confirm the saved attachment. Retry uses the same operation."
            } finally { if (ownsMedia(selection)) uploadingImage = false }
        }
    }

    // R87 - web chat-room.tsx:3876 - effective wallpaper = the per-conversation
    // theme override (chat.convThemes) ?? the global Appearance default; the
    // optional tint replaces the top glow color (web applyConvTint). The room
    // is always dark (web R54-c) so the dark glow palette is the only one.
    val convThemes by repository.convThemes.collectAsStateWithLifecycle(initialValue = emptyMap<String, ConvTheme>())
    val globalPrefs by repository.pulsePrefs.collectAsStateWithLifecycle(initialValue = WirePulsePrefs())
    val roomTheme = convThemes[groupId]
    val roomWallpaper = roomTheme?.wallpaper ?: (globalPrefs.wallpaper ?: "none")
    val roomTint = roomTheme?.tint
    val glowTop = when {
        roomTint == "emerald" -> Color(0x2B10B981)
        roomTint == "rose" -> Color(0x29F43F5E)
        roomTint == "amber" -> Color(0x29F59E0B)
        roomTint == "violet" -> Color(0x2B8B5CF6)
        roomTint == "teal" -> Color(0x2914B8A6)
        roomWallpaper == "aurora" -> Color(0x1FF59E0B)
        roomWallpaper == "dusk" -> Color(0x1AF59E0B)
        roomWallpaper == "forest" -> Color(0x21059669)
        roomWallpaper == "mono" -> Color.Transparent
        else -> Color(0x0EF59E0B)
    }
    val glowBottom = when (roomWallpaper) {
        "aurora" -> Color(0x178B5CF6)
        "dusk" -> Color(0x17F43F5E)
        "forest" -> Color(0x1284CC16)
        "mono" -> Color.Transparent
        else -> Color(0x0A14B8A6)
    }

    Column(
        Modifier
            .fillMaxSize()
            // R87 - the room canvas is OPAQUE (web art-scene absolute inset-0):
            // without this base the shell tab content and the dock bleed
            // through every surface of the room.
            .background(MirrorArt.Bg)
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
                // ONE tap target: 40dp avatar + title + subtitle - room info
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
                        // R72 - the stored group/channel photo (DMs carry the
                        // peer's avatar in Conversation.avatar)
                        photo = avatarPath,
                    )
                    Column(Modifier.weight(1f)) {
                        // web: title row carries the "Channel" broadcast pill
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                title,
                                color = MirrorArt.Text,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.2).sp,
                                lineHeight = 19.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (isGroup && isBroadcast) {
                                Row(
                                    Modifier.clip(RoundedCornerShape(50))
                                        .background(MirrorArt.Accent.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    MirrorLucideIcon("LRadio", tint = MirrorArt.Accent2, modifier = Modifier.size(10.dp))
                                    Text(
                                        "Channel",
                                        color = MirrorArt.Accent2,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
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
                onCreate = { name, icon ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val created = runCatching { repository.createTopic(groupId, name, icon).getOrNull() }.getOrNull()
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
                    // top glow: the themed color (R87) - default radial-gradient(ellipse 90% 34% at 50% -8%, rgba(245,158,11,0.055), transparent 62%)
                    sceneGlow(
                        core = glowTop,
                        cx = w / 2f,
                        cy = -0.08f * h,
                        rx = 0.90f * w,
                        ry = 0.34f * h,
                        fadeStop = 0.62f,
                    )
                    // bottom glow: the themed color (R87) - default radial-gradient(ellipse 110% 40% at 50% 110%, rgba(20,184,166,0.04), transparent 62%)
                    sceneGlow(
                        core = glowBottom,
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
            item(key = "history-control") {
                if (historyHasMore) {
                    androidx.compose.material3.TextButton(onClick = { loadEarlierMessages() }, enabled = !loadingHistory) {
                        Text(if (loadingHistory) "Loading earlier messages…" else "Load earlier messages")
                    }
                }
            }
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
                        val lastOwnId = visibleMessages.lastOrNull { it.authorId == viewerId && it.deletedAt == null }?.id
                        val showReadBy = mine && isGroup &&
                            entry.message.id == lastOwnId && entry.message.deletedAt == null
                        val created = MirrorRoomEpoch(entry.message.createdAt)
                        val readers = members.filter {
                            it.id != viewerId && (it.lastReadAt ?: 0L) >= created
                        }
                        // R73 - the web bubble entrance (chat-room.tsx:7510-7520):
                        // the NEWEST row plays the Telegram squash & stretch on
                        // arrival; history rows render settled.
                        val isNewest = entry.message.id == visibleMessages.lastOrNull()?.id
                        // R76 - the jump flash ring (web flashHighlight): an amber
                        // border drawn OVER the row bounds - zero layout shift.
                        val flashing = jumpFlashId == entry.message.id
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (flashing) {
                                        Modifier
                                            .background(Color(0x1AF59E0B), RoundedCornerShape(16.dp))
                                            .border(1.5.dp, Color(0x8CF59E0B), RoundedCornerShape(16.dp))
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
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
                            animateIn = isNewest && entry.message.deletedAt == null,
                            onOpenMenu = { actionFor = entry.message },
                            onViewOnce = { openViewOnce(entry.message) },
                            onVote = { optionId ->
                                entry.message.poll?.let { poll ->
                                    voteOnPoll(poll.id, optionId)
                                }
                            },
                            onToggleReaction = { messageId, emoji ->
                                if (messageId == entry.message.id) toggleReaction(entry.message, emoji)
                            },
                            // R74 - swipe-to-reply + effect origin registration
                            onReply = {
                                replyTo = if (entry.message.viewOnce) entry.message.copy(body = "View-once message", imagePath = null) else entry.message
                            },
                            registerRect = { rect -> bubbleRects[entry.message.id] = rect },
                            repository = repository,
                            onRematch = {
                                CoroutineScope(Dispatchers.IO).launch {
                                    runCatching { repository.refreshMessages(groupId, activeTopicId) }
                                }
                            },
                        )
                        }
                    }
                }
            }
            // R64 - the web typing indicator: 24dp avatar + art-bubble-in pill
            // whose borderRadius breathes 1.25rem - 0.875rem - 1.25rem (0.72s
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

        // R73 - composer chips stack (web AnimatePresence bars above the
        // composer): offline pill, slow-mode countdown, scheduled chip, topic
        // filing pill, reply bar, edit bar. One expand/fade for the stack.
        val chipsVisible = pendingOutbox > 0 || slowModeSecondsLeft > 0 ||
            scheduledCount > 0 || activeTopicId != null || replyTo != null ||
            editing != null || sendError != null
        AnimatedVisibility(
            visible = chipsVisible && !broadcastLocked,
            enter = expandVertically(MirrorMotion.snappy()) + fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                if (pendingOutbox > 0) {
                    MirrorComposerBar(
                        glyph = "LCloudOff",
                        tint = MirrorArt.Dim,
                        text = "Offline - messages you send will be queued ($pendingOutbox waiting)",
                    )
                }
                if (slowModeSecondsLeft > 0) {
                    MirrorComposerBar(
                        glyph = "LTimer",
                        tint = MirrorArt.Accent2,
                        text = "Slow mode on - next message in ${slowModeSecondsLeft}s",
                    )
                }
                if (scheduledCount > 0) {
                    MirrorComposerBar(
                        glyph = "LCalendarClock",
                        tint = MirrorArt.Dim,
                        text = "next - $scheduledCount pending - tap to manage",
                        onClick = { scheduledOpen = true },
                    )
                }
                if (activeTopicId != null) {
                    val topicName = topics.firstOrNull { it.id == activeTopicId }?.name ?: "topic"
                    MirrorComposerBar(
                        glyph = "LMessagesSquare",
                        tint = MirrorArt.Accent2,
                        text = "Filing to #$topicName",
                        onCancel = { activeTopicId = null },
                    )
                }
                // R74 - incognito armed chip (web L5010-5031): pulsing mask,
                // "next message hides your name", X disarms.
                if (anonNext && isGroup) {
                    MirrorComposerBar(
                        glyph = "LVenetianMask",
                        tint = MirrorArt.Accent2,
                        text = "Incognito on - next message hides your name",
                        onCancel = { anonNext = false },
                    )
                }
                // R74 - armed effect chip (web toast L5213 + the armed state):
                // the next send carries payload {effect}.
                pendingEffect?.let { armed ->
                    MirrorComposerBar(
                        glyph = "LSparkles",
                        tint = MirrorArt.Accent2,
                        text = "${armed.wire} effect armed - type a message and send",
                        onCancel = { pendingEffect = null },
                    )
                }
                replyTo?.let { target ->
                    MirrorComposerBar(
                        glyph = "LCornerDownRight",
                        tint = MirrorArt.Accent2,
                        text = "Replying to ${target.displayAuthorName} - ${target.body.replace(Regex("\\s+"), " ").take(72)}",
                        onCancel = { replyTo = null },
                    )
                }
                editing?.let { target ->
                    MirrorComposerBar(
                        glyph = "LPencilLine",
                        tint = MirrorArt.Accent2,
                        text = "Editing message - ${target.body.replace(Regex("\\s+"), " ").take(72)}",
                        onCancel = {
                            finishEditing()
                        },
                    )
                }
                sendError?.let {
                    MirrorComposerBar(glyph = "LTriangleAlert", tint = MirrorArt.Red, text = it, onCancel = { sendError = null })
                }
                if (!voiceBusy && !recording && recordFileRef.value != null && recordedDurationMs > 0L) {
                    Row {
                        androidx.compose.material3.TextButton(onClick = { sendRecording() }) { Text("Send voice note") }
                        androidx.compose.material3.TextButton(onClick = { cancelRecording(); sendError = null }) { Text("Discard voice note") }
                    }
                }
            }
        }

        // F-MS-29 quick phrases rail: glass chips ABOVE the composer; tap
        // INSERTS into the draft; the 28dp pencil opens the manage sheet.
        // R72: hidden while broadcast-locked (web hides phrases for viewers).
        if (phrases.isNotEmpty() && !broadcastLocked) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (phrase in phrases) {
                    Box(
                        Modifier
                            .widthIn(max = 220.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.White7)
                            .border(1.dp, MirrorArt.Hairline, CircleShape)
                            .clickable {
                                val base = draftValue.text
                                draftValue = TextFieldValue(
                                    if (base.isBlank()) phrase.text else "$base ${phrase.text}",
                                    selection = androidx.compose.ui.text.TextRange(
                                        (if (base.isBlank()) phrase.text else "$base ${phrase.text}").length,
                                    ),
                                )
                            }
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

        // R54-c/R73 composer row: ONE art-input-pill (paperclip / Type here /
        // camera) with the 44dp dark-glass art-fab OUTSIDE on the right.
        // R72: broadcast channels swap it for the locked pill (web 5230:
        // glass-deep rounded-2xl Lock + "Only admins can post").
        // R73: voice recording swaps the pill for the recorder UI (web
        // in-pill recording view, chat-room.tsx:5460-5518); the mention
        // autocomplete floats above the pill.
        // R73 - @mention autocomplete (web L2923-2961): the token after the
        // caret's last @ matched against the room roster, top-5.
        val mentionSuggestion = run {
            val text = draftValue.text
            val caret = draftValue.selection.end.coerceIn(0, text.length)
            val before = text.substring(0, caret)
            val at = before.lastIndexOf('@')
            val token = if (at >= 0) before.substring(at + 1) else ""
            if (at >= 0 && token.length in 1..16 && !token.contains(' ')) {
                memberNames.filter { it.contains(token, ignoreCase = true) && it != viewerName }.take(5)
            } else {
                emptyList()
            }
        }
        fun insertMention(name: String) {
            val text = draftValue.text
            val caret = draftValue.selection.end.coerceIn(0, text.length)
            val before = text.substring(0, caret)
            val at = before.lastIndexOf('@')
            if (at < 0) return
            val newText = (before.substring(0, at) + "@$name ") + text.substring(caret)
            val newCaret = at + name.length + 2
            draftValue = TextFieldValue(newText, selection = androidx.compose.ui.text.TextRange(newCaret))
        }
        // R74 - the slash palette (web SlashPalette): anchored above the
        // composer whenever the draft starts with '/', fuzzy filtered.
        if (draftValue.text.startsWith("/")) {
            MirrorSlashPalette(
                query = draftValue.text,
                onPick = { cmd -> runSlashCommand(cmd) },
            )
        }
        if (broadcastLocked) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MirrorArt.White7)
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                MirrorLucideIcon("LLock", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                Text(
                    "Only admins can post",
                    color = MirrorArt.Dim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else if (recording) {
            // R73 - the in-pill recorder (web chat-room.tsx:5460-5518): cancel,
            // pulsing radar ring, live timer, send FAB
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .background(MirrorArt.White7)
                        .border(1.dp, MirrorArt.Hairline, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .mirrorPressClick(onClick = { cancelRecording() }),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LX", tint = MirrorArt.Dim, modifier = Modifier.size(18.dp))
                    }
                    val radar = rememberInfiniteTransition(label = "radar")
                    val radarAlpha by radar.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                        label = "radarAlpha",
                    )
                    Box(
                        Modifier
                            .size(14.dp)
                            .graphicsLayer { alpha = radarAlpha }
                            .clip(CircleShape)
                            .background(MirrorArt.Red),
                    )
                    Text(
                        "Recording voice note - %02d:%02d".format(recordSeconds / 60, recordSeconds % 60),
                        color = MirrorArt.TextSoft,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MirrorArt.White7)
                        .border(1.dp, MirrorArt.Hairline, CircleShape)
                        .mirrorPressClick(onClick = { sendRecording() }),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LSendHorizontal", tint = MirrorArt.Text, modifier = Modifier.size(20.dp))
                }
            }
        } else
        Column {
            if (mentionSuggestion.isNotEmpty()) {
                // web mention popup: listbox, first-row highlight, avatars
                Column(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xF21C1610))
                        .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(14.dp))
                        .padding(6.dp),
                ) {
                    for ((index, name) in mentionSuggestion.withIndex()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (index == 0) MirrorArt.ChipActive else Color.Transparent)
                                .clickable { insertMention(name) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MirrorAvatar(
                                name = name,
                                color = members.firstOrNull { it.name == name }?.color,
                                isGroup = false,
                                groupId = "",
                                online = false,
                                showPresence = false,
                                sizeDp = 20,
                                cornerDp = 10,
                            )
                            Text(name, color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
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
                        if (draftValue.text.isBlank()) {
                            Text("Type here", color = MirrorArt.Faint, fontSize = 15.sp, lineHeight = 20.sp)
                        }
                        BasicTextField(
                            value = draftValue,
                            onValueChange = {
                                // web maxLength 2000 (chat-room.tsx:5549)
                                draftValue = if (it.text.length > 2000) it.copy(it.text.take(2000)) else it
                                pumpTyping(it.text)
                            },
                            textStyle = TextStyle(
                                color = MirrorArt.Text,
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendNow() }),
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
                                newMediaSelection("image")?.let { selection ->
                                    pendingPhoto = selection
                                    try { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                    catch (_: Exception) { pendingPhoto = null; sendError = "Photo picker is unavailable." }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LCamera", tint = MirrorArt.Dim, modifier = Modifier.size(20.dp))
                    }
                }
                // art-fab: 44dp DARK GLASS always - plus (opens the tray) becomes
                // the send plane the moment the draft holds text; the rotation
                // ANIMATES with the web snappy spring (web L5609) and the plane
                // shows a spinner while the send is in flight.
                val hasText = draftValue.text.isNotBlank()
                val fabRotation by animateFloatAsState(
                    targetValue = if (!hasText && trayOpen) 45f else 0f,
                    animationSpec = MirrorMotion.press(),
                    label = "fabRotation",
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .graphicsLayer { rotationZ = fabRotation }
                        .clip(CircleShape)
                        .background(MirrorArt.White7)
                        .border(1.dp, MirrorArt.Hairline, CircleShape)
                        .mirrorPressClick(onClick = {
                            if (hasText) {
                                sendNow()
                                trayOpen = false
                            } else {
                                trayOpen = !trayOpen
                            }
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sending || voiceBusy || uploadingImage || preparingAttachment) {
                        MirrorLucideIcon("LLoaderCircle", tint = MirrorArt.Text, modifier = Modifier.size(20.dp))
                    } else if (hasText) {
                        MirrorLucideIcon("LSendHorizontal", tint = MirrorArt.Text, modifier = Modifier.size(20.dp))
                    } else {
                        MirrorLucideIcon("LPlus", tint = MirrorArt.Text, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }

    // R101 - the attachments tray rebuilt on the web truth (chat-room.tsx
    // 5113-5256 + trayGroups 2581-2830): the tray is a rounded block that
    // opens DIRECTLY ABOVE the composer - four purpose groups (Create /
    // Gather / Organise / Express) with pill section chips, a 2-col tile
    // grid, tone-tinted 36dp icon chips and the web tile set 1:1. Photo
    // left the tray on the web - the bar's camera button owns photos.
    // imePadding + navigationBarsPadding lift the tray above the keyboard
    // (the overlays are siblings of the inset-padded room Column, so they
    // must pad themselves) and the 80dp bottom clearance (72dp composer
    // row + 8dp web gap) keeps the composer visible under the tray.
    // Every tile opens a real mirror surface - zero dead tiles.
    if (trayOpen && !broadcastLocked) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(onClick = { trayOpen = false }),
        ) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 80.dp)
                    // web: max-h-[min(58vh,440px)] overflow-y-auto rounded-3xl
                    .heightIn(max = minOf(LocalConfiguration.current.screenHeightDp * 0.58f, 440f).dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xCC1C1610)) // bg-[#1c1610]/80
                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(24.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
                    .clickable(enabled = false) {},
            ) {
                MirrorTrayGroup(
                    "Create",
                    listOf(
                        MirrorTrayTileSpec("LFileText", "Document", "Share a PDF, TXT, CSV or ZIP", TrayToneOrange) {
                            trayOpen = false
                            newMediaSelection("file")?.let { selection ->
                                pendingDocument = selection
                                try { pickDocument.launch("*/*") }
                                catch (_: Exception) { pendingDocument = null; sendError = "Document picker is unavailable." }
                            }
                        },
                        MirrorTrayTileSpec("LMessageSquarePlus", "Quick phrase", "Save lines you send often", TrayToneSky) {
                            trayOpen = false
                            phraseManagerOpen = true
                        },
                        MirrorTrayTileSpec("LVote", "Poll", "Live votes in this chat", TrayToneViolet) {
                            trayOpen = false
                            pollOpen = true
                        },
                        MirrorTrayTileSpec("LCalendarClock", "Schedule", "Send this message later", TrayToneOrange) {
                            trayOpen = false
                            scheduleOpen = true
                        },
                        MirrorTrayTileSpec("LPresentation", "Whiteboard", "Sketch together on one canvas", TrayToneAmber) {
                            trayOpen = false
                            whiteboardOpen = true
                        },
                        MirrorTrayTileSpec("LGift", "Red packet", "Wrap coins as a gift", TrayToneRose) {
                            trayOpen = false
                            redPacketOpen = true
                        },
                    ),
                )
                Spacer(Modifier.height(12.dp))
                MirrorTrayGroup(
                    "Gather",
                    listOf(
                        MirrorTrayTileSpec("LCalendarDays", "Events", "Plan meetups with RSVP", TrayToneAmber) {
                            trayOpen = false
                            eventsOpen = true
                        },
                        MirrorTrayTileSpec("LPodcast", "Stage", "Live audio stage for the room", TrayToneOrange) {
                            trayOpen = false
                            onOpenLiveRoom(app.pulse.feature.voice.VoiceRoomsEngine.RoomKind.STAGE)
                        },
                        MirrorTrayTileSpec("LMap", "Space", "Hang out in a spatial room", TrayToneAmber) {
                            trayOpen = false
                            onOpenLiveRoom(app.pulse.feature.voice.VoiceRoomsEngine.RoomKind.SPACE)
                        },
                        MirrorTrayTileSpec("LGamepad2", "Game", "Start tic-tac-toe here", TrayToneViolet) {
                            trayOpen = false
                            startTicTacToe()
                        },
                        MirrorTrayTileSpec(
                            "LTrophy",
                            "Tournament",
                            "Bracketed group competition",
                            TrayToneRose,
                            enabled = isGroup,
                        ) {
                            trayOpen = false
                            tournamentOpen = true
                        },
                    ),
                )
                Spacer(Modifier.height(12.dp))
                MirrorTrayGroup(
                    "Organise",
                    buildList {
                        add(
                            MirrorTrayTileSpec("LSquareKanban", "Kanban", "Group tasks on a board", TrayToneOrange) {
                                trayOpen = false
                                kanbanOpen = true
                            },
                        )
                        // web groupOnly filter (chat-room.tsx:2828): Topic hides in DMs
                        if (isGroup) {
                            add(
                                MirrorTrayTileSpec("LMessagesSquare", "Topic", "File the chat under a topic", TrayToneAmber) {
                                    trayOpen = false
                                    topicsVisible = !topicsVisible
                                },
                            )
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                MirrorTrayGroup(
                    "Express",
                    buildList {
                        add(
                            MirrorTrayTileSpec("LMic", "Voice note", "Hold-free recording with a live timer", TrayToneAmber) {
                                trayOpen = false
                                permissionRequestRoom = groupId
                                micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                            },
                        )
                        add(
                            MirrorTrayTileSpec("LSticker", "Sticker", "Send a stamp from the packs", TrayToneAmber) {
                                trayOpen = false
                                stickerOpen = true
                            },
                        )
                        add(
                            MirrorTrayTileSpec("LSparkles", "Effects", "Confetti, lasers, echo, sparkles", TrayToneViolet) {
                                trayEffectsOpen = !trayEffectsOpen
                            },
                        )
                        add(
                            MirrorTrayTileSpec("LDices", "Commands", "Every slash command", TrayToneOrange) {
                                trayOpen = false
                                draftValue = TextFieldValue("/")
                            },
                        )
                        add(
                            MirrorTrayTileSpec("LLocation", "Location", "Drop a live map pin", TrayToneOrange) {
                                trayOpen = false
                                locationOpen = true
                            },
                        )
                        // web groupOnly filter: Incognito hides in DMs; armed
                        // state rides the amber tint (chat-room.tsx:2804-2812)
                        if (isGroup) {
                            add(
                                MirrorTrayTileSpec(
                                    "LVenetianMask",
                                    "Incognito",
                                    if (anonNext) "Armed - next send is anonymous" else "Next send hides your name",
                                    if (anonNext) TrayToneAmber else TrayToneZinc,
                                ) {
                                    anonNext = !anonNext
                                    if (anonNext) trayOpen = false
                                },
                            )
                        }
                    },
                )
                // effects chooser pills (web 5218-5256): violet chips, arm on tap
                if (trayEffectsOpen) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (effect in MirrorMessageEffect.entries) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(Color(0x1A8B5CF6)) // violet-500/10
                                    .border(1.dp, Color(0x408B5CF6), CircleShape) // violet-500/25
                                    .clickable {
                                        pendingEffect = effect
                                        trayOpen = false
                                        trayEffectsOpen = false
                                        sendError = "${effect.wire} effect armed - type a message and send"
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    effect.wire,
                                    color = Color(0xFFC4B5FD), // violet-300
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
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
                    // web min-w-56 (224px) shrink-wraps to exactly 224px -
                    // FIXED width, the weighted row labels must not stretch
                    // the menu edge to edge.
                    .width(224.dp)
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
                    onOpenLiveRoom(app.pulse.feature.voice.VoiceRoomsEngine.RoomKind.VOICE)
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
                // R72 - DM-only safety number row (web ShieldCheck item,
                // chat-room.tsx:4232): label + tint flip when verified
                if (!isGroup && dmPeerId != null) {
                    MirrorRoomMenuItem(
                        "LShieldCheck",
                        if (safetyVerified == true) "Verified safety number" else "Verify safety number",
                    ) {
                        menuOpen = false
                        safetyOpen = true
                    }
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
                        enabled = !roomSettingsBusy,
                        pillText = if (roomSettingsBusy) "Updating…" else "",
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
                        enabled = !roomSettingsBusy,
                        iconColor = if ((ttlSeconds ?: 0) > 0) MirrorArt.Accent2 else MirrorArt.Dim,
                        pillText = when {
                            roomSettingsBusy -> "Updating…"
                            ttlSeconds == null -> "Not loaded"
                            ttlSeconds > 0 -> when (ttlSeconds) { 86_400 -> "24h"; 604_800 -> "7d"; else -> "30d" }
                            else -> ""
                        },
                        pillStyle = if (ttlSeconds == null) "neutral" else "accentUp",
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
                    onOpenLiveRoom(app.pulse.feature.voice.VoiceRoomsEngine.RoomKind.STAGE)
                }
                MirrorRoomMenuItem("LMap", "Space") {
                    menuOpen = false
                    onOpenLiveRoom(app.pulse.feature.voice.VoiceRoomsEngine.RoomKind.SPACE)
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
            messages = messages,
            fetchPinned = fetchPinned,
            onUnpin = onUnpinMessage,
            onDismiss = { pinnedOpen = false },
        )
    }

    // R69 - the Conversation menu surfaces: the same real sheets the web opens
    if (remindersOpen) {
        MirrorRemindersSheet(repository, viewerId, groupId,
            onOpen = { target ->
                if (roomActionsLive.get() && repository.viewerId == viewerId) {
                    remindersOpen = false
                    onOpenReminder(target)
                }
            }, onDismiss = { remindersOpen = false })
    }
    if (scheduledOpen) {
        MirrorScheduledSheet(repository, groupId) { scheduledOpen = false; scheduledRefresh++ }
    }
    // R72 - DM safety number sheet (verify/unverify on the real contract)
    if (safetyOpen && dmPeerId != null) {
        MirrorRoomSafetySheet(repository, dmPeerId) { safetyOpen = false }
    }
    // R72 - schedule message composer (web /schedule drawer)
    if (scheduleOpen) {
        val scheduledRevision = remember { composerDraft.revision }
        MirrorScheduleComposerSheet(
            repository = repository,
            conversationId = groupId,
            initialDraft = draftValue.text,
            onConsumedDraft = { if (roomActionsLive.get()) { composerDraft.clearIfUnchanged(scheduledRevision); scheduledRefresh++ } },
            onDismiss = { scheduleOpen = false },
        )
    }
    if (recapOpen) {
        MirrorRecapSheet(repository, groupId) { recapOpen = false }
    }
    if (eventsOpen) {
        MirrorEventsSheet(repository, groupId, viewerId, viewerIsAdmin) { eventsOpen = false }
    }
    if (kanbanOpen) {
        MirrorKanbanSheet(repository, groupId, viewerId, viewerIsAdmin) { kanbanOpen = false }
    }
    if (whiteboardOpen) {
        MirrorWhiteboardSheet(repository, groupId, viewerId) { whiteboardOpen = false }
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

    // R73 - the message-level surfaces (web chat-room.tsx mounts)
    actionFor?.let { target ->
        MirrorMessageActionPanel(
            message = target,
            conversationId = groupId,
            viewerId = viewerId,
            members = members,
            repository = repository,
            onReaction = { emoji -> toggleReaction(target, emoji) },
            onReply = { replyTo = if (target.viewOnce) target.copy(body = "View-once message", imagePath = null) else target },
            onEdit = {
                if (editing == null) {
                    draftBeforeEdit = draftValue
                    replyBeforeEdit = replyTo
                }
                editing = target
                draftValue = TextFieldValue(target.body)
            },
            onThread = { threadJumpId = null; threadFor = target },
            onForward = { forwardFor = target },
            onRemind = { remindersOpen = true },
            onInfo = { infoFor = target },
            onDeleted = { actionFor = null },
            onTaskCreated = { note -> sendError = note },
            onDismiss = { actionFor = null },
        )
    }
    forwardFor?.let { target ->
        MirrorForwardSheet(
            repository = repository,
            source = target,
            onDismiss = { forwardFor = null },
            onForwarded = { name -> sendError = "Forward queued for $name" },
        )
    }
    threadFor?.let { root ->
        androidx.compose.runtime.key(root.id, repository, viewerId) {
            MirrorThreadSheet(
                repository = repository,
                root = root,
                viewerId = viewerId,
                viewerName = viewerName,
                memberNames = memberNames,
                jumpMessageId = threadJumpId,
                readingEnabled = readingEnabled && !roomPanelOpen,
                onDismiss = { threadFor = null; threadJumpId = null },
            )
        }
    }
    if (pollOpen) {
        MirrorPollBuilderSheet(
            repository = repository,
            conversationId = groupId,
            onDismiss = { pollOpen = false },
            onPosted = { note ->
                pollOpen = false
                sendError = note
            },
        )
    }
    if (stickerOpen) {
        MirrorStickerSheet(
            onDismiss = { stickerOpen = false },
            onPick = { emoji, pack ->
                stickerOpen = false
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        repository.sendRichMessage(
                            conversationId = groupId,
                            body = "",
                            kind = "sticker",
                            payload = "{\"emoji\":\"$emoji\",\"pack\":\"$pack\"}",
                        )
                    }
                }
            },
        )
    }
    if (locationOpen) {
        MirrorLocationSheet(
            onDismiss = { locationOpen = false },
            onSend = { lat, lng, label ->
                locationOpen = false
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val payload = Json.encodeToString(
                            LocationPayload.serializer(),
                            LocationPayload(lat = lat, lng = lng, label = label),
                        )
                        repository.sendRichMessage(
                            conversationId = groupId,
                            body = label,
                            kind = "location",
                            payload = payload,
                        )
                    }
                }
            },
        )
    }
    infoFor?.let { target ->
        MirrorMessageInfoPanel(
            message = target,
            members = members,
            onDismiss = { infoFor = null },
        )
    }
    captionFor?.let { staged ->
        MirrorCaptionSheet(
            selection = staged,
            destination = if (isGroup) "$title · ${staged.draft.topicId?.let { id -> topics.firstOrNull { it.id == id }?.name ?: "Original topic" } ?: "General"}" else title,
            busy = uploadingImage,
            error = sendError,
            onViewOnce = { value -> if (!uploadingImage && staged.submitted == null) captionFor = staged.withViewOnce(value) },
            onDismiss = { if (!uploadingImage) captionFor = null },
            onSend = { caption -> sendStagedAttachment(staged, caption) },
        )
    }

    // R74 - red packet composer (web useRedPacketSheet wiring)
    if (redPacketOpen) {
        MirrorRedPacketSheet(
            conversationId = groupId,
            repository = repository,
            onDismiss = { redPacketOpen = false },
            onSent = {
                redPacketOpen = false
                // createRedPacket already caches the confirmed carrier message and
                // schedules its account-owned conversation refresh. No detached job.
            },
        )
    }

    // R74 - /help sheet: every command with its args + help line
    if (slashHelpOpen) {
        MirrorSheet(title = "Slash commands", onDismiss = { slashHelpOpen = false }) {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (command in MIRROR_SLASH_COMMANDS) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(MirrorArt.White7),
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon(command.glyph, tint = command.tone, modifier = Modifier.size(14.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(command.cmd, color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(command.help, color = MirrorArt.Dim, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    // R74 - the full-screen message effects canvas (web MessageEffectsLayer
    // L6290): pointer-transparent overlay, one shared instance, queue shifted
    // on done. The host rect feeds the normalized bubble origin.
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { effectsHostRect = it.boundsInRoot() },
    ) {
        MirrorMessageEffectsLayer(
            active = activeEffect,
            onDone = { handleEffectDone() },
        )
    }
}

/** Web tray tone palette (chat-room.tsx trayGroups) - the dark-mode 400 stops. */
private val TrayToneOrange = Color(0xFFFB923C) // orange-400
private val TrayToneSky = Color(0xFF38BDF8) // sky-400
private val TrayToneViolet = Color(0xFFA78BFA) // violet-400
private val TrayToneAmber = Color(0xFFFBBF24) // amber-400
private val TrayToneRose = Color(0xFFFB7185) // rose-400
private val TrayToneZinc = Color(0xFFA1A1AA) // zinc-400

/** One attachments-tray tile spec (web TrayTile): glyph, copy, tone, action. */
private class MirrorTrayTileSpec(
    val icon: String,
    val label: String,
    val help: String,
    val tone: Color,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Web tray group (5152-5160): pill section chip + a 2-col tile grid, gap-2. */
@Composable
private fun MirrorTrayGroup(title: String, tiles: List<MirrorTrayTileSpec>) {
    Text(
        title.uppercase(),
        color = MirrorArt.Dim,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 8.dp, vertical = 2.dp), // web px-2 py-0.5 chip
    )
    Spacer(Modifier.height(6.dp))
    for (row in tiles.chunked(2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (tile in row) {
                MirrorTrayTile(tile, Modifier.weight(1f))
            }
            if (row.size == 1) {
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** One attachments-tray tile (web 5173-5192): tone chip 36dp + label + help. */
@Composable
private fun MirrorTrayTile(spec: MirrorTrayTileSpec, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (spec.enabled) 1f else 0.4f) // web disabled:opacity-40 - whole tile
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
            .clickable(enabled = spec.enabled, onClick = spec.onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(spec.tone.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon(spec.icon, tint = spec.tone, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(
                spec.label,
                color = MirrorArt.Text,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                spec.help,
                color = MirrorArt.Faint,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val hits = remember(query, messages, now) {
        if (query.isBlank()) emptyList()
        else reconcileMessageSelection(messages, messages, now) {
            !it.viewOnce && it.body.contains(query.trim(), ignoreCase = true)
        }.take(40)
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
    messages: List<Message>,
    fetchPinned: suspend () -> List<Message>,
    onUnpin: suspend (String) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    var pinned by remember { mutableStateOf<List<Message>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(setOf<String>()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    LaunchedEffect(retry) {
        error = null
        try {
            pinned = fetchPinned()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "Couldn't load pins. Tap to retry."
        }
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
            error?.let { text ->
                Text(text, color = MirrorArt.Accent, fontSize = 13.sp,
                    modifier = Modifier.padding(8.dp).clickable(enabled = busy.isEmpty()) { retry++ })
            }
            val list = pinned?.let { reconcileMessageSelection(it, messages, now) { row -> row.pinnedAt != null && !row.viewOnce } }
            when {
                list == null && error == null -> Text("Loading pins…", color = MirrorArt.Faint, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                list == null -> Unit
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
                                    .clickable(enabled = busy.isEmpty()) {
                                        busy = busy + message.id
                                        scope.launch {
                                            try {
                                                onUnpin(message.id).getOrThrow()
                                                pinned = pinned?.filterNot { it.id == message.id }
                                                error = null
                                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                throw cancelled
                                            } catch (_: Exception) {
                                                error = "Couldn't remove pin. Try Unpin again."
                                            } finally {
                                                busy = busy - message.id
                                            }
                                        }
                                    },
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

internal fun MirrorRoomStamp(iso: String): String =
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    animateIn: Boolean,
    onOpenMenu: () -> Unit,
    onViewOnce: () -> Unit,
    onVote: (String) -> Unit,
    onToggleReaction: (String, String) -> Unit,
    // R74 - swipe-to-reply trigger + the effect-origin registration
    onReply: () -> Unit = {},
    registerRect: (Rect) -> Unit = {},
    repository: PulseRepository? = null,
    onRematch: () -> Unit = {},
) {
    // R74 - effect origin registration (web data-mid querySelector): report
    // this row's live geometry so a payload effect can burst from the bubble.
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { registerRect(it.boundsInRoot()) },
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
                        name = message.displayAuthorName,
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
            // R74 - self-contained cards (web plainChrome L7400): red packet +
            // game boards render WITHOUT bubble chrome, taps belong to the card.
            val redPacketInfo = if (message.kind == Message.Kind.RED_PACKET) mirrorParseRedPacketPayload(message.payload) else null
            val gameInfo = if (message.kind == Message.Kind.GAME) mirrorParseGamePayload(message.payload) else null
            val isCard = message.deletedAt == null && (redPacketInfo != null || gameInfo != null)
            // R73 - web bubble entrance physics (chat-room.tsx:7510-7520):
            // mine = squash (scaleX 1.06 / scaleY 0.94 -> 1), incoming = 0.85
            // -> 1, both on the bouncy spring with the origin at the bubble base.
            val entranceX = remember(message.id) { Animatable(if (animateIn && mine) 1.06f else 1f) }
            val entranceY = remember(message.id) { Animatable(if (animateIn && mine) 0.94f else (if (animateIn) 0.85f else 1f)) }
            LaunchedEffect(message.id) {
                if (animateIn) {
                    launch {
                        entranceX.animateTo(
                            1f,
                            spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                        )
                    }
                    entranceY.animateTo(
                        1f,
                        spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                    )
                }
            }
            val haptics = LocalHapticFeedback.current
            // R74 - swipe-to-reply (web chat-room L7424-7451): drag the bubble
            // toward the center past the 28dp threshold to reply; the Reply
            // hint fades in with the drag; the bubble springs back on release.
            // Plain offset state + a state-driven settle spring: no suspend
            // calls inside the drag lambda (it is not a coroutine context).
            var swipeDragPx by remember(message.id) { androidx.compose.runtime.mutableFloatStateOf(0f) }
            var swipeDragging by remember(message.id) { mutableStateOf(false) }
            val swipeSettle by animateFloatAsState(
                targetValue = if (swipeDragging) swipeDragPx else 0f,
                animationSpec = MirrorMotion.snappy(),
                label = "swipeSettle",
            )
            val swipeX = if (swipeDragging) swipeDragPx else swipeSettle
            val swipeThreshold = with(LocalDensity.current) { 28.dp.toPx() }
            val swipeClamp = with(LocalDensity.current) { 64.dp.toPx() }
            val swipeToward = if (mine) -swipeX else swipeX
            val hintAlpha = ((swipeToward - 4f) / (swipeThreshold - 4f)).coerceIn(0f, 1f)
            val hintScale = (0.5f + (swipeToward / swipeThreshold) * 0.6f).coerceIn(0.5f, 1.1f)
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                Box(Modifier.fillMaxWidth()) {
                // R74 - reply hint (web hintOpacity/hintScale L7424-7432):
                // revealed from under the edge as the bubble slides toward it
                if (hintAlpha > 0.02f) {
                    Box(
                        Modifier
                            .align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
                            .graphicsLayer {
                                alpha = hintAlpha
                                scaleX = hintScale
                                scaleY = hintScale
                            },
                    ) {
                        MirrorLucideIcon("LCornerDownRight", tint = MirrorArt.Accent2, modifier = Modifier.size(18.dp))
                    }
                }
                // bubble block - tap AND long-press open the web action panel;
                // double-tap fires the heart reaction (web L7558-7563)
                // R74: self-contained cards drop the chrome AND the tap-to-open
                // panel - their taps belong to the card (web data-card-interactive).
                // R87 - the block MUST align inside the full-width Box: own
                // bubbles right, incoming left (web justify-end/justify-start).
                Column(
                    Modifier
                        .align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
                        .widthIn(max = if (isCard) 304.dp else bubbleMax)
                        .graphicsLayer {
                            scaleX = entranceX.value
                            scaleY = entranceY.value
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .offset { androidx.compose.ui.unit.IntOffset(swipeX.roundToInt(), 0) }
                        .then(
                            if (isCard) {
                                Modifier
                            } else {
                                Modifier
                                    .mirrorBubbleShape(mine, message.deletedAt != null)
                                    .mirrorBubbleBackground(mine, message.deletedAt != null)
                            },
                        )
                        .then(if (mentionsMe) Modifier.border(2.dp, Color(0xB3FFAB5E), mirrorBubbleShapeOf(mine, deleted = false)) else Modifier)
                        .then(
                            if (isCard) {
                                Modifier
                            } else {
                                Modifier.combinedClickable(
                                    onClick = { if (message.deletedAt == null) onOpenMenu() },
                                    onLongClick = {
                                        if (message.deletedAt == null) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onOpenMenu()
                                        }
                                    },
                                    onDoubleClick = {
                                        if (message.deletedAt == null) onToggleReaction(message.id, "heart")
                                    },
                                )
                            },
                        )
                        .then(
                            if (isCard) {
                                Modifier
                            } else {
                                Modifier.padding(
                                    horizontal = if (isImage) 4.dp else 12.dp,
                                    vertical = if (isImage) 4.dp else 8.dp,
                                )
                            },
                        )
                        // R74 - the horizontal drag gesture (web drag="x"
                        // constraints ±64, snapToOrigin, threshold 28)
                        .pointerInput(message.id, mine) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val toward = if (mine) -swipeDragPx else swipeDragPx
                                    if (toward >= swipeThreshold) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onReply()
                                    }
                                    swipeDragging = false
                                    swipeDragPx = 0f
                                },
                                onDragCancel = {
                                    swipeDragging = false
                                    swipeDragPx = 0f
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                swipeDragging = true
                                swipeDragPx = (swipeDragPx + dragAmount).coerceIn(-swipeClamp, swipeClamp)
                            }
                        },
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
                        if (message.integrationLabel != null) {
                            Text(message.integrationLabel.orEmpty(), color = MirrorArt.Accent2, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 2.dp))
                        } else if (!mine && isGroup && head) {
                            Text(
                                message.authorName,
                                color = MirrorArt.TextSoft,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                        if (message.visibleQuote()?.content != null || message.visibleQuote()?.senderName != null) {
                            MirrorReplyQuote(
                                author = message.visibleQuote()?.senderName.orEmpty().ifBlank { "Unknown" },
                                body = message.visibleQuote()?.content.orEmpty().replace(Regex("\\s+"), " ").take(120),
                                mine = mine,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        // R73 - the full wire-kind renderers (web bubble switch):
                        // poll tallies, stamps, location pins, voice waveform
                        // players, document cards, images, plain text.
                        when {
                            message.viewOnce && !mine -> {
                                Text(if (message.viewedAt != null) "Photo opened" else "View-once photo · Tap to open",
                                    color = MirrorArt.TextSoft, fontSize = 13.sp,
                                    modifier = Modifier.padding(12.dp).clickable(enabled = message.viewedAt == null, onClick = onViewOnce))
                            }
                            // R74 - self-contained cards (web plainChrome branch)
                            redPacketInfo != null -> {
                                MirrorRedPacketBubble(
                                    packetId = redPacketInfo.packetId,
                                    viewerId = viewerId,
                                    mine = mine,
                                    senderName = message.authorName,
                                    repository = repository!!,
                                )
                            }
                            gameInfo != null -> {
                                MirrorTicTacToeCard(
                                    matchId = gameInfo.matchId,
                                    viewerId = viewerId,
                                    repository = repository!!,
                                    onRematch = onRematch,
                                )
                            }
                            message.kind == Message.Kind.POLL && message.poll != null -> {
                                MirrorPollBubble(
                                    poll = message.poll!!,
                                    mine = mine,
                                    viewerId = viewerId,
                                    onVote = onVote,
                                )
                            }
                            message.kind == Message.Kind.STICKER -> {
                                val stamp = message.payload?.let { raw ->
                                    runCatching {
                                        Json.decodeFromString(StickerPayload.serializer(), raw)
                                    }.getOrNull()
                                }
                                val glyph = stamp?.let { MirrorStickerGlyphs[it.emoji] } ?: "LStar"
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    MirrorLucideIcon(glyph, tint = MirrorArt.Accent2, modifier = Modifier.size(44.dp))
                                    if (stamp?.pack != null) {
                                        Text(
                                            "${stamp!!.pack} - ${stamp.emoji}",
                                            color = MirrorArt.Faint,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                            }
                            message.kind == Message.Kind.LOCATION -> {
                                val loc = message.payload?.let { raw ->
                                    runCatching { Json.decodeFromString(LocationPayload.serializer(), raw) }.getOrNull()
                                }
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        MirrorLucideIcon("LLocation", tint = MirrorArt.Accent2, modifier = Modifier.size(18.dp))
                                        Text(
                                            loc?.label?.ifBlank { null } ?: "Current location",
                                            color = if (mine) MirrorArt.Ink else MirrorArt.Text,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    if (loc != null) {
                                        Text(
                                            "Lat %.5f  -  Lng %.5f".format(loc.lat, loc.lng),
                                            color = if (mine) MirrorArt.InkSoft else MirrorArt.Dim,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                            message.kind == Message.Kind.VOICE && message.audioPath != null -> {
                                MirrorVoiceNoteBubble(
                                    message = message,
                                    mine = mine,
                                    filePath = message.audioPath!!,
                                )
                            }
                            message.kind == Message.Kind.FILE && message.filePath != null -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(if (mine) Color(0x1A000000) else MirrorArt.White10),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        MirrorLucideIcon("LFile", tint = if (mine) MirrorArt.Ink else MirrorArt.Text, modifier = Modifier.size(16.dp))
                                    }
                                    Column {
                                        Text(
                                            message.fileName ?: "Document",
                                            color = if (mine) MirrorArt.Ink else MirrorArt.Text,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "File - ${(message.fileSize ?: 0L) / 1024} KB",
                                            color = if (mine) MirrorArt.InkSoft else MirrorArt.Dim,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                                if (message.body.isNotBlank()) {
                                    Text(
                                        mirrorAnnotated(message.body, mine, memberNames),
                                        color = if (mine) MirrorArt.Ink else MirrorArt.Text,
                                        fontSize = 13.sp,
                                        lineHeight = 17.sp,
                                    )
                                }
                            }
                            isImage -> {
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
                            }
                            else -> {
                                Text(
                                    mirrorAnnotated(message.body, mine, memberNames),
                                    color = if (mine) MirrorArt.Ink else MirrorArt.Text,
                                    fontSize = 15.sp,
                                    lineHeight = 20.sp,
                                )
                            }
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
                                val glyph = MirrorReactions.glyphOf(chip.emoji)
                                val tint = if (chip.iReacted) MirrorArt.Accent2 else MirrorArt.TextSoft
                                if (glyph.startsWith("P")) MirrorPhosphorIcon(glyph, tint, Modifier.size(12.dp))
                                else MirrorLucideIcon(glyph, tint, Modifier.size(12.dp))
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
internal fun mirrorBubbleShapeOf(mine: Boolean, deleted: Boolean) =
    if (deleted) {
        RoundedCornerShape(16.dp)
    } else if (mine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    }

internal fun Modifier.mirrorBubbleShape(mine: Boolean, deleted: Boolean): Modifier =
    this.clip(mirrorBubbleShapeOf(mine, deleted))

internal fun Modifier.mirrorBubbleBackground(mine: Boolean, deleted: Boolean): Modifier =
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

/** Pick - downscale (max 1280px) - JPEG 82 - data URL (web compressImageToDataUrl parity). */
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
    onCreate: (String, String) -> Unit,
) {
    var createOpen by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    // R72 - the web topic icon-id picker (topic-bar.tsx + icon-ids.ts):
    // the persisted value is an icon id, rendered as its designed glyph.
    var iconDraft by remember { mutableStateOf(TOPIC_ICON_DEFAULT) }
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
                glyph = "LMessageCircle",
                onClick = { onSelect(null) },
            )
            for (topic in topics) {
                MirrorTopicChip(
                    label = topic.name,
                    count = topic.messageCount,
                    active = topic.id == activeTopicId,
                    glyph = topicGlyphName(topic.emoji),
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (icon in TOPIC_ICON_IDS) {
                        val active = icon == iconDraft
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (active) MirrorArt.Accent else MirrorArt.White7)
                                .clickable { iconDraft = icon },
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon(topicGlyphName(icon) ?: "LMessageCircle", tint = if (active) Color.White else MirrorArt.TextSoft, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
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
                            onCreate(nameDraft.trim(), iconDraft)
                            nameDraft = ""
                            iconDraft = TOPIC_ICON_DEFAULT
                            createOpen = false
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
                }
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

/**
 * R72 - the web topic icon-id registry (icon-ids.ts TOPIC_ICON_IDS +
 * topicIconId()): the wire carries the id, each surface renders its own
 * glyph. Unknown/stale ids resolve to the default (web registry contract).
 */
private val TOPIC_ICON_IDS = listOf("chat", "palette", "rocket", "brain", "confetti", "wrench", "pin", "coffee")
private const val TOPIC_ICON_DEFAULT = "chat"

/** Topic icon id -> native lucide glyph (web TOPIC_ICON_GLYPHS mapping). */
internal fun topicGlyphName(value: String?): String? = when (value) {
    "chat" -> "LMessageCircle"
    "palette" -> "LPalette"
    "rocket" -> "LRocket"
    "brain" -> "LBrain"
    "confetti" -> "LPartyPopper"
    "wrench" -> "LWrench"
    "pin" -> "LPin"
    "coffee" -> "LCoffee"
    else -> null
}

/** One topic chip (web h-9 rounded-full px-3 12.5px semibold, amber active). */
@Composable
private fun MirrorTopicChip(label: String, count: Int, active: Boolean, glyph: String?, onClick: () -> Unit) {
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
        // R72 - persisted topic icon glyph (web TopicIconGlyph)
        if (glyph != null) {
            MirrorLucideIcon(glyph, tint = if (active) Color.White else MirrorArt.TextSoft, modifier = Modifier.size(12.dp))
        }
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

/**
 * R72 - the DM safety number sheet from the room kebab row (web safety
 * sheet via chat-room.tsx setSafetyOpen): renders the REAL 60-digit safety
 * number from /api/users/{id}/safety and verify/unverify on the same wire.
 * Mirrors the info-page ENCRYPTION section behaviour (MirrorRoomInfoPage).
 */
@Composable
private fun MirrorRoomSafetySheet(
    repository: PulseRepository,
    peerId: String,
    onDismiss: () -> Unit,
) {
    var state by remember { mutableStateOf<app.pulse.domain.model.SafetyState?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        failed = false
        state = runCatching { repository.safetyState(peerId).getOrThrow() }.getOrNull()
        if (state == null) failed = true
    }

    fun toggle(verify: Boolean) {
        if (busy) return
        busy = true
        CoroutineScope(Dispatchers.IO).launch {
            val next = runCatching {
                if (verify) repository.verifyPeer(peerId).getOrThrow() else repository.unverifyPeer(peerId).getOrThrow()
            }.getOrNull()
            withContext(Dispatchers.Main) {
                busy = false
                if (next != null) state = next
            }
        }
    }

    MirrorSheet(title = "Safety number", onDismiss = onDismiss) {
        MirrorSheetScroll {
            when {
                failed -> Text(
                    "Could not load the safety number",
                    color = MirrorArt.Red, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clickable { reloadKey++ }.padding(vertical = 16.dp),
                    textAlign = TextAlign.Center,
                )
                state == null -> Text(
                    "Loading...",
                    color = MirrorArt.Faint, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    textAlign = TextAlign.Center,
                )
                else -> {
                    val loaded = state!!
                    Text(
                        "Verify this account's safety number to confirm the end-to-end encryption identity.",
                        color = MirrorArt.Dim, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text(
                        loaded.safetyNumber,
                        color = MirrorArt.Text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (loaded.verified) MirrorArt.White7 else MirrorArt.Accent)
                                .clickable(enabled = !busy && !loaded.verified) { toggle(true) }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        ) {
                            MirrorLucideIcon("LShieldCheck", tint = if (loaded.verified) MirrorArt.Accent2 else Color.White, modifier = Modifier.size(16.dp))
                            Text(
                                if (loaded.verified) "Verified" else "Verify",
                                color = if (loaded.verified) MirrorArt.Accent2 else Color.White,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                        Row(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp))
                                .background(MirrorArt.White7)
                                .clickable(enabled = !busy && loaded.verified) { toggle(false) }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        ) {
                            MirrorLucideIcon("LEyeOff", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
                            Text("Unverify", color = MirrorArt.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * R72 - the schedule-message composer (web /schedule palette entry + drawer,
 * chat-room.tsx scheduleSend): drafts a message with a real future time and
 * POSTs /api/conversations/{id}/scheduled via the repository - the relay
 * sends itself later. The current composer draft rides in as the default.
 */
@Composable
private fun MirrorScheduleComposerSheet(
    repository: PulseRepository,
    conversationId: String,
    initialDraft: String,
    onConsumedDraft: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.runtime.key(repository, repository.attachmentSessionKey(), conversationId) {
        MirrorOwnedScheduleComposerSheet(repository, conversationId, initialDraft, onConsumedDraft, onDismiss)
    }
}

@Composable
private fun MirrorOwnedScheduleComposerSheet(
    repository: PulseRepository, conversationId: String, initialDraft: String,
    onConsumedDraft: () -> Unit, onDismiss: () -> Unit,
) {
    var body by remember { mutableStateOf(initialDraft) }
    var customWhen by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val epoch = repository.attachmentSessionKey()
    var attempted by remember { mutableStateOf<app.pulse.domain.sync.ScheduleTimes.Attempt?>(null) }
    val zone = remember { java.time.ZoneId.systemDefault() }
    val presets = remember {
        val now = java.time.Instant.now()
        listOf("In 1 hour" to app.pulse.domain.sync.ScheduleTimes.Preset.HOUR,
            "Tonight 20:00" to app.pulse.domain.sync.ScheduleTimes.Preset.TONIGHT,
            "Tomorrow 09:00" to app.pulse.domain.sync.ScheduleTimes.Preset.TOMORROW_MORNING)
            .map { (label, preset) -> label to app.pulse.domain.sync.ScheduleTimes.resolve(preset, now, zone) }
    }

    fun parseWhen(iso: String): java.time.Instant? = runCatching {
        java.time.LocalDateTime.parse(iso.trim().replace(' ', 'T')).atZone(zone).toInstant()
    }.getOrNull()

    fun scheduleAt(preset: java.time.Instant?) {
        if (busy) return
        val text = body.trim()
        if (text.isEmpty()) {
            error = "Write the message first"
            return
        }
        if (text.length > 2000) {
            error = "Message must be at most 2000 characters"
            return
        }
        val instant = preset ?: parseWhen(customWhen.trim())
        if (instant == null) {
            error = "Use the YYYY-MM-DD HH:MM format"
            return
        }
        val now = java.time.Instant.now()
        if (!app.pulse.domain.sync.ScheduleTimes.canSubmit(text, instant, now, attempted)) {
            error = "Choose a time between 30 seconds and 30 days from now"
            return
        }
        attempted = app.pulse.domain.sync.ScheduleTimes.Attempt(text, instant)
        error = null
        busy = true
        scope.launch {
            try {
                if (epoch == null || repository.attachmentSessionKey() != epoch) return@launch
                val item = repository.scheduleMessage(conversationId, text, instant.toString()).getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repository.attachmentSessionKey() != epoch) return@launch
                onConsumedDraft()
                repository.acknowledgeScheduledCreation(item).getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repository.attachmentSessionKey() != epoch) return@launch
                onDismiss()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: Exception) {
                if (repository.attachmentSessionKey() == epoch) error = failure.message ?: "Could not schedule. Your draft is kept."
            } finally { if (repository.attachmentSessionKey() == epoch) busy = false }
        }
    }

    MirrorSheet(title = "Schedule message", onDismiss = onDismiss) {
        MirrorSheetScroll {
            BasicTextField(
                value = body,
                onValueChange = { if (!busy) body = it },
                textStyle = TextStyle(color = MirrorArt.Text, fontSize = 13.sp, lineHeight = 18.sp),
                cursorBrush = SolidColor(MirrorArt.Accent2),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MirrorArt.White7)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    if (body.isBlank()) {
                        Text("Message to send later", color = MirrorArt.Faint, fontSize = 13.sp)
                    }
                    inner()
                },
            )
            Text(
                "QUICK TIMES",
                color = MirrorArt.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (preset in presets) {
                    Text(
                        preset.first,
                        color = MirrorArt.TextSoft, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MirrorArt.White7)
                            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(50))
                            .clickable(enabled = !busy) { scheduleAt(preset.second) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Text(
                "OR PICK EXACTLY (YYYY-MM-DD HH:MM)",
                color = MirrorArt.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
            )
            BasicTextField(
                value = customWhen,
                onValueChange = { if (!busy) customWhen = it },
                singleLine = true,
                textStyle = TextStyle(color = MirrorArt.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(MirrorArt.Accent2),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MirrorArt.White7)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    if (customWhen.isBlank()) {
                        Text(java.time.LocalDateTime.now().plusDays(2).withSecond(0).withNano(0).toString().take(16), color = MirrorArt.Faint, fontSize = 13.sp)
                    }
                    inner()
                },
            )
            error?.let {
                Text(it, color = MirrorArt.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MirrorArt.Accent)
                    .clickable(enabled = !busy) { scheduleAt(null) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                MirrorLucideIcon("LCalendarClock", tint = Color.White, modifier = Modifier.size(16.dp))
                Text(
                    if (busy) "Scheduling..." else "Schedule message",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// R73 - composer-level helpers ==================================================

/** Serializable sticker payload blob (web { emoji, pack }, chat-room L3227). */
@kotlinx.serialization.Serializable
internal data class StickerPayload(val emoji: String, val pack: String = "")

/** stamp item name -> the lucide glyph the MirrorStickerSheet tile used. */
internal val MirrorStickerGlyphs = mapOf(
    "bolt" to "LZap", "flame" to "LFlame", "sparkles" to "LSparkles", "rocket" to "LRocket",
    "target" to "LTarget", "star" to "LStar", "trophy" to "LTrophy", "crown" to "LCrown",
    "gift" to "LGift", "cake" to "LCake", "music" to "LMusic", "heart" to "LHeart",
    "palette" to "LPalette", "camera" to "LCamera", "mic" to "LMic", "gamepad" to "LGamepad",
    "brain" to "LBrain", "drama" to "LSticker", "leaf" to "LLeaf", "moon" to "LMoon",
    "drop" to "LDroplet", "planet" to "LPlanet", "coffee" to "LCoffee", "paw" to "LPaw",
    "smile" to "LSmile", "pin" to "LPin", "sun" to "LSun", "shield" to "LShieldCheck",
    "key" to "LKey", "thumbsup" to "LThumbsUp", "thumbsdown" to "LThumbsDown",
)

/** One dismissible composer bar (web AnimatePresence chip anatomy). */
@Composable
private fun MirrorComposerBar(
    glyph: String,
    tint: Color,
    text: String,
    onClick: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MirrorArt.White7)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(10.dp))
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MirrorLucideIcon(glyph, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text,
            color = MirrorArt.TextSoft,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onCancel != null) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LX", tint = MirrorArt.Dim, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** R73 - the photo caption sheet (web caption-sheet chat-room.tsx:5868-5945). */
@Composable
private fun MirrorCaptionSheet(selection: MirrorMediaSelection, destination: String, busy: Boolean, error: String?, onViewOnce: (Boolean) -> Unit, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val draft = selection.draft
    val locked = selection.submitted != null
    var caption by remember(draft.selectionId) { mutableStateOf(draft.caption) }
    val preview = remember(draft.selectionId) {
        if (draft.kind == "image") runCatching { Base64.decode(draft.dataUrl.substringAfter(','), Base64.NO_WRAP) }.getOrNull() else null
    }
    MirrorSheet(title = "Add a caption", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(destination, color = MirrorArt.Dim, fontSize = 12.sp)
            if (draft.replyToId != null) Text("Replying to the selected message", color = MirrorArt.Dim, fontSize = 12.sp)
            if (draft.kind == "file") {
                Text(draft.fileName ?: "Document", color = MirrorArt.Text, fontWeight = FontWeight.SemiBold)
                Text("${draft.fileSize ?: 0} bytes · review before sending", color = MirrorArt.Dim, fontSize = 12.sp)
            } else AsyncImage(
                model = preview,
                contentDescription = "Staged photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(14.dp)),
            )
            if (draft.kind == "image") {
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy && !locked) { onViewOnce(!draft.viewOnce) }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("View once", color = MirrorArt.Text, fontSize = 13.sp)
                    Text(if (draft.viewOnce) "On" else "Off", color = MirrorArt.Accent, fontSize = 13.sp)
                }
            }
            if (draft.anon) Text("Sending anonymously", color = MirrorArt.Accent2, fontSize = 12.sp)
            if (locked) Text("Send was attempted. This attachment may already be queued; retries use the same operation.", color = MirrorArt.Dim, fontSize = 12.sp)
            error?.let { Text(it, color = MirrorArt.Accent2, fontSize = 12.sp) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(MirrorArt.White7)
                    .border(1.dp, MirrorArt.Hairline, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    if (caption.isBlank()) {
                        Text("Add a caption...", color = MirrorArt.Faint, fontSize = 13.sp)
                    }
                    BasicTextField(
                        value = caption,
                        onValueChange = { if (!busy && !locked) caption = it },
                        readOnly = busy || locked,
                        textStyle = TextStyle(color = MirrorArt.Text, fontSize = 13.sp),
                        cursorBrush = SolidColor(MirrorArt.Accent),
                        maxLines = 2,
                    )
                }
                if (caption.length > 1900) {
                    Text(
                        "${2000 - caption.trim().length}",
                        color = MirrorArt.Accent2,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable(enabled = !busy, onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(if (locked) "Close" else "Cancel", color = MirrorArt.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(MirrorArt.Accent)
                        .clickable(enabled = !busy && caption.trim().length <= 2000, onClick = { onSend(caption.trim()) })
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (busy) "Saving..." else if (locked) "Retry" else "Send",
                        color = Color(0xFF20150C),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * R73 - the voice-note bubble (web voice renderer + voiceBars): deterministic
 * decorative waveform bars from the message id, the real duration, and a REAL
 * MediaPlayer playback path over the uploaded /api/uploads file.
 */
@Composable
private fun MirrorVoiceNoteBubble(message: Message, mine: Boolean, filePath: String) {
    val vm: app.pulse.feature.chat.VoicePlaybackViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val state by vm.player.playback.collectAsStateWithLifecycle()
    val active = state?.takeIf { it.messageId == message.id }
    val playing = active?.playing == true
    val positionMs = active?.positionMs?.toInt() ?: 0
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(message.id, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.player.releaseIfActive(message.id)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); vm.player.releaseIfActive(message.id) }
    }
    // deterministic bars (web voiceBars hash LCG)
    val bars = remember(message.id) {
        var h = MirrorArt.hashString(message.id).toLong()
        List(22) {
            h = java.lang.Long.remainderUnsigned(h * 1103515245L + 12345L, 2147483648L)
            val v = (h / 2147483648.0)
            0.28f + (v.toFloat() * 0.72f)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (mine) Color(0x1A000000) else MirrorArt.White10)
                .mirrorPressClick(onClick = { vm.player.toggle(message) }),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon(
                if (playing) "LSquare" else "LPlay",
                tint = if (mine) MirrorArt.Ink else MirrorArt.Text,
                modifier = Modifier.size(15.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.height(26.dp),
        ) {
            for (bar in bars) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height((10.dp.value * bar).dp.coerceAtLeast(4.dp))
                        .clip(CircleShape)
                        .background(if (mine) MirrorArt.InkSoft else MirrorArt.Dim),
                )
            }
        }
        val totalSec = ((message.durationMs ?: 0L) / 1000L).toInt()
        Text(
            if (active?.loading == true) "Loading…"
            else if (playing) "%d:%02d".format(positionMs / 60000, (positionMs / 1000) % 60)
            else "%d:%02d".format(totalSec / 60, totalSec % 60),
            color = if (mine) MirrorArt.InkSoft else MirrorArt.Dim,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
