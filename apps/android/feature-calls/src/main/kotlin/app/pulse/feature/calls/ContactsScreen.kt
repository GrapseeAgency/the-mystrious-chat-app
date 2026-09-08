package app.pulse.feature.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette
import app.pulse.domain.model.User
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Contacts tab — native directory of Pulse accounts with live presence,
 * one-tap DM creation, and safety actions (block/report) in native menus.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onOpenRoom: (String) -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val users by viewModel.users.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val dmResult by viewModel.dmResult.collectAsStateWithLifecycle()

    var filter by remember { mutableStateOf("") }
    var safetyTarget by remember { mutableStateOf<User?>(null) }
    var reportTarget by remember { mutableStateOf<User?>(null) }
    var reportReason by remember { mutableStateOf("") }

    LaunchedEffect(dmResult) {
        when (val result = dmResult) {
            is ContactsViewModel.DmResult.Ready -> {
                viewModel.consumeDmResult()
                onOpenRoom(result.conversationId)
            }
            else -> Unit
        }
    }

    val visible = if (filter.isBlank()) users else users.filter {
        it.name.contains(filter, true) || it.handle.contains(filter, true)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Contacts", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "${presence.size} online on Pulse",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Find people") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        when {
            state.error != null && users.isEmpty() -> Text(
                state.error ?: "",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(20.dp),
            )
            state.loading && users.isEmpty() -> Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(6) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)))
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(width = 120.dp, height = 12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)))
                            Box(Modifier.size(width = 80.dp, height = 10.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)))
                        }
                    }
                }
            }
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.id }) { user ->
                    ContactRow(
                        user = user,
                        online = presence.contains(user.id),
                        onMessage = { viewModel.openDm(user) },
                        onSafety = { safetyTarget = user },
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    safetyTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { safetyTarget = null }, sheetState = rememberModalBottomSheetState()) {
            Text(
                "@${target.handle.ifBlank { target.name.lowercase().replace(" ", "_") }}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            SheetRow(icon = Icons.AutoMirrored.Filled.Chat, label = "Open chat") {
                safetyTarget = null
                viewModel.openDm(target)
            }
            SheetRow(icon = Icons.Filled.Block, label = "Block", tint = MaterialTheme.colorScheme.error) {
                safetyTarget = null
                viewModel.block(target)
            }
            SheetRow(icon = Icons.Filled.Flag, label = "Report", tint = MaterialTheme.colorScheme.error) {
                safetyTarget = null
                reportTarget = target
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    reportTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { reportTarget = null },
            title = { Text("Report ${target.name}") },
            text = {
                OutlinedTextField(
                    value = reportReason,
                    onValueChange = { reportReason = it },
                    placeholder = { Text("What happened?") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.report(target, reportReason.ifBlank { "unspecified" })
                        reportReason = ""
                        reportTarget = null
                    },
                ) { Text("Send report", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { reportTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, color = tint)
    }
}

@Composable
private fun ContactRow(
    user: User,
    online: Boolean,
    onMessage: () -> Unit,
    onSafety: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PulseAvatar(name = user.name, colorHex = user.color, size = 44.dp, online = online)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(user.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (user.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.Verified, contentDescription = "Verified", tint = PulsePalette.Emerald, modifier = Modifier.size(13.dp))
                    }
                }
                Text(
                    user.statusText ?: "@${user.handle.ifBlank { user.name.lowercase().replace(" ", "_") }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onMessage) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp), tint = PulsePalette.Emerald)
                Spacer(Modifier.width(6.dp))
                Text("Chat", color = PulsePalette.Emerald)
            }
            Box {
                IconButton(onClick = { menuOpen = true; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Open chat") }, onClick = { menuOpen = false; onMessage() })
                    DropdownMenuItem(text = { Text("Block / report") }, onClick = { menuOpen = false; onSafety() })
                }
            }
        }
    }
}
