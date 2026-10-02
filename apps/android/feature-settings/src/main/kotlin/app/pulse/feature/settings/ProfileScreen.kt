package app.pulse.feature.settings

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import app.pulse.core.PulseEndpoints
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.core.PulseEndpoints
import app.pulse.ui.EmberGlassButton
import app.pulse.ui.EmberPalette
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulseIcons
import app.pulse.ui.PulseMonoFamily
import app.pulse.ui.PulsePalette
import app.pulse.ui.LocalPulseUiTheme
import app.pulse.ui.emberBackdrop
import app.pulse.ui.pulseTabBackdrop
import app.pulse.ui.emberGlass
import app.pulse.ui.update.LiveUpdater
import app.pulse.ui.update.UpdaterDetail
import coil.compose.AsyncImage
import java.io.File
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
 * Profile tab - identity management (the native onboarding parity surface),
 * appearance (dark override + ambient FX picker - the WebGL modes reborn as
 * AGSL shaders), motion respect, and honest about-notes.
 *
 * EMB: the surface sits on the warm ember backdrop - glass kebab chrome up
 * top (hosting the existing profile actions), a centered 96dp identity
 * avatar with the ember presence dot, one row of three stat cards, and an
 * ember account card. Every loader, handler and dialog is untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onEditProfile: () -> Unit = {},
    onOpenBlocked: () -> Unit = {},
    // R39/R50-c - the profile three-dot menu hosts the Hub AND Settings
    // next to the existing actions (web ProfileMoreMenu parity).
    onOpenHub: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    // R16 - web profile-tab.tsx:497-508 "Saved messages" row -> the real
    // starred library (MainActivity routes this to the existing "saved" nav
    // destination - fetch / search / unsave / jump-to-message).
    onOpenSaved: () -> Unit = {},
    // R6 - M3: the durable sign-out - the app-level SessionViewModel
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
    var kebabMenu by remember { mutableStateOf(false) }
    // R6 - M3: the forget confirmation (iOS IdentityPickerSheet "Forget this
    // viewer" semantics - destructive, so it asks first).
    var forgetConfirm by remember { mutableStateOf(false) }
    val haptics = app.pulse.ui.rememberGatedHaptics()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // R16 - web profile-tab.tsx:169 `iAmOnline` (onlineIds.has(me.id)) + the
    // full viewer row (name/handle/bio/color/status fields ride the SAME
    // users list the IdentitySheet already loads).
    val onlineIds by viewModel.onlineIds.collectAsStateWithLifecycle()
    val viewer = state.users.firstOrNull { it.id == viewerId }
    val iAmOnline = viewerId != null && viewerId in onlineIds
    val handle = viewer?.handle?.takeIf { it.isNotBlank() }

    Column(
        Modifier
            .fillMaxSize()
            .pulseTabBackdrop(LocalPulseUiTheme.current)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // EMB top chrome: this tab is a root destination with no back stack,
        // so there is no back control; the kebab glass button hosts the
        // EXISTING profile actions (edit / saved / identity switch).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box {
                EmberGlassButton(
                    icon = PulseIcons.KebabVertical,
                    label = "More actions",
                    onClick = { kebabMenu = true },
                )
                DropdownMenu(
                    expanded = kebabMenu,
                    onDismissRequest = { kebabMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Hub", color = Color.White) },
                        onClick = {
                            kebabMenu = false
                            onOpenHub()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings", color = Color.White) },
                        onClick = {
                            kebabMenu = false
                            onOpenSettings()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit profile", color = Color.White) },
                        onClick = {
                            kebabMenu = false
                            onEditProfile()
                        },
                    )
                    // R50-c - Hub and Settings join the kebab (web parity;
                    // the user audit: "no hub and settings on the three dots").
                    DropdownMenuItem(
                        text = { Text("Hub", color = Color.White) },
                        onClick = {
                            kebabMenu = false
                            onOpenHub()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings", color = Color.White) },
                        onClick = {
                            kebabMenu = false
                            onOpenSettings()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Saved messages", color = Color.White) },
                        onClick = {
                            kebabMenu = false
                            onOpenSaved()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Switch identity", color = Color.White) },
                        onClick = {
                            kebabMenu = false
                            identitySheet = true
                        },
                    )
                }
            }
        }

        // R39 - profile cover picture (web parity): the picked cover renders
        // as the hero banner; a server path wins, the on-device copy is the
        // offline fallback. No cover set = the ember field shows as before.
        val coverPath = viewer?.coverImage
        val localCover = remember { File(context.filesDir, "pulse_cover.jpg") }
        if (coverPath != null || localCover.exists()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, EmberPalette.Hairline, RoundedCornerShape(24.dp)),
            ) {
                if (coverPath != null) {
                    AsyncImage(
                        model = PulseEndpoints.http(coverPath),
                        contentDescription = "Your cover picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AsyncImage(
                        model = localCover,
                        contentDescription = "Your cover picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f)),
                            ),
                        ),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(2.dp))

        // R50-b - the profile cover: a real uploaded background photo rides
        // ABOVE the identity block, melting into the ember backdrop through a
        // bottom fade. No cover set = the clean ember stage (unchanged).
        val coverPath = viewer?.cover
        if (!coverPath.isNullOrBlank() && coverPath.startsWith("/api/uploads/")) {
            AsyncImage(
                model = PulseEndpoints.http(coverPath),
                contentDescription = "Profile cover photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .drawBehind {
                        // ember fade so the band melts into the backdrop
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                1f to Color(0xFF1A0F0A).copy(alpha = 0.85f),
                            ),
                        )
                    },
            )
            Spacer(Modifier.height(10.dp))
        }

        // EMB identity block: centered 96dp avatar; the presence dot rides
        // bottom-right with a 2.5dp BackdropBase ground ring (the old emerald
        // dot inside PulseAvatar is bypassed so the ring melts into the
        // ember ground).
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp)) {
                PulseAvatar(
                    name = viewerName ?: "You",
                    colorHex = viewer?.color,
                    size = 96.dp,
                )
                if (iAmOnline) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(17.dp)
                            .clip(CircleShape)
                            .background(EmberPalette.BackdropBase)
                            .padding(2.5.dp)
                            .clip(CircleShape)
                            .background(EmberPalette.Online),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Name 20sp bold centered, with the quiet verified seal (same a11y
        // label as before) now tinted ember online.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = viewerName ?: "No identity",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (viewerId != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    PulseIcons.Check,
                    contentDescription = "Registered member",
                    tint = EmberPalette.Online,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // The mono handle chip, centered: tap to copy (or set). 13sp white
        // 55% per the Ember reference (web .stat-mono handle line).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(EmberPalette.ChipFill)
                    .border(1.dp, EmberPalette.Hairline, RoundedCornerShape(999.dp))
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (handle == null) {
                            onEditProfile()
                        } else {
                            copyText(context, "Pulse handle", "@$handle", "Handle copied")
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (handle != null) "@$handle" else "Set your handle",
                    fontFamily = PulseMonoFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Status line + bio, 13sp, centered on the ember ground. The status
        // glyph/text are the user's own wire values, displayed verbatim.
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
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }
            Text(
                text = viewer?.bio?.takeIf { it.isNotBlank() } ?: "No bio yet",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        // Quick actions: the ember gradient edit pill + the glass share pill
        // (same handlers as before; ink on the gradient for contrast).
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Brush.linearGradient(EmberPalette.Gradient))
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onEditProfile()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            PulseIcons.Pencil,
                            contentDescription = null,
                            tint = Color(0xFF1C1410),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Edit profile",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1C1410),
                        )
                    }
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(EmberPalette.GlassFill)
                        .border(1.dp, EmberPalette.GlassBorder, RoundedCornerShape(999.dp))
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            shareProfile(context, viewerId, state.users)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            PulseIcons.PaperPlane,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Share",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // EMB: ONE row of three equal stat cards, fed by the SAME loaders as
        // before (GET /api/users/{id}/stats + the wallet source). The Coins
        // card stays tappable-to-retry when the wallet errored.
        if (viewerId != null) {
            LaunchedEffect(viewerId) {
                viewModel.loadStats()
                viewModel.loadWallet()
            }
            val statsState by viewModel.stats.collectAsStateWithLifecycle()
            val stats = statsState.stats
            val coins by viewModel.wallet.collectAsStateWithLifecycle()
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfileStatCard(
                    label = "Messages",
                    value = stats?.messages?.toString() ?: if (statsState.loading) "·" else "-",
                    modifier = Modifier.weight(1f),
                )
                ProfileStatCard(
                    label = "Rooms",
                    value = stats?.chats?.toString() ?: if (statsState.loading) "·" else "-",
                    modifier = Modifier.weight(1f),
                )
                ProfileStatCard(
                    label = "Coins",
                    value = coins.coins?.toString() ?: if (coins.loading) "·" else "-",
                    onTap = if (coins.error) viewModel::loadWallet else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // EMB account card: Saved messages + Copy account ID + identity
        // switch + Sign out (web :493-524 Saved/Account sections). Every
        // previous entry point keeps working; the destructive row still
        // opens the forget confirmation. Blocked accounts live in Settings
        // privacy, same as the web.
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .emberGlass(RoundedCornerShape(16.dp)),
        ) {
            if (viewerId != null) {
                AccountRow(
                    icon = PulseIcons.Bookmark,
                    label = "Saved messages",
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenSaved()
                    },
                )
                AccountDivider()
            }
            AccountRow(
                icon = PulseIcons.Copy,
                label = "Copy account ID",
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (viewerId != null) {
                        copyText(context, "Pulse account ID", viewerId ?: "", "Account ID copied")
                    }
                },
            )
            AccountDivider()
            AccountRow(
                icon = PulseIcons.Users,
                label = if (viewerId == null) "Choose identity" else "Switch identity",
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    identitySheet = true
                },
            )
            if (viewerId != null) {
                AccountDivider()
                // R6 - M3: the destructive confirm still gates the real forget.
                AccountRow(
                    icon = PulseIcons.Logout,
                    label = "Sign out",
                    destructive = true,
                    showChevron = false,
                    onClick = { forgetConfirm = true },
                )
            }
        }

        SectionHeader(PulseIcons.Sparkle, "Appearance")
        SettingCard {
            Column {
                Text("Mode", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("system" to "Auto", "light" to "Light", "dark" to "Dark").forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = dark == value,
                            onClick = { viewModel.setDarkOverride(value) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                            colors = emberSegmentedColors(),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Ambient field (native shaders)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(
                    "aurora · caustics · mesh · stars · liquid - the web's WebGL modes ported to AGSL",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(8.dp))
                FlowRowCompat {
                    FX_OPTIONS.forEach { (id, label) ->
                        val selected = fxMode == id
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (selected) EmberPalette.Deep else EmberPalette.ChipFill,
                            modifier = Modifier
                                .padding(end = 8.dp, bottom = 8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { viewModel.setFxMode(id) },
                        ) {
                            Text(
                                label,
                                color = if (selected) Color(0xFF1C1410) else Color.White.copy(alpha = 0.55f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader(PulseIcons.Waveform, "Motion")
        SettingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reduce motion", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(
                        "Static ambient frame, no particle bursts",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }
                Switch(
                    checked = reduced,
                    onCheckedChange = { viewModel.setReducedMotion(it) },
                    colors = emberSwitchColors(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader(PulseIcons.Info, "About")
        SettingCard {
            Column {
                Text(
                    "Pulse ${LiveUpdater.installedVersionLabel(context) ?: "?"}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Kotlin · Compose · Hilt · Room · Ktor · Socket.IO - rebuilt natively against the same live gateway as the web app.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader(PulseIcons.Radio, "App updates")
        SettingCard {
            Column {
                UpdaterDetail()
                Spacer(Modifier.height(4.dp))
                Text(
                    "Quiet check on every launch · byte-range resume · integrity-gated before install",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
                TextButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch { LiveUpdater.syncFrom(context, force = true) }
                }) {
                    Text("Check for updates", color = EmberPalette.Amber)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader(PulseIcons.Globe, "Connection")
        SettingCard {
            val savedBase by viewModel.serverBase.collectAsStateWithLifecycle()
            val probe by viewModel.probe.collectAsStateWithLifecycle()
            var serverField by remember(savedBase) { mutableStateOf(savedBase ?: "") }
            Column {
                Text("Server address", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(
                    if (savedBase.isNullOrBlank()) {
                        "Not set - Pulse runs offline-first. Paste your Pulse web origin (the https:// address of this app's server) to go live."
                    } else {
                        "REST + realtime point at:\n$savedBase"
                    },
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverField,
                    onValueChange = { serverField = it },
                    singleLine = true,
                    placeholder = { Text("https://your-pulse-server") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmberPalette.Amber,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                        cursorColor = EmberPalette.Amber,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.probeServer(serverField)
                    }) { Text("Test", color = EmberPalette.Amber) }
                    TextButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.setServerBase(serverField.trim().takeIf { it.isNotBlank() })
                    }) { Text("Save & use", color = EmberPalette.Amber) }
                    if (!savedBase.isNullOrBlank()) {
                        TextButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            serverField = ""
                            viewModel.setServerBase(null)
                        }) { Text("Go offline", color = EmberPalette.Signal) }
                    }
                }
                when {
                    probe.running -> Text(
                        "Testing $serverField/api/users …",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                    // R35 Neo: plain verdict text replaces the old glyph prefixes.
                    probe.ok == true -> Text(
                        "Reachable: ${probe.detail}",
                        fontSize = 12.sp,
                        color = EmberPalette.Online,
                    )
                    probe.ok == false -> Text(
                        "Not reachable: ${probe.detail}",
                        fontSize = 12.sp,
                        color = EmberPalette.Signal,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    // R6 - M3: the destructive confirm before the real forget.
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
                                        Icon(PulseIcons.Check, contentDescription = null, tint = EmberPalette.Online, modifier = Modifier.size(13.dp))
                                    }
                                }
                                Text("@${user.handle.ifBlank { user.name.lowercase().replace(" ", "_") }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (selected) {
                                Text("You", style = MaterialTheme.typography.labelSmall, color = EmberPalette.Online, fontWeight = FontWeight.Bold)
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
 * R6 - BE8: profile share via the OS share sheet (web profile-tab.tsx:258-278
 * `shareProfile` parity): "Find me on Pulse - @handle". No handle yet -> the
 * web's honest info toast; a failed chooser -> "Could not share right now".
 */
internal fun shareProfile(
    context: android.content.Context,
    viewerId: String?,
    users: List<app.pulse.domain.model.User>,
) {
    val handle = users.firstOrNull { it.id == viewerId }?.handle
    if (handle.isNullOrBlank()) {
        Toast.makeText(context, "Claim a handle first - it is how people find you", Toast.LENGTH_SHORT).show()
        return
    }
    val text = "Find me on Pulse - @$handle"
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
 * R16 - clipboard helper for the profile tap-to-copy affordances (web
 * profile-tab.tsx copyHandle :243-255 / copyId :233-241 - same toasts).
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
private fun SectionHeader(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, contentDescription = null, tint = EmberPalette.Amber, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.55f))
    }
}

/**
 * EMB: one equal stat card of the profile stats row - white 5% glass card,
 * 64dp tall, 11sp white 50% label on top, 16sp bold white value below.
 * [onTap] keeps the old wallet card's retry affordance on the Coins card.
 */
@Composable
private fun ProfileStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    Column(
        modifier
            .height(64.dp)
            .emberGlass(RoundedCornerShape(16.dp))
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** EMB hairline divider between account card rows. */
@Composable
private fun AccountDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = EmberPalette.Hairline,
    )
}

/**
 * EMB account card row: 52dp tall, 20dp PulseIcon white 80%, 14sp white
 * label, trailing chevron white 30% (the destructive row drops the chevron
 * and wears the ember signal red).
 */
@Composable
private fun AccountRow(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    showChevron: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (destructive) EmberPalette.Signal else Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (destructive) EmberPalette.Signal else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showChevron) {
            Icon(
                PulseIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** EMB setting card: the ember glass panel (white 5% fill + hairline). */
@Composable
private fun SettingCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .emberGlass(RoundedCornerShape(16.dp))
            .padding(16.dp),
        content = content,
    )
}

/** EMB: segmented pickers ride the ember signal instead of the mint accent. */
@Composable
private fun emberSegmentedColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = EmberPalette.Deep,
    activeContentColor = Color(0xFF1C1410),
    inactiveContainerColor = Color.Transparent,
    inactiveContentColor = Color.White.copy(alpha = 0.55f),
)

/** EMB: switches wear the ember signal, not the mint accent. */
@Composable
private fun emberSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = EmberPalette.Deep,
    checkedBorderColor = EmberPalette.Deep,
    uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
    uncheckedTrackColor = EmberPalette.ChipFill,
    uncheckedBorderColor = Color.White.copy(alpha = 0.25f),
)

/** FlowRow without the ExperimentalLayoutApi opt-in churn at call sites. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}
