package app.pulse.android.mirror


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pulse.feature.stories.StoryViewerInput
import app.pulse.feature.stories.StoryViewerStateMachine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import app.pulse.domain.model.FolderSummary
import app.pulse.domain.model.StoryGroup
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseRepository
import app.pulse.core.PulseEndpoints
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

/**
 * Absolute gateway URL for a stored upload path. The wire stores photos as
 * "/api/uploads/<file>" (web uploads) or a bare "<file>" (older rows), so
 * the prefix is applied only when missing - the old double-prefix made every
 * web-uploaded photo render blank inside the app.
 */
internal fun mirrorUploadHttp(path: String): String =
    PulseEndpoints.http(if (path.startsWith("/api/uploads/")) path else "/api/uploads/$path")

/** New chat sheet: real user directory + createDm on tap. */
@Composable
internal fun MirrorNewChatSheet(
    repository: PulseRepository,
    onDismiss: () -> Unit,
    onOpened: (String) -> Unit,
) {
    val actionRepository = repository.boundToCurrentAccount()
    val actionScope = androidx.compose.runtime.rememberCoroutineScope()
    var directoryError by remember { mutableStateOf<String?>(null) }
    var directoryRetry by remember { mutableStateOf(0) }
    var directoryLoading by remember { mutableStateOf(false) }
    var directoryTicket by remember { mutableStateOf(0L) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    // R73 - the web NewChatSheet carries THREE modes (dm/group/channel,
    // main-shell.tsx:529). The mirror had DM only - channel creation rides
    // the real POST /api/channels + subscribe chain here.
    var mode by remember { mutableStateOf("dm") }
    var channelName by remember { mutableStateOf("") }
    var channelDesc by remember { mutableStateOf("") }
    var channelError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(actionRepository, query, directoryRetry) {
        directoryTicket++
        val ticket = directoryTicket
        directoryLoading = true
        directoryError = null
        try {
            delay(250)
            app.pulse.domain.sync.confirmedAction({ actionRepository.users(query) }) { loaded ->
                if (ticket == directoryTicket) users = loaded.filter { it.id != actionRepository.viewerId }
            }.getOrThrow()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (ticket == directoryTicket) directoryError = "People couldn't be loaded. Tap to retry."
        } finally { if (ticket == directoryTicket) directoryLoading = false }
    }

    MirrorSheet(title = if (mode == "dm") "New chat" else "New channel", onDismiss = onDismiss) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((value, label) in listOf("dm" to "Direct message", "channel" to "Channel")) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(if (mode == value) MirrorArt.ChipActive else MirrorArt.White7)
                        .border(1.dp, if (mode == value) MirrorArt.Hairline else Color.Transparent, CircleShape)
                        .mirrorPressClick(onClick = { if (busy == null) mode = value })
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        label,
                        color = if (mode == value) MirrorArt.Text else MirrorArt.Dim,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        if (mode == "channel") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirrorArt.White7)
                        .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        if (channelName.isBlank()) Text("Channel name", color = MirrorArt.Faint, fontSize = 13.sp)
                        BasicTextField(
                            value = channelName,
                            onValueChange = { if (it.length <= 48) channelName = it },
                            singleLine = true,
                            textStyle = TextStyle(color = MirrorArt.Text, fontSize = 13.sp),
                            cursorBrush = SolidColor(MirrorArt.Accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirrorArt.White7)
                        .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        if (channelDesc.isBlank()) Text("Purpose (optional)", color = MirrorArt.Faint, fontSize = 13.sp)
                        BasicTextField(
                            value = channelDesc,
                            onValueChange = { if (it.length <= 140) channelDesc = it },
                            textStyle = TextStyle(color = MirrorArt.Text, fontSize = 13.sp),
                            cursorBrush = SolidColor(MirrorArt.Accent),
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                channelError?.let { Text(it, color = MirrorArt.Red, fontSize = 12.sp) }
                Box(
                    Modifier
                        .align(Alignment.End)
                        .clip(CircleShape)
                        .background(MirrorArt.Accent)
                        .mirrorPressClick(enabled = busy == null, onClick = {
                            if (channelName.isBlank()) {
                                channelError = "Give the channel a name"
                            } else {
                                busy = "channel"
                                val name = channelName.trim()
                                val description = channelDesc.trim().ifBlank { null }
                                actionScope.launch {
                                    try {
                                        app.pulse.domain.sync.confirmedAction({ actionRepository.createChannel(name, description, null) }) {
                                            onOpened(it.id)
                                        }.getOrThrow()
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        channelError = failure.message ?: "Couldn't confirm channel creation. Check Channels before retrying."
                                    } finally { busy = null }
                                }
                            }
                        })
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text(
                        if (busy == "channel") "Creating..." else "Create channel",
                        color = Color(0xFF20150C),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else {
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
        if (directoryLoading) Text("Loading people…", color = MirrorArt.TextSoft, fontSize = 12.sp)
        directoryError?.let { Text(it, color = MirrorArt.Accent, fontSize = 12.sp,
            modifier = Modifier.clickable { directoryRetry++ }.padding(8.dp)) }
        actionError?.let { Text(it, color = MirrorArt.Accent, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
        if (users.isEmpty() && !directoryLoading && directoryError == null) {
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
                                actionError = null
                                actionScope.launch {
                                    try {
                                        app.pulse.domain.sync.confirmedAction({ actionRepository.createDm(user.id) }) { onOpened(it.id) }.getOrThrow()
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        actionError = failure.message ?: "Couldn't open this chat. Try again."
                                    } finally { busy = null }
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
}

/** Home kebab menu - web KEBAB_CONTENT_CLS language, real actions only. */
@Composable
/**
 * R64 - the home kebab menu as the web's chats-tab R54-c dropdown: three
 * labelled sections (ACTIONS / BROWSE / SYSTEM) with trailing counts, every
 * row wired to a real gateway-backed action. Dark ember dropdown (#1c1610/95,
 * rounded-16, hairline border, 240dp min width) exactly like KEBAB_CONTENT_CLS.
 * R68 - the web menu is scrollable (`max-h-[min(70vh,560px)] overflow-y-auto`)
 * and carries 15 rows; the column now clamps to min(70vh, 560dp) and scrolls,
 * and the Appearance row (Sun + "Dark") closes the gap to the web's full set.
 */
internal fun MirrorKebabMenu(
    archivedCount: Int,
    selfOpen: Boolean,
    mentionCount: Int,
    channelCount: Int,
    archivedUnread: Int = 0,
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
    onAppearance: () -> Unit,
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
                // web KEBAB_CONTENT_CLS min-w-[240px] shrink-wraps to exactly
                // 240px (content is narrower) - a FIXED 240dp card, never the
                // fillMaxWidth blowout that stretched the menu edge to edge.
                .width(240.dp)
                // web KEBAB_CONTENT_CLS: max-h-[min(70vh,560px)] overflow-y-auto
                .heightIn(max = minOf(LocalConfiguration.current.screenHeightDp * 0.7f, 560f).dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(6.dp)
                .clickable(enabled = false) {},
        ) {
            MirrorKebabLabel("Actions")
            MirrorKebabItem("LSearch", "Search", onClick = { onDismiss(); onSearch() })
            // web icon: PulseCompose = bold(PencilSimple)
            MirrorKebabItem("PPencilSimpleBold", "New chat", onClick = { onDismiss(); onNewChat() })
            MirrorKebabItem("LUsers", "New group", onClick = { onDismiss(); onNewGroup() })
            MirrorKebabItem("LTicket", "Join with code", onClick = { onDismiss(); onJoinCode() })
            Spacer(Modifier.height(1.dp).fillMaxWidth().background(MirrorArt.Hairline))
            MirrorKebabLabel("Browse")
            MirrorKebabItem("LBookUser", "Contacts", onClick = { onDismiss(); onContacts() })
            MirrorKebabItem("LPhone", "Calls", onClick = { onDismiss(); onCalls() })
            // web: Archived trailing shows the unread sum when one exists
            MirrorKebabItem(
                "LArchive",
                "Archived",
                trailing = when {
                    archivedUnread > 0 -> "${if (archivedUnread > 99) 99 else archivedUnread} unread"
                    archivedCount > 0 -> archivedCount.toString()
                    else -> ""
                },
                onClick = { onDismiss(); onArchived() },
            )
            MirrorKebabItem("LNotebookPen", "Note to Self", trailing = if (selfOpen) "Open" else "New", onClick = { onDismiss(); onNoteToSelf() })
            MirrorKebabItem("LAtSign", "Mentions", trailing = if (mentionCount > 0) (if (mentionCount > 99) "99+" else mentionCount.toString()) else "", onClick = { onDismiss(); onMentions() })
            MirrorKebabItem("LRadio", "Channels", trailing = channelCount.toString(), onClick = { onDismiss(); onChannels() })
            MirrorKebabItem("LFolderPlus", "Folders", onClick = { onDismiss(); onFolders() })
            Spacer(Modifier.height(1.dp).fillMaxWidth().background(MirrorArt.Hairline))
            MirrorKebabLabel("System")
            MirrorKebabItem("LBookmark", "Saved", onClick = { onDismiss(); onSaved() })
            MirrorKebabItem("LCircleDashed", "Stories", onClick = { onDismiss(); onStories() })
            MirrorKebabItem("LSettings", "Settings", onClick = { onDismiss(); onSettings() })
            // web: Sun/Moon + KebabTrailing(isDarkTheme ? 'Dark' : 'Light'); the
            // native mirror is dark-locked, so the row renders the web's dark state.
            MirrorKebabItem("LSun", "Appearance", trailing = "Dark", onClick = { onDismiss(); onAppearance() })
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
    // web KEBAB_ITEM_CLS data-[highlighted]:bg-white/[0.07] - the row lights up
    // while pressed, no ripple (the web has no ripples, only bg tints).
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (glyph.startsWith("P")) {
            MirrorPhosphorIcon(glyph, tint = MirrorArt.Dim, modifier = Modifier.size(18.dp))
        } else {
            MirrorLucideIcon(glyph, tint = MirrorArt.Dim, modifier = Modifier.size(18.dp))
        }
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
    playbackEnabled: Boolean = true,
    onDeleted: (String) -> Unit = {},
) {
    val owner = repository.attachmentSessionKey()
    androidx.compose.runtime.key(owner, group.user?.id) {
        MirrorOwnedStoryViewer(group, repository, onDismiss, playbackEnabled, onDeleted)
    }
}

@Composable
private fun MirrorOwnedStoryViewer(group: StoryGroup, repository: PulseRepository, onDismiss: () -> Unit, playbackEnabled: Boolean, onDeleted: (String) -> Unit) {
    val owner = remember { repository.attachmentSessionKey() }
    val account = remember { repository.boundToCurrentAccount() }
    val actionScope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var viewersFor by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var deletedIds by remember { mutableStateOf(emptySet<String>()) }
    val visibleGroup = group.copy(stories = group.stories.filterNot { it.id in deletedIds })
    val canPlay = playbackEnabled && confirmDelete == null && viewersFor == null && !deleting && actionError == null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val machine = remember(group.user?.id) { StoryViewerStateMachine(group.user?.id) }
    val state by machine.state.collectAsStateWithLifecycle()
    val index = state.index
    val stories = state.flat.map { it.story }
    val story = state.current?.story

    LaunchedEffect(machine, visibleGroup) {
        machine.on(StoryViewerInput.GroupsUpdated(listOf(visibleGroup), System.currentTimeMillis()))
    }
    // Expiry continues while a modal pauses playback; privacy deadlines are not playback time.
    LaunchedEffect(machine, lifecycle, visibleGroup) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                machine.on(StoryViewerInput.GroupsUpdated(listOf(visibleGroup), System.currentTimeMillis()))
                delay(1_000)
            }
        }
    }
    LaunchedEffect(machine, lifecycle, canPlay, visibleGroup) {
        machine.on(StoryViewerInput.PlaybackBlocked(true, SystemClock.elapsedRealtime()))
        if (!canPlay) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            machine.on(StoryViewerInput.PlaybackBlocked(false, SystemClock.elapsedRealtime()))
            try {
                while (isActive) {
                    machine.on(StoryViewerInput.Tick(SystemClock.elapsedRealtime()))
                    delay(16)
                }
            } finally {
                machine.on(StoryViewerInput.PlaybackBlocked(true, SystemClock.elapsedRealtime()))
            }
        }
    }
    // A slow/failed view receipt must not freeze playback. Only mark while actually visible.
    LaunchedEffect(machine, state.pendingMark, canPlay, lifecycle) {
        val mark = state.pendingMark ?: return@LaunchedEffect
        if (!canPlay) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (owner == null || repository.attachmentSessionKey() != owner) return@repeatOnLifecycle
            val result = account.markStoryViewed(mark.storyId)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (repository.attachmentSessionKey() != owner) return@repeatOnLifecycle
            result.fold(
                onSuccess = { machine.on(StoryViewerInput.ViewMarkOk(mark.storyId, it)) },
                onFailure = { machine.on(StoryViewerInput.ViewMarkFailed(mark.storyId)) },
            )
        }
    }
    LaunchedEffect(story?.id) {
        if (confirmDelete != story?.id) confirmDelete = null
        if (viewersFor != null && visibleGroup.stories.none { it.id == viewersFor }) viewersFor = null
    }
    LaunchedEffect(state.dismissed) { if (state.dismissed) onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                if (story?.imagePath != null) SolidColor(Color(0xFF17110C))
                else Brush.verticalGradient(MirrorArt.avatarGradient(story?.background ?: "emerald")),
            ),
    ) {
        if (story?.imagePath != null) {
            coil.compose.AsyncImage(
                model = mirrorUploadHttp(story.imagePath!!),
                contentDescription = story.caption.ifBlank { "Story photo" },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // full-screen tap zones first: everything declared later sits on top
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(enabled = canPlay) { machine.on(StoryViewerInput.TapPrev) },
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(enabled = canPlay) { machine.on(StoryViewerInput.TapNext) },
            )
        }
        // progress bars - one segment per story, the ACTIVE one fills with
        // the real elapsed fraction (web rAF parity)
        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (i in stories.indices) {
                Box(Modifier.weight(1f).height(2.5.dp).clip(CircleShape).background(MirrorArt.Text.copy(alpha = 0.25f))) {
                    if (i < index) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(MirrorArt.Text.copy(alpha = 0.9f)),
                        )
                    } else if (i == index) {
                        Box(
                            Modifier
                                .fillMaxWidth(state.progress)
                                .height(2.5.dp)
                                .background(MirrorArt.Text.copy(alpha = 0.9f)),
                        )
                    }
                }
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
            if (group.mine && group.user?.id == account.viewerId && story != null) {
                Text("Viewers", color = MirrorArt.Text, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !deleting) { viewersFor = story.id }.padding(8.dp))
                Text("Delete", color = MirrorArt.Accent, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !deleting) { confirmDelete = story.id }.padding(8.dp))
            }
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
    if (confirmDelete != null) {
        val target = confirmDelete!!
        MirrorSheet(title = "Delete this status?", onDismiss = { if (!deleting) confirmDelete = null }) {
            Text("This removes the status for everyone. It stays here unless deletion is confirmed.", color = MirrorArt.Dim, fontSize = 13.sp)
            Text(if (deleting) "Deleting…" else "Delete status", color = MirrorArt.Accent,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !deleting) {
                    deleting = true; actionError = null
                    actionScope.launch {
                        try {
                            if (owner == null || repository.attachmentSessionKey() != owner) return@launch
                            account.deleteStory(target).getOrThrow()
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (repository.attachmentSessionKey() != owner) return@launch
                            deletedIds = deletedIds + target
                            confirmDelete = null
                            onDeleted(target)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                        } catch (failure: Exception) {
                            if (repository.attachmentSessionKey() == owner) {
                                confirmDelete = null
                                actionError = failure.message ?: "Deletion wasn't confirmed. Your status has not been removed from this viewer."
                            }
                        } finally { if (repository.attachmentSessionKey() == owner) deleting = false }
                    }
                }.padding(vertical = 16.dp))
        }
    }
    if (actionError != null) MirrorSheet(title = "Status action", onDismiss = { actionError = null }) {
        Text(actionError.orEmpty(), color = MirrorArt.Text, fontSize = 13.sp)
    }
    viewersFor?.let { id ->
        androidx.compose.runtime.key(owner, id) {
            MirrorStoryViewersSheet(id, account, repository, owner) { viewersFor = null }
        }
    }

}

/** Owner-only list: empty, unavailable and offline are deliberately distinct. */
@Composable
private fun MirrorStoryViewersSheet(storyId: String, account: PulseRepository, repository: PulseRepository, owner: String?, onDismiss: () -> Unit) {
    var viewers by remember { mutableStateOf<List<app.pulse.domain.model.StoryViewer>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(storyId, owner, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                if (owner == null || repository.attachmentSessionKey() != owner) return@repeatOnLifecycle
                val result = account.storyViewers(storyId)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repository.attachmentSessionKey() != owner) return@repeatOnLifecycle
                loading = false
                result.fold(onSuccess = { viewers = it; error = null }, onFailure = { failure ->
                    val kind = (failure as? app.pulse.domain.model.PulseApiException)?.kind
                    if (kind in setOf("AUTH", "FORBIDDEN", "NOT_FOUND")) viewers = emptyList()
                    error = failure.message ?: "Viewers could not be loaded. Reconnect to retry."
                })
                delay(5_000)
            }
        }
    }
    MirrorSheet(title = "Viewers", onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            if (loading) item { Text("Loading viewers…", color = MirrorArt.Dim) }
            error?.let { message -> item { Text(message, color = MirrorArt.Accent, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp)) } }
            if (!loading && error == null && viewers.isEmpty()) item { Text("No views yet", color = MirrorArt.Dim) }
            items(viewers, key = { it.userId }) { viewer ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(viewer.name, color = MirrorArt.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    viewer.username?.let { Text("@$it", color = MirrorArt.Dim, fontSize = 12.sp) }
                }
            }
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
    val owner = repository.attachmentSessionKey()
    androidx.compose.runtime.key(owner) {
        MirrorOwnedStoryComposer(repository, onDismiss, onPosted)
    }
}

@Composable
private fun MirrorOwnedStoryComposer(repository: PulseRepository, onDismiss: () -> Unit, onPosted: () -> Unit) {
    val actionScope = androidx.compose.runtime.rememberCoroutineScope()
    val owner = remember { repository.attachmentSessionKey() }
    val account = remember { repository.boundToCurrentAccount() }
    var error by remember { mutableStateOf<String?>(null) }
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
                onValueChange = { if (!posting) { caption = app.pulse.feature.stories.ComposerState.boundedCaption(it); error = null } },
                enabled = !posting,
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
                        .clickable(enabled = !posting) { background = key },
                )
            }
        }
        if (error != null) Text(error.orEmpty(), color = MirrorArt.Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (caption.isBlank() || posting) MirrorArt.Chip else MirrorArt.Accent)
                .clickable(enabled = caption.isNotBlank() && !posting) {
                    val submittedCaption = caption
                    val submittedBackground = background
                    posting = true; error = null
                    actionScope.launch {
                        try {
                            if (owner == null || repository.attachmentSessionKey() != owner) return@launch
                            account.createStory(submittedCaption, submittedBackground, null).getOrThrow()
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if (repository.attachmentSessionKey() == owner) onPosted()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
                        } catch (failure: Exception) {
                            if (repository.attachmentSessionKey() == owner) error = failure.message ?: "Couldn't share your status. Your text is kept."
                        } finally { if (repository.attachmentSessionKey() == owner) posting = false }
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

/**
 * R73 - bottom sheet chrome with REAL motion (web sheets spring every where):
 * scrim fade, panel spring slide-up (web spring 380/34, stories-sheet.tsx
 * L280-287), drag-to-dismiss with the web's elastic release, and a spring
 * slide-down exit before composing out. The signature is UNCHANGED so every
 * existing call site inherits the motion.
 */
@Composable
internal fun MirrorSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // enter/exit progress 0..1 driven by spring physics (web AnimatePresence)
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    var leaving by remember { androidx.compose.runtime.mutableStateOf(false) }
    var dragPx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
            ),
        )
    }
    LaunchedEffect(leaving) {
        if (leaving) {
            progress.animateTo(
                0f,
                androidx.compose.animation.core.tween(170, easing = androidx.compose.animation.core.FastOutLinearInEasing),
            )
            onDismiss()
        }
    }
    val scrimAlpha = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (leaving) 0f else 0.4f,
        animationSpec = androidx.compose.animation.core.tween(170),
        label = "sheetScrim",
    )
    val panelTranslation = with(density) { (1f - progress.value) * 220.dp.toPx() + dragPx }
    val panelScale = 0.94f + 0.06f * progress.value
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrimAlpha.value.coerceIn(0f, 0.66f)))
            .clickable(onClick = { leaving = true }),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = panelTranslation
                    scaleX = panelScale
                    scaleY = panelScale
                    alpha = progress.value.coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(enabled = false) {}
                .padding(16.dp)
                .navigationBarsPadding()
                // web drag-to-dismiss: pull down past the threshold releases the
                // sheet, a short drag springs back (stories-sheet.tsx elastic 0.65)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        dragPx = (dragPx + delta).coerceAtLeast(0f)
                    },
                    onDragStopped = {
                        if (dragPx > with(density) { 88.dp.toPx() }) leaving = true else dragPx = 0f
                    },
                ),
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
                        .clickable(onClick = { leaving = true }),
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
internal fun MirrorSheetScroll(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
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

/** New group: real users() picker + createGroup (server needs 2+ others). */
@Composable
internal fun MirrorGroupSheet(
    repository: PulseRepository,
    onOpened: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val actionRepository = remember(repository, repository.viewerId) { repository.boundToCurrentAccount() }
    val actionScope = androidx.compose.runtime.rememberCoroutineScope()
    var errorText by remember { mutableStateOf<String?>(null) }
    var directoryError by remember { mutableStateOf<String?>(null) }
    var directoryLoading by remember { mutableStateOf(false) }
    var directoryRetry by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    val picked = remember { mutableStateOf(setOf<String>()) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(actionRepository, query, directoryRetry) {
        directoryLoading = true
        directoryError = null
        try {
            delay(180)
            app.pulse.domain.sync.confirmedAction({ actionRepository.users(query.trim()) }) { loaded ->
                users = loaded.filter { it.id != actionRepository.viewerId }
            }.getOrThrow()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: Exception) { directoryError = "People couldn't be loaded. Tap to retry." } finally { directoryLoading = false }
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
                readOnly = creating,
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
        if (directoryLoading) Text("Loading people…", color = MirrorArt.TextSoft, fontSize = 12.sp)
        directoryError?.let { Text(it, color = MirrorArt.Accent, fontSize = 12.sp,
            modifier = Modifier.clickable { directoryRetry++ }.padding(8.dp)) }
        errorText?.let { Text(it, color = MirrorArt.Accent, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
        MirrorSheetScroll {
            for (user in users) {
                val checked = user.id in picked.value
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = !creating) {
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
                    errorText = null
                    val submittedName = name.trim()
                    val submittedMembers = picked.value.toList()
                    actionScope.launch {
                        try {
                            app.pulse.domain.sync.confirmedAction({ actionRepository.createGroup(submittedName, submittedMembers) }) {
                                onOpened(it.id)
                            }.getOrThrow()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: Exception) {
                            errorText = failure.message ?: "Couldn't confirm creation. Retry the same name and people to recover the same group."
                        } finally { creating = false }
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
    val actionScope = androidx.compose.runtime.rememberCoroutineScope()
    val actionRepository = remember(repository) { repository.boundToCurrentAccount() }
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
                enabled = !busy,
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
                    val clean = app.pulse.core.link.PulseDeepLink.inviteCode(code)
                    if (clean == null) {
                        error = "Paste a valid invite code or invite link"
                    } else {
                        val target = actionRepository
                        val owner = target.viewerId
                        busy = true
                        error = ""
                        actionScope.launch {
                            try {
                                if (owner.isNullOrBlank() || target.viewerId != owner) return@launch
                                app.pulse.domain.sync.confirmedAction({ target.joinInvite(clean) }) { outcome ->
                                    if (target.viewerId == owner) onOpened(outcome.conversationId)
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "Couldn't join. Check the connection and try again."
                            } finally { busy = false }
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

// R92 - MirrorArchivedSheet and MirrorMentionsSheet DELETED: the web opens
// Archived (#/chats/archived, chats-tab.tsx:2083) and Mentions (#/mentions,
// chats-tab.tsx:2113) as full zinc-900 sub-pages, never bottom sheets. The
// replacements live in MirrorSubPages.kt (MirrorArchivedPage/MirrorMentionsPage).

// R92 - MirrorMentionsSheet deleted with the archived sheet; the mentions
// feed lives in MirrorSubPages.kt MirrorMentionsPage now.

/** Folders: pick a live folder filter (tap again to clear). */
@Composable
internal fun MirrorFoldersSheet(
    repository: PulseRepository,
    conversations: List<Conversation>,
    activeFolderId: String?,
    onPick: (String?) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.runtime.key(repository, repository.viewerId) {
        MirrorOwnedFoldersSheet(repository, conversations, activeFolderId, onPick, onChanged, onDismiss)
    }
}

@Composable
private fun MirrorOwnedFoldersSheet(
    repository: PulseRepository,
    conversations: List<Conversation>,
    activeFolderId: String?,
    onPick: (String?) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    // R72 - the web FoldersSheet parity (folders-sheet.tsx): create with the
    // 8-icon registry, rename, delete (2-tap, chats stay), membership editor
    // (FULL ordered PUT replace) - every action on the real /api/folders wire.
    var folders by remember { mutableStateOf<List<FolderSummary>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var createOpen by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newIcon by remember { mutableStateOf("folder") }
    var notice by remember { mutableStateOf("") }
    var createBusy by remember { mutableStateOf(false) }
    val actionScope = androidx.compose.runtime.rememberCoroutineScope()
    val owner = repository.viewerId
    var readRevision by remember { mutableStateOf(0) }
    fun mutate(action: suspend () -> Unit, confirmed: () -> Unit) {
        if (createBusy || owner.isNullOrBlank() || repository.viewerId != owner) return
        createBusy = true; readRevision++
        actionScope.launch {
            try {
                if (repository.viewerId != owner) return@launch
                action()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (repository.viewerId != owner) return@launch
                confirmed(); reloadKey++; onChanged()
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) { if (repository.viewerId == owner) notice = error.message ?: "Could not save the folder" }
            finally { if (repository.viewerId == owner) createBusy = false }
        }
    }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var editingFolder by remember { mutableStateOf<FolderSummary?>(null) }

    LaunchedEffect(repository, owner, reloadKey) {
        val ticket = readRevision
        loadFailed = false
        try {
            val page = repository.folders().getOrThrow()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (repository.viewerId == owner && ticket == readRevision) folders = page
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (error: Exception) {
            if (repository.viewerId == owner && ticket == readRevision) {
                loadFailed = true
                notice = "Could not refresh folders - tap to retry"
            }
        }
    }

    MirrorSheet(title = "Folders", onDismiss = onDismiss) {
        MirrorSheetScroll {
            if (editingFolder != null) {
                // membership editor - checkbox list of every conversation,
                // Save runs the FULL replace PUT the web uses
                val folder = editingFolder!!
                var selected by remember(folder.id) { mutableStateOf(folder.conversationIds.toSet()) }
                Text(
                    "Chats in \u201C${folder.name}\u201D",
                    color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
                for (convo in conversations.filter { !it.isSelf }) {
                    val checked = convo.id in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (checked) MirrorArt.ChipActive else Color.Transparent)
                            .clickable(enabled = !createBusy) {
                                selected = if (checked) selected - convo.id else selected + convo.id
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MirrorLucideIcon(if (checked) "LCheck" else "LFolder", tint = if (checked) MirrorArt.Accent2 else MirrorArt.Faint, modifier = Modifier.size(16.dp))
                        Text(convo.title, color = MirrorArt.Text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "Cancel",
                        color = MirrorArt.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MirrorArt.White7)
                            .clickable(enabled = !createBusy) { editingFolder = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Text(
                        "Save chats",
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MirrorArt.Accent)
                            .clickable(enabled = !createBusy) {
                                val payload = selected.toList()
                                mutate({ repository.setFolderConversations(folder.id, payload).getOrThrow() }) {
                                    folders = folders?.map { if (it.id == folder.id) it.copy(conversationIds = payload) else it }
                                    editingFolder = null; notice = "Folder chats saved"
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            } else {
            if (folders == null) {
                if (loadFailed) {
                    Text(
                        "Could not load folders - tap to retry",
                        color = MirrorArt.Red, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !createBusy) { reloadKey++ }.padding(vertical = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text("Loading...", color = MirrorArt.Faint, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center)
                }
            }
            val loaded = folders
            if (loaded != null && loaded.isEmpty()) {
                Text("No folders yet - create your first one below", color = MirrorArt.Faint, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
            for (folder in loaded ?: emptyList()) {
                val active = folder.id == activeFolderId
                if (renamingId == folder.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicTextField(
                            value = renameDraft,
                            onValueChange = { if (!createBusy && it.length <= 24) renameDraft = it },
                            singleLine = true,
                            textStyle = TextStyle(color = MirrorArt.Text, fontSize = 13.sp),
                            cursorBrush = SolidColor(MirrorArt.Accent2),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MirrorArt.White7)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                        Text(
                            "Save",
                            color = MirrorArt.Accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = renameDraft.isNotBlank() && !createBusy) {
                                    val name = renameDraft.trim()
                                    mutate({ repository.updateFolder(folder.id, name, null, null).getOrThrow() }) {
                                        folders = folders?.map { if (it.id == folder.id) it.copy(name = name) else it }
                                        renamingId = null; notice = "Folder renamed"
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) MirrorArt.ChipActive else Color.Transparent)
                            .clickable(enabled = !createBusy) { onPick(if (active) null else folder.id) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MirrorLucideIcon(folderGlyphName(folder.emoji) ?: "LFolder", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                        Text(folder.name, color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(folder.conversationIds.size.toString(), color = MirrorArt.Faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        // web row kebab: rename / chats / delete
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(enabled = !createBusy) { renamingId = folder.id; renameDraft = folder.name },
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon("LPenLine", tint = MirrorArt.Faint, modifier = Modifier.size(14.dp))
                        }
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable(enabled = !createBusy) { editingFolder = folder },
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon("LUsers", tint = MirrorArt.Faint, modifier = Modifier.size(14.dp))
                        }
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (confirmDeleteId == folder.id) MirrorArt.Red else Color.Transparent)
                                .clickable(enabled = !createBusy) {
                                    if (confirmDeleteId == folder.id) {
                                        confirmDeleteId = null
                                        mutate({ repository.deleteFolder(folder.id).getOrThrow() }) {
                                            folders = folders?.filterNot { it.id == folder.id }
                                            notice = "Folder deleted - chats stay in your list"
                                        }
                                    } else {
                                        confirmDeleteId = folder.id
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon("LTrash2", tint = if (confirmDeleteId == folder.id) Color.White else MirrorArt.Red, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            if (!createOpen) {
                Text(
                    "+ New folder",
                    color = MirrorArt.Accent2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirrorArt.White7)
                        .clickable(enabled = !createBusy) { createOpen = true }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MirrorArt.White7)
                        .padding(8.dp),
                ) {
                    BasicTextField(
                        value = newName,
                        onValueChange = { if (!createBusy && it.length <= 24) newName = it },
                        singleLine = true,
                        textStyle = TextStyle(color = MirrorArt.Text, fontSize = 13.sp),
                        cursorBrush = SolidColor(MirrorArt.Accent2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MirrorArt.White10)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        decorationBox = { inner ->
                            if (newName.isBlank()) {
                                Text("Folder name (1-24 characters)", color = MirrorArt.Faint, fontSize = 13.sp)
                            }
                            inner()
                        },
                    )
                    Text(
                        "ICON",
                        color = MirrorArt.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 4.dp),
                    )
                    // web FOLDER_ICON_IDS registry - the 8 designed icons
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (icon in listOf("folder", "briefcase", "game", "heart", "flame", "target", "music", "brain")) {
                            val active = icon == newIcon
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(if (active) MirrorArt.Accent else MirrorArt.White10)
                                    .clickable(enabled = !createBusy) { newIcon = icon },
                                contentAlignment = Alignment.Center,
                            ) {
                                MirrorLucideIcon(folderGlyphName(icon) ?: "LFolder", tint = if (active) Color.White else MirrorArt.TextSoft, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        Text(
                            "Cancel",
                            color = MirrorArt.Dim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !createBusy) { createOpen = false; newName = "" }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                        Text(
                            if (createBusy) "Creating..." else "Create folder",
                            color = if (newName.isNotBlank()) Color.White else MirrorArt.Faint,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (newName.isNotBlank() && !createBusy) MirrorArt.Accent else MirrorArt.White10)
                                .clickable(enabled = newName.isNotBlank() && !createBusy) {
                                    val name = newName.trim(); val icon = newIcon
                                    var created: FolderSummary? = null
                                    mutate({ created = repository.createFolder(name, icon).getOrThrow() }) {
                                        folders = (folders ?: emptyList()) + requireNotNull(created)
                                        notice = "Folder created"; newName = ""; newIcon = "folder"; createOpen = false
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (notice.isNotEmpty()) {
                Text(notice, color = MirrorArt.Dim, fontSize = 12.sp, modifier = Modifier.clickable(enabled = loadFailed && !createBusy) { reloadKey++ }.padding(8.dp))
            }
            }
        }
    }
}

/** Folder icon id -> native lucide glyph (web FOLDER_ICON_GLYPHS mapping). */
internal fun folderGlyphName(value: String?): String? = when (value) {
    "folder" -> "LFolder"
    "briefcase" -> "LBriefcase"
    "game" -> "LGamepad2"
    "heart" -> "LHeart"
    "flame" -> "LFlame"
    "target" -> "LTarget"
    "music" -> "LMusic"
    "brain" -> "LBrain"
    else -> null
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
    val exportContext = androidx.compose.ui.platform.LocalContext.current
    val actionRepository = repository.boundToCurrentAccount()
    val actionScope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember(conversationId, actionRepository) { mutableStateOf(false) }
    fun act(close: Boolean = true, work: suspend () -> Result<*>) {
        if (busy) return
        busy = true
        note = ""
        actionScope.launch {
            try {
                app.pulse.domain.sync.confirmedAction(request = work) { value ->
                    if (close) { onChanged(); onDismiss() } else note = "Saved as $value"
                }.getOrThrow()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                note = failure.message ?: "Couldn't confirm this change. Try again."
            } finally { busy = false }
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
                                            // R72 fix - the /mute route ONLY accepts the
                                            // preset strings ("8h"|"1w"|"always"|null); the
                                            // old ISO timestamps were a silent 400 and the
                                            // mute never landed (same bug R70 fixed in the
                                            // room kebab strip).
                                            actionRepository.setMutedUntil(
                                                conversationId,
                                                when (choice) {
                                                    "8h" -> "8h"
                                                    "1w" -> "1w"
                                                    else -> "always"
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
                                        actionRepository.clearMyMessages(conversationId)
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
                        act { actionRepository.togglePin(conversationId, !pinned) }
                    }
                    MirrorOptionRow("LArchive", if (archived) "Unarchive chat" else "Archive chat") {
                        act { actionRepository.archive(conversationId, !archived) }
                    }
                    MirrorOptionRow(if (manualUnread) "LMailOpen" else "LMail", if (manualUnread) "Mark as read" else "Mark as unread") {
                        act { actionRepository.markUnread(conversationId, !manualUnread) }
                    }
                    MirrorOptionRow("LBellOff", "Mute notifications") { muteStrip = true }
                    // Export runs WITHOUT closing the sheet - the saved file
                    // name lands in the note row (web downloadTranscript feel).
                    MirrorOptionRow("LDownload", "Export chat (.txt)") {
                        val owner = actionRepository.viewerId
                        val epoch = actionRepository.attachmentSessionKey()
                        act(close = false) {
                            val fileName = actionRepository.exportChat(conversationId).getOrThrow()
                            if (!kotlin.coroutines.coroutineContext.isActive || actionRepository.viewerId != owner ||
                                actionRepository.attachmentSessionKey() != epoch) throw kotlinx.coroutines.CancellationException("Export owner changed")
                            val file = java.io.File(java.io.File(exportContext.cacheDir, "exports"), fileName)
                            exportContext.startActivity(app.pulse.feature.chat.MediaSupport.buildShareIntent(exportContext, file.absolutePath, "text/plain", null))
                            Result.success(fileName)
                        }
                    }
                    MirrorOptionRow("LEraser", "Clear chat…") { confirmClear = true }
                }
            }
            if (busy) Text("Updating…", color = MirrorArt.TextSoft, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            if (note.isNotEmpty()) {
                Text(note, color = MirrorArt.Accent2, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
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

