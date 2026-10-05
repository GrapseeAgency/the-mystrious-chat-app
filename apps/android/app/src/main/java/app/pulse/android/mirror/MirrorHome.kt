package app.pulse.android.mirror

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import app.pulse.domain.model.MessageHit
import app.pulse.domain.repository.PulseRepository
import coil.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * R62 - MirrorHome speaks the web's EXACT chats-tab geometry. Every number
 * below is lifted from src/components/chat/chats-tab.tsx (R54-b artboard
 * block): header 26sp title + three bare 44dp icons, 46x60 rounded-14 story
 * PHOTO CARDS, the 30dp chip rail whose count bubbles are white glass (the
 * signal red badge lives on ROWS only), and flat rows with the trailing
 * 18dp art-badge. Interactions are real: search filters, chips filter,
 * rows open rooms, every header icon opens something.
 */

/** The real All/Unread/Groups filter set (web ChatsListFilter). */
internal enum class MirrorFilter { All, Unread, Groups }

/** One story cell view model - shaped from the domain StoryGroup. */
internal data class MirrorStoryCard(
    val label: String,
    val name: String,
    val color: String,
    val isYou: Boolean,
    val unseen: Boolean,
    val badge: Int,
    val hasPhoto: Boolean,
    val background: String,
)

/** One folder chip view model - shaped from the domain FolderSummary. */
internal data class MirrorFolderChip(
    val id: String,
    val name: String,
    val emoji: String,
    val count: Int,
    val conversationIds: List<String> = emptyList(),
)

/** Row view model the home renders (data shaped from the domain Conversation). */
internal data class ConversationRow(
    val id: String,
    val title: String,
    val color: String?,
    val isGroup: Boolean,
    val preview: String,
    val previewPrefix: String = "",
    val previewDeleted: Boolean = false,
    val time: String,
    val unread: Int,
    val online: Boolean,
    val pinned: Boolean,
    val muted: Boolean,
    val streak: Int = 0,
    // R64 - web row affordances the mirror was missing
    val typing: Boolean = false,
    val draft: String? = null,
    val manualUnread: Boolean = false,
)

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Row time: HH:mm today, Yesterday, else MMM d - the artboard row clock. */
internal fun MirrorRowTime(iso: String?): String {
    val parsed = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
    val zoned = parsed.atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    val day = zoned.toLocalDate()
    return when {
        day == today -> TIME_FORMAT.format(parsed)
        day == today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("MMM d").format(day)
    }
}

/** Shared artboard avatar tile: gradient fill, initials, presence dot. */
@Composable
internal fun MirrorAvatar(
    name: String,
    color: String?,
    isGroup: Boolean,
    groupId: String,
    online: Boolean,
    showPresence: Boolean,
    sizeDp: Int,
    cornerDp: Int,
    modifier: Modifier = Modifier,
    /** Real uploaded photo path (bare filename or "/api/uploads/...") - null = initials. */
    photo: String? = null,
) {
    Box(modifier = modifier.size(sizeDp.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerDp.dp))
                .background(MirrorArt.avatarBrush(color, isGroup, groupId)),
        ) {
            if (photo.isNullOrBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // web: DM initials round(size*0.36), group round(size*0.34)
                    Text(
                        text = MirrorArt.initials(name),
                        color = Color.White,
                        fontSize = (sizeDp * if (isGroup) 0.34f else 0.36f).sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                // web UserAvatar/GroupAvatar: the photo covers the whole tile
                AsyncImage(
                    model = mirrorUploadHttp(photo),
                    contentDescription = "$name profile photo",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (showPresence) {
            // web: dot = max(9, round(size*0.26)), ring-2 zinc-900, bottom-right
            val dot = (sizeDp * 0.26f).coerceAtLeast(9f)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(dot.dp)
                    .clip(CircleShape)
                    .border(2.dp, MirrorArt.PresenceRing, CircleShape)
                    .background(if (online) MirrorArt.PresenceOnline else MirrorArt.PresenceOffline),
            )
        }
    }
}

/** Home surface: header, story cards, chip rail, flat rows (artboard geometry). */
@Composable
internal fun MirrorHome(
    conversations: List<ConversationRow>,
    stories: List<MirrorStoryCard>,
    folders: List<MirrorFolderChip>,
    viewerName: String,
    searching: Boolean,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onOpenSearch: () -> Unit,
    onCamera: () -> Unit,
    onKebab: () -> Unit,
    filter: MirrorFilter,
    onFilter: (MirrorFilter) -> Unit,
    activeFolderId: String?,
    onFolder: (String?) -> Unit,
    onStory: (MirrorStoryCard) -> Unit,
    onOpenConversation: (ConversationRow) -> Unit,
    onRowOptions: (ConversationRow) -> Unit,
    typingIds: Set<String> = emptySet(),
    // R72 - server message search (web /api/search rail in the home search)
    repository: PulseRepository? = null,
    onOpenConversationId: (String) -> Unit = {},
) {
    val maxW = 560.dp
    val unreadTotal = conversations.sumOf { it.unread }

    // Real filtering, exactly the web rails: All / Unread / Groups + folder.
    val activeFolder = folders.firstOrNull { f -> f.id == activeFolderId }
    val visible = conversations
        .filter { when (filter) {
            MirrorFilter.All -> true
            MirrorFilter.Unread -> it.unread > 0
            MirrorFilter.Groups -> it.isGroup
        } }
        .filter { activeFolder == null || it.id in activeFolder.conversationIds }

    // Web overlay parity: the list viewport runs EDGE-TO-EDGE so rows scroll
    // BEHIND the floating glass dock and stay visible through its translucent
    // panel, exactly like the web's absolute-positioned dock. A layout padding
    // here was a hard clip line that amputated rows mid-glyph above the dock.
    // Only the LAST item must clear the dock, so the 108dp dock clearance plus
    // the system nav inset live in contentPadding, which scrolls with content.
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            bottom = 108.dp +
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = maxW)) {
                if (searching) {
                    // Search pill - web: h-10 rounded-full bg-white/[0.07] ring-hairline px-4
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
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
                                if (searchQuery.isBlank()) {
                                    Text("Search chats and messages…", color = MirrorArt.Faint, fontSize = 14.sp)
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQuery,
                                    singleLine = true,
                                    textStyle = TextStyle(color = MirrorArt.Text, fontSize = 14.sp),
                                    cursorBrush = SolidColor(MirrorArt.Accent),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (searchQuery.isNotEmpty()) {
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x1AFFFFFF))
                                        .clickable { onSearchQuery("") },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    MirrorLucideIcon("LX", tint = MirrorArt.Dim, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onCloseSearch),
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon("LX", tint = MirrorArt.Dim, modifier = Modifier.size(20.dp))
                        }
                    }
                } else {
                    // Header - web: px-3 pt-10px, inner row py-2, title 26 bold pl-1, icons 44dp gap-0.5
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "Chats",
                            color = MirrorArt.Text,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp),
                        )
                        MirrorHeaderIconButton("LSearch", "Search chats and messages", onOpenSearch)
                        MirrorHeaderIconButton("LCamera", "Open story camera", onCamera)
                        MirrorHeaderIconButton("LKebab", "More options", onKebab)
                    }

                    // Story row - web: px-3 pt-1.5 pb-1, gap-3, 46x60 cards
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        for (story in stories.take(8)) {
                            MirrorStoryCardCell(story, onClick = { onStory(story) })
                        }
                    }

                    // Chip rail - web: px-3 pt-1.5 pb-2, gap-1.5, h-30 chips
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MirrorChip(
                            label = "All",
                            active = filter == MirrorFilter.All,
                            onClick = { onFilter(MirrorFilter.All) },
                        )
                        MirrorChip(
                            label = "Unread",
                            active = filter == MirrorFilter.Unread,
                            onClick = { onFilter(MirrorFilter.Unread) },
                            // web: idle Unread chip carries the white glass count bubble
                            count = if (filter != MirrorFilter.Unread) unreadTotal else 0,
                        )
                        MirrorChip(
                            label = "Groups",
                            active = filter == MirrorFilter.Groups,
                            onClick = { onFilter(MirrorFilter.Groups) },
                        )
                        if (folders.isNotEmpty()) {
                            // web divider: h-4 w-px hairline mx-0.5
                            Box(
                                Modifier
                                    .padding(horizontal = 2.dp)
                                    .height(16.dp)
                                    .width(1.dp)
                                    .background(MirrorArt.Hairline),
                            )
                            for (folder in folders) {
                                MirrorFolderChipCell(
                                    folder = folder,
                                    active = activeFolderId == folder.id,
                                    onClick = { onFolder(if (activeFolderId == folder.id) null else folder.id) },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // web list container: px-3, pt-1, rows flat on the scene
        val shown = visible
            .filter { r -> searchQuery.isBlank() || r.title.contains(searchQuery, true) || r.preview.contains(searchQuery, true) }
        items(shown, key = { it.id }) { row ->
            Box(
                Modifier
                    .widthIn(max = maxW)
                    .padding(horizontal = 12.dp),
            ) {
                MirrorConversationRow(
                    row = if (row.id in typingIds) row.copy(typing = true) else row,
                    onOpen = { onOpenConversation(row) },
                    onOptions = { onRowOptions(row) },
                    // R74 - swipe chips run the SAME handlers the option sheet uses
                    onPin = {
                        repository?.let { repo ->
                            CoroutineScope(Dispatchers.IO).launch {
                                runCatching { repo.togglePin(row.id, !row.pinned) }
                            }
                        }
                    },
                    onArchive = {
                        repository?.let { repo ->
                            CoroutineScope(Dispatchers.IO).launch {
                                runCatching { repo.archive(row.id, true) }
                            }
                        }
                    },
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (searchQuery.isBlank()) "No chats here yet" else "No matches",
                        color = MirrorArt.Dim,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (searchQuery.isBlank()) "Your next great chat is one tap away. Find someone and break the ice."
                        else "Try a different word or check the spelling.",
                        color = MirrorArt.Faint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
                    )
                }
            }
        }
        // R72 - MESSAGES section: the web home search ALSO queries the real
        // /api/search endpoint and renders message hits (chats-tab 1880-1899).
        // R73 CI fix: the fetch state must live INSIDE an item{} (LazyListScope
        // is not a composable context).
        if (searchQuery.length >= 2 && repository != null) {
            item {
                var hits by remember(searchQuery) { mutableStateOf<List<MessageHit>?>(null) }
                LaunchedEffect(searchQuery) {
                    kotlinx.coroutines.delay(250)
                    hits = runCatching { repository.searchMessages(searchQuery).getOrNull() }.getOrNull()
                }
                val loaded = hits
                if (loaded != null && loaded.isNotEmpty()) {
                    Column {
                        Text(
                            "MESSAGES",
                            color = MirrorArt.Faint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                        )
                        for (hit in loaded.take(12)) {
                            Column(
                                Modifier
                                    .widthIn(max = maxW)
                                    .fillMaxWidth()
                                    .clickable { onOpenConversationId(hit.conversationId) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        hit.senderName,
                                        color = MirrorArt.Text,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        hit.conversationName,
                                        color = MirrorArt.Faint,
                                        fontSize = 10.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    hit.content,
                                    color = MirrorArt.Dim,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else if (loaded != null && loaded.isEmpty() && shown.isEmpty()) {
                    Text(
                        "No messages found for \u201C$searchQuery\u201D",
                        color = MirrorArt.Faint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
            }
        }
    }
}

/** 44dp bare header icon button - web HEADER_ICON_CLS: no chrome, 22dp glyph. */
@Composable
private fun MirrorHeaderIconButton(glyph: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MirrorLucideIcon(glyph, tint = MirrorArt.Text, modifier = Modifier.size(22.dp))
    }
}

/**
 * Story cell - web StoryRingCell: w-14 column, 46x60 rounded-14 card.
 * You = white/6 tile + centered Plus; unseen = 2dp accent/85 ring; seen =
 * 1dp hairline ring; card interior falls back to the dark initials tile the
 * web renders when a story has no photo; red count badge top-right.
 */
@Composable
private fun MirrorStoryCardCell(story: MirrorStoryCard, onClick: () -> Unit) {
    Column(
        Modifier
            .width(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 46.dp, height = 60.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = if (story.isYou) 0.dp else if (story.unseen) 2.dp else 1.dp,
                    brush = SolidColor(
                        when {
                            story.isYou -> Color.Transparent
                            story.unseen -> MirrorArt.Accent.copy(alpha = 0.85f)
                            else -> MirrorArt.Hairline
                        },
                    ),
                    shape = RoundedCornerShape(14.dp),
                )
                .background(
                    if (story.isYou) SolidColor(MirrorArt.Chip)
                    else SolidColor(Color(0xFF17110C)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                story.isYou -> MirrorLucideIcon(
                    "LPlus",
                    tint = MirrorArt.Dim,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.2f,
                )
                // No native image pipeline yet - the card interior falls back to
                // the web's own no-photo rendering: dark tile + initials avatar.
                else -> MirrorAvatar(
                    name = story.name,
                    color = story.color,
                    isGroup = false,
                    groupId = "",
                    online = false,
                    showPresence = false,
                    sizeDp = 30,
                    cornerDp = 15,
                )
            }
            if (!story.isYou && story.badge > 0) {
                // web: absolute right-1 top-1 h-[15px] min-w-[15px] px-1 text-[9px] bold art-badge
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 4.dp, top = 4.dp)
                        .heightIn(min = 15.dp)
                        .widthIn(min = 15.dp)
                        .clip(CircleShape)
                        .background(MirrorArt.Red)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (story.badge > 9) "9+" else story.badge.toString(),
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 15.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            story.label,
            color = MirrorArt.Dim,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}

/** Artboard chip - web ART_CHIP_CLS: h-30 px-3.5 rounded-full 13sp medium. */
@Composable
private fun MirrorChip(label: String, active: Boolean, onClick: () -> Unit, count: Int = 0) {
    Row(
        Modifier
            .height(30.dp)
            .clip(CircleShape)
            .background(if (active) MirrorArt.ChipActive else MirrorArt.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (count > 0) {
            // web idle count bubble: h-[15px] min-w-[15px] px-1 bg-white/10 text-[9px] bold TextSoft
            Box(
                Modifier
                    .heightIn(min = 15.dp)
                    .widthIn(min = 15.dp)
                    .clip(CircleShape)
                    .background(MirrorArt.White10)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (count > 99) "99+" else count.toString(),
                    color = MirrorArt.TextSoft,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 15.sp,
                )
            }
        }
        Text(
            label,
            color = if (active) MirrorArt.Text else MirrorArt.Dim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Folder chip - web: glyph 14 + name (max 96) + white count bubble. */
@Composable
private fun MirrorFolderChipCell(folder: MirrorFolderChip, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(30.dp)
            .clip(CircleShape)
            .background(if (active) MirrorArt.ChipActive else MirrorArt.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(folder.emoji, fontSize = 14.sp)
        Text(
            folder.name,
            color = if (active) MirrorArt.Text else MirrorArt.Dim,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        if (folder.count > 0) {
            Box(
                Modifier
                    .heightIn(min = 15.dp)
                    .widthIn(min = 15.dp)
                    .clip(CircleShape)
                    .background(if (active) MirrorArt.White20 else MirrorArt.White7)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (folder.count > 99) "99+" else folder.count.toString(),
                    color = if (active) MirrorArt.Text else MirrorArt.Dim,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

/**
 * Artboard conversation row - web ArtConversationRow (R64 re-audit, exact):
 * px-1.5 wrapper + rounded-2xl px-2 py-2.5 gap-3 flat row, 50dp avatar,
 * L1 = name 15 semibold baseline + [streak chip] + time 11 tabular
 * (TextSoft semibold when unread, Faint otherwise); L2 = typing dots /
 * Draft line / preview 13 Dim, then the rotated FILLED pin (size-3), the
 * BellOff muted chip, and the FLAT signal-red 18dp art-badge (web R54-b
 * replaced the old gradient pill with var(--art-red)). Long-press opens
 * the web's ChatOptionsSheet rows; press opens the room.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MirrorConversationRow(
    row: ConversationRow,
    onOpen: () -> Unit,
    onOptions: () -> Unit,
    // R74 - swipe-left action chips (web chats-row.tsx R27-e/R43): the same
    // pin/archive handlers the option menu uses, revealed behind the row.
    onPin: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val hasUnread = row.unread > 0 || row.manualUnread
    // swipe state (web SWIPE_REVEAL_PX 112 / SWIPE_OPEN_THRESHOLD_PX 56).
    // Plain offset state + a state-driven settle spring: no suspend calls
    // inside the drag lambda (it is not a coroutine context).
    val density = LocalDensity.current
    val revealPx = with(density) { 112.dp.toPx() }
    val openThresholdPx = with(density) { 56.dp.toPx() }
    var dragOffsetPx by remember(row.id) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var dragging by remember(row.id) { mutableStateOf(false) }
    var swipeOpen by remember(row.id) { mutableStateOf(false) }
    val settle by androidx.compose.animation.core.animateFloatAsState(
        targetValue = when {
            dragging -> dragOffsetPx
            swipeOpen -> -revealPx
            else -> 0f
        },
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        ),
        label = "swipeSettle",
    )
    val dragX = if (dragging) dragOffsetPx else settle
    val chipsVisible = swipeOpen && (onPin != null || onArchive != null)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
    ) {
        // swipe-left glass action chips (web L230-296): Pin/Unpin + Archive
        if (chipsVisible) {
            Row(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (onPin != null) {
                    Column(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MirrorArt.Chip)
                            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(14.dp))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                swipeOpen = false
                                dragging = false
                                dragOffsetPx = 0f
                                onPin()
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        MirrorLucideIcon(if (row.pinned) "LPinOff" else "LPin", tint = MirrorArt.Accent2, modifier = Modifier.size(17.dp))
                        Text(
                            if (row.pinned) "Unpin" else "Pin",
                            color = MirrorArt.Dim,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (onArchive != null) {
                    Column(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MirrorArt.Chip)
                            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(14.dp))
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                swipeOpen = false
                                dragging = false
                                dragOffsetPx = 0f
                                onArchive()
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        MirrorLucideIcon("LArchive", tint = MirrorArt.Accent2, modifier = Modifier.size(17.dp))
                        Text(
                            "Archive",
                            color = MirrorArt.Dim,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .offset { androidx.compose.ui.unit.IntOffset(dragX.roundToInt(), 0) }
                .pointerInput(row.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val opened = -dragOffsetPx >= openThresholdPx
                            swipeOpen = opened
                            dragging = false
                            dragOffsetPx = 0f
                        },
                        onDragCancel = {
                            dragging = false
                            dragOffsetPx = 0f
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragging = true
                        dragOffsetPx = (dragOffsetPx + dragAmount).coerceIn(-revealPx, 0f)
                    }
                }
                .combinedClickable(
                    onClick = {
                        if (swipeOpen) {
                            // web L193: a tap on an open row closes the tray
                            swipeOpen = false
                            dragging = false
                            dragOffsetPx = 0f
                        } else {
                            onOpen()
                        }
                    },
                    onLongClick = onOptions,
                )
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MirrorAvatar(
                name = row.title,
                color = row.color,
                isGroup = row.isGroup,
                groupId = row.id,
                online = row.online,
                showPresence = !row.isGroup,
                sizeDp = 50,
                // web: DMs are full circles, groups corner = max(10, size*0.28) = 14
                cornerDp = if (row.isGroup) 14 else 25,
            )
        Column(Modifier.weight(1f)) {
            // L1 - BASELINE row: name ... streak chip + time (web items-baseline).
            // The name OWNS all the free space (weight fill) so the chip + time
            // right group sits flush at the row end on EVERY row - the web's
            // justify-between. The old weight(fill=false) name + second weighted
            // spacer split the free space 50/50 regardless of the name width,
            // leaving the time floating at a name-length-dependent x (crooked
            // column the user circled).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title,
                    color = MirrorArt.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                if (row.streak > 0) {
                    // web streak chip: bg-white/[0.06] px-1.5 py-0.5 text-10 bold Dim ring hairline
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(MirrorArt.Chip)
                            .border(1.dp, MirrorArt.Hairline, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        MirrorLucideIcon("LFlame", tint = MirrorArt.Dim, modifier = Modifier.size(12.dp))
                        Text("${row.streak}", color = MirrorArt.Dim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    row.time,
                    color = if (hasUnread) MirrorArt.TextSoft else MirrorArt.Faint,
                    fontSize = 11.sp,
                    fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Spacer(Modifier.height(2.dp))
            // L2 - typing | Draft | preview ... rotated pin + muted chip + flat red badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    when {
                        row.typing -> {
                            // web: 3 bouncing accent dots + italic typing… (accent-2)
                            MirrorTypingDotsSmall()
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "typing…",
                                color = MirrorArt.Accent2,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                fontStyle = FontStyle.Italic,
                                maxLines = 1,
                            )
                        }
                        !row.draft.isNullOrBlank() -> {
                            MirrorLucideIcon("LPencilLine", tint = MirrorArt.Accent, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Draft:",
                                color = MirrorArt.Accent2,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                row.draft!!, // captured for the smart-cast
                                color = MirrorArt.Dim,
                                fontSize = 13.sp,
                                fontStyle = FontStyle.Italic,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        else -> {
                            if (row.previewPrefix.isNotEmpty()) {
                                Text(
                                    row.previewPrefix,
                                    color = MirrorArt.Faint,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                row.preview,
                                color = MirrorArt.Dim,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // web: deleted tombstones render italic
                                fontStyle = if (row.previewDeleted) FontStyle.Italic else FontStyle.Normal,
                            )
                        }
                    }
                }
                if (row.pinned) {
                    // web: Pin size-3 rotate-45 fill art-faint (rotated + FILLED)
                    MirrorLucideIcon(
                        "LPin",
                        tint = MirrorArt.Faint,
                        modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = 45f },
                        filled = true,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (row.muted) {
                    // web muted chip: BellOff size-3 in a white/[0.08] pill, count when unread
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(if (hasUnread) MirrorArt.White10 else Color.Transparent)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        MirrorLucideIcon("LBellOff", tint = MirrorArt.Faint, modifier = Modifier.size(12.dp))
                        if (hasUnread) {
                            Text(
                                if (row.unread > 99) "99+" else row.unread.toString(),
                                color = MirrorArt.TextSoft,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (hasUnread && row.unread > 0) {
                    // web R54-b art-badge: FLAT var(--art-red), h-18 min-w-18 px-1.5 text-11 bold
                    Box(
                        Modifier
                            .heightIn(min = 18.dp)
                            .widthIn(min = 18.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.Red)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (row.unread > 99) "99+" else row.unread.toString(),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 18.sp,
                        )
                    }
                } else if (hasUnread) {
                    // manual mark-as-unread dot (web size-2.5 art-badge)
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.Red),
                    )
                }
            }
        }
    }
    }
}

/** The chat-list typing dots: 3 x 3.5dp accent dots, y bounce + opacity pulse. */
@Composable
internal fun MirrorTypingDotsSmall() {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val transition = rememberInfiniteTransition(label = "listTyper$i")
            val y by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0f at 0 using LinearEasing
                        -2.5f at 250 using LinearEasing
                        0f at 500
                    },
                    initialStartOffset = StartOffset(i * 150),
                ),
                label = "listTyperY$i",
            )
            val alpha by transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 0.45f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0.45f at 0
                        1f at 250
                        0.45f at 500
                    },
                    initialStartOffset = StartOffset(i * 150),
                ),
                label = "listTyperA$i",
            )
            Box(
                Modifier
                    .offset(y = y.dp)
                    .size(3.5.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .background(MirrorArt.Accent),
            )
        }
    }
}
