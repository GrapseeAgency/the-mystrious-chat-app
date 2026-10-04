package app.pulse.android.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import app.pulse.android.SessionViewModel
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.QuickPhrase
import app.pulse.domain.model.StoryGroup
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Mirror destinations - the artboard dock set (Chats / Calls / Updates / Profile). */
internal enum class MirrorTab { Chats, Calls, Updates, Profile }

/**
 * R62 - native mirror root: the ARTBOARD rendered natively in Compose with
 * the web's EXACT tokens and full interactivity - real filters, real search,
 * real story cards/viewer/composer, real Calls log, real channel directory,
 * real new-chat DMs, real rooms. Data rides the same repository flows the
 * rest of the app uses (live gateway, zero mock). The scene replicates the
 * web .art-scene: warm linear wash + two elliptical horizon glows at 63/70%
 * height, painted with canvas scale transforms so the falloff is elliptical
 * exactly like the CSS radial-gradient(135% 44% at 50% 63%).
 */
@Composable
fun MirrorRoot(
    session: SessionViewModel,
    repository: PulseRepository,
) {
    val viewerId by session.viewerId.collectAsState()
    val viewerName by session.viewerName.collectAsState()
    val conversations by repository.observeConversations().collectAsState(initial = emptyList())
    val presence by repository.observePresence().collectAsState(initial = emptySet())
    var tab by remember { mutableStateOf(MirrorTab.Chats) }
    var openRoom by remember { mutableStateOf<Conversation?>(null) }
    var stories by remember { mutableStateOf<List<StoryGroup>>(emptyList()) }
    var folders by remember { mutableStateOf<List<MirrorFolderChip>>(emptyList()) }

    var filter by remember { mutableStateOf(MirrorFilter.All) }
    var activeFolderId by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var kebabOpen by remember { mutableStateOf(false) }
    var newChatOpen by remember { mutableStateOf(false) }
    var composerOpen by remember { mutableStateOf(false) }
    var viewingStory by remember { mutableStateOf<StoryGroup?>(null) }

    // Refresh rhythm: conversations + stories + folders on mount and every 5s.
    LaunchedEffect(viewerId) {
        while (true) {
            if (viewerId != null) {
                runCatching { repository.refreshConversations() }
                stories = repository.stories().getOrDefault(emptyList())
                folders = repository.folders().getOrDefault(emptyList()).map { f ->
                    MirrorFolderChip(
                        id = f.id,
                        name = f.name,
                        emoji = f.emoji,
                        count = f.conversationIds.count { cid -> conversations.any { it.id == cid } },
                        conversationIds = f.conversationIds,
                    )
                }
            }
            delay(5_000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MirrorArt.Bg)
            .drawBehind { MirrorScene(this) },
    ) {
        when {
            viewingStory != null -> {
                viewingStory?.let { group ->
                    MirrorStoryViewer(group = group, repository = repository, onDismiss = { viewingStory = null })
                }
            }
            openRoom != null -> {
                val convo = openRoom!!
                MirrorRoomScaffold(
                    convo = convo,
                    repository = repository,
                    viewerId = viewerId.orEmpty(),
                    presence = presence,
                    onClose = { openRoom = null },
                )
            }
            else -> {
                val myGroup = stories.firstOrNull { it.mine }
                val otherGroups = stories.filter { !it.mine }
                val cards = buildList {
                    add(
                        MirrorStoryCard(
                            label = "You",
                            name = viewerName.orEmpty(),
                            color = "emerald",
                            isYou = true,
                            unseen = myGroup != null,
                            badge = 0,
                            hasPhoto = myGroup != null,
                            background = myGroup?.stories?.firstOrNull()?.background ?: "emerald",
                        ),
                    )
                    for (g in otherGroups) {
                        add(
                            MirrorStoryCard(
                                label = g.user?.name ?: "?",
                                name = g.user?.name ?: "?",
                                color = g.user?.color ?: "emerald",
                                isYou = false,
                                unseen = !g.allSeen,
                                badge = if (g.allSeen) 0 else g.stories.size,
                                hasPhoto = g.stories.any { it.imagePath != null },
                                background = g.stories.firstOrNull()?.background ?: g.user?.color ?: "emerald",
                            ),
                        )
                    }
                }

                when (tab) {
                    MirrorTab.Chats -> {
                        MirrorHome(
                            conversations = conversations.map { it.toRow(presence, viewerId) },
                            stories = cards,
                            folders = folders,
                            viewerName = viewerName.orEmpty(),
                            searching = searching,
                            searchQuery = searchQuery,
                            onSearchQuery = { searchQuery = it },
                            onCloseSearch = {
                                searching = false
                                searchQuery = ""
                            },
                            onOpenSearch = { searching = true },
                            onCamera = { composerOpen = true },
                            onKebab = { kebabOpen = true },
                            filter = filter,
                            onFilter = { filter = it },
                            activeFolderId = activeFolderId,
                            onFolder = { activeFolderId = it },
                            onStory = { card ->
                                val group = if (card.isYou) myGroup else otherGroups.firstOrNull { it.user?.name == card.label }
                                if (group != null) {
                                    viewingStory = group
                                } else if (card.isYou) {
                                    composerOpen = true
                                }
                            },
                            onOpenConversation = { row ->
                                conversations.firstOrNull { it.id == row.id }?.let { openRoom = it }
                            },
                        )
                    }
                    MirrorTab.Profile -> {
                        MirrorProfile(
                            viewerId = viewerId.orEmpty(),
                            repository = repository,
                            iAmOnline = viewerId != null && presence.contains(viewerId),
                            onSignOut = { session.forgetViewer() },
                            onCopyId = { },
                        )
                    }
                    MirrorTab.Calls -> MirrorCalls(repository)
                    MirrorTab.Updates -> MirrorUpdates(repository)
                }

                MirrorDock(
                    activeTab = tab,
                    unread = conversations.sumOf { it.unreadCount },
                    onTab = { tab = it },
                    onFab = { newChatOpen = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                if (kebabOpen) {
                    MirrorKebabMenu(
                        onSearch = {
                            tab = MirrorTab.Chats
                            searching = true
                        },
                        onStories = { composerOpen = true },
                        onProfile = { tab = MirrorTab.Profile },
                        onDismiss = { kebabOpen = false },
                    )
                }
                if (newChatOpen) {
                    MirrorNewChatSheet(
                        repository = repository,
                        onDismiss = { newChatOpen = false },
                        onOpened = { convoId ->
                            newChatOpen = false
                            conversations.firstOrNull { it.id == convoId }?.let { openRoom = it }
                        },
                    )
                }
                if (composerOpen) {
                    MirrorStoryComposer(
                        repository = repository,
                        onDismiss = { composerOpen = false },
                        onPosted = {
                            composerOpen = false
                            CoroutineScope(Dispatchers.IO).launch {
                                stories = repository.stories().getOrDefault(emptyList())
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Room scaffold: title/avatar live from the conversation, messages from the cache. */
@Composable
private fun MirrorRoomScaffold(
    convo: Conversation,
    repository: PulseRepository,
    viewerId: String,
    presence: Set<String>,
    onClose: () -> Unit,
) {
    val messages by repository.observeMessages(convo.id).collectAsState(initial = emptyList<Message>())
    var phrases by remember { mutableStateOf<List<QuickPhrase>>(emptyList()) }

    LaunchedEffect(convo.id) {
        runCatching { repository.refreshMessages(convo.id) }
        phrases = repository.phrases().getOrDefault(emptyList())
        // entering the room marks it read (web POST /read on open)
        runCatching { repository.markRead(convo.id) }
    }

    val other = convo.members.firstOrNull { it.id != viewerId }
    // R54-c subtitle truth: groups roll the member names, DMs the presence line
    val subtitle = when {
        convo.isGroupish -> convo.memberNames.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Group" }
        other != null && presence.contains(other.id) -> "online"
        other != null -> "offline"
        else -> ""
    }

    MirrorRoom(
        title = convo.title,
        color = convo.accentColor ?: other?.color,
        isGroup = convo.isGroupish,
        groupId = convo.id,
        subtitle = subtitle,
        messages = messages,
        viewerId = viewerId,
        viewerName = convo.members.firstOrNull { it.id == viewerId }?.name.orEmpty(),
        memberNames = convo.members.map { it.name },
        members = convo.members,
        phrases = phrases,
        onBack = onClose,
        onSend = { text ->
            if (viewerId.isNotBlank() && text.isNotBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { repository.sendMessage(convo.id, text) }
                }
            }
        },
        onSendImage = { dataUrl ->
            if (viewerId.isNotBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    // web flow: compress → POST /api/uploads → send the imagePath row
                    runCatching {
                        val path = repository.uploadMedia(dataUrl).getOrThrow()
                        repository.sendMediaMessage(convo.id, "", imagePath = path)
                    }
                }
            }
        },
        onToggleReaction = { messageId, emoji ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.react(messageId, emoji) }
            }
        },
        onAddPhrase = { text ->
            CoroutineScope(Dispatchers.IO).launch {
                repository.addPhrase(text).onSuccess { phrases = repository.phrases().getOrDefault(emptyList()) }
            }
        },
        onDeletePhrase = { phraseId ->
            CoroutineScope(Dispatchers.IO).launch {
                repository.deletePhrase(phraseId).onSuccess { phrases = repository.phrases().getOrDefault(emptyList()) }
            }
        },
    )
}

/** art-scene: linear wash + two elliptical horizon glows, CSS-exact. */
private fun MirrorScene(scope: DrawScope) {
    // linear-gradient(180deg, #2b1c10 0%, #241609 30%, #170e07 58%, #0d0906 92%)
    scope.drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF2B1C10),
            0.30f to Color(0xFF241609),
            0.58f to Color(0xFF170E07),
            0.92f to Color(0xFF0D0906),
            1f to Color(0xFF0D0906),
        ),
    )
    // radial-gradient(135% 44% at 50% 63%, glow1 0%, transparent 62%)
    scope.ellipseGlow(
        core = Color(0x70BE6826),
        cx = scope.size.width / 2f,
        cy = scope.size.height * 0.63f,
        rx = scope.size.width * 1.35f,
        ry = scope.size.height * 0.44f,
    )
    // radial-gradient(170% 64% at 50% 70%, glow2 0%, transparent 72%)
    scope.ellipseGlow(
        core = Color(0x458A481C),
        cx = scope.size.width / 2f,
        cy = scope.size.height * 0.70f,
        rx = scope.size.width * 1.70f,
        ry = scope.size.height * 0.64f,
    )
}

/** One elliptical radial glow: scale the canvas, draw a unit radial circle. */
private fun DrawScope.ellipseGlow(core: Color, cx: Float, cy: Float, rx: Float, ry: Float) {
    if (rx <= 0f || ry <= 0f) return
    withTransform({ scale(scaleX = rx, scaleY = ry, pivot = Offset(cx, cy)) }) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(core, core.copy(alpha = 0f)),
                center = Offset.Zero,
                radius = 1f,
            ),
            radius = 1f,
            center = Offset.Zero,
        )
    }
}

private fun Conversation.toRow(presence: Set<String>, viewerId: String?): ConversationRow {
    val other = members.firstOrNull { it.id != viewerId }
    val prefix = when {
        lastMessageMine -> "You: "
        isGroupish && lastMessageAuthorName != null -> "$lastMessageAuthorName: "
        else -> ""
    }
    val basePreview = lastMessagePreview ?: "No messages yet"
    val preview = when {
        lastMessageDeleted -> "Message deleted"
        lastMessageIsImage -> "Photo"
        lastMessageIsAudio -> "Voice message"
        lastMessageIsFile -> lastMessageFileName ?: "File"
        else -> basePreview
    }
    return ConversationRow(
        id = id,
        title = title,
        color = accentColor ?: other?.color,
        isGroup = isGroupish,
        preview = preview,
        previewPrefix = prefix,
        previewDeleted = lastMessageDeleted,
        time = MirrorRowTime(lastActivityAt),
        unread = unreadCount,
        online = otherUserId != null && presence.contains(otherUserId),
        pinned = isPinned,
        muted = isMuted,
        streak = streakCount,
    )
}
