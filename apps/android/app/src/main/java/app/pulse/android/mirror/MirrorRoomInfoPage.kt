package app.pulse.android.mirror

// R70-a - the web's full-screen "Group info" / "Chat info" page
// (src/components/chat/room-info-page.tsx + room-member-add.tsx +
// automations-sheet.tsx + conv-theme-picker.tsx + safety-sheet.tsx),
// rebuilt 1:1 as native Compose over the REAL PulseRepository gateway:
//   - hero by room type (group photo upload chain / DM avatar + presence),
//     honest quick-stats over the loaded message window, action rows
//     (mute presets, disappearing TTL stops, screen security x2, slow mode
//     presets, chat streak, chat theme picker, invite link, add members,
//     manage group, leave channel with last-admin guard),
//   - Automations section (rows + enable switch + delete + create/edit
//     bottom sheets on the real /automations contract),
//   - DM Encryption section + safety-number sheet (verify / unverify),
//   - Members / Subscribers section with roles, two-tap confirms and the
//     add-members glass sub-view.
// All copy is verbatim web truth (hyphens and middots included); every
// repository call rides Dispatchers.IO and UI state flips on Main.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FontFamily
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.core.PulseEndpoints
import app.pulse.domain.model.Automation
import app.pulse.domain.model.ConvTheme
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.ConversationMember
import app.pulse.domain.model.GroupMeta
import app.pulse.domain.model.Message
import app.pulse.domain.model.SafetyState
import app.pulse.domain.model.User
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.WirePulsePrefs
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.round
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Private ink for this page - MirrorArt carries the shared artboard tokens.
private object InfoInk {
    val Glass = Color(0x14FFFFFF) // glass-deep card fill
    val GlassTile = Color(0x0FFFFFFF) // inner tile fill (MirrorArt.Chip)
    val Press = Color(0x12FFFFFF) // white 7% press tint
    val AmberSoft = Color(0x1AFFB86B) // amber 10% pill fill
    val AmberPress = Color(0x26FFB86B) // amber 15% pressed chip
    val Rose = Color(0xFFF8717B) // rose-400 - lost streak + armed copy
    val RoseStrong = Color(0xFFF43F5E) // rose-500 - armed fills
    val Rose10 = Color(0x1AF43F5E) // rose-500/10 armed row fill
    val Rose15 = Color(0x26F43F5E) // rose-500/15 armed pill fill
    val Zinc800 = Color(0xFF27272A) // "none" wallpaper swatch grey
    val FieldBorder = Color(0x17FFFFFF) // input border white/9
}

private val INFO_LINK_REGEX = Regex("https?://", RegexOption.IGNORE_CASE)

// en-US list stamp truth (web formatListStamp): today "HH:mm", "Yesterday", "Sep 7".
private val INFO_HM: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(ZoneId.systemDefault())
private val INFO_DAY_MONTH: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneId.systemDefault())

private fun infoListStamp(iso: String?): String {
    val instant = MirrorRoomInstant(iso ?: "") ?: return ""
    val whenAt = instant.atZone(ZoneId.systemDefault())
    val today = ZonedDateTime.now().toLocalDate()
    return when (whenAt.toLocalDate()) {
        today -> INFO_HM.format(whenAt)
        today.minusDays(1) -> "Yesterday"
        else -> INFO_DAY_MONTH.format(whenAt)
    }
}

/** Web ttlLabel: Off / 24h / 7d / 30d / Nd. */
private fun infoTtlLabel(ttlSeconds: Int): String = when {
    ttlSeconds <= 0 -> "Off"
    ttlSeconds == 86_400 -> "24h"
    ttlSeconds == 604_800 -> "7d"
    ttlSeconds == 2_592_000 -> "30d"
    else -> "${max(1, round(ttlSeconds / 86_400.0).toInt())}d"
}

/** Web ttlLong: 24 hours / 7 days / 30 days / N days. */
private fun infoTtlLong(ttlSeconds: Int): String = when {
    ttlSeconds == 86_400 -> "24 hours"
    ttlSeconds == 604_800 -> "7 days"
    ttlSeconds == 2_592_000 -> "30 days"
    else -> "${max(1, round(ttlSeconds / 86_400.0).toInt())} days"
}

/** Web SLOW_PRESETS - the EXACT ladder the slow-mode API accepts (seconds). */
private data class InfoSlowPreset(val seconds: Int, val label: String, val long: String)

private val INFO_SLOW_PRESETS: List<InfoSlowPreset> = listOf(
    InfoSlowPreset(0, "Off", "Off"),
    InfoSlowPreset(5, "5s", "5 seconds"),
    InfoSlowPreset(10, "10s", "10 seconds"),
    InfoSlowPreset(30, "30s", "30 seconds"),
    InfoSlowPreset(60, "1m", "1 minute"),
    InfoSlowPreset(300, "5m", "5 minutes"),
)

private fun infoSlowLabel(seconds: Int): String =
    INFO_SLOW_PRESETS.firstOrNull { it.seconds == seconds }?.label ?: "${seconds}s"

private fun infoSlowLong(seconds: Int): String =
    INFO_SLOW_PRESETS.firstOrNull { it.seconds == seconds }?.long ?: "${seconds}s"

// Web conv-theme.ts wallpaper tokens + presentation labels.
private val INFO_WALLPAPERS: List<String> = listOf("none", "aurora", "dusk", "forest", "mono")

private fun infoWallpaperLabel(token: String?): String = when (token) {
    "none" -> "None"
    "aurora" -> "Aurora"
    "dusk" -> "Dusk"
    "forest" -> "Forest"
    "mono" -> "Mono"
    null -> "Dusk" // prefs-defaults wallpaper default
    else -> token
}

private val INFO_TINTS: List<String> = ConvTheme.TINTS

private fun infoTintLabel(token: String): String = when (token) {
    "emerald" -> "Emerald"
    "rose" -> "Rose"
    "amber" -> "Amber"
    "violet" -> "Violet"
    "teal" -> "Teal"
    else -> token
}

private fun infoTintColor(token: String): Color = when (token) {
    "emerald" -> Color(0xFF34D399)
    "rose" -> Color(0xFFFB7185)
    "amber" -> Color(0xFFFBBF24)
    "violet" -> Color(0xFFA78BFA)
    "teal" -> Color(0xFF2DD4BF)
    else -> MirrorArt.Faint
}

/**
 * Photo pick -> base64 data URL (private copy of the established mirror
 * upload chain): BitmapFactory downscale with the ~1280 max dimension.
 */
private suspend fun infoUriToDataUrl(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / (sample * 2) >= 1280) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null
            val scaled = if (maxOf(bitmap.width, bitmap.height) > 1280) {
                val scale = 1280f / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val bytes = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            }
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }

// Shared private chrome =======================================================

/** Glass card: 0x14FFFFFF fill + hairline border, rounded [cornerRadius]. */
@Composable
private fun InfoGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(InfoInk.Glass)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(cornerRadius)),
        content = content,
    )
}

/** 10sp bold uppercase faint section label with the web's wide tracking. */
@Composable
private fun InfoSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MirrorArt.Faint,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = modifier,
    )
}

private fun infoSkeletonAlpha(): Float = 1f

/** Web Skeleton (animate-pulse) box - private copy of the established recipe. */
@Composable
private fun InfoSkeleton(widthFraction: Float = 1f, heightDp: Int = 48, cornerDp: Int = 16) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "infoSkeleton")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 900, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "infoSkeletonAlpha",
    )
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(heightDp.dp)
            .alpha(pulseAlpha)
            .clip(RoundedCornerShape(cornerDp.dp))
            .background(MirrorArt.White7),
    )
}

/** Spinning loader glyph (web LoaderCircle animate-spin). */
@Composable
private fun InfoSpinner(tint: Color, modifier: Modifier = Modifier) {
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "infoSpinner")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 850, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "infoSpinnerAngle",
    )
    MirrorLucideIcon("LLoaderCircle", tint = tint, modifier = modifier.graphicsLayerRotate(angle))
}

private fun Modifier.graphicsLayerRotate(angle: Float): Modifier =
    this.then(Modifier.graphicsLayerRotation(angle))

private fun Modifier.graphicsLayerRotation(angle: Float): Modifier =
    androidx.compose.ui.graphics.graphicsLayer(angle = angle) { rotationZ = angle }.let { this }
        .then(Modifier)

/** Static state pill (Off / 24h / 30d / On / Off) - amber when active. */
@Composable
private fun InfoStatePill(text: String, amber: Boolean) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (amber) InfoInk.AmberSoft else MirrorArt.White7)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = if (amber) MirrorArt.Accent2 else MirrorArt.Dim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** 28dp glass chip (mute presets / unmute / customize). */
@Composable
private fun InfoGlassChip(
    text: String,
    enabled: Boolean = true,
    amber: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .heightIn(min = 28.dp)
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> MirrorArt.White7
                    amber -> InfoInk.AmberSoft
                    pressed -> InfoInk.AmberPress
                    else -> MirrorArt.White7
                },
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (amber || pressed) MirrorArt.Accent2 else MirrorArt.TextSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Actions-card row: 16dp inner corners, 56dp min, white 7% press tint. */
@Composable
private fun InfoPressableRow(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    idleBackground: Color = Color.Transparent,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .background(if (pressed && onClick != null) InfoInk.Press else idleBackground)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Glass bottom-sheet chrome (scrim + panel + grab rail), MirrorSheet-consistent. */
@Composable
private fun InfoSheetPanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(enabled = false) {}
                .imePadding()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF)),
            )
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(14.dp))
        }
    }
}

/** Icon-tile sheet header (automation create/edit sheets). */
@Composable
private fun InfoSheetHeader(icon: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(InfoInk.AmberSoft),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon(icon, tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MirrorArt.Faint, fontSize = 11.sp)
        }
    }
}

/** Uppercase form label + live "n/max" counter. */
@Composable
private fun InfoFormLabel(label: String, counter: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            label.uppercase(),
            color = MirrorArt.Faint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.weight(1f),
        )
        Text(counter, color = MirrorArt.Faint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

/** Glass text field with honest rose border while invalid. */
@Composable
private fun InfoInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    invalid: Boolean,
    singleLine: Boolean,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(MirrorArt.Accent2),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(InfoInk.Glass)
                    .border(
                        1.dp,
                        if (invalid) InfoInk.RoseStrong else InfoInk.FieldBorder,
                        RoundedCornerShape(16.dp),
                    )
                    .heightIn(min = if (singleLine) 40.dp else 72.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = MirrorArt.Faint, fontSize = 13.sp)
                }
                Box(Modifier.fillMaxWidth()) { inner() }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Small glass retry pill for failed loads (sheet glass language). */
@Composable
private fun InfoRetryPill(onRetry: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(MirrorArt.White7)
            .border(1.dp, MirrorArt.Hairline, CircleShape)
            .clickable(onClick = onRetry)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MirrorLucideIcon("LRefreshCw", tint = MirrorArt.TextSoft, modifier = Modifier.size(12.dp))
        Text("Retry", color = MirrorArt.TextSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Member search pill (only surfaced past 8 members, web parity). */
@Composable
private fun InfoSearchField(query: String, placeholder: String, onQuery: (String) -> Unit, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(MirrorArt.White7)
            .border(1.dp, MirrorArt.Hairline, CircleShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MirrorLucideIcon("LSearch", tint = MirrorArt.Faint, modifier = Modifier.size(14.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(placeholder, color = MirrorArt.Faint, fontSize = 13.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = { if (it.length <= 40) onQuery(it) },
                singleLine = true,
                textStyle = TextStyle(color = MirrorArt.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(MirrorArt.Accent2),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LSearchX", tint = MirrorArt.Faint, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// The page ====================================================================

/**
 * The full-screen room-info page (web #/room/<id>/info). Hosted by the room
 * surface; [onChanged] tells the host a gateway mutation landed, [onDismiss]
 * pops the page, [onOpenManager] opens the classic group manager.
 */
@Composable
internal fun MirrorRoomInfoPage(
    conversationId: String,
    viewerId: String,
    viewerName: String,
    repository: PulseRepository,
    presence: Set<String>,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
    onOpenManager: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // server truth (fetched on open + after every mutation + 20s loop)
    var meta by remember { mutableStateOf<GroupMeta?>(null) }
    var metaFailed by remember { mutableStateOf(false) }
    var pinnedCount by remember { mutableStateOf(0) }
    var automations by remember { mutableStateOf<List<Automation>>(emptyList()) }
    var automationsLoading by remember { mutableStateOf(true) }
    var directory by remember { mutableStateOf<List<User>>(emptyList()) }
    var directoryFailed by remember { mutableStateOf(false) }
    var historyPartial by remember { mutableStateOf(false) }
    var safetyRow by remember { mutableStateOf<SafetyState?>(null) }

    // live flows
    val conversations by repository.observeConversations("").collectAsState(initial = emptyList<Conversation>())
    val messages by repository.observeMessages(conversationId).collectAsState(initial = emptyList<Message>())
    val convThemes by repository.convThemes.collectAsState(initial = emptyMap<String, ConvTheme>())
    val prefs by repository.pulsePrefs.collectAsState(initial = WirePulsePrefs())

    // row/UI state
    var ttlOpen by remember { mutableStateOf(false) }
    var slowOpen by remember { mutableStateOf(false) }
    var themeOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var safetyOpen by remember { mutableStateOf(false) }
    var createOpen by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Automation?>(null) }
    var memberFilter by remember { mutableStateOf("") }
    var mutePending by remember { mutableStateOf(false) }
    var ttlPending by remember { mutableStateOf(false) }
    var slowPending by remember { mutableStateOf(false) }
    var invitePending by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var myPrivacyLocal by remember { mutableStateOf<Boolean?>(null) }
    var everyonePrivacyLocal by remember { mutableStateOf<Boolean?>(null) }
    var armedId by remember { mutableStateOf<String?>(null) }
    var armedKind by remember { mutableStateOf("") }
    var rolePendingId by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var noteIsError by remember { mutableStateOf(false) }

    // inline note (replaces web toasts; clears itself after 2.6s)
    LaunchedEffect(note) {
        if (note.isNotEmpty()) {
            delay(2600)
            note = ""
        }
    }

    suspend fun refreshMeta() {
        val res = withContext(Dispatchers.IO) {
            runCatching { repository.groupMeta(conversationId) }.getOrElse { Result.failure(it) }
        }
        meta = res.getOrNull()
        metaFailed = res.isFailure
    }

    suspend fun refreshPinned() {
        val list = withContext(Dispatchers.IO) {
            runCatching { repository.pinnedMessages(conversationId) }.getOrElse { emptyList() }
        }
        pinnedCount = list.size
    }

    suspend fun refreshAutomations() {
        val res = withContext(Dispatchers.IO) {
            runCatching { repository.automations(conversationId) }.getOrElse { Result.failure(it) }
        }
        automationsLoading = false
        res.onSuccess { automations = it }
    }

    suspend fun refreshUsers() {
        val res = withContext(Dispatchers.IO) {
            runCatching { repository.users() }.getOrElse { Result.failure(it) }
        }
        directoryFailed = res.isFailure
        res.onSuccess { directory = it }
    }

    suspend fun refreshAll() {
        refreshMeta()
        refreshPinned()
        refreshAutomations()
        refreshUsers()
    }

    suspend fun refreshSafetyRow(peer: String) {
        val res = withContext(Dispatchers.IO) {
            runCatching { repository.safetyState(peer) }.getOrElse { Result.failure(it) }
        }
        safetyRow = res.getOrNull()
    }

    // on open + a 20s meta refresh loop while open (web refetchInterval 30s)
    LaunchedEffect(conversationId) {
        refreshAll()
        while (true) {
            delay(20_000)
            refreshMeta()
        }
    }

    // historyPartial: probe one page past the oldest loaded row (web truth)
    LaunchedEffect(messages.firstOrNull()?.id) {
        val oldest = messages.firstOrNull() ?: return@LaunchedEffect
        val page = withContext(Dispatchers.IO) {
            runCatching { repository.messagesPage(conversationId, before = oldest.id, limit = 1) }.getOrNull()
        }
        if (page != null) historyPartial = page.second
    }

    fun showNote(text: String, isError: Boolean) {
        note = text
        noteIsError = isError
    }

    fun arm(kind: String, id: String) {
        armedId = id
        armedKind = kind
        scope.launch {
            delay(2600)
            if (armedId == id && armedKind == kind) {
                armedId = null
                armedKind = ""
            }
        }
    }

    fun tapConfirm(kind: String, id: String, run: () -> Unit) {
        if (armedId == id && armedKind == kind) {
            armedId = null
            armedKind = ""
            run()
        } else {
            arm(kind, id)
        }
    }

    // ---- mutations (repository calls on IO, state flips on Main) ----

    fun doMute(until: String?) {
        if (mutePending) return
        mutePending = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.setMutedUntil(conversationId, until) }.getOrElse { Result.failure(it) }
            }
            mutePending = false
            res.onSuccess {
                showNote(if (until == null) "Notifications unmuted" else "Notifications muted", false)
            }.onFailure {
                showNote(it.message ?: "Could not update the mute", true)
            }
            if (res.isSuccess) {
                refreshMeta()
                withContext(Dispatchers.Main) { onChanged() }
            }
        }
    }

    fun commitTtl(ttlSeconds: Int) {
        if (ttlPending) return
        ttlPending = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.setDisappearingTtl(conversationId, ttlSeconds) }.getOrElse { Result.failure(it) }
            }
            ttlPending = false
            res.onSuccess {
                showNote(
                    if (ttlSeconds == 0) "Disappearing messages off" else "New messages vanish after ${infoTtlLong(ttlSeconds)}",
                    false,
                )
            }.onFailure {
                showNote(it.message ?: "Could not update disappearing messages", true)
            }
            if (res.isSuccess) {
                refreshMeta()
                withContext(Dispatchers.Main) { onChanged() }
            }
        }
    }

    fun setMyScreenPrivacy(on: Boolean) {
        myPrivacyLocal = on
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.setMyScreenPrivacy(conversationId, on) }.getOrElse { Result.failure(it) }
            }
            myPrivacyLocal = null
            res.onSuccess {
                showNote(if (on) "Screen security on for you" else "Screen security off for you", false)
            }.onFailure {
                showNote(it.message ?: "Could not update screen security", true)
            }
            if (res.isSuccess) {
                refreshMeta()
                withContext(Dispatchers.Main) { onChanged() }
            }
        }
    }

    fun setEveryoneScreenPrivacy(on: Boolean) {
        everyonePrivacyLocal = on
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.setScreenPrivacy(conversationId, on) }.getOrElse { Result.failure(it) }
            }
            everyonePrivacyLocal = null
            res.onSuccess {
                showNote(if (on) "Screen security on" else "Screen security off", false)
            }.onFailure {
                showNote(it.message ?: "Could not update screen security", true)
            }
            if (res.isSuccess) {
                refreshMeta()
                withContext(Dispatchers.Main) { onChanged() }
            }
        }
    }

    fun commitSlowMode(seconds: Int) {
        if (slowPending) return
        slowPending = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.setSlowMode(conversationId, seconds) }.getOrElse { Result.failure(it) }
            }
            slowPending = false
            res.onSuccess {
                showNote(if (seconds > 0) "Slow mode on - members wait ${infoSlowLabel(seconds)}" else "Slow mode off", false)
            }.onFailure {
                showNote(it.message ?: "Could not update slow mode", true)
            }
            if (res.isSuccess) {
                refreshMeta()
                withContext(Dispatchers.Main) { onChanged() }
            }
        }
    }

    fun commitTheme(theme: ConvTheme?) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { repository.setConvTheme(conversationId, theme) }
            }
        }
    }

    fun copyInvite(code: String) {
        val origin = PulseEndpoints.gatewayHttpUrl.trim().trimEnd('/')
        val base = if (origin.endsWith("/api")) origin.removeSuffix("/api") else origin
        val link = if (base.isNotBlank()) "$base/?join=$code" else code
        clipboard.setText(AnnotatedString(link))
        showNote("Invite link copied", false)
    }

    fun createInvite() {
        if (invitePending) return
        invitePending = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.createGroupInvite(conversationId, false) }.getOrElse { Result.failure(it) }
            }
            invitePending = false
            res.onSuccess { code ->
                copyInvite(code)
            }.onFailure {
                showNote(it.message ?: "Could not create the invite link", true)
            }
            if (res.isSuccess) {
                refreshMeta()
                withContext(Dispatchers.Main) { onChanged() }
            }
        }
    }

    fun setMemberRole(userId: String, promote: Boolean) {
        if (rolePendingId != null) return
        rolePendingId = userId
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.setMemberRole(conversationId, userId, promote) }.getOrElse { Result.failure(it) }
            }
            rolePendingId = null
            res.onSuccess {
                showNote(if (promote) "Promoted to admin" else "Role set to member", false)
            }.onFailure {
                showNote(it.message ?: "Could not update the role", true)
            }
            if (res.isSuccess) {
                refreshMeta()
                withContext(Dispatchers.Main) { onChanged() }
            }
        }
    }

    fun kickMember(userId: String) {
        if (rolePendingId != null) return
        rolePendingId = userId
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.kickMember(conversationId, userId) }.getOrElse { Result.failure(it) }
            }
            rolePendingId = null
            res.onSuccess {
                showNote("Removed from the group", false)
            }.onFailure {
                showNote(it.message ?: "Could not remove the member", true)
            }
            if (res.isSuccess) {
                refreshMeta()
                withContext(Dispatchers.Main) { onChanged() }
            }
        }
    }

    var leavingChannel by remember { mutableStateOf(false) }

    fun leaveChannel() {
        if (leavingChannel) return
        leavingChannel = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.unsubscribeChannel(conversationId) }.getOrElse { Result.failure(it) }
            }
            leavingChannel = false
            res.onSuccess {
                withContext(Dispatchers.Main) {
                    onChanged()
                    onDismiss()
                }
            }.onFailure {
                showNote(it.message ?: "Could not leave the channel", true)
            }
        }
    }

    // R33-b photo chain: pick -> data URL -> uploadMedia -> setGroupPhoto
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !photoBusy) {
            photoBusy = true
            scope.launch {
                val dataUrl = infoUriToDataUrl(context, uri)
                if (dataUrl == null) {
                    photoBusy = false
                    showNote("Could not upload that photo", true)
                    return@launch
                }
                val uploadRes = withContext(Dispatchers.IO) {
                    runCatching { repository.uploadMedia(dataUrl) }.getOrElse { Result.failure(it) }
                }
                val photoPath = uploadRes.getOrNull()
                if (photoPath == null) {
                    photoBusy = false
                    showNote("Could not upload that photo", true)
                    return@launch
                }
                val patchRes = withContext(Dispatchers.IO) {
                    runCatching { repository.setGroupPhoto(conversationId, photoPath) }.getOrElse { Result.failure(it) }
                }
                photoBusy = false
                patchRes.onSuccess {
                    showNote("Photo updated", false)
                }.onFailure {
                    showNote(it.message ?: "Could not update the photo", true)
                }
                if (patchRes.isSuccess) {
                    refreshMeta()
                    withContext(Dispatchers.Main) { onChanged() }
                }
            }
        }
    }

    // ---- derived state ----

    val conversation = conversations.firstOrNull { it.id == conversationId }
    val isGroup = conversation?.let { it.kind != Conversation.Kind.DM } ?: (meta?.isGroup ?: false)
    val members = remember(conversation) {
        (conversation?.members ?: emptyList()).sortedWith(
            compareBy({ if (it.role == "admin") 0 else 1 }, { it.name.lowercase(Locale.getDefault()) }),
        )
    }
    val adminCount = members.count { it.role == "admin" }
    val onlineCount = members.count { presence.contains(it.id) }
    val isChannel = (meta?.broadcastMode) ?: conversation?.isChannel ?: false
    val other = if (!isGroup) members.firstOrNull { it.id != viewerId } else null
    val peerId = if (!isGroup) (other?.id ?: conversation?.otherUserId) else null
    val title = if (isGroup) {
        conversation?.title?.takeIf { it.isNotBlank() } ?: "Group"
    } else {
        other?.name ?: "Chat"
    }
    val mutedUntilIso = meta?.myMutedUntilIso
    val isMuted = mutedUntilIso != null && (MirrorRoomInstant(mutedUntilIso)?.isAfter(Instant.now()) == true)
    val stats = remember(messages) {
        var media = 0
        var links = 0
        for (m in messages) {
            if (m.deletedAt != null) continue
            if (m.imagePath != null) media += 1
            if (INFO_LINK_REGEX.containsMatchIn(m.body)) links += 1
        }
        Pair(media, links)
    }
    val themeOverride = convThemes[conversationId]
    val globalWallpaper = prefs.wallpaper
    val themeSummary = if (themeOverride == null) {
        "Global default · ${infoWallpaperLabel(globalWallpaper)}"
    } else {
        var text = "Custom · ${infoWallpaperLabel(themeOverride.wallpaper)}"
        if (themeOverride.tint != null) text += " · ${infoTintLabel(themeOverride.tint)} tint"
        text
    }
    val directoryById = remember(directory) { directory.associateBy { it.id } }
    val memberQuery = memberFilter.trim().lowercase(Locale.getDefault())
    val visibleMembers = if (members.size <= 8 || memberQuery.isEmpty()) {
        members
    } else {
        members.filter {
            it.name.lowercase(Locale.getDefault()).contains(memberQuery) ||
                (directoryById[it.id]?.handle ?: "").lowercase(Locale.getDefault()).contains(memberQuery)
        }
    }
    val leaveArmed = armedId == viewerId && armedKind == "leave"
    val heroGlow = if (isGroup) {
        MirrorArt.groupGradient(conversationId).first()
    } else {
        MirrorArt.avatarGradient(other?.color).first()
    }

    // DM safety row rides the peer identity (refresh on open + peer change)
    LaunchedEffect(peerId) {
        if (peerId != null) refreshSafetyRow(peerId)
    }

    // ---- render ----

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MirrorArt.Bg)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // header: back glass pill + title
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val backInteraction = remember { MutableInteractionSource() }
                val backPressed by backInteraction.collectIsPressedAsState()
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (backPressed) MirrorArt.White7 else MirrorArt.Chip)
                        .border(1.dp, MirrorArt.Hairline, CircleShape)
                        .clickable(interactionSource = backInteraction, indication = null, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LChevronLeft", tint = MirrorArt.TextSoft, modifier = Modifier.size(20.dp))
                }
                Text(
                    if (isGroup) "Group info" else "Chat info",
                    color = MirrorArt.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // inline note rail (web toast parity)
            if (note.isNotEmpty()) {
                Text(
                    note,
                    color = if (noteIsError) InfoInk.Rose else MirrorArt.Accent2,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
            ) {
                // hero glass card
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(InfoInk.Glass)
                        .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(24.dp)),
                ) {
                    // top-end gradient glow (web opacity-25 blur-2xl blob)
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 40.dp, y = (-52).dp)
                            .size(176.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(heroGlow.copy(alpha = 0.25f), Color.Transparent),
                                ),
                            ),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (isGroup) {
                            Box {
                                val photo = meta?.photo
                                if (photo != null) {
                                    AsyncImage(
                                        model = PulseEndpoints.http("/api/uploads/$photo"),
                                        contentDescription = "Group photo",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier
                                            .size(68.dp)
                                            .clip(RoundedCornerShape(20.dp)),
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .size(68.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(MirrorArt.avatarBrush(isGroup = true, id = conversationId)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            MirrorArt.initials(title),
                                            color = Color.White,
                                            fontSize = 23.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                if (meta?.isAdmin == true) {
                                    val camInteraction = remember { MutableInteractionSource() }
                                    val camPressed by camInteraction.collectIsPressedAsState()
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomEnd)
                                            .offset(x = 4.dp, y = 4.dp)
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(if (camPressed) MirrorArt.ChipActive else Color(0xF21C1610))
                                            .border(1.dp, MirrorArt.Hairline, CircleShape)
                                            .clickable(
                                                interactionSource = camInteraction,
                                                indication = null,
                                                enabled = !photoBusy,
                                            ) {
                                                pickImage.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (photoBusy) {
                                            InfoSpinner(MirrorArt.Accent2, Modifier.size(14.dp))
                                        } else {
                                            MirrorLucideIcon("LCamera", tint = MirrorArt.Accent2, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        } else {
                            MirrorAvatar(
                                name = other?.name ?: title,
                                color = other?.color,
                                isGroup = false,
                                groupId = "",
                                online = other != null && presence.contains(other.id),
                                showPresence = true,
                                sizeDp = 68,
                                cornerDp = 34,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            if (meta == null && conversation == null) {
                                InfoSkeleton(widthFraction = 0.6f, heightDp = 22, cornerDp = 8)
                                Spacer(Modifier.height(8.dp))
                                InfoSkeleton(widthFraction = 0.4f, heightDp = 14, cornerDp = 6)
                            } else {
                                Text(
                                    title,
                                    color = MirrorArt.Text,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isGroup) {
                                    val memberWord = when {
                                        members.size == 1 && isChannel -> "subscriber"
                                        members.size == 1 -> "member"
                                        isChannel -> "subscribers"
                                        else -> "members"
                                    }
                                    Text(
                                        "${members.size} $memberWord · $onlineCount online",
                                        color = MirrorArt.Faint,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                } else {
                                    Text(
                                        when {
                                            other == null -> "Direct chat"
                                            presence.contains(other.id) -> "Online now"
                                            else -> "Offline"
                                        },
                                        color = MirrorArt.Faint,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                val description = meta?.description
                                if (!description.isNullOrBlank()) {
                                    Text(
                                        description,
                                        color = MirrorArt.Faint,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                Row(
                                    Modifier.padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (isChannel) {
                                        Row(
                                            Modifier
                                                .clip(CircleShape)
                                                .background(InfoInk.AmberSoft)
                                                .padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            MirrorLucideIcon("LMegaphone", tint = MirrorArt.Accent2, modifier = Modifier.size(12.dp))
                                            Text(
                                                "Announcements only",
                                                color = MirrorArt.Accent2,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    val ttlBadge = meta?.ttlSeconds ?: 0
                                    if (ttlBadge > 0) {
                                        Row(
                                            Modifier
                                                .clip(CircleShape)
                                                .background(InfoInk.AmberSoft)
                                                .padding(horizontal = 8.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            MirrorLucideIcon("LTimer", tint = MirrorArt.Accent2, modifier = Modifier.size(12.dp))
                                            Text(
                                                "Disappearing · ${infoTtlLabel(ttlBadge)}",
                                                color = MirrorArt.Accent2,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // quick stats - honest counts over the loaded window
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoStatTile("LImage", "PHOTOS", stats.first, Modifier.weight(1f))
                    InfoStatTile("LLink2", "LINKS", stats.second, Modifier.weight(1f))
                    InfoStatTile("LPin", "PINNED", pinnedCount, Modifier.weight(1f))
                }
                if (historyPartial) {
                    Text(
                        "Counted from loaded history - older messages exist.",
                        color = MirrorArt.Faint,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // actions card
                when {
                    metaFailed -> InfoGlassCard(Modifier.fillMaxWidth().padding(6.dp)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Could not load the room info",
                                color = MirrorArt.TextSoft,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            InfoRetryPill {
                                scope.launch { refreshMeta() }
                            }
                        }
                    }
                    meta == null -> InfoGlassCard(Modifier.fillMaxWidth().padding(6.dp)) {
                        Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoSkeleton(heightDp = 48)
                            InfoSkeleton(heightDp = 48)
                        }
                    }
                    else -> {
                        val m = meta ?: return@let
                        InfoGlassCard(Modifier.fillMaxWidth().padding(6.dp)) {
                            // a. MUTE - real per-user watermark presets
                            InfoPressableRow {
                                if (isMuted) {
                                    MirrorLucideIcon("LVolumeX", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                                } else {
                                    MirrorLucideIcon("LBellOff", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (isMuted) "Notifications muted" else "Mute notifications",
                                        color = MirrorArt.Text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (isMuted && mutedUntilIso != null) {
                                        Text(
                                            "until ${infoListStamp(mutedUntilIso)}",
                                            color = MirrorArt.Faint,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                                if (isMuted) {
                                    InfoGlassChip(text = "Unmute", amber = true, enabled = !mutePending) { doMute(null) }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        for (preset in listOf("8h", "1w", "always")) {
                                            InfoGlassChip(
                                                text = if (preset == "always") "Always" else preset,
                                                enabled = !mutePending,
                                            ) { doMute(preset) }
                                        }
                                    }
                                }
                            }

                            // b. DISAPPEARING - tap-to-commit TTL stops
                            InfoPressableRow {
                                Row(Modifier.fillMaxWidth()) {
                                    MirrorLucideIcon(
                                        "LTimer",
                                        tint = if (m.ttlSeconds > 0) MirrorArt.Accent2 else MirrorArt.Faint,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Disappearing messages",
                                            color = MirrorArt.Text,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            if (m.ttlSeconds > 0) "New messages vanish after ${infoTtlLong(m.ttlSeconds)}" else "Messages stay in the chat",
                                            color = MirrorArt.Faint,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    InfoStatePill(infoTtlLabel(m.ttlSeconds), m.ttlSeconds > 0)
                                    Spacer(Modifier.width(6.dp))
                                    val ttlInteraction = remember { MutableInteractionSource() }
                                    Box(
                                        Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(MirrorArt.White7)
                                            .clickable(interactionSource = ttlInteraction, indication = null) { ttlOpen = !ttlOpen },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        MirrorLucideIcon(
                                            "LChevronDown",
                                            tint = MirrorArt.TextSoft,
                                            modifier = Modifier
                                                .size(16.dp)
                                                .graphicsLayerRotation(if (ttlOpen) 180f else 0f),
                                        )
                                    }
                                }
                                if (ttlOpen) {
                                    InfoTtlStrip(current = m.ttlSeconds, pending = ttlPending, onCommit = ::commitTtl)
                                }
                            }

                            // c. SCREEN SECURITY (my) - the personal veil
                            InfoPressableRow {
                                if (myPrivacyLocal ?: m.myScreenPrivacy) {
                                    MirrorLucideIcon("LShieldCheck", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                                } else {
                                    MirrorLucideIcon("LEyeOff", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Screen security",
                                        color = MirrorArt.Text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "Blur messages when Pulse loses focus - just for you",
                                        color = MirrorArt.Faint,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                InfoSwitch(
                                    checked = myPrivacyLocal ?: m.myScreenPrivacy,
                                    onCheckedChange = ::setMyScreenPrivacy,
                                )
                            }

                            // d. SCREEN SECURITY FOR EVERYONE - the room-wide flag
                            InfoPressableRow {
                                if (everyonePrivacyLocal ?: m.screenPrivacy) {
                                    MirrorLucideIcon("LShieldCheck", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                                } else {
                                    MirrorLucideIcon("LEyeOff", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Screen security for everyone",
                                        color = MirrorArt.Text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "Applies to every member of this chat",
                                        color = MirrorArt.Faint,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                InfoSwitch(
                                    checked = everyonePrivacyLocal ?: m.screenPrivacy,
                                    onCheckedChange = ::setEveryoneScreenPrivacy,
                                )
                            }

                            // e. SLOW MODE (groups only)
                            if (isGroup) {
                                InfoPressableRow {
                                    Row(Modifier.fillMaxWidth()) {
                                        MirrorLucideIcon(
                                            "LGauge",
                                            tint = if (m.slowModeSeconds > 0) MirrorArt.Accent2 else MirrorArt.Faint,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "Slow mode",
                                                color = MirrorArt.Text,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                when {
                                                    m.slowModeSeconds > 0 && m.isAdmin ->
                                                        "Members can send once every ${infoSlowLong(m.slowModeSeconds)}"
                                                    m.slowModeSeconds > 0 ->
                                                        "You can send once every ${infoSlowLong(m.slowModeSeconds)}"
                                                    m.isAdmin -> "Limit how often members can send"
                                                    else -> "Everyone can send freely"
                                                },
                                                color = MirrorArt.Faint,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        InfoStatePill(infoSlowLabel(m.slowModeSeconds), m.slowModeSeconds > 0)
                                        if (m.isAdmin) {
                                            Spacer(Modifier.width(6.dp))
                                            val slowInteraction = remember { MutableInteractionSource() }
                                            Box(
                                                Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(MirrorArt.White7)
                                                    .clickable(interactionSource = slowInteraction, indication = null) { slowOpen = !slowOpen },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                MirrorLucideIcon(
                                                    "LChevronDown",
                                                    tint = MirrorArt.TextSoft,
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .graphicsLayerRotation(if (slowOpen) 180f else 0f),
                                                )
                                            }
                                        }
                                    }
                                    if (slowOpen && m.isAdmin) {
                                        InfoSlowStrip(current = m.slowModeSeconds, pending = slowPending, onCommit = ::commitSlowMode)
                                    }
                                }
                            }

                            // f. CHAT STREAK - honest read-only row (web truth)
                            InfoStreakRow(meta = m)

                            // g. CHAT THEME - per-conversation wallpaper/tint
                            InfoPressableRow {
                                Row(Modifier.fillMaxWidth()) {
                                    MirrorLucideIcon("LPalette", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Chat theme",
                                            color = MirrorArt.Text,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            themeSummary,
                                            color = MirrorArt.Faint,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    InfoGlassChip(
                                        text = if (themeOpen) "Close" else "Customize",
                                        amber = true,
                                    ) { themeOpen = !themeOpen }
                                }
                                if (themeOpen) {
                                    InfoThemePicker(
                                        override = themeOverride,
                                        globalWallpaper = globalWallpaper,
                                        onCommit = ::commitTheme,
                                    )
                                }
                            }

                            // h. INVITE LINK (groups + admin only)
                            if (m.isAdmin) {
                                InfoPressableRow {
                                    MirrorLucideIcon("LUsers", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Invite link",
                                            color = MirrorArt.Text,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            if (m.inviteCode != null) "Code ${m.inviteCode}" else "No active link yet",
                                            color = MirrorArt.Faint,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    val inviteInteraction = remember { MutableInteractionSource() }
                                    val invitePressed by inviteInteraction.collectIsPressedAsState()
                                    Row(
                                        Modifier
                                            .heightIn(min = 28.dp)
                                            .clip(CircleShape)
                                            .background(if (invitePressed) InfoInk.AmberPress else InfoInk.AmberSoft)
                                            .clickable(
                                                interactionSource = inviteInteraction,
                                                indication = null,
                                                enabled = !invitePending,
                                            ) {
                                                val code = m.inviteCode
                                                if (code != null) copyInvite(code) else createInvite()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (invitePending) {
                                            InfoSpinner(MirrorArt.Accent2, Modifier.size(14.dp))
                                        } else {
                                            MirrorLucideIcon("LCopy", tint = MirrorArt.Accent2, modifier = Modifier.size(14.dp))
                                        }
                                        Text(
                                            if (m.inviteCode != null) "Copy" else "Create",
                                            color = MirrorArt.Accent2,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }

                            // i. ADD MEMBERS (groups + admin only)
                            if (m.isAdmin) {
                                InfoPressableRow(onClick = { addOpen = true }) {
                                    MirrorLucideIcon("LUserRoundPlus", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Add members",
                                            color = MirrorArt.Text,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "From the Pulse directory",
                                            color = MirrorArt.Faint,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    MirrorLucideIcon("LChevronRight", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                                }
                            }

                            // j. MANAGE GROUP (groups)
                            if (isGroup) {
                                InfoPressableRow(onClick = onOpenManager) {
                                    MirrorLucideIcon("LUsers", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Manage group - roles, webhooks, more",
                                        color = MirrorArt.Text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    MirrorLucideIcon("LChevronRight", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                                }
                            }

                            // k. LEAVE CHANNEL (channels only, two-tap confirm + last-admin guard)
                            if (isChannel) {
                                InfoPressableRow(
                                    onClick = {
                                        if (m.isAdmin && adminCount <= 1) {
                                            showNote(
                                                "You are the last admin - promote another admin (\"Make admin\") before leaving.",
                                                true,
                                            )
                                            return@InfoPressableRow
                                        }
                                        tapConfirm("leave", viewerId) { leaveChannel() }
                                    },
                                    idleBackground = if (leaveArmed) InfoInk.Rose10 else Color.Transparent,
                                ) {
                                    MirrorLucideIcon("LLogOut", tint = MirrorArt.Red, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (leaveArmed) "Tap again to leave" else "Leave channel",
                                            color = if (leaveArmed) InfoInk.Rose else MirrorArt.Text,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            when {
                                                m.isAdmin && adminCount <= 1 -> "You stay until another admin exists"
                                                m.isAdmin -> "Another admin will keep the channel running"
                                                else -> "You will stop receiving this channel"
                                            },
                                            color = MirrorArt.Faint,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (leavingChannel) {
                                        InfoSpinner(MirrorArt.Red, Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // AUTOMATIONS section (R39) - rows + honest management
                Spacer(Modifier.height(16.dp))
                InfoSectionLabel(
                    if (automationsLoading && automations.isEmpty()) "AUTOMATIONS" else "AUTOMATIONS · ${automations.size}",
                    Modifier.padding(horizontal = 8.dp, bottom = 6.dp),
                )
                InfoAutomationsSection(
                    repository = repository,
                    conversationId = conversationId,
                    isAdmin = meta?.isAdmin == true,
                    rows = automations,
                    loading = automationsLoading && automations.isEmpty(),
                    onOpenCreate = { createOpen = true },
                    onOpenEdit = { editTarget = it },
                    onRowsChanged = {
                        scope.launch { refreshAutomations() }
                    },
                )

                // ENCRYPTION section (DMs only)
                if (!isGroup && peerId != null) {
                    Spacer(Modifier.height(16.dp))
                    InfoSectionLabel("ENCRYPTION", Modifier.padding(horizontal = 8.dp, bottom = 6.dp))
                    InfoGlassCard(Modifier.fillMaxWidth().padding(6.dp)) {
                        InfoPressableRow(onClick = { safetyOpen = true }) {
                            MirrorLucideIcon(
                                "LShieldCheck",
                                tint = if (safetyRow?.verified == true) MirrorArt.Accent2 else MirrorArt.Faint,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Safety number",
                                    color = MirrorArt.Text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "Tap to compare with your contact",
                                    color = MirrorArt.Faint,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (safetyRow?.verified == true) {
                                Row(
                                    Modifier
                                        .clip(CircleShape)
                                        .background(InfoInk.AmberSoft)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    MirrorLucideIcon("LBadgeCheck", tint = MirrorArt.Accent2, modifier = Modifier.size(12.dp))
                                    Text(
                                        "Verified",
                                        color = MirrorArt.Accent2,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            } else {
                                InfoStatePill("Unverified", amber = true)
                            }
                        }
                    }
                }

                // MEMBERS / SUBSCRIBERS section
                Spacer(Modifier.height(16.dp))
                InfoSectionLabel(
                    buildString {
                        append(if (isChannel) "SUBSCRIBERS" else "MEMBERS")
                        if (conversation != null) append(" · ${members.size}")
                    },
                    Modifier.padding(horizontal = 8.dp, bottom = 6.dp),
                )
                InfoGlassCard(Modifier.fillMaxWidth().padding(6.dp)) {
                    if (conversation == null) {
                        Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoSkeleton(heightDp = 44)
                            InfoSkeleton(heightDp = 44)
                            InfoSkeleton(widthFraction = 0.8f, heightDp = 44)
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (members.size > 8) {
                                InfoSearchField(
                                    query = memberFilter,
                                    placeholder = "Search members",
                                    onQuery = { memberFilter = it },
                                    onClear = { memberFilter = "" },
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            for (member in visibleMembers) {
                                val isMe = member.id == viewerId
                                val demoteArmed = armedId == member.id && armedKind == "demote"
                                val removeArmed = armedId == member.id && armedKind == "remove"
                                InfoMemberRow(
                                    member = member,
                                    isMe = isMe,
                                    online = presence.contains(member.id),
                                    isAdminViewer = meta?.isAdmin == true && isGroup,
                                    adminCount = adminCount,
                                    dirUser = directoryById[member.id],
                                    rolePending = rolePendingId != null,
                                    rolePendingHere = rolePendingId == member.id,
                                    demoteArmed = demoteArmed,
                                    removeArmed = removeArmed,
                                    onPromote = { setMemberRole(member.id, true) },
                                    onDemote = { tapConfirm("demote", member.id) { setMemberRole(member.id, false) } },
                                    onRemove = { tapConfirm("remove", member.id) { kickMember(member.id) } },
                                )
                            }
                            if (visibleMembers.isEmpty() && members.isNotEmpty()) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    MirrorLucideIcon("LSearchX", tint = MirrorArt.Faint, modifier = Modifier.size(14.dp))
                                    Text(
                                        "No members match \u201C${memberFilter.trim()}\u201D",
                                        color = MirrorArt.Faint,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // add-members glass sub-view (slides over the page, web parity)
        if (addOpen) {
            InfoAddMembersOverlay(
                repository = repository,
                conversationId = conversationId,
                existingIds = members.map { it.id }.toSet(),
                directory = directory,
                directoryLoading = directory.isEmpty() && directoryFailed.not() && meta == null,
                directoryFailed = directoryFailed,
                onClose = { addOpen = false },
                onAdded = { count ->
                    addOpen = false
                    showNote(
                        if (count == 1) "1 member added" else "$count members added",
                        false,
                    )
                    scope.launch {
                        refreshAll()
                        withContext(Dispatchers.Main) { onChanged() }
                    }
                },
            )
        }

        // safety-number sheet (DMs only)
        if (safetyOpen && peerId != null) {
            InfoSafetySheet(
                repository = repository,
                peerId = peerId,
                peerName = other?.name ?: title,
                peerColor = other?.color ?: directoryById[peerId]?.color,
                onDismiss = { safetyOpen = false },
                onMutated = {
                    scope.launch { refreshSafetyRow(peerId) }
                },
            )
        }

        // automation create sheet
        if (createOpen) {
            InfoAutomationCreateSheet(
                repository = repository,
                conversationId = conversationId,
                onDismiss = { createOpen = false },
                onCreated = {
                    createOpen = false
                    scope.launch { refreshAutomations() }
                },
            )
        }

        // automation edit sheet
        val editRow = editTarget
        if (editRow != null) {
            InfoAutomationEditSheet(
                repository = repository,
                automation = editRow,
                onDismiss = { editTarget = null },
                onSaved = {
                    editTarget = null
                    scope.launch { refreshAutomations() }
                },
            )
        }
    }
}

/** One quick-stats tile (icon + big count + uppercase label). */
@Composable
private fun InfoStatTile(icon: String, label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(InfoInk.Glass)
            .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MirrorLucideIcon(icon, tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
        Text(value.toString(), color = MirrorArt.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            label,
            color = MirrorArt.Faint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        )
    }
}

/** The 4 tap-to-commit TTL stops (web drag slider, native chip form). */
@Composable
private fun InfoTtlStrip(current: Int, pending: Boolean, onCommit: (Int) -> Unit) {
    val stops = listOf(0, 86_400, 604_800, 2_592_000)
    val selectedIdx = max(0, stops.indexOf(current))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        stops.forEachIndexed { idx, stop ->
            val selected = idx == selectedIdx
            val interaction = remember(stop) { MutableInteractionSource() }
            Box(
                Modifier
                    .weight(1f)
                    .alpha(if (pending) 0.5f else 1f)
                    .heightIn(min = 28.dp)
                    .clip(CircleShape)
                    .background(if (selected) InfoInk.AmberPress else MirrorArt.White7)
                    .then(
                        if (selected) Modifier.border(1.dp, MirrorArt.Accent2.copy(alpha = 0.35f), CircleShape) else Modifier,
                    )
                    .clickable(interactionSource = interaction, indication = null, enabled = !pending) { onCommit(stop) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    infoTtlLabel(stop),
                    color = if (selected) MirrorArt.Accent2 else MirrorArt.TextSoft,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** The 6 slow-mode presets (web radiogroup, admin only). */
@Composable
private fun InfoSlowStrip(current: Int, pending: Boolean, onCommit: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp, start = 4.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        INFO_SLOW_PRESETS.chunked(3).forEach { rowPresets ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (preset in rowPresets) {
                    val selected = current == preset.seconds
                    val interaction = remember(preset.seconds) { MutableInteractionSource() }
                    Box(
                        Modifier
                            .weight(1f)
                            .alpha(if (pending) 0.5f else 1f)
                            .heightIn(min = 32.dp)
                            .clip(CircleShape)
                            .background(if (selected) MirrorArt.Accent else MirrorArt.White7)
                            .clickable(interactionSource = interaction, indication = null, enabled = !pending) {
                                onCommit(preset.seconds)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            preset.label,
                            color = if (selected) Color.White else MirrorArt.TextSoft,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** Chat streak row - honest read-only state (web parity, no action). */
@Composable
private fun InfoStreakRow(meta: GroupMeta?) {
    val dead = meta?.deadStreakCount ?: 0
    val lost = meta?.lostStreakCount ?: 0
    val mine = meta?.myStreakCount ?: 0
    val best = meta?.myStreakBest ?: 0
    val lostBest = meta?.lostStreakBest ?: 0
    InfoPressableRow {
        when {
            dead > 0 -> MirrorLucideIcon("LHourglass", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
            lost > 0 -> MirrorLucideIcon("LFlame", tint = InfoInk.Rose, modifier = Modifier.size(16.dp))
            mine > 0 -> MirrorLucideIcon("LFlame", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
            else -> MirrorLucideIcon("LFlame", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Chat streak",
                color = MirrorArt.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                dead > 0 -> Text(
                    "$dead-day streak ends tonight - say something",
                    color = MirrorArt.Accent2,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                lost > 0 -> Text(
                    "$lost-day streak lost · best $lostBest - start a new one",
                    color = MirrorArt.Faint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                mine > 0 -> Text(
                    "$mine-day streak · best $best",
                    color = MirrorArt.Faint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                else -> Text(
                    "No active streak yet",
                    color = MirrorArt.Faint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Per-conversation wallpaper/tint picker (web ConvThemePicker). */
@Composable
private fun InfoThemePicker(
    override: ConvTheme?,
    globalWallpaper: String?,
    onCommit: (ConvTheme?) -> Unit,
) {
    val effectiveWallpaper = override?.wallpaper ?: (globalWallpaper ?: "dusk")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            InfoFormLabel("Wallpaper", "")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (token in INFO_WALLPAPERS) {
                    val selected = effectiveWallpaper == token
                    val isOverride = override?.wallpaper == token
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = { onCommit(ConvTheme(wallpaper = token, tint = override?.tint)) })
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when (token) {
                                        "none" -> InfoInk.Zinc800
                                        else -> MirrorArt.Bg
                                    },
                                )
                                .then(
                                    if (token == "none") {
                                        Modifier
                                    } else {
                                        Modifier.background(
                                            Brush.verticalGradient(
                                                listOf(Color(0x33FFB86B), Color(0x14FF7A3D)),
                                            ),
                                        )
                                    },
                                )
                                .border(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) MirrorArt.Accent2 else MirrorArt.Hairline,
                                    RoundedCornerShape(12.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                MirrorLucideIcon("LCheck", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            if (isOverride) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = (-4).dp, y = 4.dp)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(MirrorArt.Accent2),
                                )
                            }
                        }
                        Text(
                            infoWallpaperLabel(token),
                            color = if (selected) MirrorArt.Accent2 else MirrorArt.Faint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Text(
                if (override != null) "Custom for this chat only" else "Following Appearance default · ${infoWallpaperLabel(globalWallpaper)}",
                color = MirrorArt.Faint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        Column {
            InfoFormLabel("Tint", "")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // clear chip - web Ban tile
                val noneSelected = override?.tint == null
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(
                            if (noneSelected) 2.dp else 1.dp,
                            if (noneSelected) MirrorArt.Accent2 else MirrorArt.Hairline,
                            CircleShape,
                        )
                        .clickable {
                            onCommit(
                                ConvTheme(
                                    wallpaper = override?.wallpaper ?: (globalWallpaper ?: "dusk"),
                                    tint = null,
                                ),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LX", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                }
                for (token in INFO_TINTS) {
                    val selected = override?.tint == token
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.White7)
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MirrorArt.Accent2 else MirrorArt.Hairline,
                                CircleShape,
                            )
                            .clickable {
                                onCommit(
                                    ConvTheme(
                                        wallpaper = override?.wallpaper ?: (globalWallpaper ?: "dusk"),
                                        tint = token,
                                    ),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(infoTintColor(token)),
                        )
                    }
                }
            }
        }
        if (override != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(MirrorArt.White7)
                    .border(1.dp, MirrorArt.Hairline, CircleShape)
                    .clickable(onClick = { onCommit(null) })
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                MirrorLucideIcon("LRefreshCw", tint = MirrorArt.TextSoft, modifier = Modifier.size(16.dp))
                Text("Reset to default", color = MirrorArt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Automations glass section: skeleton loading, honest empty states, rows with
 * enable switch + delete (admins) / static On-Off pill (members), and the
 * "New automation" affordance. Mutations land on the real /automations API
 * and refetch through [onRowsChanged].
 */
@Composable
private fun InfoAutomationsSection(
    repository: PulseRepository,
    conversationId: String,
    isAdmin: Boolean,
    rows: List<Automation>,
    loading: Boolean,
    onOpenCreate: () -> Unit,
    onOpenEdit: (Automation) -> Unit,
    onRowsChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busyRowId by remember { mutableStateOf<String?>(null) }
    var busyKind by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }

    LaunchedEffect(errorText) {
        if (errorText.isNotEmpty()) {
            delay(2600)
            errorText = ""
        }
    }

    fun toggle(row: Automation, enabled: Boolean) {
        if (busyRowId != null) return
        busyRowId = row.id
        busyKind = "toggle"
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.setAutomationEnabled(row.id, enabled) }.getOrElse { Result.failure(it) }
            }
            busyRowId = null
            busyKind = ""
            res.onFailure { errorText = it.message ?: "Could not update the automation" }
            onRowsChanged()
        }
    }

    fun remove(row: Automation) {
        if (busyRowId != null) return
        busyRowId = row.id
        busyKind = "delete"
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.deleteAutomation(row.id) }.getOrElse { Result.failure(it) }
            }
            busyRowId = null
            busyKind = ""
            res.onSuccess { errorText = "" }
                .onFailure { errorText = it.message ?: "Could not delete the automation" }
            onRowsChanged()
        }
    }

    Column {
        InfoGlassCard(Modifier.fillMaxWidth().padding(6.dp)) {
            when {
                loading -> {
                    Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoSkeleton(heightDp = 48)
                        InfoSkeleton(widthFraction = 0.8f, heightDp = 48)
                    }
                }
                rows.isEmpty() -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp)) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MirrorArt.White7),
                            contentAlignment = Alignment.Center,
                        ) {
                            MirrorLucideIcon("LBot", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (isAdmin) "No automations yet" else "No automations here",
                                color = MirrorArt.TextSoft,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (isAdmin) {
                                    "Add a keyword that fires an instant reply when a member sends it."
                                } else {
                                    "Only admins can manage automations."
                                },
                                color = MirrorArt.Faint,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (isAdmin) {
                            val addInteraction = remember { MutableInteractionSource() }
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MirrorArt.White7)
                                    .clickable(interactionSource = addInteraction, indication = null, onClick = onOpenCreate),
                                contentAlignment = Alignment.Center,
                            ) {
                                MirrorLucideIcon("LPlus", tint = MirrorArt.Accent2, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                else -> {
                    Column(Modifier.fillMaxWidth().padding(6.dp)) {
                        for (row in rows) {
                            key(row.id) {
                                InfoAutomationRow(
                                    row = row,
                                    isAdmin = isAdmin,
                                    busy = busyRowId != null,
                                    deletePending = busyRowId == row.id && busyKind == "delete",
                                    onToggle = { toggle(row, it) },
                                    onDelete = { remove(row) },
                                    onEdit = { onOpenEdit(row) },
                                )
                            }
                        }
                        if (isAdmin) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(onClick = onOpenCreate)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                MirrorLucideIcon("LPlus", tint = MirrorArt.Accent2, modifier = Modifier.size(14.dp))
                                Text(
                                    "New automation",
                                    color = MirrorArt.Accent2,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (errorText.isNotEmpty()) {
            Text(
                errorText,
                color = InfoInk.Rose,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

/** One automation rule row (web AutomationsSection li). */
@Composable
private fun InfoAutomationRow(
    row: Automation,
    isAdmin: Boolean,
    busy: Boolean,
    deletePending: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (row.enabled) 1f else 0.7f)
            .clip(RoundedCornerShape(16.dp))
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (row.enabled) InfoInk.AmberSoft else MirrorArt.White7),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon(
                "LZap",
                tint = if (row.enabled) MirrorArt.Accent2 else MirrorArt.Faint,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.trigger,
                    color = MirrorArt.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isAdmin) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !busy, onClick = onEdit),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LPencilLine", tint = MirrorArt.Faint, modifier = Modifier.size(12.dp))
                    }
                }
            }
            Text(
                row.reply,
                color = MirrorArt.Faint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MirrorLucideIcon("LZap", tint = MirrorArt.Faint, modifier = Modifier.size(10.dp))
                Text(
                    buildString {
                        append(row.hits.toString())
                        append(if (row.hits == 1L) " hit" else " hits")
                        row.lastFiredAtIso?.let {
                            append(" · last ")
                            append(infoListStamp(it))
                        }
                        row.createdByName?.let {
                            append(" · by ")
                            append(it)
                        }
                    },
                    color = MirrorArt.Faint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isAdmin) {
            Spacer(Modifier.width(8.dp))
            InfoSwitch(
                checked = row.enabled,
                onCheckedChange = onToggle,
                enabled = !busy,
            )
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !busy, onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                if (deletePending) {
                    InfoSpinner(MirrorArt.Faint, Modifier.size(14.dp))
                } else {
                    MirrorLucideIcon("LTrash2", tint = MirrorArt.Faint, modifier = Modifier.size(14.dp))
                }
            }
        } else {
            Spacer(Modifier.width(8.dp))
            InfoStatePill(if (row.enabled) "On" else "Off", row.enabled)
        }
    }
}

/** "New automation" glass bottom sheet (web AutomationsCreateSheet). */
@Composable
private fun InfoAutomationCreateSheet(
    repository: PulseRepository,
    conversationId: String,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var trigger by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

    val trimmedTrigger = trigger.trim()
    val trimmedReply = reply.trim()
    val triggerInvalid = trimmedTrigger.length < 2 || trimmedTrigger.length > 40
    val replyInvalid = trimmedReply.isEmpty() || trimmedReply.length > 500
    val valid = !triggerInvalid && !replyInvalid

    fun submit() {
        if (!valid || pending) return
        pending = true
        errorText = ""
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.createAutomation(conversationId, trimmedTrigger, trimmedReply) }
                    .getOrElse { Result.failure(it) }
            }
            pending = false
            res.onSuccess {
                onCreated()
            }.onFailure {
                errorText = it.message ?: "Could not create the automation"
            }
        }
    }

    InfoSheetPanel(onDismiss = onDismiss) {
        InfoSheetHeader(
            icon = "LBot",
            title = "New automation",
            subtitle = "Fires once per matching message - as a reply from you",
        )
        Spacer(Modifier.height(12.dp))
        InfoFormLabel("Trigger keyword", "${trimmedTrigger.length}/40")
        InfoInputField(
            value = trigger,
            onValueChange = { if (it.length <= 48) trigger = it },
            placeholder = "e.g. pricing",
            invalid = trigger.isNotEmpty() && triggerInvalid,
            singleLine = true,
        )
        Text(
            if (trigger.isNotEmpty() && triggerInvalid) {
                "Use 2-40 characters."
            } else {
                "Matched as a standalone word - \"pricing\" will not fire on \"pricinggg\"."
            },
            color = if (trigger.isNotEmpty() && triggerInvalid) InfoInk.Rose else MirrorArt.Faint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        InfoFormLabel("Reply", "${trimmedReply.length}/500")
        InfoInputField(
            value = reply,
            onValueChange = { if (it.length <= 508) reply = it },
            placeholder = "Sent as a normal message when the keyword appears",
            invalid = reply.isNotEmpty() && replyInvalid,
            singleLine = false,
        )
        Text(
            if (reply.isNotEmpty() && replyInvalid) {
                "Use 1-500 characters."
            } else {
                "The message lands from your account, marked Automation."
            },
            color = if (reply.isNotEmpty() && replyInvalid) InfoInk.Rose else MirrorArt.Faint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
        if (errorText.isNotEmpty()) {
            Text(
                errorText,
                color = InfoInk.Rose,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        InfoSolidButton(
            label = "Create automation",
            icon = "LZap",
            enabled = valid && !pending,
            pending = pending,
            onClick = ::submit,
        )
    }
}

/** Solid amber full-width sheet button (web bg-amber-500 text-white). */
@Composable
private fun InfoSolidButton(
    label: String,
    icon: String,
    enabled: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(if (enabled) MirrorArt.Accent else MirrorArt.White7)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (pending) {
            InfoSpinner(Color.White, Modifier.size(16.dp))
        } else {
            MirrorLucideIcon(icon, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** "Edit trigger" sheet (web AutomationsEditSheet - only the keyword moves). */
@Composable
private fun InfoAutomationEditSheet(
    repository: PulseRepository,
    automation: Automation,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var trigger by remember { mutableStateOf(automation.trigger) }
    var pending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

    val trimmedTrigger = trigger.trim()
    val triggerInvalid = trimmedTrigger.length < 2 || trimmedTrigger.length > 40
    val unchanged = trimmedTrigger == automation.trigger
    val valid = !triggerInvalid && !unchanged

    fun submit() {
        if (!valid || pending) return
        pending = true
        errorText = ""
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.setAutomationTrigger(automation.id, trimmedTrigger) }
                    .getOrElse { Result.failure(it) }
            }
            pending = false
            res.onSuccess {
                onSaved()
            }.onFailure {
                errorText = it.message ?: "Could not rename the trigger"
            }
        }
    }

    InfoSheetPanel(onDismiss = onDismiss) {
        InfoSheetHeader(
            icon = "LPencilLine",
            title = "Edit trigger",
            subtitle = "Only the keyword moves - the reply stays unchanged",
        )
        Spacer(Modifier.height(12.dp))
        // read-only reply context
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MirrorArt.White7)
                .border(1.dp, InfoInk.FieldBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                "REPLY",
                color = MirrorArt.Faint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            Text(
                automation.reply,
                color = MirrorArt.TextSoft,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        InfoFormLabel("Trigger keyword", "${trimmedTrigger.length}/40")
        InfoInputField(
            value = trigger,
            onValueChange = { if (it.length <= 48) trigger = it },
            placeholder = "e.g. pricing",
            invalid = trigger.isNotEmpty() && triggerInvalid,
            singleLine = true,
        )
        Text(
            if (trigger.isNotEmpty() && triggerInvalid) {
                "Use 2-40 characters."
            } else {
                "Matched as a standalone word - must stay unique in this chat."
            },
            color = if (trigger.isNotEmpty() && triggerInvalid) InfoInk.Rose else MirrorArt.Faint,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
        if (errorText.isNotEmpty()) {
            Text(
                errorText,
                color = InfoInk.Rose,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        InfoSolidButton(
            label = "Save trigger",
            icon = "LCheck",
            enabled = valid && !pending,
            pending = pending,
            onClick = ::submit,
        )
    }
}

/**
 * Safety-number glass sheet (DMs, web SafetySheet): 12x5 digit grid,
 * compare caption, real verify / unverify with settle-refetch.
 */
@Composable
private fun InfoSafetySheet(
    repository: PulseRepository,
    peerId: String,
    peerName: String,
    peerColor: String?,
    onDismiss: () -> Unit,
    onMutated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SafetyState?>(null) }
    var failed by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

    fun refetch() {
        scope.launch {
            failed = false
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.safetyState(peerId) }.getOrElse { Result.failure(it) }
            }
            state = res.getOrNull()
            failed = res.isFailure
        }
    }

    LaunchedEffect(peerId) { refetch() }

    fun act(verify: Boolean) {
        if (pending) return
        pending = true
        errorText = ""
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    if (verify) repository.verifyPeer(peerId) else repository.unverifyPeer(peerId)
                }.getOrElse { Result.failure(it) }
            }
            pending = false
            res.onSuccess {
                state = it
                onMutated()
            }.onFailure {
                errorText = it.message ?: if (verify) "Could not verify the safety number" else "Could not reset the verification"
            }
        }
    }

    val digits = state?.safetyNumber?.filter { it.isDigit() }?.padStart(60, '0') ?: ""
    val groups = remember(digits) { if (digits.isEmpty()) emptyList() else digits.chunked(5).take(12) }

    InfoSheetPanel(onDismiss = onDismiss) {
        // peer identity row (web sheet header)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MirrorAvatar(
                name = peerName,
                color = peerColor,
                isGroup = false,
                groupId = "",
                online = false,
                showPresence = false,
                sizeDp = 40,
                cornerDp = 20,
            )
            Column(Modifier.weight(1f)) {
                Text(peerName, color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    "ENCRYPTION",
                    color = MirrorArt.Faint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (state == null && !failed) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in 0 until 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (col in 0 until 3) {
                            Box(Modifier.weight(1f)) {
                                InfoSkeleton(heightDp = 40, cornerDp = 12)
                            }
                        }
                    }
                }
            }
        } else if (groups.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (rowGroups in groups.chunked(3)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (group in rowGroups) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(InfoInk.Glass)
                                    .border(1.dp, InfoInk.FieldBorder, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    group,
                                    color = MirrorArt.Text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 1.8.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            "Compare these 60 digits with $peerName in person. If they match, mark this contact as verified.",
            color = MirrorArt.Dim,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 10.dp),
        )

        if (errorText.isNotEmpty()) {
            Text(
                errorText,
                color = InfoInk.Rose,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        if (failed && state == null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                InfoRetryPill { refetch() }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state?.verified == true) {
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(CircleShape)
                        .background(InfoInk.AmberSoft)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MirrorLucideIcon("LBadgeCheck", tint = MirrorArt.Accent2, modifier = Modifier.size(14.dp))
                    Text(
                        "Verified",
                        color = MirrorArt.Accent2,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    val verifiedAt = state?.verifiedAtIso
                    if (verifiedAt != null) {
                        Text(
                            "· ${infoListStamp(verifiedAt)}",
                            color = MirrorArt.Accent2,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .clip(CircleShape)
                        .background(MirrorArt.White7)
                        .clickable(enabled = !pending) { act(verify = false) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    if (pending) {
                        InfoSpinner(MirrorArt.Rose, Modifier.size(14.dp))
                    }
                    Text(
                        "Unverify",
                        color = InfoInk.Rose,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else if (state != null) {
                InfoSolidButton(
                    label = "Verify",
                    icon = "LShieldCheck",
                    enabled = !pending,
                    pending = pending,
                    onClick = { act(verify = true) },
                )
            }
        }
    }
}

/**
 * Add-members glass sub-view (web RoomMemberAddPage): the real directory
 * minus current members, live filter, multi-select rows and the floating
 * "Add N members" action bar over the real addGroupMembers API.
 */
@Composable
private fun InfoAddMembersOverlay(
    repository: PulseRepository,
    conversationId: String,
    existingIds: Set<String>,
    directory: List<User>,
    directoryLoading: Boolean,
    directoryFailed: Boolean,
    onClose: () -> Unit,
    onAdded: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }

    val query = draft.trim().lowercase(Locale.getDefault())
    val candidates = remember(directory, existingIds, query) {
        directory
            .filter { it.id !in existingIds }
            .filter { user ->
                query.isEmpty() ||
                    user.name.lowercase(Locale.getDefault()).contains(query) ||
                    user.handle.lowercase(Locale.getDefault()).contains(query)
            }
    }

    fun submit() {
        if (selected.isEmpty() || pending) return
        pending = true
        errorText = ""
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { repository.addGroupMembers(conversationId, selected.toList()) }
                    .getOrElse { Result.failure(it) }
            }
            pending = false
            res.onSuccess { added ->
                onAdded(added.size)
            }.onFailure {
                errorText = it.message ?: "Could not add members"
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MirrorArt.Bg)
            .statusBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            // header: back + title + selection count
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val backInteraction = remember { MutableInteractionSource() }
                val backPressed by backInteraction.collectIsPressedAsState()
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (backPressed) MirrorArt.White7 else MirrorArt.Chip)
                        .border(1.dp, MirrorArt.Hairline, CircleShape)
                        .clickable(interactionSource = backInteraction, indication = null, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LChevronLeft", tint = MirrorArt.TextSoft, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Add members", color = MirrorArt.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("People already on this Pulse", color = MirrorArt.Faint, fontSize = 11.sp)
                }
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(if (selected.isNotEmpty()) InfoInk.AmberPress else MirrorArt.White7)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        "${selected.size} selected",
                        color = if (selected.isNotEmpty()) MirrorArt.Accent2 else MirrorArt.Faint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // search
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.White7)
                            .border(1.dp, MirrorArt.Hairline, CircleShape)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MirrorLucideIcon("LSearch", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                        Box(Modifier.weight(1f)) {
                            if (draft.isEmpty()) {
                                Text("Search people", color = MirrorArt.Faint, fontSize = 14.sp)
                            }
                            BasicTextField(
                                value = draft,
                                onValueChange = { if (it.length <= 40) draft = it },
                                singleLine = true,
                                textStyle = TextStyle(color = MirrorArt.Text, fontSize = 14.sp),
                                cursorBrush = SolidColor(MirrorArt.Accent2),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (draft.isNotEmpty()) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable(onClick = { draft = "" }),
                                contentAlignment = Alignment.Center,
                            ) {
                                MirrorLucideIcon("LX", tint = MirrorArt.Faint, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            // directory
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 116.dp),
            ) {
                when {
                    directoryLoading -> {
                        InfoGlassCard(Modifier.fillMaxWidth().padding(6.dp)) {
                            Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                InfoSkeleton(heightDp = 52)
                                InfoSkeleton(heightDp = 52)
                                InfoSkeleton(widthFraction = 0.8f, heightDp = 52)
                            }
                        }
                    }
                    directoryFailed -> {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MirrorArt.White7),
                                contentAlignment = Alignment.Center,
                            ) {
                                MirrorLucideIcon("LUsersRound", tint = MirrorArt.Faint, modifier = Modifier.size(24.dp))
                            }
                            Text(
                                "Could not load the directory",
                                color = MirrorArt.TextSoft,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("Check your connection.", color = MirrorArt.Faint, fontSize = 12.sp)
                        }
                    }
                    candidates.isEmpty() -> {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(InfoInk.AmberSoft),
                                contentAlignment = Alignment.Center,
                            ) {
                                MirrorLucideIcon(
                                    if (query.isNotEmpty()) "LSearchX" else "LUsersRound",
                                    tint = MirrorArt.Accent2,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            Text(
                                if (query.isNotEmpty()) "No people match \u201C$query\u201D" else "Everyone is already here",
                                color = MirrorArt.TextSoft,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                if (query.isNotEmpty()) "Try a different name or @handle." else "Every account on this Pulse is already in this group.",
                                color = MirrorArt.Faint,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                    else -> {
                        InfoGlassCard(Modifier.fillMaxWidth().padding(6.dp)) {
                            Column(Modifier.fillMaxWidth().padding(6.dp)) {
                                for (user in candidates) {
                                    val isSelected = user.id in selected
                                    val interaction = remember(user.id) { MutableInteractionSource() }
                                    val pressed by interaction.collectIsPressedAsState()
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(
                                                when {
                                                    isSelected -> Color(0x14FFB86B)
                                                    pressed -> InfoInk.Press
                                                    else -> Color.Transparent
                                                },
                                            )
                                            .clickable(interactionSource = interaction, indication = null) {
                                                selected = if (isSelected) {
                                                    selected - user.id
                                                } else {
                                                    selected + user.id
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        MirrorAvatar(
                                            name = user.name,
                                            color = user.color,
                                            isGroup = false,
                                            groupId = "",
                                            online = false,
                                            showPresence = false,
                                            sizeDp = 40,
                                            cornerDp = 20,
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                user.name,
                                                color = MirrorArt.Text,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                if (user.handle.isNotBlank()) "@${user.handle}" else (user.bio?.takeIf { it.isNotBlank() } ?: "Member"),
                                                color = MirrorArt.Faint,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Box(
                                            Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) MirrorArt.Accent else Color.Transparent)
                                                .border(
                                                    1.dp,
                                                    if (isSelected) MirrorArt.Accent else MirrorArt.Hairline,
                                                    CircleShape,
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (isSelected) {
                                                MirrorLucideIcon(
                                                    "LCheck",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 3f,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // floating action bar over a scrim gradient
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x000D0906), Color(0x990D0906), Color(0xF00D0906)),
                    ),
                )
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (errorText.isNotEmpty()) {
                Text(
                    errorText,
                    color = InfoInk.Rose,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (selected.isNotEmpty() && !pending) MirrorArt.Accent else MirrorArt.White7)
                    .border(
                        1.dp,
                        if (selected.isNotEmpty() && !pending) Color.Transparent else MirrorArt.Hairline,
                        CircleShape,
                    )
                    .clickable(enabled = selected.isNotEmpty() && !pending, onClick = ::submit)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                if (pending) {
                    InfoSpinner(Color.White, Modifier.size(16.dp))
                } else {
                    MirrorLucideIcon(
                        "LUserRoundPlus",
                        tint = if (selected.isNotEmpty()) Color.White else MirrorArt.Faint,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    when {
                        pending -> "Adding\u2026"
                        selected.isEmpty() -> "Select people to add"
                        selected.size == 1 -> "Add 1 member"
                        else -> "Add ${selected.size} members"
                    },
                    color = if (selected.isEmpty()) MirrorArt.Faint else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** One member row: avatar + presence, name/crown/(you), directory subtitle, role actions. */
@Composable
private fun InfoMemberRow(
    member: ConversationMember,
    isMe: Boolean,
    online: Boolean,
    isAdminViewer: Boolean,
    adminCount: Int,
    dirUser: User?,
    rolePending: Boolean,
    rolePendingHere: Boolean,
    demoteArmed: Boolean,
    removeArmed: Boolean,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MirrorAvatar(
            name = member.name,
            color = member.color,
            isGroup = false,
            groupId = "",
            online = online,
            showPresence = true,
            sizeDp = 38,
            cornerDp = 19,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    member.name,
                    color = MirrorArt.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (member.role == "admin") {
                    MirrorLucideIcon("LCrown", tint = MirrorArt.Accent2, modifier = Modifier.size(12.dp))
                }
                if (isMe) {
                    Text("(you)", color = MirrorArt.Faint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
            val subtitle = buildString {
                if (member.role == "admin") append("Admin · ")
                val handle = dirUser?.handle?.takeIf { it.isNotBlank() }
                val about = dirUser?.bio?.takeIf { it.isNotBlank() }
                append(handle?.let { "@$it" } ?: about ?: "Member")
            }
            Text(
                subtitle,
                color = MirrorArt.Faint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // role actions (web guards mirrored: no dead UI)
        if (isAdminViewer && !isMe) {
            if (member.role != "admin") {
                val promoteInteraction = remember(member.id) { MutableInteractionSource() }
                val promotePressed by promoteInteraction.collectIsPressedAsState()
                Row(
                    Modifier
                        .heightIn(min = 28.dp)
                        .clip(CircleShape)
                        .background(if (promotePressed) InfoInk.AmberPress else MirrorArt.White7)
                        .clickable(
                            interactionSource = promoteInteraction,
                            indication = null,
                            enabled = !rolePending,
                            onClick = onPromote,
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (rolePendingHere) {
                        InfoSpinner(MirrorArt.Faint, Modifier.size(12.dp))
                    } else {
                        MirrorLucideIcon("LCrown", tint = MirrorArt.Faint, modifier = Modifier.size(12.dp))
                    }
                    Text(
                        "Make admin",
                        color = MirrorArt.TextSoft,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else if (adminCount > 1) {
                val demoteInteraction = remember(member.id) { MutableInteractionSource() }
                Box(
                    Modifier
                        .heightIn(min = 28.dp)
                        .clip(CircleShape)
                        .background(if (demoteArmed) InfoInk.Rose15 else InfoInk.AmberSoft)
                        .clickable(
                            interactionSource = demoteInteraction,
                            indication = null,
                            enabled = !rolePending,
                            onClick = onDemote,
                        )
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (rolePendingHere) {
                            InfoSpinner(MirrorArt.Faint, Modifier.size(12.dp))
                        } else {
                            MirrorLucideIcon("LCrown", tint = if (demoteArmed) InfoInk.Rose else MirrorArt.Accent2, modifier = Modifier.size(12.dp))
                        }
                        Text(
                            if (demoteArmed) "Dismiss?" else "Admin",
                            color = if (demoteArmed) InfoInk.Rose else MirrorArt.Accent2,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else {
                // the last admin cannot be dismissed - honest static chip
                Row(
                    Modifier
                        .heightIn(min = 28.dp)
                        .clip(CircleShape)
                        .background(InfoInk.AmberSoft)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MirrorLucideIcon("LCrown", tint = MirrorArt.Accent2, modifier = Modifier.size(12.dp))
                    Text("Admin", color = MirrorArt.Accent2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        // remove (viewer admin + other member, web API guards mirrored)
        if (isAdminViewer && !isMe && member.role != "admin") {
            if (removeArmed) {
                val confirmInteraction = remember(member.id) { MutableInteractionSource() }
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(InfoInk.Rose15)
                        .clickable(interactionSource = confirmInteraction, indication = null, enabled = !rolePending, onClick = onRemove)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text("Remove?", color = InfoInk.Rose, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                val removeInteraction = remember(member.id) { MutableInteractionSource() }
                val removePressed by removeInteraction.collectIsPressedAsState()
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (removePressed) InfoInk.Rose10 else Color.Transparent)
                        .clickable(
                            interactionSource = removeInteraction,
                            indication = null,
                            enabled = !rolePending,
                            onClick = onRemove,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LUserRoundMinus", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Material3 Switch with the mirror's amber checked track. */
@Composable
private fun InfoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MirrorArt.Accent,
            uncheckedThumbColor = MirrorArt.Dim,
            uncheckedTrackColor = MirrorArt.White7,
            uncheckedBorderColor = MirrorArt.Hairline,
        ),
    )
}
