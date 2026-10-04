package app.pulse.android.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.wrapContentSize
import app.pulse.domain.model.Message
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ROOM_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Room surface: header (back / avatar / video / phone / kebab), bubbles, composer. */
@Composable
internal fun MirrorRoom(
    title: String,
    color: String?,
    isGroup: Boolean,
    groupId: String,
    subtitle: String,
    messages: List<Message>,
    viewerId: String,
    phrases: List<String>,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Header 56dp
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LChevronLeft", tint = MirrorArt.Text, modifier = Modifier.size(22.dp))
            }
            MirrorAvatar(
                name = title,
                color = color,
                isGroup = isGroup,
                groupId = groupId,
                online = false,
                showPresence = !isGroup,
                sizeDp = 38,
                cornerDp = 19,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = MirrorArt.Text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    color = MirrorArt.Dim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MirrorRoomHeaderIcon("LVideo")
            MirrorRoomHeaderIcon("LPhone")
            MirrorRoomHeaderIcon("LKebab")
        }

        // Message list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                val prev = messages.getOrNull(index - 1)
                val gap = MirrorGapMinutes(prev?.createdAt, message.createdAt)
                if (index == 0 || gap >= 4) {
                    Text(
                        MirrorRoomTimeLabel(message.createdAt),
                        color = MirrorArt.Faint,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .wrapContentSize(Alignment.Center),
                    )
                }
                MirrorBubbleRow(
                    message = message,
                    mine = message.authorId == viewerId,
                    showSender = isGroup && message.authorId != viewerId,
                )
            }
        }

        // Quick phrase rail (server-backed F-MS-29 rows)
        if (phrases.isNotEmpty() && draft.isBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (phrase in phrases.take(3)) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(MirrorArt.Chip)
                            .clickable { onSend(phrase) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            phrase,
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
                        .background(MirrorArt.Chip),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorPhosphorIcon("PEdit", tint = MirrorArt.Dim, modifier = Modifier.size(14.dp))
                }
            }
        }

        // Composer: glass pill + plus/send FAB
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(Color(0x12FFFFFF))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MirrorLucideIcon("LPaperclip", tint = MirrorArt.Dim, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (draft.isBlank()) {
                        Text("Type here", color = MirrorArt.Dim, fontSize = 15.sp)
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = TextStyle(color = MirrorArt.Text, fontSize = 15.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                }
                Spacer(Modifier.width(10.dp))
                MirrorLucideIcon("LCamera", tint = MirrorArt.Dim, modifier = Modifier.size(20.dp))
            }
            val hasText = draft.isNotBlank()
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (hasText) MirrorArt.FabGradient else androidx.compose.ui.graphics.SolidColor(Color(0x12FFFFFF)))
                    .clickable {
                        if (hasText) {
                            onSend(draft)
                            draft = ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (hasText) {
                    MirrorPhosphorIcon("PSend", tint = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    MirrorLucideIcon("LPlus", tint = MirrorArt.Text, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
private fun MirrorRoomHeaderIcon(glyph: String) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        MirrorLucideIcon(glyph, tint = MirrorArt.Text, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MirrorBubbleRow(message: Message, mine: Boolean, showSender: Boolean) {
    val bubbleShape = if (mine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        // centered time divider handled per-gap by content
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(bubbleShape)
                .background(if (mine) MirrorArt.BubbleOut else MirrorArt.BubbleIn)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                if (showSender) {
                    Text(
                        message.authorName,
                        color = MirrorArt.TextSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(1.dp))
                }
                Text(
                    if (message.deletedAt != null) "This message was deleted" else message.body,
                    color = if (mine) MirrorArt.OnBubbleOut else MirrorArt.Text,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontStyle = if (message.deletedAt != null) androidx.compose.ui.text.font.FontStyle.Italic else null,
                )
                if (mine && message.deletedAt == null) {
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        MirrorLucideIcon(
                            "LCheck",
                            tint = MirrorArt.OnBubbleOut.copy(alpha = 0.45f),
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
    }
}

/** HH:mm cluster label for the gap dividers. */
internal fun MirrorRoomTimeLabel(iso: String): String {
    val parsed = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    return ROOM_TIME.format(parsed)
}

/** Whole minutes between two ISO stamps (null-safe, gaps drive the dividers). */
internal fun MirrorGapMinutes(prevIso: String?, nextIso: String?): Long {
    val a = prevIso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return Long.MAX_VALUE
    val b = nextIso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return Long.MAX_VALUE
    return kotlin.math.abs(java.time.Duration.between(a, b).toMinutes())
}
