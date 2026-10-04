package app.pulse.android.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.Channel
import app.pulse.domain.model.StoryGroup
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * R62 - the REAL mirror pages behind every dock tab and header icon:
 * Calls (live call log), Updates (channel directory with real subscribe),
 * the New-chat sheet (createDm), the home kebab menu, the story viewer and
 * the text-story composer. Every tap does something against the gateway -
 * zero dead controls, zero mock data.
 */

/** Calls tab: the live call log, direction/status/duration per row. */
@Composable
internal fun MirrorCalls(repository: PulseRepository) {
    val log by repository.observeCallLog().collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { runCatching { repository.refreshCallLog() } }
    MirrorListScaffold(title = "Calls") {
        if (log.isEmpty()) {
            item { MirrorEmptyState("No calls yet", "Voice and video calls you make show up here.") }
        }
        items(log, key = { it.id }) { entry ->
            MirrorCallRow(entry)
        }
    }
}

@Composable
private fun MirrorCallRow(entry: CallLogEntry) {
    val peer = entry.peer
    val missed = entry.status == app.pulse.domain.model.CallStatus.MISSED
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MirrorAvatar(
            name = peer?.name ?: "Unknown",
            color = peer?.color,
            isGroup = false,
            groupId = "",
            online = false,
            showPresence = false,
            sizeDp = 50,
            cornerDp = 25,
        )
        Column(Modifier.weight(1f)) {
            Text(
                peer?.name ?: "Unknown",
                color = if (missed) MirrorArt.Red else MirrorArt.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MirrorLucideIcon(
                    if (entry.outgoing) "LPhone" else "LArrowLeft",
                    tint = if (missed) MirrorArt.Red else MirrorArt.Dim,
                    modifier = Modifier.size(12.dp),
                )
                val label = buildString {
                    append(
                        when {
                            missed -> "Missed"
                            entry.outgoing -> "Outgoing"
                            else -> "Incoming"
                        },
                    )
                    if (entry.durationSec > 0) append(" · ${entry.durationSec / 60}m ${entry.durationSec % 60}s")
                }
                Text(label, color = MirrorArt.Dim, fontSize = 13.sp)
            }
        }
        Text(
            MirrorRowTime(entry.startedAt),
            color = MirrorArt.Faint,
            fontSize = 11.sp,
        )
    }
}

/** Updates tab: the real channel directory with working subscribe. */
@Composable
internal fun MirrorUpdates(repository: PulseRepository) {
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var busyId by remember { mutableStateOf<String?>(null) }

    fun load() {
        CoroutineScope(Dispatchers.IO).launch {
            channels = repository.channels(mineOnly = false).getOrDefault(emptyList())
        }
    }
    LaunchedEffect(Unit) { load() }

    MirrorListScaffold(title = "Updates") {
        if (channels.isEmpty()) {
            item { MirrorEmptyState("No channels yet", "Broadcast channels you create or join show up here.") }
        }
        items(channels, key = { it.id }) { channel ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Channel tile: the web GroupAvatar language - rounded-14 gradient square
                Box(
                    Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.verticalGradient(MirrorArt.groupGradient(channel.id))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        MirrorArt.initials(channel.name),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        channel.name,
                        color = MirrorArt.Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        channel.preview ?: "${channel.memberCount} subscribers",
                        color = MirrorArt.Dim,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!channel.isSubscribed) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(MirrorArt.ChipActive)
                            .clickable(enabled = busyId != channel.id) {
                                busyId = channel.id
                                CoroutineScope(Dispatchers.IO).launch {
                                    runCatching { repository.subscribeChannel(channel.id) }
                                    channels = repository.channels(mineOnly = false).getOrDefault(emptyList())
                                    busyId = null
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            if (busyId == channel.id) "Joining" else "Join",
                            color = MirrorArt.Accent2,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Text(
                        "${channel.memberCount}",
                        color = MirrorArt.Faint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** New chat sheet: real user directory + createDm on tap. */
@Composable
internal fun MirrorNewChatSheet(
    repository: PulseRepository,
    onDismiss: () -> Unit,
    onOpened: (String) -> Unit,
) {
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        users = repository.users("").getOrDefault(emptyList())
    }
    LaunchedEffect(query) {
        delay(250)
        users = repository.users(query).getOrDefault(emptyList())
    }

    MirrorSheet(title = "New chat", onDismiss = onDismiss) {
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
                if (query.isBlank()) Text("Search people…", color = MirrorArt.Faint, fontSize = 14.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MirrorArt.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(MirrorArt.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (users.isEmpty()) {
            MirrorEmptyState("No people found", "Invite someone to Pulse and they will appear here.")
        }
        Column(
            Modifier
                .fillMaxWidth()
                .height(320.dp),
        ) {
            LazyColumn {
                items(users, key = { it.id }) { user ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = busy == null) {
                                busy = user.id
                                CoroutineScope(Dispatchers.IO).launch {
                                    val convo = repository.createDm(user.id).getOrNull()
                                    busy = null
                                    if (convo != null) onOpened(convo.id)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MirrorAvatar(
                            name = user.name,
                            color = user.color,
                            isGroup = false,
                            groupId = "",
                            online = false,
                            showPresence = false,
                            sizeDp = 44,
                            cornerDp = 22,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (busy == user.id) "Opening chat…" else user.name,
                                color = MirrorArt.Text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (user.handle.isNotBlank()) {
                                Text("@${user.handle}", color = MirrorArt.Faint, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Home kebab menu - web KEBAB_CONTENT_CLS language, real actions only. */
@Composable
internal fun MirrorKebabMenu(
    onSearch: () -> Unit,
    onStories: () -> Unit,
    onProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 62.dp, end = 12.dp)
                .widthIn(min = 240.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .padding(6.dp),
        ) {
            MirrorKebabItem("LSearch", "Search", onClick = {
                onDismiss()
                onSearch()
            })
            MirrorKebabItem("LCamera", "Stories", onClick = {
                onDismiss()
                onStories()
            })
            MirrorKebabItem("LChevronRight", "Profile", onClick = {
                onDismiss()
                onProfile()
            })
        }
    }
}

@Composable
private fun MirrorKebabItem(glyph: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MirrorLucideIcon(glyph, tint = MirrorArt.Dim, modifier = Modifier.size(18.dp))
        Text(label, color = MirrorArt.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** Full-screen story viewer: real progress bars, tap zones, view marking. */
@Composable
internal fun MirrorStoryViewer(
    group: StoryGroup,
    repository: PulseRepository,
    onDismiss: () -> Unit,
) {
    var index by remember { mutableStateOf(0) }
    val stories = group.stories
    LaunchedEffect(group.user?.id, index) {
        val story = stories.getOrNull(index) ?: return@LaunchedEffect
        if (!story.viewedByMe) {
            runCatching { repository.markStoryViewed(story.id) }
        }
        if (stories.isNotEmpty()) {
            delay(6_000)
            if (index < stories.size - 1) index += 1 else onDismiss()
        }
    }
    val story = stories.getOrNull(index)
    Box(
        Modifier
            .fillMaxSize()
            .background(
                if (story?.imagePath != null) SolidColor(Color(0xFF17110C))
                else Brush.verticalGradient(MirrorArt.avatarGradient(story?.background ?: "emerald")),
            )
            .clickable(onClick = onDismiss),
    ) {
        // full-screen tap zones first: everything declared later sits on top
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable { if (index > 0) index -= 1 else onDismiss() },
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable { if (index < stories.size - 1) index += 1 else onDismiss() },
            )
        }
        // progress bars - one segment per story
        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (i in stories.indices) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(2.5.dp)
                        .clip(CircleShape)
                        .background(
                            if (i <= index) MirrorArt.Text.copy(alpha = 0.9f) else MirrorArt.Text.copy(alpha = 0.25f),
                        ),
                )
            }
        }
        // header: avatar + name + close
        Row(
            Modifier
                .statusBarsPadding()
                .padding(start = 12.dp, top = 24.dp, end = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MirrorAvatar(
                name = group.user?.name ?: "?",
                color = group.user?.color,
                isGroup = false,
                groupId = "",
                online = false,
                showPresence = false,
                sizeDp = 34,
                cornerDp = 17,
            )
            Text(
                group.user?.name ?: "Story",
                color = MirrorArt.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LX", tint = MirrorArt.Text, modifier = Modifier.size(20.dp))
            }
        }
        if (!story?.caption.isNullOrBlank()) {
            Text(
                story?.caption.orEmpty(),
                color = MirrorArt.Text,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x66000000))
                    .padding(14.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

/** Text story composer: background palette + caption + real createStory. */
@Composable
internal fun MirrorStoryComposer(
    repository: PulseRepository,
    onDismiss: () -> Unit,
    onPosted: () -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    var background by remember { mutableStateOf("emerald") }
    var posting by remember { mutableStateOf(false) }
    val palette = listOf("emerald", "rose", "amber", "violet", "teal", "orange", "pink", "cyan")

    MirrorSheet(title = "New status", onDismiss = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(MirrorArt.avatarGradient(background))),
            contentAlignment = Alignment.Center,
        ) {
            BasicTextField(
                value = caption,
                onValueChange = { if (it.length <= 160) caption = it },
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
            )
            if (caption.isBlank()) {
                Text(
                    "Type your status",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            for (key in palette) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .then(
                            if (background == key) Modifier.border(2.dp, MirrorArt.Text, CircleShape)
                            else Modifier,
                        )
                        .background(Brush.verticalGradient(MirrorArt.avatarGradient(key)))
                        .clickable { background = key },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (caption.isBlank() || posting) MirrorArt.Chip else MirrorArt.Accent)
                .clickable(enabled = caption.isNotBlank() && !posting) {
                    posting = true
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching { repository.createStory(caption, background, null) }
                        posting = false
                        onPosted()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (posting) "Sharing…" else "Share status",
                color = if (caption.isBlank() || posting) MirrorArt.Dim else MirrorArt.OnBubbleOut,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Shared page scaffold for dock tabs: title header + flat list on the scene. */
@Composable
private fun MirrorListScaffold(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(bottom = 108.dp),
    ) {
        item {
            Text(
                title,
                color = MirrorArt.Text,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
            )
        }
        content()
    }
}

/** Honest empty state shared by the tab pages. */
@Composable
internal fun MirrorEmptyState(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 32.dp, end = 32.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = MirrorArt.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MirrorArt.Faint, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

/** Bottom sheet chrome: scrim + artboard panel with the title row. */
@Composable
private fun MirrorSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(enabled = false) {}
                .padding(16.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = MirrorArt.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LX", tint = MirrorArt.Dim, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
