package app.pulse.android.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import app.pulse.android.SessionViewModel
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.StoryGroup
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Mirror destinations - the artboard dock set. */
internal enum class MirrorTab { Chats, Calls, Updates, Profile }

/** Row view model the home renders (data shaped from the domain Conversation). */
internal data class ConversationRow(
    val id: String,
    val title: String,
    val color: String?,
    val isGroup: Boolean,
    val preview: String,
    val time: String,
    val unread: Int,
    val online: Boolean,
    val pinned: Boolean,
    val muted: Boolean,
    val streak: Int,
)

internal data class StoryDisc(
    val label: String,
    val name: String,
    val color: String,
    val seen: Boolean,
)

/**
 * R59 native mirror root - the ARTBOARD rendered natively (Compose), driven by
 * the SAME repository flows the native shell uses (real gateway, real data,
 * zero mock). Geometry, palette and glyphs are copied 1:1 from the web
 * artboard surfaces so this build stands beside the web shell for the audit.
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

    // Refresh rhythm: conversations + stories on mount and every 5s while open.
    LaunchedEffect(viewerId) {
        while (true) {
            if (viewerId != null) {
                runCatching { repository.refreshConversations() }
                runCatching { stories = repository.stories().getOrDefault(emptyList()) }
            }
            delay(5_000)
        }
    }

    // Horizon scene: the artboard wash behind every surface (no WebGL needed).
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D0906), Color(0xFF0D0906)))),
    ) {
        // ember horizon glows, painted as the art-scene does
        Box(
            Modifier
                .fillMaxSize()
                .background(MirrorSceneBrush()),
        )

        when {
            openRoom != null -> {
                val convo = openRoom!!
                MirrorRoomScaffold(convo, repository, viewerId.orEmpty(), onClose = { openRoom = null })
            }
            else -> {
                when (tab) {
                    MirrorTab.Chats -> {
                        MirrorHome(
                            conversations = conversations.map { it.toRow(presence, viewerId) },
                            stories = stories.map { g ->
                                StoryDisc(
                                    label = g.user?.name ?: "You",
                                    name = g.user?.name ?: viewerName.orEmpty(),
                                    color = g.user?.color ?: "emerald",
                                    seen = g.allSeen,
                                )
                            },
                            viewerName = viewerName.orEmpty(),
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
                    MirrorTab.Calls -> MirrorPlaceholder("Calls")
                    MirrorTab.Updates -> MirrorPlaceholder("Updates")
                }
                MirrorDock(
                    activeTab = tab,
                    unread = conversations.sumOf { it.unreadCount },
                    onTab = { tab = it },
                    onFab = { },
                )
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
    onClose: () -> Unit,
) {
    val messages by repository.observeMessages(convo.id).collectAsState(initial = emptyList<Message>())
    var phrases by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(convo.id) {
        runCatching { repository.refreshMessages(convo.id) }
        phrases = repository.quickPhrases().map { it.second }
    }

    MirrorRoom(
        title = convo.title,
        color = convo.accentColor ?: convo.members.firstOrNull { it.id != viewerId }?.color,
        isGroup = convo.isGroupish,
        groupId = convo.id,
        subtitle = convo.lastMessagePreview ?: "",
        messages = messages,
        viewerId = viewerId,
        phrases = phrases,
        onBack = onClose,
        onSend = { text ->
            if (viewerId.isNotBlank() && text.isNotBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { repository.sendMessage(convo.id, text) }
                }
            }
        },
    )
}

/** art-scene: warm horizon glows from below + ember crest, on the carbon base. */
@Composable
private fun MirrorSceneBrush(): Brush = Brush.verticalGradient(
    listOf(
        Color(0xFF2B1C10),
        Color(0xFF150D07),
        Color(0xFF0D0906),
    ),
)

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
        else -> prefix + basePreview
    }
    return ConversationRow(
        id = id,
        title = title,
        color = accentColor ?: other?.color,
        isGroup = isGroupish,
        preview = preview,
        time = MirrorRowTime(lastActivityAt),
        unread = unreadCount,
        online = otherUserId != null && presence.contains(otherUserId),
        pinned = isPinned,
        muted = isMuted,
        streak = streakCount,
    )
}

/** Honest audit placeholder for dock tabs outside this round's reference set. */
@Composable
private fun MirrorPlaceholder(title: String) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(bottom = 110.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(80.dp))
        Text(title, color = MirrorArt.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Native mirror audit - this tab mirrors the web next round",
            color = MirrorArt.Dim,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MirrorArt.Panel)
                .clickable { }
                .padding(16.dp),
        )
    }
}
