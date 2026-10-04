package app.pulse.android.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.model.MentionItem
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
/**
 * R64 - the home kebab menu as the web's chats-tab R54-c dropdown: three
 * labelled sections (ACTIONS / BROWSE / SYSTEM) with trailing counts, every
 * row wired to a real gateway-backed action. Dark ember dropdown (#1c1610/95,
 * rounded-16, hairline border, 240dp min width) exactly like KEBAB_CONTENT_CLS.
 */
internal fun MirrorKebabMenu(
    archivedCount: Int,
    selfOpen: Boolean,
    mentionCount: Int,
    channelCount: Int,
    onSearch: () -> Unit,
    onNewChat: () -> Unit,
    onNewGroup: () -> Unit,
    onJoinCode: () -> Unit,
    onContacts: () -> Unit,
    onCalls: () -> Unit,
    onArchived: () -> Unit,
    onNoteToSelf: () -> Unit,
    onMentions: () -> Unit,
    onChannels: () -> Unit,
    onFolders: () -> Unit,
    onSaved: () -> Unit,
    onStories: () -> Unit,
    onSettings: () -> Unit,
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
                .padding(6.dp)
                .clickable(enabled = false) {},
        ) {
            MirrorKebabLabel("Actions")
            MirrorKebabItem("LSearch", "Search", onClick = { onDismiss(); onSearch() })
            MirrorKebabItem("LMessageCircle", "New chat", onClick = { onDismiss(); onNewChat() })
            MirrorKebabItem("LUsers", "New group", onClick = { onDismiss(); onNewGroup() })
            MirrorKebabItem("LTicket", "Join with code", onClick = { onDismiss(); onJoinCode() })
            Spacer(Modifier.height(1.dp).fillMaxWidth().background(MirrorArt.Hairline))
            MirrorKebabLabel("Browse")
            MirrorKebabItem("LBookUser", "Contacts", onClick = { onDismiss(); onContacts() })
            MirrorKebabItem("LPhone", "Calls", onClick = { onDismiss(); onCalls() })
            MirrorKebabItem("LArchive", "Archived", trailing = archivedCount.toString(), onClick = { onDismiss(); onArchived() })
            MirrorKebabItem("LNotebookPen", "Note to Self", trailing = if (selfOpen) "Open" else "New", onClick = { onDismiss(); onNoteToSelf() })
            MirrorKebabItem("LAtSign", "Mentions", trailing = if (mentionCount > 0) (if (mentionCount > 99) "99+" else mentionCount.toString()) else "", onClick = { onDismiss(); onMentions() })
            MirrorKebabItem("LRadio", "Channels", trailing = channelCount.toString(), onClick = { onDismiss(); onChannels() })
            MirrorKebabItem("LFolderPlus", "Folders", onClick = { onDismiss(); onFolders() })
            Spacer(Modifier.height(1.dp).fillMaxWidth().background(MirrorArt.Hairline))
            MirrorKebabLabel("System")
            MirrorKebabItem("LBookmark", "Saved", onClick = { onDismiss(); onSaved() })
            MirrorKebabItem("LCircleDashed", "Stories", onClick = { onDismiss(); onStories() })
            MirrorKebabItem("LSettings", "Settings", onClick = { onDismiss(); onSettings() })
        }
    }
}

/** Section label (web KEBAB_LABEL_CLS): 10px bold uppercase art-faint. */
@Composable
private fun MirrorKebabLabel(text: String) {
    Text(
        text.uppercase(),
        color = MirrorArt.Faint,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun MirrorKebabItem(
    glyph: String,
    label: String,
    trailing: String = "",
    onClick: () -> Unit,
) {
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
        if (trailing.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            Text(
                trailing,
                color = MirrorArt.Faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
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
    // Same overlay parity as the home list: viewport edge-to-edge, rows scroll
    // behind the dock glass, only the last item clears it via contentPadding.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            bottom = 108.dp +
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
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

// R64 - the web kebab menu destinations, native and REAL =========================

private const val SHEET_MAX = 380

/** Scrollable sheet body wrapper so long lists never overflow the panel. */
@Composable
private fun MirrorSheetScroll(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .heightIn(max = SHEET_MAX.dp)
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

/** Search field used by the picker sheets (same pill language as the home). */
@Composable
private fun MirrorSheetSearch(query: String, placeholder: String, onQuery: (String) -> Unit) {
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
                Text(placeholder, color = MirrorArt.Faint, fontSize = 14.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = MirrorArt.Text, fontSize = 14.sp),
                cursorBrush = SolidColor(MirrorArt.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Contacts: real users() search; tap opens (creates) the DM. */
@Composable
internal fun MirrorContactsSheet(
    repository: PulseRepository,
    onOpened: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(query) {
        loading = true
        delay(180)
        users = repository.users(query.trim()).getOrDefault(emptyList())
        loading = false
    }
    MirrorSheet(title = "Contacts", onDismiss = onDismiss) {
        MirrorSheetSearch(query, "Search people…", onQuery = { query = it })
        Spacer(Modifier.height(10.dp))
        MirrorSheetScroll {
            if (!loading && users.isEmpty()) {
                Text("Nobody here matches that", color = MirrorArt.Faint, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
            for (user in users) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            CoroutineScope(Dispatchers.IO).launch {
                                repository.createDm(user.id).onSuccess { convo ->
                                    kotlinx.coroutines.withContext(Dispatchers.Main) { onOpened(convo.id) }
                                }
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorAvatar(
                        name = user.name,
                        color = user.color,
                        isGroup = false,
                        groupId = "",
                        online = false,
                        showPresence = false,
                        sizeDp = 40,
                        cornerDp = 20,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(user.name, color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        val status = user.statusText
                        if (!status.isNullOrBlank()) {
                            Text(status, color = MirrorArt.Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** New group: real users() picker + createGroup (server needs 2+ others). */
@Composable
internal fun MirrorGroupSheet(
    repository: PulseRepository,
    onOpened: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    val picked = remember { mutableStateOf(setOf<String>()) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        delay(180)
        users = repository.users(query.trim()).getOrDefault(emptyList())
    }
    MirrorSheet(title = "New group", onDismiss = onDismiss) {
        MirrorSheetSearch(query, "Search people…", onQuery = { query = it })
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(CircleShape)
                .background(MirrorArt.White7)
                .border(1.dp, MirrorArt.Hairline, CircleShape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(color = MirrorArt.Text, fontSize = 14.sp),
                cursorBrush = SolidColor(MirrorArt.Accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (name.isBlank()) Text("Group name", color = MirrorArt.Faint, fontSize = 14.sp)
                    inner()
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        MirrorSheetScroll {
            for (user in users) {
                val checked = user.id in picked.value
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            picked.value = if (checked) picked.value - user.id else picked.value + user.id
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorAvatar(
                        name = user.name,
                        color = user.color,
                        isGroup = false,
                        groupId = "",
                        online = false,
                        showPresence = false,
                        sizeDp = 36,
                        cornerDp = 18,
                    )
                    Text(user.name, color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (checked) MirrorArt.Accent else MirrorArt.White7)
                            .border(1.dp, if (checked) Color.Transparent else MirrorArt.Hairline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (checked) MirrorLucideIcon("LCheck", tint = Color.White, modifier = Modifier.size(14.dp), strokeWidth = 2.6f)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val canCreate = name.isNotBlank() && picked.value.size >= 2 && !creating
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(CircleShape)
                .background(if (canCreate) MirrorArt.Accent else MirrorArt.White7)
                .clickable(enabled = canCreate) {
                    creating = true
                    CoroutineScope(Dispatchers.IO).launch {
                        repository.createGroup(name.trim(), picked.value.toList()).onSuccess { convo ->
                            kotlinx.coroutines.withContext(Dispatchers.Main) { onOpened(convo.id) }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (creating) "Creating…" else "Create group",
                color = if (canCreate) Color.White else MirrorArt.Faint,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Join with code: real invite join; success opens the conversation. */
@Composable
internal fun MirrorJoinSheet(
    repository: PulseRepository,
    onOpened: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    MirrorSheet(title = "Join with code", onDismiss = onDismiss) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(CircleShape)
                .background(MirrorArt.White7)
                .border(1.dp, MirrorArt.Hairline, CircleShape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = code,
                onValueChange = { code = it },
                singleLine = true,
                textStyle = TextStyle(color = MirrorArt.Text, fontSize = 15.sp),
                cursorBrush = SolidColor(MirrorArt.Accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (code.isBlank()) Text("Paste an invite code or link", color = MirrorArt.Faint, fontSize = 14.sp)
                    inner()
                },
            )
        }
        if (error.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MirrorArt.Red, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(CircleShape)
                .background(if (code.isNotBlank() && !busy) MirrorArt.Accent else MirrorArt.White7)
                .clickable(enabled = code.isNotBlank() && !busy) {
                    busy = true
                    error = ""
                    CoroutineScope(Dispatchers.IO).launch {
                        val clean = code.trim().substringAfterLast('/').trim()
                        repository.joinInvite(clean)
                            .onSuccess { outcome ->
                                kotlinx.coroutines.withContext(Dispatchers.Main) { onOpened(outcome.conversationId) }
                            }
                            .onFailure {
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    error = it.message ?: "That code did not work"
                                    busy = false
                                }
                            }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (busy) "Joining…" else "Join",
                color = if (code.isNotBlank() && !busy) Color.White else MirrorArt.Faint,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Archived chats: every archived row with an unarchive action. */
@Composable
internal fun MirrorArchivedSheet(
    archived: List<Conversation>,
    presence: Set<String>,
    viewerId: String,
    onOpen: (Conversation) -> Unit,
    onUnarchive: (Conversation) -> Unit,
    onDismiss: () -> Unit,
) {
    MirrorSheet(title = "Archived", onDismiss = onDismiss) {
        MirrorSheetScroll {
            if (archived.isEmpty()) {
                Text("Nothing archived", color = MirrorArt.Faint, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
            for (convo in archived) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpen(convo) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorAvatar(
                        name = convo.title,
                        color = convo.accentColor,
                        isGroup = convo.isGroupish,
                        groupId = convo.id,
                        online = convo.otherUserId != null && presence.contains(convo.otherUserId),
                        showPresence = !convo.isGroupish,
                        sizeDp = 44,
                        cornerDp = if (convo.isGroupish) 14 else 22,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(convo.title, color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            convo.lastMessagePreview ?: "No messages yet",
                            color = MirrorArt.Dim,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable { onUnarchive(convo) },
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LArchiveRestore", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** Mentions: live GET /api/mentions rows; tap opens the conversation. */
@Composable
internal fun MirrorMentionsSheet(
    repository: PulseRepository,
    onOpenConv: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var items by remember { mutableStateOf<List<MentionItem>?>(null) }
    LaunchedEffect(Unit) {
        items = repository.mentions().getOrDefault(emptyList())
    }
    MirrorSheet(title = "Mentions", onDismiss = onDismiss) {
        MirrorSheetScroll {
            val list = items
            when {
                list == null -> Text("Loading mentions…", color = MirrorArt.Faint, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                list.isEmpty() -> Text("No mentions yet - when someone @-names you it lands here", color = MirrorArt.Faint, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                else -> for (mention in list) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onOpenConv(mention.conversationId) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                mention.conversationName ?: "Conversation",
                                color = MirrorArt.Text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(MirrorRowTime(mention.createdAt), color = MirrorArt.Faint, fontSize = 11.sp)
                        }
                        Text(
                            (if (mention.authorName.isNotBlank()) mention.authorName + ": " else "") + mention.snippet,
                            color = MirrorArt.Dim,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Folders: pick a live folder filter (tap again to clear). */
@Composable
internal fun MirrorFoldersSheet(
    folders: List<MirrorFolderChip>,
    activeFolderId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    MirrorSheet(title = "Folders", onDismiss = onDismiss) {
        MirrorSheetScroll {
            if (folders.isEmpty()) {
                Text("No folders yet - create them on the web and they appear here", color = MirrorArt.Faint, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
            for (folder in folders) {
                val active = folder.id == activeFolderId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) MirrorArt.ChipActive else Color.Transparent)
                        .clickable { onPick(if (active) null else folder.id) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(folder.emoji, fontSize = 16.sp)
                    Text(folder.name, color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(folder.count.toString(), color = MirrorArt.Faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Row long-press sheet - the web ChatOptionsSheet rows verbatim: pin / archive
 * / mark-unread / mute (8h/1w/Always strip) / export .txt / clear chat (own
 * messages, confirm-gated). Every action runs the real repository call.
 */
@Composable
internal fun MirrorRowOptionsSheet(
    conversationId: String,
    title: String,
    pinned: Boolean,
    archived: Boolean,
    manualUnread: Boolean,
    muted: Boolean,
    repository: PulseRepository,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    var muteStrip by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    fun act(work: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { work() }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                onChanged()
                onDismiss()
            }
        }
    }
    MirrorSheet(title = title, onDismiss = onDismiss) {
        MirrorSheetScroll {
            when {
                muteStrip -> {
                    Text(
                        "MUTE FOR",
                        color = MirrorArt.Faint,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in listOf("8h", "1w", "Always")) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MirrorArt.White7)
                                    .clickable {
                                        act {
                                            repository.setMutedUntil(
                                                conversationId,
                                                when (choice) {
                                                    "8h" -> java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 8L * 3_600_000L).toString()
                                                    "1w" -> java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 7L * 86_400_000L).toString()
                                                    else -> java.time.Instant.ofEpochMilli(3_252_524_799_999L).toString()
                                                },
                                            )
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(choice, color = MirrorArt.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                confirmClear -> {
                    Text(
                        "Clear chat",
                        color = MirrorArt.Text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Text(
                        "This deletes YOUR OWN messages in this chat for everyone. The other side keeps theirs.",
                        color = MirrorArt.Dim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MirrorArt.White7)
                                .clickable { confirmClear = false }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Keep", color = MirrorArt.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MirrorArt.Red)
                                .clickable {
                                    act {
                                        repository.clearMyMessages(conversationId)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Clear my messages", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                else -> {
                    MirrorOptionRow("LPin", if (pinned) "Unpin from top" else "Pin to top") {
                        act { repository.togglePin(conversationId, !pinned) }
                    }
                    MirrorOptionRow("LArchive", if (archived) "Unarchive chat" else "Archive chat") {
                        act { repository.archive(conversationId, !archived) }
                    }
                    MirrorOptionRow(if (manualUnread) "LMailOpen" else "LMail", if (manualUnread) "Mark as read" else "Mark as unread") {
                        act { repository.markUnread(conversationId, !manualUnread) }
                    }
                    MirrorOptionRow("LBellOff", "Mute notifications") { muteStrip = true }
                    // Export runs WITHOUT closing the sheet - the saved file
                    // name lands in the note row (web downloadTranscript feel).
                    MirrorOptionRow("LDownload", "Export chat (.txt)") {
                        CoroutineScope(Dispatchers.IO).launch {
                            repository.exportChat(conversationId).onSuccess { file ->
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    note = "Saved as $file"
                                }
                            }
                        }
                    }
                    MirrorOptionRow("LEraser", "Clear chat…") { confirmClear = true }
                    if (note.isNotEmpty()) {
                        Text(note, color = MirrorArt.Accent2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MirrorOptionRow(icon: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MirrorLucideIcon(icon, tint = MirrorArt.Dim, modifier = Modifier.size(18.dp))
        Text(label, color = MirrorArt.Text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** Room info / Manage chat: live members, presence, mute + export + clear. */
@Composable
internal fun MirrorRoomInfoSheet(
    conversationId: String,
    title: String,
    color: String?,
    isGroup: Boolean,
    members: List<ConversationMember>,
    presence: Set<String>,
    viewerId: String,
    muted: Boolean,
    repository: PulseRepository,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember { mutableStateOf("") }
    fun act(work: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { work() }
            kotlinx.coroutines.withContext(Dispatchers.Main) { onChanged() }
        }
    }
    MirrorSheet(title = if (isGroup) "Group info" else "Chat info", onDismiss = onDismiss) {
        MirrorSheetScroll {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MirrorAvatar(
                    name = title,
                    color = color,
                    isGroup = isGroup,
                    groupId = conversationId,
                    online = false,
                    showPresence = false,
                    sizeDp = 56,
                    cornerDp = if (isGroup) 16 else 28,
                )
                Column {
                    Text(title, color = MirrorArt.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isGroup) "${members.size} members" else "Direct message",
                        color = MirrorArt.Faint,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            for (member in members) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorAvatar(
                        name = member.name,
                        color = member.color,
                        isGroup = false,
                        groupId = "",
                        online = presence.contains(member.id),
                        showPresence = true,
                        sizeDp = 32,
                        cornerDp = 16,
                    )
                    Text(member.name, color = MirrorArt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (member.id == viewerId) {
                        Text("you", color = MirrorArt.Faint, fontSize = 11.sp)
                    } else if (presence.contains(member.id)) {
                        Text("online", color = MirrorArt.PresenceOnline, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            MirrorOptionRow(if (muted) "LVolumeX" else "LBellOff", if (muted) "Unmute notifications" else "Mute notifications") {
                act { repository.setMuted(conversationId, !muted) }
            }
            MirrorOptionRow("LDownload", "Export chat (.txt)") {
                act {
                    repository.exportChat(conversationId).onSuccess { file ->
                        kotlinx.coroutines.withContext(Dispatchers.Main) { note = "Saved as $file" }
                    }
                }
            }
            if (note.isNotEmpty()) {
                Text(note, color = MirrorArt.Accent2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}
