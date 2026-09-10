package app.pulse.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import app.pulse.ui.update.LiveUpdater
import app.pulse.ui.update.UpdaterDetail
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

private val FX_OPTIONS = listOf(
    "aurora" to "Aurora",
    "caustics" to "Caustics",
    "mesh" to "Mesh",
    "stars" to "Stars",
    "liquid" to "Liquid",
    "off" to "Off",
)

private val SWATCHES = listOf("#10B981", "#14B8A6", "#8B5CF6", "#F59E0B", "#FB7185", "#0EA5E9")

/**
 * Profile tab — identity management (the native onboarding parity surface),
 * appearance (dark override + ambient FX picker — the WebGL modes reborn as
 * AGSL shaders), motion respect, and honest about-notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: ProfileViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val viewerId by viewModel.viewerId.collectAsStateWithLifecycle()
    val viewerName by viewModel.viewerName.collectAsStateWithLifecycle()
    val fxMode by viewModel.fxMode.collectAsStateWithLifecycle()
    val dark by viewModel.darkOverride.collectAsStateWithLifecycle()
    val reduced by viewModel.reducedMotion.collectAsStateWithLifecycle()

    var identitySheet by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Profile", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Spacer(Modifier.height(16.dp))

        // Identity card
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 1.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                PulseAvatar(
                    name = viewerName ?: "You",
                    colorHex = null,
                    size = 56.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (viewerId == null) {
                        Text("No identity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Pick who you are to light up Pulse", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(viewerName ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Signed in on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { identitySheet = true; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp), tint = PulsePalette.Emerald)
                    Spacer(Modifier.width(6.dp))
                    Text(if (viewerId == null) "Choose" else "Switch", color = PulsePalette.Emerald)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader(Icons.Filled.Palette, "Appearance")
        SettingCard {
            Column {
                Text("Mode", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("system" to "Auto", "light" to "Light", "dark" to "Dark").forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = dark == value,
                            onClick = { viewModel.setDarkOverride(value) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Ambient field (native shaders)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "aurora · caustics · mesh · stars · liquid — the web's WebGL modes ported to AGSL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRowCompat {
                    FX_OPTIONS.forEach { (id, label) ->
                        val selected = fxMode == id
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .padding(end = 8.dp, bottom = 8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { viewModel.setFxMode(id) },
                        ) {
                            Text(
                                label,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader(Icons.Filled.Bolt, "Motion")
        SettingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reduce motion", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Static ambient frame, no particle bursts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = reduced, onCheckedChange = { viewModel.setReducedMotion(it) })
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader(Icons.Filled.Info, "About")
        SettingCard {
            Column {
                Text(
                    "Pulse ${LiveUpdater.installedVersionLabel(context) ?: "?"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Kotlin · Compose · Hilt · Room · Ktor · Socket.IO — rebuilt natively against the same live gateway as the web app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader(Icons.Filled.SystemUpdate, "App updates")
        SettingCard {
            Column {
                UpdaterDetail()
                Spacer(Modifier.height(4.dp))
                Text(
                    "Quiet check on every launch · byte-range resume · integrity-gated before install",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch { LiveUpdater.syncFrom(context, force = true) }
                }) {
                    Text("Check for updates", color = PulsePalette.Emerald)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader(Icons.Filled.Wifi, "Connection")
        SettingCard {
            val savedBase by viewModel.serverBase.collectAsStateWithLifecycle()
            val probe by viewModel.probe.collectAsStateWithLifecycle()
            var serverField by remember(savedBase) { mutableStateOf(savedBase ?: "") }
            Column {
                Text("Server address", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (savedBase.isNullOrBlank()) {
                        "Not set — Pulse runs offline-first. Paste your Pulse web origin (the https:// address of this app's server) to go live."
                    } else {
                        "REST + realtime point at:\n$savedBase"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverField,
                    onValueChange = { serverField = it },
                    singleLine = true,
                    placeholder = { Text("https://your-pulse-server") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.probeServer(serverField)
                    }) { Text("Test", color = PulsePalette.Emerald) }
                    TextButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setServerBase(serverField.trim().takeIf { it.isNotBlank() })
                    }) { Text("Save & use", color = PulsePalette.Emerald) }
                    if (!savedBase.isNullOrBlank()) {
                        TextButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            serverField = ""
                            viewModel.setServerBase(null)
                        }) { Text("Go offline", color = MaterialTheme.colorScheme.error) }
                    }
                }
                when {
                    probe.running -> Text(
                        "Testing $serverField/api/users …",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    probe.ok == true -> Text(
                        "✓ ${probe.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulsePalette.Emerald,
                    )
                    probe.ok == false -> Text(
                        "✗ ${probe.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulsePalette.Amber,
                    )
                }
            }
        }

        if (viewerId != null) {
            Spacer(Modifier.height(14.dp))
            SettingCard {
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.forgetIdentity() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Forget identity on this device", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    if (identitySheet) {
        IdentitySheet(
            state = state,
            viewerId = viewerId,
            onDismiss = { identitySheet = false },
            onChoose = { viewModel.chooseIdentity(it) },
            onNameChange = viewModel::setNewIdentityName,
            onCreate = viewModel::createIdentity,
            onRetry = viewModel::refresh,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentitySheet(
    state: ProfileViewModel.UiState,
    viewerId: String?,
    onDismiss: () -> Unit,
    onChoose: (app.pulse.domain.model.User) -> Unit,
    onNameChange: (String) -> Unit,
    onCreate: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var swatch by remember { mutableStateOf(SWATCHES.first()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Who are you?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            if (state.error != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }

            Column(Modifier.height(280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.users.forEach { user ->
                    val selected = user.id == viewerId
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onChoose(user)
                                onDismiss()
                            },
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            PulseAvatar(name = user.name, colorHex = user.color, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(user.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                                    if (user.verified) {
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.Filled.Verified, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(13.dp))
                                    }
                                }
                                Text("@${user.handle.ifBlank { user.name.lowercase().replace(" ", "_") }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (selected) {
                                Text("You", style = MaterialTheme.typography.labelSmall, color = PulsePalette.Emerald, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Or create a new identity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.newIdentityName,
                    onValueChange = onNameChange,
                    placeholder = { Text("Display name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                TextButton(
                    onClick = { onCreate(swatch) },
                    enabled = state.newIdentityName.isNotBlank() && !state.creating,
                ) { Text(if (state.creating) "…" else "Create") }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                SWATCHES.forEach { hex ->
                    val color = PulsePalette.parse(hex) ?: PulsePalette.Emerald
                    Box(
                        Modifier
                            .padding(end = 10.dp)
                            .size(if (swatch == hex) 30.dp else 26.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.7f))))
                            .clickable { swatch = hex },
                    )
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** FlowRow without the ExperimentalLayoutApi opt-in churn at call sites. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}
