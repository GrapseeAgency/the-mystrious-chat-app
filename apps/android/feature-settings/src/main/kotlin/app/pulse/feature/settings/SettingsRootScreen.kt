package app.pulse.feature.settings

// ─────────────────────────────────────────────────────────────
// Wave 8 (master-spec §7 "Platform & hardening") — SettingsRootScreen
// + all nine sections, replacing the dead dock toast. Web truth:
// src/components/chat/settings-screen.tsx SECTION_MAP labels/captions.
// Every row is real: toggles PATCH /api/settings (optimistic local
// first), quiet hours is a local pulse.settings.v1 parity feature,
// drafts/outbox/footprint/probe are live reads of real state.
// ─────────────────────────────────────────────────────────────
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette
import app.pulse.ui.PulseWallpaper
import app.pulse.ui.update.LiveUpdater
import app.pulse.ui.update.UpdaterDetail

// verbatim web section registry (settings-screen.tsx SECTION_MAP)
private data class SectionDef(val id: String, val label: String, val caption: String, val icon: ImageVector)

private val SECTIONS = listOf(
    SectionDef("account", "Account", "Profile, handle and session", Icons.Filled.AccountCircle),
    SectionDef("appearance", "Appearance", "Theme languages, color mode, navigation", Icons.Filled.Palette),
    SectionDef("chat", "Chat", "Wallpaper, bubbles, drafts and outbox", Icons.Filled.Forum),
    SectionDef("notifications", "Notifications", "Sound, previews, quiet hours", Icons.Filled.Notifications),
    SectionDef("privacy", "Privacy & Security", "Read receipts and presence", Icons.Filled.VerifiedUser),
    SectionDef("realtime", "Real-time & Voice", "Live connection and voice rooms", Icons.Filled.Radar),
    SectionDef("accessibility", "Accessibility", "Motion and haptic feedback", Icons.Filled.Accessibility),
    SectionDef("data", "Data & Storage", "Footprint, local data, install", Icons.Filled.Storage),
    SectionDef("about", "About", "Version and project", Icons.Filled.Info),
)

private val GROUPS: List<Pair<String, List<String>>> = listOf(
    "Personal" to listOf("account", "appearance", "chat", "notifications"),
    "System" to listOf("privacy", "realtime", "accessibility"),
    "Data" to listOf("data"),
    "About" to listOf("about"),
)

/** Root — compact grouped section list (web parity: no search, no overlay). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootScreen(
    onBack: () -> Unit,
    onOpenSection: (String) -> Unit,
    onOpenBlocked: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefsOffline by viewModel.prefsOffline.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            if (prefsOffline) {
                Text(
                    "Offline — changes stay on this device until Pulse reconnects.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            GROUPS.forEach { (label, ids) ->
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 14.dp, bottom = 6.dp)
                        .semantics { heading() },
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Column {
                        ids.forEachIndexed { index, id ->
                            val def = SECTIONS.first { it.id == id }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenSection(id) }
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(def.icon, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(def.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Text(def.caption, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (index != ids.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) { content() }
    }
}

@Composable
private fun RowToggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SegPicker(label: String, options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (id, name) ->
            SegmentedButton(
                selected = value == id,
                onClick = { onPick(id) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(name, fontSize = 12.5.sp, maxLines = 1) }
        }
    }
}

// ── Account ──────────────────────────────────────────────────

@Composable
fun AccountSection(onBack: () -> Unit, onEditProfile: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val profile by viewModel.viewerProfile.collectAsStateWithLifecycle()
    val viewerId by viewModel.viewerId.collectAsStateWithLifecycle()
    val viewerName by viewModel.viewerName.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    SectionScaffold("Account", onBack) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PulseAvatar(name = profile?.name ?: viewerName ?: "?", colorHex = profile?.color, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // R14 gap 7b — status glyph rides the name line (web
                    // settings-screen.tsx:815-831) + honest member-since line.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile?.name ?: viewerName ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        val glyph = profile?.statusEmoji
                        if (!glyph.isNullOrBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            ) {
                                Text(glyph, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Text(
                        profile?.handle?.let { "@$it" } ?: "No handle yet",
                        fontSize = 12.5.sp,
                        color = PulsePalette.Emerald,
                    )
                    if (viewerId != null) {
                        memberSinceLine(profile?.createdAtIso)?.let { formatted ->
                            Text(
                                "Member since $formatted",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Pulse user ID", viewerId ?: ""))
                    Toast.makeText(context, "User ID copied", Toast.LENGTH_SHORT).show()
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Copy user ID", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                Text("Share this with tools that troubleshoot your account.", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onEditProfile)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text("Edit profile", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
        }
        // R14 gap 4 — the session-scope row (web settings-screen.tsx:858-863):
        // the session lives in the device's encrypted SecureSessionStore vault
        // ("session.vault"), so the honest Android copy says device — not tab.
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Session scope", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Signed in on this device only (encrypted session vault) — other devices keep their own sessions.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (viewerId != null) PulsePalette.Emerald.copy(alpha = 0.14f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f),
            ) {
                Text(
                    if (viewerId != null) "Active" else "None",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (viewerId != null) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ── Appearance ───────────────────────────────────────────────

private val WALLPAPERS = PulseWallpaper.TOKENS

/** R2-A item 11 — web WEBGL_MODES as two legible 3-wide picker rows. */
private val FX_MODE_ROW_1 = listOf("aurora" to "Aurora", "caustics" to "Caustics", "mesh" to "Mesh")
private val FX_MODE_ROW_2 = listOf("stars" to "Stars", "liquid" to "Liquid", "off" to "Off")

/** R2-C item 3 — the five design languages (web UI_THEMES) as picker rows. */
private val UI_THEME_ROW_1 = listOf(
    "glass" to "Glass",
    "kinetic" to "Kinetic",
    "minimal" to "Minimal",
)
private val UI_THEME_ROW_2 = listOf(
    "dynamic" to "Dynamic",
    "aero" to "Aero",
)

/**
 * R4-B item 3 / R14 — ALL 13 navigation architectures with the web
 * nav-registry.ts:51-63 label + hint strings VERBATIM (floating-dock /
 * command-bar / radial / gesture / contextual-dock now ship as honest
 * mobile adaptations — see the MainActivity renderer comments).
 */
private val NAV_STYLE_CARDS = listOf(
    Triple(app.pulse.protocol.PulseNavStyle.CAPSULE, "Floating Capsule", "Detached glass capsule dock — the default"),
    Triple(app.pulse.protocol.PulseNavStyle.FLOATING_TOP, "Floating Top Nav", "Capsule bar floating beneath the top edge"),
    Triple(app.pulse.protocol.PulseNavStyle.FLOATING_DOCK, "Floating Dock", "Desktop-style dock with magnifying icons"),
    Triple(app.pulse.protocol.PulseNavStyle.PILL, "Pill Navigation", "Single segmented pill with sliding fill"),
    Triple(app.pulse.protocol.PulseNavStyle.BOTTOM_BAR, "Bottom Bar", "Classic edge-to-edge bottom bar"),
    Triple(app.pulse.protocol.PulseNavStyle.TAB_BAR, "Tab Bar", "iOS-style tab bar with tinted squircles"),
    Triple(app.pulse.protocol.PulseNavStyle.FLOATING_TAB_BAR, "Floating Tab Bar", "Detached card, elevated active tab"),
    Triple(app.pulse.protocol.PulseNavStyle.COMMAND_BAR, "Command Bar", "Compact text command strip with search"),
    Triple(app.pulse.protocol.PulseNavStyle.RAIL, "Navigation Rail", "Persistent vertical side rail"),
    Triple(app.pulse.protocol.PulseNavStyle.ISLAND, "Island Navigation", "Dynamic-island pill that expands on tap"),
    Triple(app.pulse.protocol.PulseNavStyle.RADIAL, "Radial Navigation", "FAB fanning destinations in an arc"),
    Triple(app.pulse.protocol.PulseNavStyle.GESTURE, "Gesture Navigation", "Edge swipes + gesture pill quick switcher"),
    Triple(app.pulse.protocol.PulseNavStyle.CONTEXTUAL_DOCK, "Contextual Dock", "Dock that adapts to the active tab"),
)

@Composable
fun AppearanceSection(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.pulsePrefs.collectAsStateWithLifecycle()
    val darkOverride by viewModel.darkOverride.collectAsStateWithLifecycle()
    val fxMode by viewModel.fxMode.collectAsStateWithLifecycle()
    // R2-C item 3 — the selected design language.
    val uiTheme by viewModel.uiTheme.collectAsStateWithLifecycle()
    val uiThemeMeta = app.pulse.ui.PulseUiTheme.fromId(uiTheme)
    // R4-B item 3 — the selected navigation architecture.
    val navStyle by viewModel.navStyle.collectAsStateWithLifecycle()
    SectionScaffold("Appearance", onBack) {
        // R2-C item 3 — the design-language picker (web ui-theme.ts five
        // languages; value strings ride the SAME `pulse.uiTheme.v2` key the
        // web persists, so a native pick and a web pick stay in step locally).
        Text("Design language", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            UI_THEME_ROW_1.forEachIndexed { index, (id, name) ->
                SegmentedButton(
                    selected = uiThemeMeta.id == id,
                    onClick = { viewModel.setUiTheme(id) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = UI_THEME_ROW_1.size),
                ) { Text(name, fontSize = 12.5.sp, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            UI_THEME_ROW_2.forEachIndexed { index, (id, name) ->
                SegmentedButton(
                    selected = uiThemeMeta.id == id,
                    onClick = { viewModel.setUiTheme(id) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = UI_THEME_ROW_2.size),
                ) { Text(name, fontSize = 12.5.sp, maxLines = 1) }
            }
        }
        Text(
            uiThemeMeta.label + " — " + uiThemeMeta.detail,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        // R4-B item 3 / R14 — the navigation-architecture picker (web
        // nav-registry.ts labels/hints verbatim, now ALL 13 styles): 2-column
        // card grid, selection ring like the wallpaper swatches. The value
        // strings ride the SAME `pulse.navStyle.v2` key the web persists.
        val haptics = LocalHapticFeedback.current
        Text(
            "Navigation style",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        )
        NAV_STYLE_CARDS.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { (style, label, hint) ->
                    val selected = navStyle == style
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) PulsePalette.Emerald else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                RoundedCornerShape(14.dp),
                            )
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.setNavStyle(style)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .semantics {
                                contentDescription = "Navigation style: $label"
                                if (selected) stateDescription = "Selected"
                            },
                    ) {
                        Text(
                            label,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            hint,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        SegPicker("Color mode", listOf("system" to "System", "dark" to "Dark", "light" to "Light"), darkOverride, viewModel::setDarkOverride)
        Text("Wallpaper", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            WALLPAPERS.forEach { (id, name) ->
                val selected = prefs.wallpaper == id
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { viewModel.setWallpaper(id) }) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(PulseWallpaper.brush(id) ?: Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)))
                            .then(if (selected) Modifier.border(2.dp, PulsePalette.Emerald, CircleShape) else Modifier),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(name, fontSize = 10.5.sp, color = if (selected) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // R2-A item 11 — the FULL six-mode set (web WEBGL_MODES: off · aurora ·
        // caustics · mesh · stars · liquid); two 3-wide rows keep the labels legible.
        Text("Ambient FX", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            FX_MODE_ROW_1.forEachIndexed { index, (id, name) ->
                SegmentedButton(
                    selected = fxMode == id,
                    onClick = { viewModel.setFxMode(id) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = FX_MODE_ROW_1.size),
                ) { Text(name, fontSize = 12.5.sp, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            FX_MODE_ROW_2.forEachIndexed { index, (id, name) ->
                SegmentedButton(
                    selected = fxMode == id,
                    onClick = { viewModel.setFxMode(id) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = FX_MODE_ROW_2.size),
                ) { Text(name, fontSize = 12.5.sp, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ── Chat ─────────────────────────────────────────────────────

@Composable
fun ChatSection(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.pulsePrefs.collectAsStateWithLifecycle()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val outbox by viewModel.outbox.collectAsStateWithLifecycle()
    // R14 gap 10 — the default list filter (web settings-screen.tsx:1065-1083
    // "Default list filter" picker): same device key the chats chips write.
    val listFilter by viewModel.chatsListFilter.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    SectionScaffold("Chat", onBack) {
        SegPicker(
            "Default list filter",
            listOf("all" to "All", "unread" to "Unread", "groups" to "Groups"),
            listFilter,
            viewModel::setChatsListFilter,
        )
        SegPicker(
            "Bubble corners",
            listOf("md" to "Rounded", "lg" to "Soft", "pill" to "Pill"),
            prefs.bubbleRadius ?: "lg",
            viewModel::setBubbleRadius,
        )
        SegPicker(
            "Density",
            listOf("cozy" to "Cozy", "compact" to "Compact"),
            prefs.density ?: "cozy",
            viewModel::setDensity,
        )
        Text("Drafts & Outbox", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        Text("Drafts live on this device; outbox messages flush when Pulse reconnects.", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (drafts.isEmpty() && outbox.isEmpty()) {
            Text("Nothing queued — all clear.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        }
        if (drafts.isNotEmpty()) {
            Text("Drafts · ${drafts.size}", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.clearDrafts()
                }) { Text("Clear all drafts") }
            }
            drafts.forEach { draft ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(draft.title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(draft.text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = { viewModel.deleteDraft(draft.conversationId) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete draft in ${draft.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (outbox.isNotEmpty()) {
            Text("Outbox · ${outbox.size}", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.clearOutbox()
                }) { Text("Clear all") }
            }
            outbox.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("${row.content} · attempt ${row.attempts}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    IconButton(onClick = { viewModel.deleteOutboxEntry(row.clientId) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Discard queued message", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ── Notifications ────────────────────────────────────────────

@Composable
fun NotificationsSection(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.pulsePrefs.collectAsStateWithLifecycle()
    // R14 gap 1 — the device-local ding master (web "Incoming sound" row) and
    // the haptics toggle the Preview alert honors, both from pulse.settings.v1.
    val soundOn by viewModel.soundOn.collectAsStateWithLifecycle()
    val hapticsOn by viewModel.hapticsOn.collectAsStateWithLifecycle()
    val quietOn by viewModel.quietHoursOn.collectAsStateWithLifecycle()
    val quietStart by viewModel.quietStart.collectAsStateWithLifecycle()
    val quietEnd by viewModel.quietEnd.collectAsStateWithLifecycle()
    val quietNow = viewModel.isQuietNow()
    val pushStatus by viewModel.pushStatus.collectAsStateWithLifecycle()
    // re-read the device snapshot on every section entry (cheap; the snapshot
    // only changes when the :app push wiring itself transitions)
    LaunchedEffect(Unit) { viewModel.refreshPushStatus() }
    SectionScaffold("Notifications", onBack) {
        RowToggle("Incoming sound", "Master ding gate on this device — the web soundOn toggle.", soundOn, viewModel::setSoundOn)
        RowToggle("Show message previews", "Message text in notification-style toasts.", prefs.notifPreviews == true, viewModel::setNotifPreviews)
        RowToggle("Play a soft pop", "Per-account pop sound for incoming messages.", prefs.notifSound == true, viewModel::setNotifSound)
        RowToggle("Vibrate", "Where the device supports it.", prefs.notifVibrate == true, viewModel::setNotifVibrate)
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        RowToggle("Quiet hours", "Silence pings and haptics inside the window.", quietOn, viewModel::setQuietHoursOn)
        if (quietOn) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                OutlinedField("From", quietStart, viewModel::setQuietStart, Modifier.weight(1f))
                OutlinedField("Until", quietEnd, viewModel::setQuietEnd, Modifier.weight(1f))
            }
            Text(
                if (quietNow) "Quiet hours are active right now." else "Quiet hours are set but not active right now.",
                fontSize = 12.sp,
                color = if (quietNow) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        // R9 — honest device state (web parity: settings-screen.tsx Remote-push
        // toggle row; iOS parity: SettingsView status row). Device state, not a
        // preference — an unarmed build says Off instead of pretending.
        RemotePushStatusRow(pushStatus, onResync = { viewModel.resyncPush() })
        // R14 gap 2 — the "Preview alert" test row (web settings-screen.tsx
        // Test group :1258-1275): plays the REAL incoming ding through
        // IncomingAttention — honoring soundOn, haptics and quiet hours.
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Text("Test", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        Button(
            // R14 gap 2 — the REAL :app IncomingAttention path through the
            // domain seam (feature modules cannot see app-module code).
            onClick = { app.pulse.domain.notify.PreviewAlertHook.playPreview(hapticsOn) },
            enabled = !quietNow,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Preview alert", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "Plays the real incoming ding and fires a haptic buzz, honoring the toggles above.",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun RemotePushStatusRow(
    status: app.pulse.domain.push.PulsePushStatus.Snapshot,
    onResync: () -> Unit,
) {
    val (badge, note, on) = when {
        !status.armed -> Triple("Off", "This build carries no push credentials — delivery stays disabled.", false)
        !status.hasToken -> Triple("Armed", "Credentials present — the device registers on the next sign-in.", false)
        !status.viewerBound -> Triple("Token ready", "Waiting for sign-in to bind push to your account.", false)
        else -> Triple("On", "This device is registered for push delivery.", true)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Remote push", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
            Text(note, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            badge,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (on) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status.armed) {
            Text(
                "Re-check",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = PulsePalette.Emerald,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable(onClick = onResync),
            )
        }
    }
}

@Composable
private fun OutlinedField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    // local typing state — commit ONLY when a full valid HH:MM lands
    var text by remember(value) { mutableStateOf(value) }
    val valid = Regex("^\\d{1,2}:\\d{2}$").matches(text)
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.take(5)
            if (Regex("^\\d{1,2}:\\d{2}$").matches(text)) onChange(text)
        },
        label = { Text(label) },
        supportingText = {
            Text(
                if (valid) "HH:MM" else "Use HH:MM — e.g. 22:00",
                color = if (Regex("^\\d{1,2}:\\d{2}$").matches(value)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        },
        singleLine = true,
        modifier = modifier,
    )
}

// ── Privacy & Security ───────────────────────────────────────

@Composable
fun PrivacySection(onBack: () -> Unit, onOpenBlocked: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.pulsePrefs.collectAsStateWithLifecycle()
    val appLockOn by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    // R10-a — read ONCE in composable context; the App-lock callback below is
    // a plain (Boolean) -> Unit, so it can't touch LocalContext itself.
    val context = LocalContext.current
    SectionScaffold("Privacy & Security", onBack) {
        RowToggle("Last seen & online", "Everyone can see when you were last active. Server-enforced.", prefs.lastSeenVisible == true, viewModel::setLastSeenVisible)
        RowToggle("Read receipts", "Send and request read receipts.", prefs.readReceipts == true, viewModel::setReadReceipts)
        RowToggle("Typing indicator", "Broadcast when you type. Server-enforced.", prefs.typingVisible == true, viewModel::setTypingVisible)
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        // R10-a — biometric App lock (device-local). Turning it ON runs ONE
        // confirmation BiometricPrompt right here: only a device that can
        // actually verify keeps the flag — anything else is an honest revert.
        RowToggle(
            "App lock",
            "Lock Pulse behind biometrics or your device screen lock.",
            appLockOn,
        ) { want ->
            val activity = context as? FragmentActivity
            when {
                !want -> viewModel.setAppLockEnabled(false)
                activity == null -> Toast.makeText(
                    context,
                    "App lock needs the app's main screen — try again from a chat tab.",
                    Toast.LENGTH_SHORT,
                ).show()
                else -> confirmAppLock(activity) { viewModel.setAppLockEnabled(true) }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenBlocked)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Blocked accounts", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                Text("Blocked accounts cannot message you in direct chats.", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ── Real-time & Voice ────────────────────────────────────────

@Composable
fun RealtimeSection(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val probe by viewModel.probe.collectAsStateWithLifecycle()
    // R7 item 6 — web ctx.onlineCount (settings-screen.tsx:1423): the live
    // presence flow (repo onlineIds — socket Joined/PresenceSnapshot truth,
    // PulseRepositoryImpl.observePresence) reduced to a count.
    val onlineCount by viewModel.onlineCount.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.probeGateway() }
    SectionScaffold("Real-time & Voice", onBack) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, tint = if (connected) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (connected) "Realtime connected" else "Realtime offline — polling fallback", fontSize = 13.5.sp)
                }
                Text("Gateway · ${viewModel.gatewayHost}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Radar, contentDescription = null, tint = if (probe.ok == true) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            when {
                                probe.running -> "Probing gateway…"
                                probe.ok == true -> "Gateway probe · ${probe.ms} ms"
                                probe.ok == false -> "Gateway probe failed · ${probe.detail}"
                                else -> "Probe not run"
                            },
                            fontSize = 13.sp,
                        )
                        if (probe.ok == false) {
                            Text("Voice rooms and live typing need the gateway.", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.probeGateway() }, enabled = !probe.running) { Text("Test") }
                }
            }
        }
        // R7 item 6 — web StaticRow parity (settings-screen.tsx:1419-1424):
        // "People online now" / "Live presence snapshot from the socket
        // server." with the count in a zinc info-tone StatusBadge pill.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("People online now", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Live presence snapshot from the socket server.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f),
            ) {
                Text(
                    "$onlineCount",
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // R14 gap 5 — the voice-rooms info row (web settings-screen.tsx
        // :1495-1500 StaticRow, caption verbatim).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Voice rooms", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Studio capture with echo cancellation, noise suppression and auto gain. Quality presets are not configurable yet.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ── Accessibility ────────────────────────────────────────────

@Composable
fun AccessibilitySection(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val reducedMotion by viewModel.reducedMotion.collectAsStateWithLifecycle()
    val hapticsOn by viewModel.hapticsOn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val systemAnimationsOff = remember {
        // honor-OS pass: system "Remove animations" = animator scale 0
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
    SectionScaffold("Accessibility", onBack) {
        RowToggle(
            "Reduce motion",
            if (systemAnimationsOff) {
                "On — your system \"Remove animations\" setting is honored."
            } else {
                "Less parallax and ambient movement app-wide."
            },
            reducedMotion || systemAnimationsOff,
        ) { viewModel.setReducedMotion(it) }
        RowToggle("Haptic feedback", "Vibration on confirmations and celebrations.", hapticsOn, viewModel::setHapticsOn)
        Spacer(Modifier.height(20.dp))
    }
}

// ── Data & Storage ───────────────────────────────────────────

@Composable
fun DataSection(onBack: () -> Unit, onOpenHub: (() -> Unit)? = null, viewModel: SettingsViewModel = hiltViewModel()) {
    val footprint by viewModel.footprint.collectAsStateWithLifecycle()
    val footprintStats by viewModel.footprintStats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        viewModel.refreshFootprint()
        viewModel.refreshFootprintStats()
    }
    SectionScaffold("Data & Storage", onBack) {
        // ── R5-B ITEM 4 — "Your footprint" live tiles (web settings-screen.tsx
        // :1496-1571): messages / photos / voice notes / chats / groups / days
        // — the same stats route the user page consumes, evaluated for the
        // VIEWER. Loading skeletons + honest failure with a retry. ──
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your footprint", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Live counts straight from the Pulse database.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { viewModel.refreshFootprintStats() },
                        modifier = Modifier.semantics { contentDescription = "Refresh stats" },
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val fs = footprintStats
                when {
                    fs.error != null && fs.stats == null -> Column {
                        Text(
                            fs.error ?: "Couldn't load your stats.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { viewModel.refreshFootprintStats() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Try again")
                        }
                    }
                    else -> {
                        val stats = fs.stats
                        val tiles = listOf(
                            "Messages sent" to (stats?.messages ?: 0),
                            "Photos" to (stats?.photos ?: 0),
                            "Voice notes" to (stats?.voiceNotes ?: 0),
                            "Chats" to (stats?.chats ?: 0),
                            "Groups" to (stats?.groups ?: 0),
                            "Days active" to (stats?.days ?: 0),
                        )
                        if (fs.loading && stats == null) {
                            // loading — 6 skeleton tiles in the same 3-col grid
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                repeat(2) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        repeat(3) {
                                            Box(
                                                Modifier
                                                    .weight(1f)
                                                    .height(64.dp)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                tiles.chunked(3).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        row.forEach { (label, value) ->
                                            Surface(
                                                Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .semantics { contentDescription = "$label: $value" },
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                            ) {
                                                Column(Modifier.padding(10.dp)) {
                                                    Text(
                                                        if (fs.loading) "…" else value.toString(),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 16.sp,
                                                    )
                                                    Text(
                                                        label,
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                        if (row.size < 3) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Footprint · ${formatBytes(footprint.totalBytes)}", fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                Text("Database · ${formatBytes(footprint.databaseBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Image cache · ${formatBytes(footprint.imageCacheBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.clearImageCache { Toast.makeText(context, "Image cache cleared", Toast.LENGTH_SHORT).show() }
        }) { Text("Clear image cache") }
        Text("App updates", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                UpdaterDetail()
                TextButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { LiveUpdater.syncFrom(context, force = true) }
                }) { Text("Check for updates", color = PulsePalette.Emerald) }
            }
        }
        // R14 gap 6 — "The Hub" explore row (web settings-screen.tsx:1676-1686):
        // lands on the Hub tab through the shell's switchTab (which pops the
        // settings back stack to the start destination — the sheet closes).
        if (onOpenHub != null) {
            Text("Explore", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenHub)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("The Hub", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                    Text("Wallet · Tasks · Market · Swap · Apps · Logs", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Open",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulsePalette.Emerald,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

/**
 * R14 gap 7b — the web formatMemberSince (pulse-utils.ts:280): "August 2026"
 * — null when the profile row carries no birth timestamp.
 */
internal fun memberSinceLine(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        val parsed = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(iso.take(19))
            ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso.take(10))
        parsed?.let {
            java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US).format(it)
        }
    }.getOrNull()
}

// ── About ────────────────────────────────────────────────────

@Composable
fun AboutSection(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val pm = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
    // R14 gap 3 — the Realtime row carries the LIVE socket truth (the same
    // repo.observeConnected flow the Real-time section header uses).
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    SectionScaffold("About", onBack) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pulse", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Real-time chat with a built-in economy", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("versionName ${pm.versionName} · versionCode ${pm.longVersionCode}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Native Android build — Kotlin + Compose.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AboutInfoRow("Version", "${pm.versionName} · Stable")
        AboutInfoRow("Framework", "Kotlin + Jetpack Compose (native Android)")
        AboutInfoRow(
            "Realtime",
            if (connected) "socket.io relay — connected live" else "socket.io relay — offline, polling fallback",
            live = connected,
        )
        AboutInfoRow("Data", "Prisma + SQLite · zero mock data")
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { uri.openUri("https://github.com/GrapseeAgency/the-mystrious-chat-app") }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("GitHub repository", fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                Text("GrapseeAgency/the-mystrious-chat-app", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // R14 gap 3 — the footer (web settings-screen.tsx:1755-1763).
        Column(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(16.dp))
            Text("Made with Pulse", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Text(
                "Version ${pm.versionName} · chats, hub economy and settings sync live",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** R14 gap 3 — one static Build row of the About section. */
@Composable
private fun AboutInfoRow(label: String, value: String, live: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(88.dp))
        Text(
            value,
            fontSize = 12.sp,
            color = if (live) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * R10-a — the App lock enable-confirmation: ONE BiometricPrompt runs right
 * now. Availability first — a device with no biometrics/screen lock gets the
 * honest toast and the toggle stays off (the caller never persists). Success
 * is the only path that persists the flag.
 */
private fun confirmAppLock(activity: FragmentActivity, onConfirmed: () -> Unit) {
    val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(authenticators) !=
        BiometricManager.BIOMETRIC_SUCCESS
    ) {
        Toast.makeText(
            activity,
            "No biometrics or screen lock set on this device",
            Toast.LENGTH_SHORT,
        ).show()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onConfirmed()
            }
            // Failure / dismissal persist NOTHING — the toggle stays off and
            // the user can try again. No silent half-enabled state.
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Confirm App lock")
        .setSubtitle("Verify it's you to require unlock when Pulse opens")
        .setAllowedAuthenticators(authenticators)
        .build()
    prompt.authenticate(info)
}
