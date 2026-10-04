package app.pulse.android.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.android.mirror.MirrorArt.Bg
import app.pulse.android.mirror.MirrorArt.Chip
import app.pulse.android.mirror.MirrorArt.Dim
import app.pulse.android.mirror.MirrorArt.Faint
import app.pulse.android.mirror.MirrorArt.Red
import app.pulse.android.mirror.MirrorArt.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
) {
    Box(modifier = modifier.size(sizeDp.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerDp.dp))
                .background(MirrorArt.avatarBrush(color, isGroup, groupId)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = MirrorArt.initials(name),
                    color = Color.White,
                    fontSize = (sizeDp * 0.34f).sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (showPresence) {
            val dot = (sizeDp * 0.28f).coerceAtLeast(10f)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(1.dp)
                    .size(dot.dp)
                    .clip(CircleShape)
                    .border(2.dp, Bg, CircleShape)
                    .background(if (online) MirrorArt.PresenceOnline else MirrorArt.PresenceOffline),
            )
        }
    }
}

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

/** Home surface: header, story discs, chip rail, flat rows (artboard geometry). */
@Composable
internal fun MirrorHome(
    conversations: List<ConversationRow>,
    stories: List<StoryDisc>,
    viewerName: String,
    onOpenConversation: (ConversationRow) -> Unit,
) {
    val maxW = 560.dp
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier
            .fillMaxSize()
            // R60 - the artboard starts BELOW the status bar; the audited build
            // painted "Chats" and the clock on top of each other.
            .statusBarsPadding()
            .padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = maxW)) {
                // Header: Chats + three bare icons (lucide 22dp in 44dp targets)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Chats",
                        color = Text,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp),
                    )
                    MirrorHeaderIconButton("LSearch")
                    MirrorHeaderIconButton("LCamera")
                    MirrorHeaderIconButton("LKebab")
                }

                // Story discs: You cell + real groups
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MirrorStoryDisc(
                        label = "You",
                        name = viewerName,
                        color = "orange",
                        ringSeen = false,
                        isYou = true,
                    )
                    for (story in stories.take(7)) {
                        MirrorStoryDisc(
                            label = story.label,
                            name = story.name,
                            color = story.color,
                            ringSeen = story.seen,
                            isYou = false,
                        )
                    }
                }

                // Chip rail: All / Unread / Groups with real counts
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val unread = conversations.sumOf { it.unread }
                    MirrorChip("All", active = true, count = 0)
                    MirrorChip("Unread", active = false, count = unread)
                    MirrorChip("Groups", active = false, count = conversations.count { it.isGroup })
                }
                Spacer(Modifier.height(2.dp))
            }
        }

        items(conversations, key = { it.id }) { row ->
            Box(Modifier.widthIn(max = maxW), contentAlignment = Alignment.Center) {
                MirrorConversationRow(row = row, onOpen = { onOpenConversation(row) })
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** 44dp bare header icon button (lucide 22dp, artboard text tint). */
@Composable
private fun MirrorHeaderIconButton(glyph: String) {
    Box(
        Modifier
            .padding(2.dp)
            .size(40.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        MirrorLucideIcon(glyph, tint = Text, modifier = Modifier.size(22.dp))
    }
}

/** 56dp story disc with the conic ember ring (unseen) or hairline ring (seen). */
@Composable
private fun MirrorStoryDisc(label: String, name: String, color: String, ringSeen: Boolean, isYou: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (isYou) {
                // R60 - artboard You cell: a quiet dark tile with a CENTERED
                // plus glyph. The old rendering put an initials avatar inside
                // the ring, so a blank viewer name painted a loud orange "?".
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MirrorArt.Chip)
                        .border(1.dp, MirrorArt.Hairline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LPlus", tint = MirrorArt.TextSoft, modifier = Modifier.size(20.dp), strokeWidth = 2f)
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            if (ringSeen) Brush.verticalGradient(listOf(MirrorArt.Hairline, MirrorArt.Hairline))
                            else MirrorArt.FabGradient,
                        )
                        .padding(2.5.dp),
                ) {
                    MirrorAvatar(name = name, color = color, isGroup = false, groupId = "", online = false, showPresence = false, sizeDp = 48, cornerDp = 24)
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = Dim,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(60.dp),
        )
    }
}

/** Artboard chip: 30dp pill, 13sp; active = brighter glass, count rides a red dot. */
@Composable
private fun MirrorChip(label: String, active: Boolean, count: Int) {
    Row(
        Modifier
            .height(30.dp)
            .clip(CircleShape)
            .background(if (active) MirrorArt.ChipActive else Chip)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count > 0) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Red),
                contentAlignment = Alignment.Center,
            ) {
                Text(count.coerceAtMost(99).toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (active) Text else MirrorArt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Flat transparent row: 50dp avatar, name 15sp, preview 13sp, time 11sp, red badge. */
@Composable
private fun MirrorConversationRow(row: ConversationRow, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MirrorAvatar(
            name = row.title,
            color = row.color,
            isGroup = row.isGroup,
            groupId = row.id,
            online = row.online,
            showPresence = !row.isGroup,
            sizeDp = 50,
            // R60 - artboard parity: groups wear the rounded square, DMs are
            // full circles (the audited build rendered every row as a square).
            cornerDp = if (row.isGroup) 16 else 25,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title,
                    color = Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.pinned) {
                    Spacer(Modifier.width(4.dp))
                    MirrorLucideIcon("LChevronRight", tint = Faint, modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                row.preview,
                color = Dim,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (row.streak > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .height(20.dp)
                            .clip(CircleShape)
                            .background(Chip)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${row.streak}", color = MirrorArt.Accent2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.muted) {
                    MirrorLucideIcon("LCheck", tint = Faint, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    row.time,
                    color = Faint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (row.unread > 0) {
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .height(18.dp)
                        .widthIn(min = 18.dp)
                        .clip(CircleShape)
                        .background(MirrorArt.BadgeGradient)
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        row.unread.coerceAtMost(99).toString(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
