package app.pulse.feature.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import app.pulse.ui.shimmer
import app.pulse.ui.update.UpdaterBanner
import app.pulse.core.time.PulseTime
import app.pulse.domain.model.Conversation
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * Chats tab — the native rebuild of the web inbox. Same outcome (glass-era
 * emerald list with presence, typing, drafts, streaks, unread badges),
 * Compose-native mechanics: PullToRefreshBox, SwipeToDismissBox,
 * ModalBottomSheet long-press actions, haptics, animateItem springs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    viewerId: String?,
    onOpenRoom: (String) -> Unit,
    onNeedIdentity: () -> Unit,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val typing by viewModel.typing.collectAsStateWithLifecycle()

    var search by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<Conversation?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewerId) { if (viewerId != null) viewModel.refresh() }

    val visible = if (query.isBlank()) chats else chats.filter { it.title.contains(query, true) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (search) {
                    IconButton(onClick = { search = false; query = "" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search chats") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Column(Modifier.weight(1f)) {
                        Text("Pulse", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (viewerId == null) "Choose an identity to come alive"
                            else "${presence.size} online",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (viewerId == null) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { search = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                }
            }

            // LiveUpdate pill — renders nothing until the CDN serves a newer versionCode.
            UpdaterBanner(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp))

            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    viewerId == null -> EmptyState(
                        title = "No identity yet",
                        body = "Pick who you are on this device to open your live inbox.",
                        actionLabel = "Choose identity",
                        onAction = onNeedIdentity,
                    )
                    state.error != null && chats.isEmpty() -> EmptyState(
                        title = "Could not reach the gateway",
                        body = state.error ?: "Network error",
                        actionLabel = "Retry",
                        onAction = { viewModel.refresh() },
                    )
                    state.loading && chats.isEmpty() -> SkeletonList()
                    visible.isEmpty() -> EmptyState(
                        title = "No chats here",
                        body = "Start a conversation from the Contacts tab.",
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(visible, key = { it.id }) { conversation ->
                            val isTyping = typing[conversation.id] != null
                            SwipeRow(
                                conversation = conversation,
                                onPin = {
                                    viewModel.togglePin(conversation.id, !conversation.isPinned)
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                onArchive = {
                                    viewModel.archive(conversation.id, true)
                                    scope.launch { snackbar.showSnackbar("Archived · \"${conversation.title}\"") }
                                },
                                onOpen = { onOpenRoom(conversation.id) },
                                onLongPress = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    actionTarget = conversation
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    actionTarget?.let { target ->
        val inTyping = typing[target.id] != null
        ConversationActionSheet(
            conversation = target,
            isTyping = inTyping,
            onDismiss = { actionTarget = null },
            onPin = {
                viewModel.togglePin(target.id, !target.isPinned)
                actionTarget = null
            },
            onMute = {
                viewModel.toggleMute(target.id, !target.isMuted)
                actionTarget = null
            },
            onArchive = {
                viewModel.archive(target.id, true)
                actionTarget = null
            },
            onMarkRead = {
                viewModel.markRead(target.id)
                actionTarget = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationActionSheet(
    conversation: Conversation,
    isTyping: Boolean,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
    onMarkRead: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseAvatar(conversation.title, conversation.accentColor, size = 40.dp, isGroup = conversation.isGroupish)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(conversation.title, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        isTyping -> "typing…"
                        conversation.myDraft != null -> "Draft: ${conversation.myDraft}"
                        else -> "${conversation.memberNames.size} members"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SheetAction(Icons.Filled.PushPin, if (conversation.isPinned) "Unpin" else "Pin to top", onPin)
        SheetAction(Icons.Filled.NotificationsOff, if (conversation.isMuted) "Unmute" else "Mute", onMute)
        SheetAction(Icons.Filled.Archive, "Archive", onArchive)
        SheetAction(Icons.Filled.Verified, "Mark as read", onMarkRead)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeRow(
    conversation: Conversation,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onPin(); false }
                SwipeToDismissBoxValue.EndToStart -> { onArchive(); true }
                else -> false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val (tint, alignment, icon) = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(
                    PulsePalette.Emerald.copy(alpha = 0.16f),
                    Alignment.CenterStart,
                    Icons.Filled.PushPin,
                )
                else -> Triple(
                    PulsePalette.Rose.copy(alpha = 0.16f),
                    Alignment.CenterEnd,
                    Icons.Filled.Archive,
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(tint)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                Icon(icon, contentDescription = null, tint = PulsePalette.EmeraldDeep)
            }
        },
        content = {
            ConversationRow(
                conversation = conversation,
                onClick = onOpen,
                onLongPress = onLongPress,
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseAvatar(
                name = conversation.title,
                colorHex = conversation.accentColor,
                size = 52.dp,
                isGroup = conversation.isGroupish,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.isPinned) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                    }
                    if (conversation.isMuted) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.NotificationsOff, contentDescription = "Muted", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    conversation.streakCount.takeIf { it > 0 }?.let { streak ->
                        Icon(Icons.Filled.Bolt, contentDescription = null, tint = PulsePalette.Amber, modifier = Modifier.size(13.dp))
                        Text(
                            "$streak ",
                            style = MaterialTheme.typography.bodySmall,
                            color = PulsePalette.Amber,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        previewLine(conversation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    PulseTime.shortLabel(conversation.lastActivityAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conversation.unreadCount > 0) {
                    val scale by animateFloatAsState(1f, animationSpec = PulseMotion.bouncy(), label = "badge")
                    Box(
                        Modifier
                            .size(22.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${conversation.unreadCount.coerceAtMost(99)}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private fun previewLine(conversation: Conversation): String = when {
    conversation.myDraft != null -> "Draft: ${conversation.myDraft}"
    conversation.lastMessagePreview == null -> "Say hello"
    conversation.lastMessageAuthorName != null && conversation.isGroupish ->
        "${conversation.lastMessageAuthorName}: ${conversation.lastMessagePreview}"
    else -> conversation.lastMessagePreview ?: ""
}

@Composable
private fun SkeletonList() {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(7) {
            Surface(shape = RoundedCornerShape(20.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(CircleShape).shimmer())
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.width(140.dp).height(14.dp).clip(RoundedCornerShape(6.dp)).shimmer())
                        Box(Modifier.width(220.dp).height(11.dp).clip(RoundedCornerShape(6.dp)).shimmer())
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(PulsePalette.Emerald.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = PulsePalette.Emerald,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onAction) { Text(actionLabel, color = PulsePalette.Emerald) }
        }
    }
}
