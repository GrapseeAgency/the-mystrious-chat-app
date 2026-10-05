package app.pulse.android.mirror

import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.pulse.domain.model.Message
import app.pulse.domain.model.PollInfo
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * R73 - the message-level surface the mirror was missing entirely (web
 * MessageActionMenu chat-room.tsx:6401-6573): the reaction strip, Reply,
 * Reply in thread, Copy text, Forward to chat, Save/Unsave, Convert to task,
 * Remind me, Pin/Unpin, Message info, Edit message and Delete for everyone -
 * every row riding the REAL repository routes. Also the forward/thread/poll/
 * sticker/location/who-reacted surfaces the web mounts around the room.
 */

/** web icon-ids.ts REACTION_IDS - the wire values + the glyph + the label. */
internal object MirrorReactions {
    data class Def(val id: String, val label: String, val glyph: String)

    val ALL = listOf(
        Def("heart", "Heart", "PHeartStraight"),
        Def("fire", "Fire", "LFlame"),
        Def("laugh", "Laugh", "PMaskHappy"),
        Def("wow", "Wow", "PEyes"),
        Def("sad", "Sad", "PSmileySad"),
        Def("celebrate", "Celebrate", "PConfetti"),
        Def("thumbsup", "Thumbs up", "PThumbsUp"),
    )

    fun glyphOf(id: String): String = ALL.firstOrNull { it.id == id }?.glyph ?: "PHeartStraight"
}

/**
 * The web GlassMenu action panel, glass-deep #1c1610/95, rounded 16, hairline
 * ring, w-260 - identical language to MirrorKebabMenu. Rows disabled exactly
 * where the web disables them (deleted/own/media rules, chat-room.tsx:6504+).
 */
@Composable
internal fun MirrorMessageActionPanel(
    message: Message,
    conversationId: String,
    viewerId: String,
    members: List<ConversationMember>,
    repository: PulseRepository,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onThread: () -> Unit,
    onForward: () -> Unit,
    onRemind: () -> Unit,
    onInfo: () -> Unit,
    onDeleted: () -> Unit,
    onTaskCreated: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val mine = message.authorId == viewerId
    val deleted = message.deletedAt != null
    val isTextKind = message.kind == Message.Kind.TEXT
    // live saved-library truth (web isSaved = saved list contains the id)
    val savedList by repository.observeSavedLibrary().collectAsState(initial = emptyList())
    val saved = savedList.any { it.message.id == message.id }
    // optimistic local overrides so the panel reacts on the same tap
    var savedLocal by remember(message.id) { mutableStateOf(saved) }
    LaunchedEffect(saved) { savedLocal = saved }
    var pinnedLocal by remember(message.id) { mutableStateOf(message.pinnedAt != null) }
    var pendingOp by remember(message.id) { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    fun op(name: String, block: suspend () -> String?) {
        if (pendingOp != null) return
        pendingOp = name
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { block() }.getOrNull()
            CoroutineScope(Dispatchers.Main).launch {
                pendingOp = null
                if (result != null) note = result
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x33000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}
                .padding(vertical = 6.dp)
                .verticalScroll(rememberScrollState())
                .heightIn(max = 420.dp),
        ) {
            // reaction strip (web GlassMenuStrip of REACTION_IDS, whileTap 0.82)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (def in MirrorReactions.ALL) {
                    val active = message.reactions.any { it.emoji == def.id && it.userId == viewerId }
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (active) Color(0x26FFB23D) else Color.Transparent)
                            .border(
                                1.dp,
                                if (active) Color(0x73FFB23D) else Color.Transparent,
                                CircleShape,
                            )
                            .mirrorPressClick(onClick = {
                                onDismiss()
                                CoroutineScope(Dispatchers.IO).launch {
                                    runCatching { repository.react(message.id, def.id) }
                                }
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (def.glyph.startsWith("P")) {
                            MirrorPhosphorIcon(def.glyph, tint = MirrorArt.Accent2, modifier = Modifier.size(20.dp))
                        } else {
                            MirrorLucideIcon(def.glyph, tint = MirrorArt.Accent2, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            if (deleted) {
                Text(
                    "This message was deleted.",
                    color = MirrorArt.Dim,
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            MirrorActionSeparator()
            MirrorActionRow(glyph = "LCornerDownRight", label = "Reply", enabled = !deleted) { onDismiss(); onReply() }
            if (message.threadRootId == null && !deleted) {
                MirrorActionRow(glyph = "LMessagesSquare", label = "Reply in thread") { onDismiss(); onThread() }
            }
            MirrorActionSeparator()
            MirrorActionRow(glyph = "LCopy", label = "Copy text", enabled = !deleted) {
                clipboard.setText(AnnotatedString(message.body))
                onDismiss()
            }
            MirrorActionRow(glyph = "LExternalLink", label = "Forward to chat", enabled = !deleted) { onDismiss(); onForward() }
            if (!deleted) {
                MirrorActionRow(
                    glyph = "LStar",
                    label = if (savedLocal) "Unsave" else "Save message",
                    active = savedLocal,
                    enabled = pendingOp == null,
                ) {
                    op("save") {
                        val next = runCatching { repository.toggleMessageSave(message.id).getOrThrow() }.getOrDefault(savedLocal)
                        CoroutineScope(Dispatchers.Main).launch { savedLocal = next }
                        null
                    }
                }
            }
            if (message.threadRootId == null && !deleted && isTextKind) {
                MirrorActionRow(
                    glyph = "LListTodo",
                    label = "Convert to task",
                    enabled = pendingOp == null,
                ) {
                    op("task") {
                        runCatching {
                            repository.createKanbanCard(
                                conversationId = conversationId,
                                title = message.body.take(80),
                                column = null,
                                assigneeId = null,
                                messageId = message.id,
                            ).getOrThrow()
                        }.onSuccess {
                            CoroutineScope(Dispatchers.Main).launch { onTaskCreated("Task added to the board") }
                        }
                        null
                    }
                }
            }
            if (!deleted) {
                MirrorActionRow(glyph = "LBell", label = "Remind me") { onDismiss(); onRemind() }
            }
            if (!deleted) {
                MirrorActionRow(
                    glyph = if (pinnedLocal) "LPinOff" else "LPin",
                    label = if (pinnedLocal) "Unpin" else "Pin",
                    enabled = pendingOp == null,
                ) {
                    op("pin") {
                        runCatching {
                            val updated = repository.toggleMessagePin(message.id).getOrThrow()
                            CoroutineScope(Dispatchers.Main).launch { pinnedLocal = updated.pinnedAt != null }
                        }
                        null
                    }
                }
            }
            if (mine && !deleted) {
                MirrorActionRow(glyph = "LInfo", label = "Message info") { onDismiss(); onInfo() }
            }
            if (mine && !deleted && message.audioPath == null && message.filePath == null) {
                MirrorActionRow(glyph = "LPencilLine", label = "Edit message") { onDismiss(); onEdit() }
            }
            MirrorActionSeparator()
            // canDelete = sender only (web DELETE /api/messages/{id} requesterId gate)
            MirrorActionRow(
                glyph = "LTrash2",
                label = "Delete for everyone",
                destructive = true,
                enabled = mine && !deleted && pendingOp == null,
            ) {
                op("delete") {
                    runCatching { repository.deleteMessage(message.id).getOrThrow() }
                    CoroutineScope(Dispatchers.Main).launch { onDeleted() }
                    null
                }
            }
            note?.let {
                Text(
                    it,
                    color = MirrorArt.TextSoft,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MirrorActionSeparator() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .height(1.dp)
            .background(MirrorArt.Hairline),
    )
}

@Composable
private fun MirrorActionRow(
    glyph: String,
    label: String,
    active: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        destructive -> MirrorArt.Red
        active -> MirrorArt.Accent2
        else -> MirrorArt.TextSoft
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MirrorLucideIcon(glyph, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            label,
            color = if (enabled) tint else MirrorArt.Faint,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/** web ForwardSheet chat-room.tsx:6324 - chat picker, forward = re-POST. */
@Composable
internal fun MirrorForwardSheet(
    repository: PulseRepository,
    source: Message,
    onDismiss: () -> Unit,
    onForwarded: (String) -> Unit,
) {
    val conversations by repository.observeConversations().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    MirrorSheet(title = "Forward to", onDismiss = onDismiss) {
        MirrorSheetSearchField(query = query, onQuery = { query = it })
        Spacer(Modifier.height(8.dp))
        val targets = conversations.filter { it.title.contains(query, ignoreCase = true) }
        MirrorSheetScroll {
            for (convo in targets) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = busyId == null) {
                            busyId = convo.id
                            error = null
                            CoroutineScope(Dispatchers.IO).launch {
                                val result = runCatching { repository.forwardMessage(convo.id, source) }
                                CoroutineScope(Dispatchers.Main).launch {
                                    result.onSuccess {
                                        onForwarded(convo.title)
                                    }.onFailure {
                                        busyId = null
                                        error = "Forward failed - try again"
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorAvatar(
                        name = convo.title,
                        color = convo.accentColor,
                        isGroup = convo.isGroupish,
                        groupId = convo.id,
                        online = false,
                        showPresence = false,
                        sizeDp = 34,
                        cornerDp = 17,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(convo.title, color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            convo.lastMessagePreview.orEmpty().ifBlank { "Conversation" },
                            color = MirrorArt.Dim,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (busyId == convo.id) {
                        MirrorLucideIcon("LLoaderCircle", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
                    }
                }
            }
            error?.let {
                Text(it, color = MirrorArt.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** web ThreadSheet chat-room.tsx:6234 - parent + replies + reply composer. */
@Composable
internal fun MirrorThreadSheet(
    repository: PulseRepository,
    root: Message,
    viewerId: String,
    viewerName: String,
    memberNames: List<String>,
    onDismiss: () -> Unit,
) {
    val replies by repository.observeThreadMessages(root.id).collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(root.id) {
        runCatching { repository.loadThread(root.id) }
    }
    MirrorSheet(title = "Thread", onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 430.dp)) {
            MirrorSheetScroll {
                // parent bubble (incoming style)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Column(
                        Modifier
                            .widthIn(max = 250.dp)
                            .mirrorBubbleShape(mine = true, deleted = root.deletedAt != null)
                            .mirrorBubbleBackground(mine = true, deleted = root.deletedAt != null)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(root.authorName, color = MirrorArt.Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            root.body.ifBlank { "(media)" },
                            color = MirrorArt.Ink,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
                for (reply in replies) {
                    val mine = reply.authorId == viewerId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                    ) {
                        Column(
                            Modifier
                                .widthIn(max = 250.dp)
                                .mirrorBubbleShape(mine = mine, deleted = reply.deletedAt != null)
                                .mirrorBubbleBackground(mine = mine, deleted = reply.deletedAt != null)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(reply.authorName, color = if (mine) MirrorArt.Ink else MirrorArt.TextSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                reply.body.ifBlank { "(media)" },
                                color = if (mine) MirrorArt.Ink else MirrorArt.Text,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
                if (replies.isEmpty()) {
                    Text(
                        "No replies yet - start the thread",
                        color = MirrorArt.Faint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // thread reply composer - parentId rides the wire (spec 1.1)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(MirrorArt.White7)
                    .border(1.dp, MirrorArt.Hairline, CircleShape)
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 2000) draft = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = MirrorArt.Text, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
                if (draft.isNotBlank()) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.Accent)
                            .mirrorPressClick(enabled = !sending, onClick = {
                                sending = true
                                CoroutineScope(Dispatchers.IO).launch {
                                    val result = runCatching {
                                        repository.sendMessage(
                                            conversationId = root.conversationId,
                                            body = draft,
                                            parentId = root.id,
                                        ).getOrThrow()
                                    }
                                    CoroutineScope(Dispatchers.Main).launch {
                                        sending = false
                                        result.onSuccess { draft = "" }
                                    }
                                }
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LSendHorizontal", tint = Color(0xFF20150C), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** web PollBuilderSheet chat-room.tsx:6184 - question + 2..6 options. */
@Composable
internal fun MirrorPollBuilderSheet(
    repository: PulseRepository,
    conversationId: String,
    onDismiss: () -> Unit,
    onPosted: (String) -> Unit,
) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    MirrorSheet(title = "New poll", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MirrorSheetInput(value = question, placeholder = "Ask a question", max = 160, onValue = { question = it })
            for ((index, option) in options.withIndex()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) {
                        MirrorSheetInput(
                            value = option,
                            placeholder = if (index < 2) "Option ${index + 1} (required)" else "Option ${index + 1}",
                            max = 80,
                            onValue = { v -> options = options.toMutableList().also { it[index] = v } },
                        )
                    }
                    if (options.size > 2) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .mirrorPressClick(onClick = { options = options.filterIndexed { i, _ -> i != index } }),
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon("LX", tint = MirrorArt.Dim, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            if (options.size < 6) {
                Text(
                    "+ Add another option",
                    color = MirrorArt.Accent2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { options = options + "" }
                        .padding(vertical = 4.dp),
                )
            }
            error?.let { Text(it, color = MirrorArt.Red, fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(MirrorArt.Accent)
                        .mirrorPressClick(enabled = !busy, onClick = {
                            val clean = options.map { it.trim() }.filter { it.isNotEmpty() }
                            if (question.isBlank() || clean.size < 2) {
                                error = "A poll needs a question and at least two options"
                            } else {
                                busy = true
                                CoroutineScope(Dispatchers.IO).launch {
                                    val result = runCatching {
                                        repository.createPoll(conversationId, question.trim(), clean).getOrThrow()
                                    }
                                    CoroutineScope(Dispatchers.Main).launch {
                                        busy = false
                                        result.onSuccess { onPosted("Poll posted") }
                                            .onFailure { error = "Could not post the poll" }
                                    }
                                }
                            }
                        })
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) {
                    Text("Post poll", color = Color(0xFF20150C), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * web sticker-picker.tsx - five packs of stamp tiles; a tap sends the REAL
 * kind:'sticker' row with the { emoji, pack } payload (chat-room.tsx:3227).
 */
@Composable
internal fun MirrorStickerSheet(
    onDismiss: () -> Unit,
    onPick: (emoji: String, pack: String) -> Unit,
) {
    val packs = listOf(
        "Signal" to listOf("bolt", "flame", "sparkles", "rocket", "target", "star"),
        "Celebrate" to listOf("trophy", "crown", "gift", "cake", "music", "heart"),
        "Create" to listOf("palette", "camera", "mic", "gamepad", "brain", "drama"),
        "Nature" to listOf("leaf", "moon", "drop", "planet", "coffee", "paw"),
        "Marks" to listOf("smile", "pin", "sun", "shield", "key", "thumbsup", "thumbsdown"),
    )
    val glyphs = mapOf(
        "bolt" to "LZap", "flame" to "LFlame", "sparkles" to "LSparkles", "rocket" to "LRocket",
        "target" to "LTarget", "star" to "LStar", "trophy" to "LTrophy", "crown" to "LCrown",
        "gift" to "LGift", "cake" to "LCake", "music" to "LMusic", "heart" to "LHeart",
        "palette" to "LPalette", "camera" to "LCamera", "mic" to "LMic", "gamepad" to "LGamepad",
        "brain" to "LBrain", "drama" to "LSticker", "leaf" to "LLeaf", "moon" to "LMoon",
        "drop" to "LDroplet", "planet" to "LPlanet", "coffee" to "LCoffee", "paw" to "LPaw",
        "smile" to "LSmile", "pin" to "LPin", "sun" to "LSun", "shield" to "LShieldCheck",
        "key" to "LKey", "thumbsup" to "LThumbsUp", "thumbsdown" to "LThumbsDown",
    )
    var pack by remember { mutableStateOf(packs.first().first) }
    MirrorSheet(title = "Stamps", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((name, _) in packs) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(if (pack == name) MirrorArt.ChipActive else MirrorArt.White7)
                            .border(1.dp, if (pack == name) MirrorArt.Hairline else Color.Transparent, CircleShape)
                            .mirrorPressClick(onClick = { pack = name })
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(name, color = if (pack == name) MirrorArt.Text else MirrorArt.Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            val items = packs.first { it.first == pack }.second
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in items.chunked(4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in row) {
                            Box(
                                Modifier
                                    .size(58.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MirrorArt.White7)
                                    .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(14.dp))
                                    .mirrorPressClick(onClick = { onPick(item, pack) }),
                                contentAlignment = Alignment.Center,
                            ) {
                                MirrorLucideIcon(glyphs[item] ?: "LStar", tint = MirrorArt.Accent2, modifier = Modifier.size(26.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * web LocationShareSheet - sends kind:'location' {lat,lng,label} from the
 * REAL device fix (LocationManager last-known across gps/network/passive),
 * never a canned coordinate.
 */
@Composable
internal fun MirrorLocationSheet(
    onDismiss: () -> Unit,
    onSend: (lat: Double, lng: Double, label: String) -> Unit,
) {
    var fix by remember { mutableStateOf<Location?>(null) }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val last = runCatching {
            lm?.allProviders?.mapNotNull { p ->
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    lm.getLastKnownLocation(p)
                } else {
                    null
                }
            }?.maxByOrNull { it.time }
        }.getOrNull()
        if (last != null) fix = last else error = "No location fix available - enable location and retry"
    }
    MirrorSheet(title = "Share location", onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (fix != null) {
                Text(
                    "Lat %.5f  -  Lng %.5f".format(fix!!.latitude, fix!!.longitude),
                    color = MirrorArt.TextSoft,
                    fontSize = 13.sp,
                )
                MirrorSheetInput(value = label, placeholder = "Label this place (optional)", max = 80, onValue = { label = it })
            }
            error?.let { Text(it, color = MirrorArt.Red, fontSize = 12.sp) }
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(MirrorArt.Accent)
                    .mirrorPressClick(enabled = fix != null, onClick = {
                        onSend(fix!!.latitude, fix!!.longitude, label.trim())
                    })
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text("Send my location", color = Color(0xFF20150C), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * web who-reacted drawer (chat-room.tsx:5982) + the message-info content:
 * every reaction group with the reacting member names from the live roster.
 */
@Composable
internal fun MirrorMessageInfoPanel(
    message: Message,
    members: List<ConversationMember>,
    onDismiss: () -> Unit,
) {
    MirrorSheet(title = "Message info", onDismiss = onDismiss) {
        MirrorSheetScroll {
            Text(
                "Sent ${MirrorRoomStamp(message.createdAt)}",
                color = MirrorArt.TextSoft,
                fontSize = 12.sp,
            )
            if (message.editedAt != null) {
                Text("Edited", color = MirrorArt.Dim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (message.reactions.isEmpty()) {
                Text("No reactions yet", color = MirrorArt.Faint, fontSize = 12.sp)
            }
            val groups = message.reactions.groupBy { it.emoji }
            for ((emoji, hits) in groups) {
                val names = hits.mapNotNull { hit -> members.firstOrNull { it.id == hit.userId }?.name }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorPhosphorIcon(
                        MirrorReactions.glyphOf(emoji),
                        tint = MirrorArt.Accent2,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            "${names.size} reacted",
                            color = MirrorArt.Text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            names.joinToString(", ").ifBlank { "Unknown" },
                            color = MirrorArt.Dim,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Poll bubble (web poll renderer): question, bars, per-option tallies, vote. */
@Composable
internal fun MirrorPollBubble(
    poll: PollInfo,
    mine: Boolean,
    viewerId: String,
    onVote: (optionId: String) -> Unit,
) {
    val total = poll.options.maxOf { it.voteCount }.coerceAtLeast(0)
    val sum = poll.options.sumOf { it.voteCount }.coerceAtLeast(1)
    Column {
        Text(
            poll.question,
            color = if (mine) MirrorArt.Ink else MirrorArt.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        for (option in poll.options) {
            val picked = option.votedBy.contains(viewerId)
            val fraction = option.voteCount.toFloat() / sum.toFloat()
            Column(Modifier.padding(vertical = 3.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                picked -> if (mine) Color(0x26000000) else Color(0x26FF7A3D)
                                else -> if (mine) Color(0x0D000000) else Color(0x0FFFFFFF)
                            },
                        )
                        .mirrorPressClick(enabled = !picked, onClick = { onVote(option.id) })
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option.text,
                        color = if (mine) MirrorArt.Ink else MirrorArt.Text,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${option.voteCount}",
                        color = if (mine) MirrorArt.InkSoft else MirrorArt.Dim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(if (picked) MirrorArt.Accent else MirrorArt.Hairline),
                )
            }
        }
        Text(
            "$sum votes",
            color = if (mine) MirrorArt.InkSoft else MirrorArt.Faint,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
internal fun MirrorSheetSearchField(query: String, onQuery: (String) -> Unit) {
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
                Text("Search chats", color = MirrorArt.Faint, fontSize = 13.sp)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = MirrorArt.Text, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
            )
        }
    }
}

@Composable
internal fun MirrorSheetInput(value: String, placeholder: String, max: Int, onValue: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MirrorArt.White7)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isBlank()) {
                Text(placeholder, color = MirrorArt.Faint, fontSize = 13.sp)
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = { if (it.length <= max) onValue(it) },
                textStyle = androidx.compose.ui.text.TextStyle(color = MirrorArt.Text, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MirrorArt.Accent),
                maxLines = 2,
            )
        }
    }
}
