package app.pulse.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import app.pulse.core.time.PulseTime
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import app.pulse.domain.model.TEMP_MESSAGE_PREFIX
import androidx.hilt.navigation.compose.hiltViewModel

private val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")

/**
 * Chat room — the native rebuild of the web conversation surface. Same
 * outcome: emerald bubbles, glass header, typing indicator with bouncing
 * dots, reactions, replies, read receipts. Compose-native mechanics:
 * reverseLayout list, imePadding, ModalBottomSheet actions, haptics.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatRoomScreen(
    conversationId: String,
    viewerId: String?,
    onBack: () -> Unit,
    viewModel: ChatRoomViewModel = hiltViewModel(),
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<Message?>(null) }
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    // Wave 0 draft restore — the VM seeds from the local draft table (or the
    // server myDraft fallback) exactly once; never stomp live typing.
    val initialDraft by viewModel.initialDraft.collectAsStateWithLifecycle()
    LaunchedEffect(initialDraft) {
        val seed = initialDraft
        if (!seed.isNullOrBlank() && draft.isBlank()) draft = seed
    }

    // Auto-scroll to the newest row when the tail grows (and near the tail).
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        RoomHeader(
            conversation = conversation,
            partnerTypingName = state.partnerTypingName,
            onBack = onBack,
        )

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages.asReversed(), key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        conversation = conversation,
                        viewerId = viewerId,
                        partnerLastReadAt = state.partnerLastReadAt,
                        isLastMine = messages.lastOrNull()?.id == message.id,
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actionTarget = message
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = state.error != null,
                enter = fadeIn() + scaleIn(initialScale = 0.9f, animationSpec = PulseMotion.soft()),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.error ?: "", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = viewModel::retry) { Text("Retry") }
                    }
                }
            }

            // Wave 0 offline core — pending outbox state is honest, neutral,
            // and self-clearing: it shows while a `local_` bubble is queued
            // and disappears the moment the flush swaps it for the real row.
            val hasPending = messages.any { it.id.startsWith(TEMP_MESSAGE_PREFIX) }
            androidx.compose.animation.AnimatedVisibility(
                visible = hasPending && state.error == null,
                enter = fadeIn() + scaleIn(initialScale = 0.9f, animationSpec = PulseMotion.soft()),
                exit = fadeOut() + scaleOut(targetScale = 0.9f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Queued — will send when online",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // Reply quote above the composer
        androidx.compose.animation.AnimatedVisibility(
            visible = state.replyTo != null,
            enter = fadeIn() + scaleIn(initialScale = 0.96f, animationSpec = PulseMotion.snappy()),
            exit = fadeOut() + scaleOut(targetScale = 0.96f, animationSpec = tween(120)),
        ) {
            state.replyTo?.let { reply ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(reply.authorName, style = MaterialTheme.typography.labelMedium, color = PulsePalette.Emerald, fontWeight = FontWeight.SemiBold)
                            Text(reply.body, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { viewModel.setReplyTo(null) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel reply", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // Composer
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = { }, modifier = Modifier.clip(CircleShape)) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        viewModel.onDraftChanged(it)
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(PulsePalette.Emerald),
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(6.dp))
                val canSend = draft.isNotBlank()
                IconButton(
                    onClick = {
                        if (canSend) {
                            viewModel.send(draft)
                            draft = ""
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(44.dp)
                        .background(
                            if (canSend) {
                                Brush.linearGradient(listOf(PulsePalette.Emerald, PulsePalette.EmeraldDeep))
                            } else {
                                SolidColor(MaterialTheme.colorScheme.surfaceVariant)
                            },
                            CircleShape,
                        ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    actionTarget?.let { target ->
        MessageActionSheet(
            message = target,
            onDismiss = { actionTarget = null },
            onReact = { emoji ->
                viewModel.react(target.id, emoji)
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                actionTarget = null
            },
            onReply = {
                viewModel.setReplyTo(target)
                actionTarget = null
            },
            onCopy = {
                clipboard.setText(AnnotatedString(target.body))
                actionTarget = null
            },
            onPin = { actionTarget = null },
        )
    }
}

@Composable
private fun RoomHeader(
    conversation: Conversation?,
    partnerTypingName: String?,
    onBack: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            PulseAvatar(
                name = conversation?.title ?: "…",
                colorHex = conversation?.accentColor,
                size = 40.dp,
                isGroup = conversation?.isGroupish == true,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conversation?.title ?: "…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (conversation != null && !conversation.isGroupish) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(14.dp))
                    }
                }
                AnimatedContentCompat(partnerTypingName != null) { typing ->
                    if (typing) {
                        Text(
                            "${partnerTypingName.orEmpty()} is typing",
                            style = MaterialTheme.typography.labelMedium,
                            color = PulsePalette.Emerald,
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Text(
                            when {
                                conversation == null -> ""
                                conversation.isGroupish -> "${conversation.memberNames.size} members"
                                else -> "online"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            IconButton(onClick = { }) {
                Icon(Icons.Filled.Call, contentDescription = "Call (next wave)", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Small crossfade helper (kept local; Compose Crossfade without key jank). */
@Composable
private fun AnimatedContentCompat(visible: Boolean, content: @Composable (Boolean) -> Unit) {
    androidx.compose.animation.Crossfade(
        targetState = visible,
        animationSpec = tween(180),
        label = "subtitle",
    ) { content(it) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Message,
    conversation: Conversation?,
    viewerId: String?,
    partnerLastReadAt: Long?,
    isLastMine: Boolean,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mine = message.authorId == viewerId
    val system = message.kind == Message.Kind.SYSTEM || message.authorId == "system"

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (system) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(message.body, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        // Group sender label above their first bubble run
        if (!mine && conversation?.isGroupish == true) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(PulsePalette.parse(message.senderColor) ?: PulsePalette.Teal))
                Spacer(Modifier.width(5.dp))
                Text(message.authorName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Bubble(
                message = message,
                mine = mine,
                onLongPress = onLongPress,
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }

        // reactions + meta
        if (message.reactions.isNotEmpty()) {
            Row(
                Modifier.padding(start = if (mine) 0.dp else 4.dp, end = if (mine) 4.dp else 0.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                message.reactions
                    .groupBy { it.emoji }
                    .forEach { (emoji, list) ->
                        val popped by animateFloatAsState(1f, animationSpec = PulseMotion.bouncy(), label = "react")
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(emoji, fontSize = 12.sp)
                                if (list.size > 1) {
                                    Spacer(Modifier.width(3.dp))
                                    Text("${list.size}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
            }
        }

        if (mine && isLastMine) {
            if (message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                // Queued in the outbox — a clock, never a false "Seen".
                Row(
                    Modifier.padding(end = 4.dp, top = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = "Queued",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            } else {
                val seen = partnerLastReadAt != null && (PulseTime.parse(message.createdAt)?.toInstant()?.toEpochMilli() ?: 0L) <= partnerLastReadAt
                Text(
                    if (seen) "Seen" else PulseTime.clock(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (seen) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp, top = 1.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    message: Message,
    mine: Boolean,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = if (mine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp)
    }
    val background: Brush = if (mine) {
        Brush.linearGradient(listOf(PulsePalette.Emerald, PulsePalette.EmeraldDeep))
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
    }
    val contentColor = if (mine) Color.White else MaterialTheme.colorScheme.onSurface

    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = modifier.combinedClickable(onClick = { }, onLongClick = onLongPress),
    ) {
        Column(
            Modifier
                .background(background, shape)
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            // reply quote
            val replyBody = message.replyToBody
            if (message.replyToId != null && replyBody != null) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (mine) Color.White.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Column {
                        Text(
                            message.replyToAuthor ?: "Reply",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (mine) Color.White else PulsePalette.Emerald,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            replyBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mine) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            when (message.kind) {
                Message.Kind.VOICE -> VoiceBubble(message, contentColor)
                Message.Kind.IMAGE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Image, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Photo", color = contentColor, style = MaterialTheme.typography.bodyMedium)
                }
                Message.Kind.FILE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(message.body.ifBlank { "File" }, color = contentColor, style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(
                    message.body,
                    color = contentColor,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (message.id.startsWith(TEMP_MESSAGE_PREFIX)) {
                    // Pending outbox bubble — clock marker replaces the clock text.
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = "Queued",
                        tint = if (mine) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(11.dp),
                    )
                } else {
                    Text(
                        PulseTime.clock(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mine) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (message.viaAutomation) {
                    Text(
                        "Automation",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mine) Color.White.copy(alpha = 0.85f) else PulsePalette.Violet,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (message.pinnedAt != null) {
                    Icon(Icons.Filled.PushPin, contentDescription = "Pinned", tint = if (mine) Color.White.copy(alpha = 0.85f) else PulsePalette.Amber, modifier = Modifier.size(11.dp))
                }
            }
        }
    }
}

/** Native waveform placeholder — deterministic bars from the message id. */
@Composable
private fun VoiceBubble(message: Message, contentColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.GraphicEq, contentDescription = "Voice message", tint = contentColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            val seed = remember(message.id) { message.id.hashCode() }
            repeat(16) { i ->
                val h = (4 + ((seed * (i + 7)) % 14)).dp
                Box(
                    Modifier
                        .width(3.dp)
                        .size(width = 3.dp, height = h)
                        .clip(RoundedCornerShape(2.dp))
                        .background(contentColor.copy(alpha = 0.85f)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        val seconds = (message.durationMs ?: 0) / 1000
        Text(
            if (seconds > 0) "${seconds}s" else "Voice",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.8f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    message: Message,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onPin: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            QUICK_REACTIONS.forEach { emoji ->
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
            }
        }
        SheetAction(Icons.AutoMirrored.Filled.Reply, "Reply", onReply)
        SheetAction(Icons.Filled.ContentCopy, "Copy", onCopy)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
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
