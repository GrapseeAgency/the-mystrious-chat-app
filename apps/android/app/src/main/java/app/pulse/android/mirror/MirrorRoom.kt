package app.pulse.android.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
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

/** web formatDayChip: Today / Yesterday / "3 Aug" (web pulse-utils parity). */
internal fun MirrorRoomDayLabel(iso: String): String {
    val d = runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()) }.getOrNull() ?: return ""
    val today = java.time.ZonedDateTime.now(ZoneId.systemDefault()).toLocalDate()
    val day = d.toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> ROOM_DAY.format(d)
    }
}

internal fun MirrorRoomEpoch(iso: String): Long =
    runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() ?: 0L

private fun MirrorSameDayIso(aIso: String, bIso: String): Boolean {
    val zone = ZoneId.systemDefault()
    val a = runCatching { Instant.parse(aIso).atZone(zone).toLocalDate() }.getOrNull() ?: return false
    val b = runCatching { Instant.parse(bIso).atZone(zone).toLocalDate() }.getOrNull() ?: return false
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
            out.add(RoomEntry.DayLabel(label, "day-${e.message.id}-$i"))
            dayIso = e.message.createdAt
        }
        if (e.head) out.add(RoomEntry.Stamp(e.message.createdAt, "stamp-${e.message.id}"))
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
    messages: List<Message>,
    viewerId: String,
    viewerName: String,
    memberNames: List<String>,
    members: List<ConversationMember>,
    phrases: List<QuickPhrase>,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onSendImage: (String) -> Unit,
    onToggleReaction: (String, String) -> Unit,
    onAddPhrase: (String) -> Unit,
    onDeletePhrase: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var composerFocused by remember { mutableStateOf(false) }
    var phraseManagerOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val entries = remember(messages) { buildRoomEntries(messages) }

    LaunchedEffect(messages.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val dataUrl = mirrorUriToDataUrl(context, uri)
                if (dataUrl != null) onSendImage(dataUrl)
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
                // ONE tap target: 40dp avatar + title + subtitle
                Row(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
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
                        Text(
                            subtitle,
                            color = MirrorArt.Dim,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // EXACTLY three bare icons: video, audio, kebab (44dp ghosts, size-5)
                MirrorRoomHeaderIcon("LVideo")
                MirrorRoomHeaderIcon("LPhone")
                MirrorRoomHeaderIcon("LKebab")
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MirrorArt.Hairline),
            )
        }

        // List viewport: px-3 pt-3 pb-2
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
                    .drawBehind {
                        if (composerFocused) {
                            drawCircle(
                                color = Color(0x73FF7A3D), // accent /45
                                radius = size.minDimension / 2f - 1.dp.toPx(),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // paperclip: size-9 circle, size-5 dim glyph - opens photo pick
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LPaperclip", tint = MirrorArt.Dim, modifier = Modifier.size(20.dp))
                }
                Box(Modifier.weight(1f).padding(bottom = 8.dp)) {
                    if (draft.isBlank()) {
                        Text("Type here", color = MirrorArt.Faint, fontSize = 15.sp, lineHeight = 20.sp)
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = TextStyle(
                            color = MirrorArt.Text,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { composerFocused = it.isFocused },
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
            // art-fab: 44dp DARK GLASS always - plus (photo pick) becomes the
            // send plane the moment the draft holds text
            val hasText = draft.isNotBlank()
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MirrorArt.White7)
                    .border(1.dp, MirrorArt.Hairline, CircleShape)
                    .clickable {
                        if (hasText) {
                            onSend(draft)
                            draft = ""
                        } else {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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

    if (phraseManagerOpen) {
        MirrorPhraseManager(
            phrases = phrases,
            onAdd = onAddPhrase,
            onDelete = onDeletePhrase,
            onDismiss = { phraseManagerOpen = false },
        )
    }
}

@Composable
private fun MirrorRoomHeaderIcon(glyph: String) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape),
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

/** HH:mm cluster stamp (en-US h23 parity - "08:16"). */
internal fun MirrorRoomTimeLabel(iso: String): String {
    val parsed = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    return ROOM_STAMP.format(parsed)
}

/** Whole minutes between two ISO stamps (null-safe, gaps drive clusters). */
internal fun MirrorGapMinutes(prevIso: String?, nextIso: String?): Long {
    val a = prevIso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return Long.MAX_VALUE
    val b = nextIso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return Long.MAX_VALUE
    return kotlin.math.abs(java.time.Duration.between(a, b).toMinutes())
}
