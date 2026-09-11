package app.pulse.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pulse.core.time.PulseTime
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.model.Message
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette

/** Wave 1 wire-whitelist palette (spec §1.1 — 🙏 replaced by 🎉 for compliance). */
internal val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🎉")

/**
 * The long-press message sheet — Wave 0's reactions/reply/copy grown into the
 * full action surface (spec §2 row 4): edit, delete, pin, save, forward,
 * share, info, reply-in-thread. Every row is wired; conditional rows simply
 * don't render (no dead entries, no stubs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionSheet(
    message: Message,
    isMine: Boolean,
    canThread: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onReplyInThread: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleSave: () -> Unit,
    onForward: () -> Unit,
    onShare: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onInfo: (() -> Unit)?,
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
                    modifier = Modifier
                        .size(46.dp)
                        .clickable { onReact(emoji) },
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp)) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        SheetAction(Icons.AutoMirrored.Filled.Reply, "Reply", onReply)
        if (canThread) {
            SheetAction(Icons.Filled.Forum, "Reply in thread", onReplyInThread)
        }
        if (isMine && message.kind == Message.Kind.TEXT && message.imagePath == null && message.filePath == null) {
            SheetAction(Icons.Filled.Edit, "Edit", onEdit)
        }
        SheetAction(Icons.Filled.ContentCopy, "Copy", onCopy)
        SheetAction(
            Icons.Filled.PushPin,
            if (pinned) "Unpin" else "Pin",
            onTogglePin,
        )
        SheetAction(Icons.Outlined.BookmarkBorder, "Save to library", onToggleSave)
        SheetAction(Icons.Filled.Forward, "Forward", onForward)
        onShare?.let { SheetAction(Icons.Filled.Share, "Share", it) }
        onInfo?.let { SheetAction(Icons.Filled.Info, "Info", it) }
        onDelete?.let {
            SheetAction(Icons.Filled.Delete, "Delete", it, tint = PulsePalette.Rose)
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Forward targets — every conversation, search-filterable, multi-select,
 * one "Send" re-POSTing the source into each chosen chat (spec §1.1 forward).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForwardSheet(
    conversations: List<Conversation>,
    onDismiss: () -> Unit,
    onSend: (List<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val filtered = conversations.filter { it.title.contains(query.trim(), ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Forward to", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { if (selected.isNotEmpty()) onSend(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) {
                Text("Send" + if (selected.size > 1) " (${selected.size})" else "")
            }
        }
        BasicField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search chats",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .padding(bottom = 20.dp),
        ) {
            items(filtered, key = { it.id }) { conv ->
                val isSelected = conv.id in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (isSelected) selected - conv.id else selected + conv.id
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PulseAvatar(
                        name = conv.title,
                        colorHex = conv.accentColor,
                        size = 40.dp,
                        isGroup = conv.isGroupish,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            conv.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        conv.lastMessagePreview?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .padding(2.dp)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = PulsePalette.Emerald,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Read-only message info (own messages): the watermark read model turned into
 * "Seen by" (members whose lastReadAt ≥ createdAt) vs "Delivered to" (the rest)
 * plus the full reaction roster with resolved names.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageInfoSheet(
    message: Message,
    conversation: Conversation?,
    viewerId: String?,
    onDismiss: () -> Unit,
) {
    val createdAtMs = PulseTime.epochMs(message.createdAt)
    val others = conversation?.members?.filter { it.id != viewerId }.orEmpty()
    val seenBy = others.filter { (it.lastReadAt ?: 0L) >= createdAtMs && createdAtMs > 0L }
    val deliveredTo = others.filterNot { it in seenBy }
    val nameOf = { id: String ->
        conversation?.members?.firstOrNull { it.id == id }?.name ?: id
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Text(
            "Message info",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().height(380.dp).padding(bottom = 24.dp)) {
            item {
                SectionLabel("Sent")
                Text(
                    PulseTime.shortLabel(message.createdAt) + " · " + PulseTime.clock(message.createdAt),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                if (message.editedAt != null) {
                    Text(
                        "Edited " + PulseTime.shortLabel(message.editedAt),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                }
            }
            item {
                SectionLabel("Seen by · ${seenBy.size}")
            }
            if (seenBy.isEmpty()) {
                item { EmptyHint("No read receipts yet") }
            } else {
                items(seenBy, key = { "seen-" + it.id }) { member ->
                    MemberRow(member, check = true)
                }
            }
            item { SectionLabel("Delivered to · ${deliveredTo.size}") }
            if (deliveredTo.isEmpty()) {
                item { EmptyHint("Everyone has seen this message") }
            } else {
                items(deliveredTo, key = { "del-" + it.id }) { member ->
                    MemberRow(member, check = false)
                }
            }
            item { SectionLabel("Reactions · ${message.reactions.size}") }
            if (message.reactions.isEmpty()) {
                item { EmptyHint("No reactions yet") }
            } else {
                items(message.reactions, key = { it.emoji + it.userId }) { reaction ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(reaction.emoji, fontSize = 18.sp)
                        Text(
                            nameOf(reaction.userId),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

@Composable
private fun MemberRow(member: ConversationMember, check: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulseAvatar(name = member.name, colorHex = member.color, size = 32.dp)
        Text(
            member.name,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (check) {
            PulseCheckCheck(tint = PulsePalette.Emerald, modifier = Modifier.size(16.dp))
        } else {
            Icon(Icons.Outlined.Schedule, contentDescription = "Delivered", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
    }
}

/** All pinned rows of the room — tap to jump-scroll to the pin. */
@Composable
internal fun PinsDialog(
    pins: List<Message>,
    onJump: (Message) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PushPin, contentDescription = null, tint = PulsePalette.Amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pinned messages", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                    }
                }
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pins, key = { it.id }) { pin ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJump(pin) },
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    pin.authorName,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PulsePalette.Emerald,
                                )
                                Text(
                                    pin.body.ifBlank { if (pin.imagePath != null) "📷 Photo" else "Document — ${pin.fileName ?: "file"}" },
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    PulseTime.dayChip(pin.createdAt) + " · " + PulseTime.clock(pin.createdAt),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Delete confirmation — soft-delete tombstones render "🚫 Message deleted". */
@Composable
internal fun DeleteMessageDialog(
    message: Message,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete message?") },
        text = {
            Text(
                "This message will be deleted for everyone. It shows as \u201CMessage deleted\u201D afterwards.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = PulsePalette.Rose)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Rounded one-line text field — forward-sheet search + room search share it. */
@Composable
internal fun BasicField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .padding(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
internal fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp)
    }
}

/** Highlight the first matched substring — same rhythm as the chats search. */
internal fun snippetAnnotated(content: String, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return buildAnnotatedString { append(content) }
    val idx = content.lowercase().indexOf(q.lowercase())
    if (idx < 0) return buildAnnotatedString { append(content) }
    return buildAnnotatedString {
        append(content.substring(0, idx))
        withStyle(
            SpanStyle(
                background = PulsePalette.Emerald.copy(alpha = 0.25f),
                color = PulsePalette.EmeraldDeep,
                fontWeight = FontWeight.SemiBold,
            ),
        ) {
            append(content.substring(idx, (idx + q.length).coerceAtMost(content.length)))
        }
        append(content.substring((idx + q.length).coerceAtMost(content.length)))
    }
}

/**
 * Double-check "seen" glyph — material-icons-extended is deliberately kept off
 * the classpath (APK size, minify disabled), so the two-stroke check is drawn
 * natively. Same silhouette as the extended-icons CheckCheck.
 */
@Composable
internal fun PulseCheckCheck(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(
            width = size.width * 0.14f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val first = Path().apply {
            moveTo(size.width * 0.02f, size.height * 0.55f)
            lineTo(size.width * 0.30f, size.height * 0.82f)
            lineTo(size.width * 0.72f, size.height * 0.22f)
        }
        drawPath(first, tint, style = stroke)
        val second = Path().apply {
            moveTo(size.width * 0.36f, size.height * 0.55f)
            lineTo(size.width * 0.62f, size.height * 0.82f)
            lineTo(size.width * 0.98f, size.height * 0.22f)
        }
        drawPath(second, tint, style = stroke)
    }
}
