package app.pulse.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.core.PulseEndpoints
import app.pulse.domain.model.Automation
import app.pulse.domain.model.Webhook
import app.pulse.ui.PulsePalette

/**
 * R2-A items 6/7 — the room-info manager sections, ports of:
 *  · AutomationsSection — src/components/chat/automations-sheet.tsx:54
 *    (keyword-triggered auto-replies; list / optimistic toggle / R41 trigger
 *    rename / honest delete / create sheet).
 *  · WebhooksSection    — src/components/chat/group-info-sheet.tsx:503
 *    (Discord-style incoming hooks; list / copy ingest URL / admin create+delete).
 * Both read live server truth through GroupInfoViewModel → /api/automations
 * and /api/webhooks. Members see rows; management actions are admin-gated in
 * the UI exactly like the web (the server enforces it authoritatively).
 */

// ── AUTOMATIONS ──────────────────────────────────────────────────────

private const val TRIGGER_MIN = 2
private const val TRIGGER_MAX = 40
private const val REPLY_MAX = 500
private const val WEBHOOK_NAME_MAX = 32

@Composable
internal fun AutomationsSection(
    isAdmin: Boolean,
    state: GroupInfoViewModel.AutomationsUi,
    onCreate: (trigger: String, reply: String) -> Unit,
    onToggle: (Automation) -> Unit,
    onRename: (id: String, trigger: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onLoad: () -> Unit,
) {
    var createOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Automation?>(null) }
    var deleteTarget by remember { mutableStateOf<Automation?>(null) }

    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Automations", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${state.rows.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Trigger a reply by keyword — admins store a phrase and the answer lands as a message.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            when {
                state.loading && state.rows.isEmpty() -> {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading automations…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.rows.isEmpty() -> Text(
                    if (isAdmin) {
                        "No automations yet — create one to auto-answer a keyword."
                    } else {
                        "Admins can add keyword auto-replies here."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                else -> Column {
                    state.rows.forEach { row ->
                        AutomationRow(
                            row = row,
                            isAdmin = isAdmin,
                            busy = row.id in state.busyIds,
                            onToggle = { onToggle(row) },
                            onRename = { renameTarget = row },
                            onDelete = { deleteTarget = row },
                        )
                        if (row != state.rows.last()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            if (isAdmin) {
                TextButton(onClick = { onLoad(); createOpen = true }, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Create automation", fontSize = 12.sp)
                }
            }
        }
    }

    if (createOpen) {
        AutomationCreateSheet(
            creating = state.creating,
            onDismiss = { createOpen = false },
            onCreate = { trigger, reply ->
                createOpen = false
                onCreate(trigger, reply)
            },
        )
    }
    renameTarget?.let { row ->
        AutomationRenameSheet(
            row = row,
            busy = row.id in state.busyIds,
            onDismiss = { renameTarget = null },
            onRename = { trigger ->
                renameTarget = null
                onRename(row.id, trigger)
            },
        )
    }
    deleteTarget?.let { row ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete automation?") },
            text = { Text("“${row.trigger}” will no longer auto-reply. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDelete(row.id)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AutomationRow(
    row: Automation,
    isAdmin: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.trigger,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.hits > 0) {
                    Spacer(Modifier.width(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, tint = PulsePalette.Amber, modifier = Modifier.size(11.dp))
                        Text(" ${row.hits}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                row.reply,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isAdmin) {
            IconButton(onClick = onRename, enabled = !busy) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit trigger \"${row.trigger}\"", modifier = Modifier.size(15.dp))
            }
            Switch(checked = row.enabled, onCheckedChange = { onToggle() }, enabled = !busy)
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete automation \"${row.trigger}\"",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Text(
                if (row.enabled) "On" else "Off",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (row.enabled) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Create form — trigger 2-40 chars, reply 1-500 (server-side caps mirrored). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationCreateSheet(
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (trigger: String, reply: String) -> Unit,
) {
    var trigger by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    val trimmedTrigger = trigger.trim()
    val trimmedReply = reply.trim()
    val triggerInvalid = trimmedTrigger.isNotEmpty() && (trimmedTrigger.length < TRIGGER_MIN || trimmedTrigger.length > TRIGGER_MAX)
    val replyInvalid = trimmedReply.isEmpty() || trimmedReply.length > REPLY_MAX
    val valid = !triggerInvalid && !replyInvalid

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("New automation", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "When someone sends the trigger phrase, Pulse replies with your answer.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            OutlinedTextField(
                value = trigger,
                onValueChange = { if (it.length <= TRIGGER_MAX) trigger = it },
                placeholder = { Text("Trigger phrase — e.g. “hours”") },
                singleLine = true,
                isError = triggerInvalid,
                supportingText = {
                    Text(
                        when {
                            triggerInvalid -> "$TRIGGER_MIN–$TRIGGER_MAX characters"
                            else -> "Matched as a standalone phrase (case-insensitive)"
                        },
                        fontSize = 10.5.sp,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = reply,
                onValueChange = { if (it.length <= REPLY_MAX) reply = it },
                placeholder = { Text("Reply — e.g. “We're open 9am–5pm Mon–Fri.”") },
                supportingText = { Text("${trimmedReply.length}/$REPLY_MAX", fontSize = 10.5.sp) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onCreate(trimmedTrigger, trimmedReply) },
                enabled = valid && !creating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (creating) "Creating…" else "Create automation")
            }
        }
    }
}

/** R41 — rename a rule's trigger in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationRenameSheet(
    row: Automation,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRename: (trigger: String) -> Unit,
) {
    var trigger by remember { mutableStateOf(row.trigger) }
    val trimmed = trigger.trim()
    val unchanged = trimmed == row.trigger
    val invalid = trimmed.length < TRIGGER_MIN || trimmed.length > TRIGGER_MAX

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Edit trigger", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "The reply stays the same — only the matching phrase changes.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            OutlinedTextField(
                value = trigger,
                onValueChange = { if (it.length <= TRIGGER_MAX) trigger = it },
                singleLine = true,
                isError = trimmed.isNotEmpty() && invalid,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onRename(trimmed) },
                enabled = !busy && !unchanged && !invalid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Saving…" else "Save trigger")
            }
        }
    }
}

// ── WEBHOOKS ─────────────────────────────────────────────────────────

@Composable
internal fun WebhooksSection(
    isAdmin: Boolean,
    state: GroupInfoViewModel.WebhooksUi,
    onCreate: (name: String) -> Unit,
    onDelete: (token: String) -> Unit,
    onLoad: () -> Unit,
    onNotice: (String, Boolean) -> Unit,
) {
    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Webhook?>(null) }
    val clipboard = LocalClipboardManager.current

    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Webhook, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Webhooks", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("${state.rows.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            when {
                state.loading && state.rows.isEmpty() -> {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading webhooks…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.rows.isEmpty() -> Text(
                    if (isAdmin) {
                        "No webhooks yet — create one to let outside services post into this chat."
                    } else {
                        "Admins can add Discord-style integrations here."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                else -> Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.rows.forEach { webhook ->
                        WebhookRow(
                            webhook = webhook,
                            isAdmin = isAdmin,
                            onCopy = {
                                // Web pairs the relative ingest URL with the app
                                // origin (location.origin) — here the configured
                                // gateway origin plays that role.
                                val fullUrl = PulseEndpoints.gatewayHttpUrl.trimEnd('/') + webhook.url
                                clipboard.setText(AnnotatedString(fullUrl))
                                onNotice("Webhook URL copied", false)
                            },
                            onDelete = { deleteTarget = webhook },
                        )
                    }
                }
            }

            if (isAdmin) {
                TextButton(onClick = { onLoad(); createOpen = true }, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Create webhook", fontSize = 12.sp)
                }
            }
        }
    }

    if (createOpen) {
        WebhookCreateSheet(
            creating = state.creating,
            onDismiss = { createOpen = false },
            onCreate = { name ->
                createOpen = false
                onCreate(name)
            },
        )
    }
    deleteTarget?.let { webhook ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete webhook?") },
            text = { Text("“${webhook.name}” stops accepting posts immediately. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDelete(webhook.token)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WebhookRow(
    webhook: Webhook,
    isAdmin: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val accent = webhookColor(webhook.avatarColor)
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Webhook, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(webhook.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                webhook.url,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy webhook URL", modifier = Modifier.size(15.dp))
        }
        if (isAdmin) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete webhook \"${webhook.name}\"",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Wire avatarColor ("emerald"|"teal"|…) → the house palette; junk → emerald. */
private fun webhookColor(name: String): Color = when (name.lowercase()) {
    "teal" -> PulsePalette.Teal
    "violet" -> PulsePalette.Violet
    "amber" -> PulsePalette.Amber
    "rose" -> PulsePalette.Rose
    else -> PulsePalette.Emerald
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebhookCreateSheet(
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= WEBHOOK_NAME_MAX

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Create webhook", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "Outside services POST to its URL and drop messages into this chat as a named sender.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= WEBHOOK_NAME_MAX) name = it },
                placeholder = { Text("e.g. Deploys · CI · Weather") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onCreate(trimmed) },
                enabled = valid && !creating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (creating) "Creating…" else "Create webhook")
            }
        }
    }
}
