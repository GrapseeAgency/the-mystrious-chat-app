package app.pulse.feature.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulseMonoFamily
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import app.pulse.ui.isPulseDarkTheme
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
fun ProfileScreen(
    onEditProfile: () -> Unit = {},
    onOpenBlocked: () -> Unit = {},
    // R16 — web profile-tab.tsx:497-508 "Saved messages" row → the real
    // starred library (MainActivity routes this to the existing "saved" nav
    // destination — fetch / search / unsave / jump-to-message).
    onOpenSaved: () -> Unit = {},
    // R6 — M3: the durable sign-out — the app-level SessionViewModel
    // clears prefs + the encrypted session vault (ProfileViewModel's old
    // half-forget did NOT delete the vault, so the identity resurrected on
    // the next launch). MainActivity wires this to session.forgetViewer().
    onForgetViewer: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val viewerId by viewModel.viewerId.collectAsStateWithLifecycle()
    val viewerName by viewModel.viewerName.collectAsStateWithLifecycle()
    val fxMode by viewModel.fxMode.collectAsStateWithLifecycle()
    val dark by viewModel.darkOverride.collectAsStateWithLifecycle()
    val reduced by viewModel.reducedMotion.collectAsStateWithLifecycle()

    var identitySheet by remember { mutableStateOf(false) }
    // R6 — M3: the forget confirmation (iOS IdentityPickerSheet "Forget this
    // viewer" semantics — destructive, so it asks first).
    var forgetConfirm by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // R16 — web profile-tab.tsx:169 `iAmOnline` (onlineIds.has(me.id)) + the
    // full viewer row (name/handle/bio/color/status fields ride the SAME
    // users list the IdentitySheet already loads).
    val onlineIds by viewModel.onlineIds.collectAsStateWithLifecycle()
    val viewer = state.users.firstOrNull { it.id == viewerId }
    val iAmOnline = viewerId != null && viewerId in onlineIds
    val handle = viewer?.handle?.takeIf { it.isNotBlank() }

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

        // ── R35 Neo hero (web profile-tab.tsx:329-467): flat identity cover
        // with a static scanline texture + signal edge, then the overlapping
        // ringed avatar. Zero carnival blobs, zero blur, no animation, and
        // reduce-motion needs no special case (everything here is static).
        Box(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(heroGradient(viewer?.color)),
        ) {
            // Static top sheen (web :333-336 linear wash, fades by 60%).
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.16f),
                            0.6f to Color.Transparent,
                        ),
                    ),
            )
            // The scanline texture: 1px lines every 3dp, white 5%
            // (web .scan-fx::after repeating-linear-gradient). Cheap Canvas.
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val step = 3.dp.toPx()
                val line = 1.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.05f),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                        size = androidx.compose.ui.geometry.Size(size.width, line),
                    )
                    y += step
                }
            }
            // The signal line: hairline bright edge grounding the cover (:337-341).
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.8f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }

        // Overlapping avatar with the slim identity ring (web :346-359):
        // 84dp avatar, 44dp overlap, 2.5dp gradient ring + 2.5dp surface gap.
        val neoDark = isPulseDarkTheme()
        Box(
            Modifier
                .offset(y = (-44).dp)
                .size(94.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(94.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(heroColors(viewer?.color))),
            )
            Box(
                Modifier
                    .size(89.dp)
                    .clip(CircleShape)
                    .background(if (neoDark) PulsePalette.NeoSurface else Color.White),
            )
            PulseAvatar(
                name = viewerName ?: "You",
                colorHex = viewer?.color,
                size = 84.dp,
                online = iAmOnline,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Name 22sp bold, -0.02em tracking, with the quiet verified seal
        // tinted accent (web :362-375 PulseSeal).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = viewerName ?: "No identity",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.44).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (viewerId != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Verified,
                    contentDescription = "Registered member",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // @handle: tap to copy (or set), mono type inside a glass pill
        // (web :384-407 .glass-pill .stat-mono).
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                .border(1.dp, PulsePalette.Hairline, RoundedCornerShape(999.dp))
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (handle == null) {
                        onEditProfile()
                    } else {
                        copyText(context, "Pulse handle", "@$handle", "Handle copied")
                    }
                }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = if (handle != null) "@$handle" else "Set your handle",
                fontFamily = PulseMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Status line + bio, 13sp (web :410-433). The status glyph/text are
        // the user's own wire values, displayed verbatim, never decorated.
        if (viewerId != null) {
            val statusLine = listOfNotNull(
                viewer?.statusEmoji?.takeIf { it.isNotBlank() },
                viewer?.statusText?.takeIf { it.isNotBlank() },
            ).joinToString(" ")
            if (statusLine.isNotBlank()) {
                Text(
                    statusLine,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Text(
                text = viewer?.bio?.takeIf { it.isNotBlank() } ?: "No bio yet",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        // Quick actions: one signal pill, one ghost pill (web :436-467).
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onEditProfile()
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Edit profile",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                    .border(1.dp, PulsePalette.Hairline, RoundedCornerShape(999.dp))
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        shareProfile(context, viewerId, state.users)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Share",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // ── ONE flat stats row: mono numerals + hairline dividers, no heavy
        // cards, no counters (web profile-tab.tsx:471-489). Same loaders as
        // before (GET /api/users/{id}/stats + the wallet source); the Coins
        // cell stays tappable-to-retry when the wallet errored (the old
        // wallet card's affordance, folded into the instrument row).
        if (viewerId != null) {
            LaunchedEffect(viewerId) {
                viewModel.loadStats()
                viewModel.loadWallet()
            }
            val statsState by viewModel.stats.collectAsStateWithLifecycle()
            val stats = statsState.stats
            val coins by viewModel.wallet.collectAsStateWithLifecycle()
            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (neoDark) Color.White.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.55f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (neoDark) PulsePalette.Hairline else Color(0x99E4E4E7),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileStatCell(
                        label = "Messages",
                        value = stats?.messages?.toString() ?: if (statsState.loading) "·" else "-",
                        modifier = Modifier.weight(1f),
                    )
                    StatDivider()
                    ProfileStatCell(
                        label = "Rooms",
                        value = stats?.chats?.toString() ?: if (statsState.loading) "·" else "-",
                        modifier = Modifier.weight(1f),
                    )
                    StatDivider()
                    ProfileStatCell(
                        label = "Coins",
                        value = coins.coins?.toString() ?: if (coins.loading) "·" else "-",
                        accent = true,
                        onTap = if (coins.error) viewModel::loadWallet else null,
                        modifier = Modifier.weight(1f),
                    )
                    StatDivider()
                    ProfileStatCell(
                        label = "Since",
                        value = stats?.joinedAtIso?.let { memberSinceShort(it) }
                            ?: if (statsState.loading) "·" else "-",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ── Quiet hairline card: Saved messages + Copy account ID + identity
        // switch + Sign out (web :493-524 Saved/Account sections, one card).
        // Every previous entry point keeps working; blocked accounts live in
        // Settings privacy, same as the web.
        Spacer(Modifier.height(24.dp))
        QuietCard {
            if (viewerId != null) {
                QuietRow(
                    icon = Icons.Filled.Star,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "Saved messages",
                    subtitle = "Long-press any message in a chat, then Save",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenSaved()
                    },
                )
                QuietDivider()
            }
            QuietRow(
                icon = Icons.Filled.ContentCopy,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                title = "Copy account ID",
                subtitle = viewerId ?: "Pick an identity to copy its ID",
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (viewerId != null) {
                        copyText(context, "Pulse account ID", viewerId ?: "", "Account ID copied")
                    }
                },
            )
            QuietDivider()
            QuietRow(
                icon = Icons.Filled.SwapHoriz,
                iconTint = if (viewerId == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                title = if (viewerId == null) "Choose identity" else "Switch identity",
                subtitle = if (viewerId == null) "Pick who you are to light up Pulse" else "Choose or create who you are on this device",
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    identitySheet = true
                },
            )
            if (viewerId != null) {
                QuietDivider()
                // R6 - M3: the destructive confirm still gates the real forget.
                QuietRow(
                    icon = Icons.Filled.Logout,
                    iconTint = MaterialTheme.colorScheme.error,
                    title = "Sign out",
                    subtitle = "Clears the identity + stored session on this device",
                    destructive = true,
                    onClick = { forgetConfirm = true },
                )
            }
        }

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
                    Text("Check for updates", color = MaterialTheme.colorScheme.primary)
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
                    }) { Text("Test", color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setServerBase(serverField.trim().takeIf { it.isNotBlank() })
                    }) { Text("Save & use", color = MaterialTheme.colorScheme.primary) }
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
                    // R35 Neo: plain verdict text replaces the old glyph prefixes.
                    probe.ok == true -> Text(
                        "Reachable: ${probe.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    probe.ok == false -> Text(
                        "Not reachable: ${probe.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PulsePalette.Amber,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    // R6 — M3: the destructive confirm before the real forget.
    if (forgetConfirm) {
        AlertDialog(
            onDismissRequest = { forgetConfirm = false },
            title = { Text("Forget this viewer?") },
            text = {
                Text(
                    "Signs you out on this device and clears the stored session. " +
                        "You can pick (or create) an identity again from onboarding.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    forgetConfirm = false
                    onForgetViewer()
                }) {
                    Text("Forget", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { forgetConfirm = false }) { Text("Cancel") }
            },
        )
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

/**
 * R6 — BE8: profile share via the OS share sheet (web profile-tab.tsx:258-278
 * `shareProfile` parity): "Find me on Pulse — @handle". No handle yet → the
 * web's honest info toast; a failed chooser → "Could not share right now".
 */
internal fun shareProfile(
    context: android.content.Context,
    viewerId: String?,
    users: List<app.pulse.domain.model.User>,
) {
    val handle = users.firstOrNull { it.id == viewerId }?.handle
    if (handle.isNullOrBlank()) {
        Toast.makeText(context, "Claim a handle first — it is how people find you", Toast.LENGTH_SHORT).show()
        return
    }
    val text = "Find me on Pulse — @$handle"
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share your profile"))
    }.onFailure {
        Toast.makeText(context, "Could not share right now", Toast.LENGTH_SHORT).show()
    }
}

/**
 * R16 — web gradientFor (pulse-utils.ts:42-45 + AVATAR_GRADIENTS :31-40):
 * the profile hero cover derives from the identity color. Accepts BOTH the
 * web color NAMES (emerald/rose/amber/violet/teal/orange/pink/cyan) and the
 * raw hex the native onboarding creates (#10B981 …). Fallback: web default
 * emerald. The deep end darkens the start color (web's `-400 → -600` pair).
 */
internal fun heroGradient(color: String?): Brush {
    return Brush.linearGradient(heroColors(color))
}

/**
 * R35 Neo: the identity gradient as a color pair, shared by the hero cover
 * and the avatar identity ring so both read as one signal beam.
 */
internal fun heroColors(color: String?): List<Color> {
    val start = when (color?.trim()?.lowercase()) {
        "teal" -> Color(0xFF2DD4BF)
        "rose" -> Color(0xFFFB7185)
        "amber" -> Color(0xFFFBBF24)
        "violet" -> Color(0xFFA78BFA)
        "orange" -> Color(0xFFFB923C)
        "pink" -> Color(0xFFF472B6)
        "cyan" -> Color(0xFF22D3EE)
        "emerald" -> Color(0xFF34D399)
        else -> PulsePalette.parse(color) ?: Color(0xFF34D399)
    }
    val deep = Color(start.red * 0.62f, start.green * 0.68f, start.blue * 0.72f)
    return listOf(start, deep)
}

/**
 * R16 — clipboard helper for the profile tap-to-copy affordances (web
 * profile-tab.tsx copyHandle :243-255 / copyId :233-241 — same toasts).
 */
internal fun copyText(context: android.content.Context, label: String, text: String, toast: String) {
    runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "Clipboard is unavailable here", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * R35 Neo: one flat stat cell of the profile stats row (web StatTile):
 * mono numeral + small dim label, hairline-divided by [StatDivider].
 * [onTap] keeps the old wallet card's retry affordance on the Coins cell.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ProfileStatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            fontFamily = PulseMonoFamily,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Hairline vertical divider between the flat stat cells. */
@Composable
private fun StatDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(30.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

/** R35 Neo quiet card: flat hairline panel, no heavy surface/tonal build. */
@Composable
private fun QuietCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        border = androidx.compose.foundation.BorderStroke(1.dp, PulsePalette.Hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** One quiet row inside [QuietCard] (web ChevronRow: icon + title + hint). */
@Composable
private fun QuietRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Hairline divider between quiet rows. */
@Composable
private fun QuietDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

/**
 * R14 gap 7a — the web profile-tab memberSinceShort ("Sep 2025", month short
 * + year): null when the stats row carries no joinedAt timestamp.
 */
internal fun memberSinceShort(iso: String): String? = runCatching {
    val parsed = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(iso.take(19))
        ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso.take(10))
    parsed?.let { java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.US).format(it) }
}.getOrNull()

@Composable
private fun SettingCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        // R35 Neo: 24dp card radius + hairline border (locked spec).
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        border = androidx.compose.foundation.BorderStroke(1.dp, PulsePalette.Hairline),
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
