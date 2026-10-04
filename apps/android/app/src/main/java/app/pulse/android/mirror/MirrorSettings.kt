package app.pulse.android.mirror

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.model.BlockedAccount
import app.pulse.domain.model.UserStats
import app.pulse.domain.repository.PulseRepository
import app.pulse.domain.push.PulsePushStatus
import app.pulse.protocol.PulseNavStyle
import app.pulse.protocol.WirePulsePrefs
import app.pulse.android.SessionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * R71 - the web SettingsScreen (settings-screen.tsx, R26-c) as a native
 * full-screen overlay. Root = compact grouped section list with LIVE hints;
 * every section opens a sub-page with the glass sub-header. Every control
 * binds to the same real state the web binds to:
 *   - Server-synced prefs (PATCH /api/settings via updatePulsePrefs):
 *     bubble corners, density, wallpaper, notification gates, privacy trio,
 *     reduced motion, fx.webglMode.
 *   - Device-side settings (DataStore, web localStorage parity): incoming
 *     sound, haptics, quiet hours window, chats list filter, UI language,
 *     navigation style, color mode (dark override).
 *   - Live data: user stats footprint (GET /api/users/:id/stats), blocked
 *     accounts (GET/DELETE /api/users/:id/block), realtime socket state,
 *     drafts + offline outbox counts, FCM push arming snapshot.
 * Zero mock numbers, zero dead rows.
 */

private const val SETTINGS_VERSION = "0.2.1"
private const val GITHUB_URL = "https://github.com/GrapseeAgency/the-mystrious-chat-app"

private enum class SettingsSection(val label: String, val glyph: String) {
    Account("Account", "PUserCircle"),
    Appearance("Appearance", "PPalette"),
    Chat("Chat", "PChatCenteredDots"),
    Notifications("Notifications", "PBellRinging"),
    Privacy("Privacy & Security", "PShieldCheck"),
    Realtime("Real-time & Voice", "PBroadcast"),
    Accessibility("Accessibility", "PPersonArmsSpread"),
    Data("Data & Storage", "PDatabase"),
    About("About", "PInfoLine"),
}

/** Resolved pref values with the web DEFAULT_PREFERENCES fallbacks. */
private data class ResolvedPrefs(
    val bubbleRadius: String,
    val density: String,
    val wallpaper: String,
    val notifPreviews: Boolean,
    val notifSound: Boolean,
    val notifVibrate: Boolean,
    val lastSeenVisible: Boolean,
    val readReceipts: Boolean,
    val typingVisible: Boolean,
    val reducedMotion: Boolean,
    val fxWebglMode: String,
)

private fun WirePulsePrefs.resolvedOrDefaults(): ResolvedPrefs = ResolvedPrefs(
    bubbleRadius = bubbleRadius ?: "lg",
    density = density ?: "cozy",
    wallpaper = wallpaper ?: "none",
    notifPreviews = notifPreviews ?: true,
    notifSound = notifSound ?: true,
    notifVibrate = notifVibrate ?: false,
    lastSeenVisible = lastSeenVisible ?: true,
    readReceipts = readReceipts ?: true,
    typingVisible = typingVisible ?: true,
    reducedMotion = reducedMotion ?: false,
    fxWebglMode = fxWebglMode ?: "off",
)

/** Two-note incoming ding (web playIncomingPing, WebAudio parity - zero assets). */
internal object MirrorPing {
    fun play(context: Context, soundOn: Boolean, haptics: Boolean) {
        if (haptics) {
            runCatching {
                val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vibrator.vibrate(VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
        if (!soundOn) return
        // 880Hz at t=0 and 1174.66Hz (D6) at t=120ms, 300ms tails, exponential
        // envelope with the 0.12 peak - the same two-note shape the browser renders.
        Thread {
            runCatching {
                val sampleRate = 44_100
                val total = (0.45 * sampleRate).toInt()
                val pcm = ShortArray(total)
                fun note(startSec: Double, freq: Double) {
                    val start = (startSec * sampleRate).toInt()
                    val len = (0.3 * sampleRate).toInt()
                    for (i in 0 until len) {
                        val idx = start + i
                        if (idx >= total) break
                        val t = i.toDouble() / sampleRate
                        val attack = if (t < 0.02) t / 0.02 else 1.0
                        val decay = Math.exp(-t * 7.5)
                        val envelope = 0.12 * attack * decay
                        val sample = envelope * Math.sin(2.0 * Math.PI * freq * t)
                        pcm[idx] = (pcm[idx] + sample * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                note(0.0, 880.0)
                note(0.12, 1174.66)
                val track = AudioTrack(
                    AudioManager.STREAM_NOTIFICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    pcm.size * 2,
                    AudioTrack.MODE_STATIC,
                )
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(520)
                track.release()
            }
        }.start()
    }
}

/** Clipboard helper with an honest toast (web toast.success parity). */
internal fun mirrorCopyText(context: Context, text: String, toast: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Pulse", text))
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }
}

private fun isQuietHoursNow(on: Boolean, start: String, end: String): Boolean {
    if (!on) return false
    fun minutes(hhmm: String): Int {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm.trim()) ?: return 0
        val h = m.groupValues[1].toIntOrNull()?.coerceIn(0, 23) ?: 0
        val min = m.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
        return h * 60 + min
    }
    val now = java.util.Calendar.getInstance()
    val cur = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
    val s = minutes(start)
    val e = minutes(end)
    if (s == e) return false
    return if (s < e) cur in s until e else cur >= s || cur < e
}

@Composable
internal fun MirrorSettingsScreen(
    repository: PulseRepository,
    session: SessionViewModel,
    viewerId: String,
    onEditProfile: () -> Unit,
    onOpenHub: () -> Unit,
    onClose: () -> Unit,
) {
    val prefsWire by repository.pulsePrefs.collectAsState(initial = WirePulsePrefs())
    val prefs = prefsWire.resolvedOrDefaults()
    val soundOn by session.soundOn.collectAsState()
    val hapticsOn by session.hapticsOn.collectAsState()
    val quietHoursOn by session.quietHoursOn.collectAsState()
    val quietStart by session.quietStart.collectAsState()
    val quietEnd by session.quietEnd.collectAsState()
    val listFilter by session.chatsListFilter.collectAsState()
    val uiTheme by session.uiTheme.collectAsState()
    val navStyle by session.navStyle.collectAsState()
    val darkOverride by session.darkOverride.collectAsState()
    val drafts by repository.observeDrafts().collectAsState(initial = emptyMap())
    val outbox by repository.observeOutbox().collectAsState(initial = emptyList())
    val connected by repository.observeConnected().collectAsState(initial = false)
    val presence by repository.observePresence().collectAsState(initial = emptySet())

    var section by remember { mutableStateOf<SettingsSection?>(null) }

    fun savePrefs(patch: WirePulsePrefs) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.updatePulsePrefs(patch) }
        }
    }

    val wallpaperLabel = when (prefs.wallpaper) {
        "aurora" -> "Aurora"
        "dusk" -> "Dusk"
        "forest" -> "Forest"
        "mono" -> "Mono"
        else -> "None"
    }
    val themeLabel = when (uiTheme) {
        "kinetic" -> "Kinetic"
        "minimal" -> "Quiet Minimal"
        "dynamic" -> "Dynamic"
        "aero" -> "Aero Kinetic"
        else -> "Immersive Glass"
    }
    val quietNow = isQuietHoursNow(quietHoursOn, quietStart, quietEnd)

    val hints: Map<SettingsSection, String> = mapOf(
        SettingsSection.Account to (if (viewerId.isBlank()) "Signed out" else "Session"),
        SettingsSection.Appearance to themeLabel,
        SettingsSection.Chat to "${if (prefs.density == "cozy") "Cozy" else "Compact"} · $wallpaperLabel",
        SettingsSection.Notifications to when {
            quietHoursOn -> "Quiet $quietStart - $quietEnd"
            soundOn -> "Alerts on"
            else -> "Alerts off"
        },
        SettingsSection.Privacy to if (prefs.readReceipts) "Read receipts on" else "Read receipts off",
        SettingsSection.Realtime to if (connected) "${presence.size} online" else "Offline",
        SettingsSection.Accessibility to if (prefs.reducedMotion) "Reduced motion" else "Full motion",
        SettingsSection.Data to "${drafts.size} drafts · ${outbox.size} queued",
        SettingsSection.About to "v$SETTINGS_VERSION",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF09090B)),
    ) {
        if (section == null) {
            // ROOT: PULSE eyebrow + Settings title + close, grouped section list.
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            "PULSE",
                            color = SubPageInk.Zinc500,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                        )
                        Text(
                            "Settings",
                            color = SubPageInk.Zinc50,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LX", tint = SubPageInk.Zinc400, modifier = Modifier.size(18.dp))
                    }
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 48.dp),
                ) {
                    SettingsGroup("Personal") {
                        settingsRootRow(SettingsSection.Account, hints) { section = SettingsSection.Account }
                        settingsRootRow(SettingsSection.Appearance, hints) { section = SettingsSection.Appearance }
                        settingsRootRow(SettingsSection.Chat, hints) { section = SettingsSection.Chat }
                        settingsRootRow(SettingsSection.Notifications, hints) { section = SettingsSection.Notifications }
                    }
                    SettingsGroup("System") {
                        settingsRootRow(SettingsSection.Privacy, hints) { section = SettingsSection.Privacy }
                        settingsRootRow(SettingsSection.Realtime, hints) { section = SettingsSection.Realtime }
                        settingsRootRow(SettingsSection.Accessibility, hints) { section = SettingsSection.Accessibility }
                    }
                    SettingsGroup("Data") {
                        settingsRootRow(SettingsSection.Data, hints) { section = SettingsSection.Data }
                    }
                    SettingsGroup("About") {
                        settingsRootRow(SettingsSection.About, hints) { section = SettingsSection.About }
                    }
                    Text(
                        "Pulse v$SETTINGS_VERSION - every control here is live.",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 12.dp),
                    )
                }
            }
        } else {
            val def = section!!
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                // glass sub-header: back pill + section glyph tile + title
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(SubPageInk.GlassPill)
                            .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                            .clickable { section = null },
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LChevronLeft", tint = SubPageInk.Zinc300, modifier = Modifier.size(18.dp))
                    }
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x14E5A33C)),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorPhosphorIcon(def.glyph, tint = SubPageInk.Amber400, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        def.label,
                        color = SubPageInk.Zinc50,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 48.dp),
                ) {
                    when (def) {
                        SettingsSection.Account -> AccountSection(repository, viewerId, onEditProfile)
                        SettingsSection.Appearance -> AppearanceSection(
                            darkOverride = darkOverride,
                            uiTheme = uiTheme,
                            navStyle = navStyle,
                            fxMode = prefs.fxWebglMode,
                            onColorMode = { value -> session.setDarkOverride(value) },
                            onUiTheme = { value -> session.setUiTheme(value) },
                            onNavStyle = { value -> session.setNavStyle(value) },
                            onFxMode = { savePrefs(WirePulsePrefs(fxWebglMode = it)) },
                        )
                        SettingsSection.Chat -> ChatSection(
                            prefs = prefs,
                            listFilter = listFilter,
                            drafts = drafts.size,
                            outbox = outbox.size,
                            onPrefs = { savePrefs(it) },
                            onListFilter = { value -> session.setChatsListFilter(value) },
                        )
                        SettingsSection.Notifications -> NotificationsSection(
                            prefs = prefs,
                            soundOn = soundOn,
                            hapticsOn = hapticsOn,
                            quietHoursOn = quietHoursOn,
                            quietStart = quietStart,
                            quietEnd = quietEnd,
                            quietNow = quietNow,
                            onSoundOn = { value -> session.setSoundOn(value) },
                            onPrefs = { savePrefs(it) },
                            onQuietOn = { value -> session.setQuietHoursOn(value) },
                            onQuietStart = { value -> session.setQuietStart(value) },
                            onQuietEnd = { value -> session.setQuietEnd(value) },
                        )
                        SettingsSection.Privacy -> PrivacySection(repository, prefs) { savePrefs(it) }
                        SettingsSection.Realtime -> RealtimeSection(repository, connected, presence.size)
                        SettingsSection.Accessibility -> AccessibilitySection(
                            prefs = prefs,
                            hapticsOn = hapticsOn,
                            onPrefs = { savePrefs(it) },
                            onHaptics = { value -> session.setHapticsOn(value) },
                        )
                        SettingsSection.Data -> DataSection(
                            repository = repository,
                            viewerId = viewerId,
                            drafts = drafts.size,
                            outbox = outbox.size,
                            onOpenHub = {
                                section = null
                                onOpenHub()
                            },
                        )
                        SettingsSection.About -> AboutSection()
                    }
                }
            }
        }
    }
}

// root + shared building blocks

@Composable
private fun SettingsGroup(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(
            label.uppercase(),
            color = SubPageInk.Zinc500,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SubPageInk.Panel)
                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(24.dp))
                .padding(6.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun settingsRootRow(def: SettingsSection, hints: Map<SettingsSection, String>, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconTile(def.glyph, phosphor = true)
        Column(Modifier.weight(1f)) {
            Text(def.label, color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                hints[def] ?: "",
                color = SubPageInk.Zinc500,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MirrorLucideIcon("LChevronRight", tint = SubPageInk.Zinc600, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SettingsIconTile(glyph: String, phosphor: Boolean = false) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x14E5A33C)),
        contentAlignment = Alignment.Center,
    ) {
        if (phosphor) {
            MirrorPhosphorIcon(glyph, tint = SubPageInk.Amber400, modifier = Modifier.size(18.dp))
        } else {
            MirrorLucideIcon(glyph, tint = SubPageInk.Amber400, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SettingsStatusBadge(tone: String, text: String) {
    val (bg, fg) = when (tone) {
        "ok", "warn" -> Color(0x1AF59E0B) to SubPageInk.Amber400
        "off" -> Color(0x1AF43F5E) to SubPageInk.Rose400
        else -> Color(0x14FFFFFF) to SubPageInk.Zinc400
    }
    Box(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsStaticRow(
    glyph: String,
    title: String,
    caption: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconTile(glyph)
        Column(Modifier.weight(1f)) {
            Text(title, color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (caption.isNotBlank()) {
                Text(caption, color = SubPageInk.Zinc500, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SettingsToggleRow(
    glyph: String,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.04f) else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconTile(glyph)
        Column(Modifier.weight(1f)) {
            Text(title, color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = SubPageInk.Zinc500, fontSize = 12.sp, lineHeight = 16.sp)
        }
        SettingsSwitch(checked = checked, enabled = enabled, onChange = onCheckedChange)
    }
}

/** Minimal glass switch - 40x22dp track, sliding 18dp knob (web Switch geometry). */
@Composable
private fun SettingsSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val track = when {
        !enabled -> Color(0x26FFFFFF)
        checked -> SubPageInk.Amber500
        else -> Color(0x33FFFFFF)
    }
    Row(
        Modifier
            .width(40.dp)
            .height(22.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked) Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (checked) Color(0xFF20150C) else Color.White),
        )
        if (!checked) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SettingsPickerRow(
    glyph: String,
    title: String,
    caption: String,
    value: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconTile(glyph)
        Column(Modifier.weight(1f)) {
            Text(title, color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (caption.isNotBlank()) {
                Text(
                    caption,
                    color = SubPageInk.Zinc500,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            value,
            color = SubPageInk.Zinc400,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        MirrorLucideIcon("LChevronRight", tint = SubPageInk.Zinc600, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SettingsPickerBlock(
    glyph: String,
    title: String,
    caption: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsIconTile(glyph)
            Column(Modifier.weight(1f)) {
                Text(title, color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (caption.isNotBlank()) {
                    Text(caption, color = SubPageInk.Zinc500, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
        content()
    }
}

/** The glass-pill segmented picker with the filled active segment. */
@Composable
private fun SettingsPillPicker(
    value: String,
    options: List<Pair<String, String>>,
    glyphFor: (String) -> String? = { null },
    onChange: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Color(0x14201A13))
            .border(1.dp, SubPageInk.PanelBorder, CircleShape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((id, label) in options) {
            val selected = value == id
            val fg = if (selected) Color.White else SubPageInk.Zinc400
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color(0xB352525B) else Color.Transparent)
                    .clickable { onChange(id) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                glyphFor(id)?.let { glyph ->
                    MirrorLucideIcon(glyph, tint = fg, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    label,
                    color = fg,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SettingsActionRow(
    glyph: String,
    title: String,
    caption: String,
    actionLabel: String,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconTile(glyph)
        Column(Modifier.weight(1f)) {
            Text(title, color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(caption, color = SubPageInk.Zinc500, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Box(
            Modifier
                .clip(CircleShape)
                .border(1.dp, if (enabled) Color(0x4DF43F5E) else SubPageInk.PanelBorder, CircleShape)
                .clickable(enabled = enabled, onClick = onAction)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                actionLabel,
                color = if (enabled) SubPageInk.Rose400 else SubPageInk.Zinc600,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SettingsFooterNote(text: String) {
    Text(
        text,
        color = SubPageInk.Zinc500,
        fontSize = 11.5.sp,
        lineHeight = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
    )
}

// Centered glass menu popups (web GlassMenu language)

private data class MirrorSettingsMenuRow(val glyph: String, val label: String, val trailing: String, val id: String)

@Composable
private fun MirrorSettingsMenu(
    title: String,
    width: Dp,
    rows: List<MirrorSettingsMenuRow>,
    footnote: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(width)
                .heightIn(max = minOf(LocalConfiguration.current.screenHeightDp * 0.7f, 480f).dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF21C1610))
                .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(6.dp)
                .clickable(enabled = false) {},
        ) {
            Text(
                title,
                color = MirrorArt.Faint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
            )
            for (row in rows) {
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val active = row.trailing == "check"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (pressed) Color.White.copy(alpha = 0.07f) else Color.Transparent)
                        .clickable(interactionSource = interaction, indication = null) { onPick(row.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MirrorLucideIcon(
                        row.glyph,
                        tint = if (active) SubPageInk.Amber400 else MirrorArt.Dim,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        row.label,
                        color = MirrorArt.Text,
                        fontSize = 13.5.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (active) {
                        MirrorLucideIcon("LCheck", tint = SubPageInk.Amber400, modifier = Modifier.size(16.dp))
                    } else if (row.trailing.isNotEmpty()) {
                        Text(
                            row.trailing.uppercase(),
                            color = MirrorArt.Faint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                        )
                    }
                }
            }
            if (footnote != null) {
                Spacer(
                    Modifier
                        .height(1.dp)
                        .fillMaxWidth()
                        .background(MirrorArt.Hairline)
                        .padding(top = 4.dp),
                )
                Text(
                    footnote,
                    color = MirrorArt.Faint,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
                )
            }
        }
    }
}

// ACCOUNT

@Composable
private fun AccountSection(repository: PulseRepository, viewerId: String, onEditProfile: () -> Unit) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf<app.pulse.domain.model.UserProfile?>(null) }
    LaunchedEffect(viewerId) {
        if (viewerId.isNotBlank()) {
            profile = runCatching { repository.userProfile(viewerId) }.getOrNull()?.getOrNull()
        }
    }
    val user = profile
    val statusLine = (user?.statusText ?: "").ifBlank { user?.about ?: "" }

    SettingsGroup("Profile") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MirrorAvatar(
                name = user?.name ?: viewerId,
                color = user?.color,
                isGroup = false,
                groupId = "",
                online = false,
                showPresence = false,
                sizeDp = 52,
                cornerDp = 26,
            )
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        user?.name ?: "Signed out",
                        color = SubPageInk.Zinc50,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x1AF59E0B))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "YOU",
                            color = SubPageInk.Amber400,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                        )
                    }
                }
                Text(
                    if (!user?.handle.isNullOrBlank()) "@${user?.handle}" else "No handle yet",
                    color = SubPageInk.Zinc500,
                    fontSize = 12.5.sp,
                )
                if (statusLine.isNotBlank()) {
                    Text(
                        statusLine,
                        color = SubPageInk.Zinc600,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                user?.createdAtIso?.let { iso ->
                    val parsed = runCatching { java.time.Instant.parse(iso) }.getOrNull()
                    if (parsed != null) {
                        val stamp = java.time.format.DateTimeFormatter.ofPattern("MMM yyyy")
                            .withZone(java.time.ZoneId.systemDefault()).format(parsed)
                        Text(
                            "Member since $stamp",
                            color = SubPageInk.Zinc600,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
        SettingsPickerRow(
            glyph = "LUserRound",
            title = "Edit profile",
            caption = "Name, handle, status and avatar - in the Profile tab",
            value = "Open",
            onClick = onEditProfile,
        )
    }

    SettingsGroup("Session") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsIconTile("LCopy")
            Column(Modifier.weight(1f)) {
                Text("User ID", color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    viewerId.ifBlank { "-" },
                    color = SubPageInk.Zinc500,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .clip(CircleShape)
                    .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                    .clickable(enabled = viewerId.isNotBlank()) {
                        mirrorCopyText(context, viewerId, "User ID copied")
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("Copy", color = SubPageInk.Zinc100, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        SettingsStaticRow(
            glyph = "LSmartphone",
            title = "Session scope",
            caption = "Signed in on this device - your session and chats live on this install.",
            trailing = {
                SettingsStatusBadge(
                    if (viewerId.isNotBlank()) "ok" else "off",
                    if (viewerId.isNotBlank()) "Active" else "None",
                )
            },
        )
    }
}

// APPEARANCE

private val UI_THEMES = listOf(
    Triple("glass", "Immersive Glass", "LSparkles"),
    Triple("kinetic", "Kinetic", "LZap"),
    Triple("minimal", "Quiet Minimal", "LFeather"),
    Triple("dynamic", "Dynamic", "LWandSparkles"),
    Triple("aero", "Aero Kinetic", "LWind"),
)

private val THEME_DETAIL = mapOf(
    "glass" to "Layered frosted glass, aurora backdrop, specular edges, elastic motion.",
    "kinetic" to "High-contrast ink, sharp corners, bold type, whip-crack springs.",
    "minimal" to "Hairlines and whitespace. Motion whispers, structure speaks.",
    "dynamic" to "Soft neutrals with vivid gradient accents and playful bounce.",
    "aero" to "Frost-stroke panels on cool graphite, gliding inertia.",
)

private val NAV_STYLES: List<Pair<PulseNavStyle, Triple<String, String, String>>> = listOf(
    PulseNavStyle.CAPSULE to Triple("Floating Capsule", "LComponent", "bottom"),
    PulseNavStyle.FLOATING_TOP to Triple("Floating Top Nav", "LPanelTop", "top"),
    PulseNavStyle.FLOATING_DOCK to Triple("Floating Dock", "LAppWindow", "bottom"),
    PulseNavStyle.PILL to Triple("Pill Navigation", "LPill", "bottom"),
    PulseNavStyle.BOTTOM_BAR to Triple("Bottom Bar", "LPanelBottom", "bottom"),
    PulseNavStyle.TAB_BAR to Triple("Tab Bar", "LColumns3", "bottom"),
    PulseNavStyle.FLOATING_TAB_BAR to Triple("Floating Tab Bar", "LSquareStack", "bottom"),
    PulseNavStyle.COMMAND_BAR to Triple("Command Bar", "LCommand", "top"),
    PulseNavStyle.RAIL to Triple("Navigation Rail", "LPanelLeft", "side"),
    PulseNavStyle.ISLAND to Triple("Island Navigation", "LCircleEllipsis", "bottom"),
    PulseNavStyle.RADIAL to Triple("Radial Navigation", "LRadar", "overlay"),
    PulseNavStyle.GESTURE to Triple("Gesture Navigation", "LHand", "bottom"),
    PulseNavStyle.CONTEXTUAL_DOCK to Triple("Contextual Dock", "LWorkflow", "bottom"),
)

private val NAV_HINTS = mapOf(
    PulseNavStyle.CAPSULE to "Detached glass capsule dock - the default",
    PulseNavStyle.FLOATING_TOP to "Capsule bar floating beneath the top edge",
    PulseNavStyle.FLOATING_DOCK to "Desktop-style dock with magnifying icons",
    PulseNavStyle.PILL to "Single segmented pill with sliding fill",
    PulseNavStyle.BOTTOM_BAR to "Classic edge-to-edge bottom bar",
    PulseNavStyle.TAB_BAR to "iOS-style tab bar with tinted squircles",
    PulseNavStyle.FLOATING_TAB_BAR to "Detached card, elevated active tab",
    PulseNavStyle.COMMAND_BAR to "Compact text command strip with search",
    PulseNavStyle.RAIL to "Persistent vertical side rail",
    PulseNavStyle.ISLAND to "Dynamic-island pill that expands on tap",
    PulseNavStyle.RADIAL to "FAB fanning destinations in an arc",
    PulseNavStyle.GESTURE to "Edge swipes + gesture pill quick switcher",
    PulseNavStyle.CONTEXTUAL_DOCK to "Dock that adapts to the active tab",
)

@Composable
private fun AppearanceSection(
    darkOverride: String,
    uiTheme: String,
    navStyle: PulseNavStyle,
    fxMode: String,
    onColorMode: (String) -> Unit,
    onUiTheme: (String) -> Unit,
    onNavStyle: (PulseNavStyle) -> Unit,
    onFxMode: (String) -> Unit,
) {
    var menu by remember { mutableStateOf<String?>(null) }
    val themeMeta = UI_THEMES.firstOrNull { it.first == uiTheme } ?: UI_THEMES[0]
    val navMeta = NAV_STYLES.firstOrNull { it.first == navStyle } ?: NAV_STYLES[0]

    SettingsGroup("Color mode") {
        SettingsPickerBlock(
            glyph = when (darkOverride) {
                "light" -> "LSun"
                "dark" -> "LMoon"
                else -> "LMonitor"
            },
            title = "Light / dark",
            caption = "System follows your device setting.",
        ) {
            SettingsPillPicker(
                value = darkOverride,
                options = listOf("light" to "Light", "dark" to "Dark", "system" to "System"),
                glyphFor = { id -> when (id) { "light" -> "LSun"; "dark" -> "LMoon"; else -> "LMonitor" } },
                onChange = onColorMode,
            )
        }
    }

    SettingsGroup("UI language") {
        SettingsPickerRow(
            glyph = themeMeta.third,
            title = "Design language",
            caption = THEME_DETAIL[themeMeta.first] ?: "",
            value = themeMeta.second,
            onClick = { menu = "ui-theme" },
        )
        SettingsFooterNote(
            "Running ${themeMeta.second} with elastic motion - each language restyles every surface through its own design tokens, instantly.",
        )
    }

    SettingsGroup("Navigation") {
        SettingsPickerRow(
            glyph = navMeta.second.third,
            title = "Navigation style",
            caption = "${NAV_HINTS[navMeta.first]} - ${navMeta.second.third} zone",
            value = navMeta.second.first,
            onClick = { menu = "nav-style" },
        )
        SettingsFooterNote(
            "Thirteen architectures are available - switching applies to the shell navigation immediately.",
        )
    }

    SettingsGroup("Ambient field") {
        SettingsPickerBlock(
            glyph = "LSparkles",
            title = "WebGL ambience",
            caption = "A living GPU shader field behind every surface - pauses when hidden, honors reduced motion.",
        ) {
            SettingsPillPicker(
                value = fxMode,
                options = listOf(
                    "off" to "Off",
                    "aurora" to "Aurora",
                    "caustics" to "Caustics",
                    "mesh" to "Mesh",
                    "stars" to "Stars",
                    "liquid" to "Liquid",
                ),
                glyphFor = { id ->
                    when (id) {
                        "off" -> "LCircleSlash"
                        "aurora" -> "LSparkles"
                        "caustics" -> "LWaves"
                        "mesh" -> "LBlend"
                        "stars" -> "LStar"
                        "liquid" -> "LDroplet"
                        else -> null
                    }
                },
                onChange = onFxMode,
            )
        }
        SettingsFooterNote(
            "Six realtime shader modes - off keeps the DOM particle layer instead. Each mode is a different ambient world: aurora bands, glass caustics, gradient mesh, star drift, liquid metaballs.",
        )
    }

    if (menu == "ui-theme") {
        MirrorSettingsMenu(
            title = "UI language",
            width = 272.dp,
            rows = UI_THEMES.map { (id, label, glyph) ->
                MirrorSettingsMenuRow(glyph, label, if (uiTheme == id) "check" else "", id)
            },
            footnote = THEME_DETAIL[uiTheme],
            onPick = {
                onUiTheme(it)
                menu = null
            },
            onDismiss = { menu = null },
        )
    }
    if (menu == "nav-style") {
        MirrorSettingsMenu(
            title = "Navigation style",
            width = 288.dp,
            rows = NAV_STYLES.map { (id, meta) ->
                MirrorSettingsMenuRow(
                    meta.second,
                    meta.first,
                    if (navStyle == id) "check" else meta.third,
                    id.id,
                )
            },
            footnote = null,
            onPick = { id ->
                NAV_STYLES.firstOrNull { it.first.id == id }?.let { onNavStyle(it.first) }
                menu = null
            },
            onDismiss = { menu = null },
        )
    }
}

// CHAT

private data class MirrorWallpaper(val id: String, val label: String, val colors: List<Color>)

private val WALLPAPERS = listOf(
    MirrorWallpaper("none", "None", listOf(Color(0xFF27272A), Color(0xFF1F1F23))),
    MirrorWallpaper("aurora", "Aurora", listOf(Color(0xFF064E3B), Color(0xFF042F2E), Color(0xFF047857))),
    MirrorWallpaper("dusk", "Dusk", listOf(Color(0xFF451A03), Color(0xFF4C0519), Color(0xFF27272A))),
    MirrorWallpaper("forest", "Forest", listOf(Color(0xFF052E16), Color(0xFF064E3B), Color(0xFF15803D))),
    MirrorWallpaper("mono", "Mono", listOf(Color(0xFF3F3F46), Color(0xFF18181B))),
)

@Composable
private fun ChatSection(
    prefs: ResolvedPrefs,
    listFilter: String,
    drafts: Int,
    outbox: Int,
    onPrefs: (WirePulsePrefs) -> Unit,
    onListFilter: (String) -> Unit,
) {
    SettingsGroup("Message look") {
        SettingsPickerBlock(
            glyph = "LImage",
            title = "Chat wallpaper",
            caption = "Background behind every chat room - currently ${
                WALLPAPERS.firstOrNull { it.id == prefs.wallpaper }?.label ?: "None"
            }.",
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (w in WALLPAPERS) {
                    val selected = prefs.wallpaper == w.id
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { onPrefs(WirePulsePrefs(wallpaper = w.id)) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Brush.linearGradient(w.colors))
                                .border(
                                    1.dp,
                                    if (selected) SubPageInk.Amber500 else Color(0x1AFFFFFF),
                                    RoundedCornerShape(10.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                MirrorLucideIcon("LCheck", tint = SubPageInk.Amber400, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            w.label,
                            color = if (selected) SubPageInk.Amber400 else SubPageInk.Zinc500,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        SettingsPickerBlock(glyph = "LMessagesSquare", title = "Bubble corners", caption = "Corner radius of message bubbles.") {
            SettingsPillPicker(
                value = prefs.bubbleRadius,
                options = listOf("md" to "Medium", "lg" to "Large", "pill" to "Pill"),
                onChange = { onPrefs(WirePulsePrefs(bubbleRadius = it)) },
            )
        }
        SettingsPickerBlock(glyph = "LColumns3", title = "Message density", caption = "Row spacing in the message list.") {
            SettingsPillPicker(
                value = prefs.density,
                options = listOf("cozy" to "Cozy", "compact" to "Compact"),
                onChange = { onPrefs(WirePulsePrefs(density = it)) },
            )
        }
    }

    SettingsGroup("Chats list") {
        SettingsPickerBlock(glyph = "LEye", title = "Default list filter", caption = "Applied to the Chats tab when you open it.") {
            SettingsPillPicker(
                value = listFilter,
                options = listOf("all" to "All", "unread" to "Unread", "groups" to "Groups"),
                onChange = onListFilter,
            )
        }
    }

    SettingsGroup("Drafts & outbox") {
        SettingsStaticRow(
            glyph = "LFileText",
            title = "Saved drafts",
            caption = "Per-conversation composer drafts stored on this device.",
            trailing = { SettingsStatusBadge("info", drafts.toString()) },
        )
        SettingsStaticRow(
            glyph = "LCloudOff",
            title = "Offline queue",
            caption = if (outbox > 0) {
                "$outbox ${if (outbox == 1) "message" else "messages"} waiting to send when you're back online."
            } else {
                "Empty - every composed message has been delivered."
            },
            trailing = {
                if (outbox > 0) {
                    SettingsStatusBadge("warn", outbox.toString())
                } else {
                    MirrorLucideIcon("LCheck", tint = SubPageInk.Amber400, modifier = Modifier.size(16.dp))
                }
            },
        )
    }
}

// NOTIFICATIONS

@Composable
private fun NotificationsSection(
    prefs: ResolvedPrefs,
    soundOn: Boolean,
    hapticsOn: Boolean,
    quietHoursOn: Boolean,
    quietStart: String,
    quietEnd: String,
    quietNow: Boolean,
    onSoundOn: (Boolean) -> Unit,
    onPrefs: (WirePulsePrefs) -> Unit,
    onQuietOn: (Boolean) -> Unit,
    onQuietStart: (String) -> Unit,
    onQuietEnd: (String) -> Unit,
) {
    SettingsGroup("Alerts") {
        SettingsToggleRow(
            glyph = "LVolume2",
            title = "Incoming sound",
            description = "Master ding for new messages on this device.",
            checked = soundOn,
            onCheckedChange = onSoundOn,
        )
        SettingsToggleRow(
            glyph = "LEye",
            title = "Message previews",
            description = "Show message text in notification banners.",
            checked = prefs.notifPreviews,
            onCheckedChange = { onPrefs(WirePulsePrefs(notifPreviews = it)) },
        )
        SettingsToggleRow(
            glyph = "LBell",
            title = "Message pop",
            description = "Per-account pop sound for incoming messages.",
            checked = prefs.notifSound,
            onCheckedChange = { onPrefs(WirePulsePrefs(notifSound = it)) },
        )
        SettingsToggleRow(
            glyph = "LSmartphone",
            title = "Vibration",
            description = "Buzz on incoming messages, where the device supports it.",
            checked = prefs.notifVibrate,
            onCheckedChange = { onPrefs(WirePulsePrefs(notifVibrate = it)) },
        )
        RemotePushRow()
    }

    SettingsGroup("Quiet hours") {
        SettingsToggleRow(
            glyph = "LMoonStar",
            title = "Quiet hours",
            description = "Silence sounds and vibration inside the window.",
            checked = quietHoursOn,
            onCheckedChange = onQuietOn,
        )
        if (quietHoursOn) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuietTimeField("From", quietStart, onQuietStart, Modifier.weight(1f))
                QuietTimeField("Until", quietEnd, onQuietEnd, Modifier.weight(1f))
            }
            SettingsStaticRow(
                glyph = "LMoonStar",
                title = "Window status",
                caption = "$quietStart - $quietEnd - overnight windows are supported.",
                trailing = { SettingsStatusBadge(if (quietNow) "warn" else "info", if (quietNow) "Active now" else "Idle") },
            )
        }
    }

    SettingsGroup("Test") {
        val context = LocalContext.current
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(12.dp))
                    .clickable { MirrorPing.play(context, soundOn = soundOn, haptics = hapticsOn) },
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MirrorLucideIcon("LPlay", tint = SubPageInk.Zinc100, modifier = Modifier.size(16.dp))
                    Text(
                        "Preview alert",
                        color = SubPageInk.Zinc100,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                "Plays the real two-note ding and fires a haptic buzz, honoring the toggles above.",
                color = SubPageInk.Zinc600,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Remote push - the honest FCM state (PulsePush publishes the real snapshot). */
@Composable
private fun RemotePushRow() {
    val push = remember { PulsePushStatus.snapshot() }
    val caption = when {
        !push.armed -> "Push is not configured in this build - credentials are missing."
        !push.viewerBound -> "Armed - the token binds to your account on sign-in."
        push.hasToken -> "This device receives pushes even with the app closed."
        else -> "Registering this device with the server transport..."
    }
    SettingsStaticRow(
        glyph = "LBellRing",
        title = "Remote push",
        caption = caption,
        trailing = {
            SettingsStatusBadge(
                when {
                    push.armed && push.viewerBound && push.hasToken -> "ok"
                    push.armed -> "warn"
                    else -> "off"
                },
                when {
                    push.armed && push.viewerBound && push.hasToken -> "On"
                    push.armed -> "Pending"
                    else -> "Off"
                },
            )
        },
    )
}

/** 'HH:MM' field with web time-input semantics - validated before it lands. */
@Composable
private fun QuietTimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var draft by remember(value) { mutableStateOf(value) }
    Column(modifier) {
        Text(
            label.uppercase(),
            color = SubPageInk.Zinc600,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = draft,
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() || it == ':' }.take(5)
                draft = cleaned
                if (Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(cleaned)) onChange(cleaned)
            },
            singleLine = true,
            textStyle = TextStyle(
                color = SubPageInk.Zinc100,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            cursorBrush = SolidColor(SubPageInk.Amber500),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x14FFFFFF))
                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (draft.isBlank()) Text("22:00", color = SubPageInk.Zinc600, fontSize = 13.5.sp)
                inner()
            },
        )
    }
}

// PRIVACY

@Composable
private fun PrivacySection(
    repository: PulseRepository,
    prefs: ResolvedPrefs,
    onPrefs: (WirePulsePrefs) -> Unit,
) {
    var blocks by remember { mutableStateOf<List<BlockedAccount>>(emptyList()) }
    var loadingBlocks by remember { mutableStateOf(false) }
    var showBlocked by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showBlocked) {
        if (showBlocked) {
            loadingBlocks = true
            blocks = runCatching { repository.blockedAccounts() }
                .getOrNull()?.getOrNull() ?: emptyList()
            loadingBlocks = false
        }
    }

    SettingsGroup("Visibility") {
        SettingsToggleRow(
            glyph = "LEye",
            title = "Last seen & online",
            description = "Let people see when you were last active or online.",
            checked = prefs.lastSeenVisible,
            onCheckedChange = { onPrefs(WirePulsePrefs(lastSeenVisible = it)) },
        )
        SettingsToggleRow(
            glyph = "LCheckCheck",
            title = "Read receipts",
            description = "Show others when you've read their messages.",
            checked = prefs.readReceipts,
            onCheckedChange = { onPrefs(WirePulsePrefs(readReceipts = it)) },
        )
        SettingsToggleRow(
            glyph = "LPenLine",
            title = "Typing indicator",
            description = "Show others when you're typing.",
            checked = prefs.typingVisible,
            onCheckedChange = { onPrefs(WirePulsePrefs(typingVisible = it)) },
        )
    }

    SettingsGroup("Safety") {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { showBlocked = !showBlocked }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsIconTile("LBan")
            Column(Modifier.weight(1f)) {
                Text("Blocked accounts", color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !showBlocked && blocks.isEmpty() -> "No blocked accounts"
                        loadingBlocks -> "Loading..."
                        blocks.isEmpty() -> "No blocked accounts"
                        blocks.size == 1 -> "1 account blocked"
                        else -> "${blocks.size} accounts blocked"
                    },
                    color = SubPageInk.Zinc500,
                    fontSize = 12.sp,
                )
            }
            MirrorLucideIcon(
                "LChevronDown",
                tint = SubPageInk.Zinc500,
                modifier = Modifier.size(16.dp),
            )
        }
        if (showBlocked) {
            if (blocks.isEmpty()) {
                Text(
                    if (loadingBlocks) "Loading..." else "Nobody is blocked. Blocked accounts cannot message you in direct chats.",
                    color = SubPageInk.Zinc500,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            } else {
                for (account in blocks) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MirrorAvatar(
                            name = account.name,
                            color = account.color,
                            isGroup = false,
                            groupId = "",
                            online = false,
                            showPresence = false,
                            sizeDp = 34,
                            cornerDp = 17,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                account.name,
                                color = SubPageInk.Zinc100,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val stamp = account.blockedAtIso?.let { iso ->
                                runCatching { java.time.Instant.parse(iso) }.getOrNull()?.let {
                                    java.time.format.DateTimeFormatter.ofPattern("MMM d")
                                        .withZone(java.time.ZoneId.systemDefault()).format(it)
                                }
                            }
                            Text(
                                if (stamp != null) "Blocked $stamp" else "Blocked",
                                color = SubPageInk.Zinc600,
                                fontSize = 11.sp,
                            )
                        }
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                                .clickable(enabled = busyId == null) {
                                    busyId = account.id
                                    CoroutineScope(Dispatchers.IO).launch {
                                        runCatching { repository.unblock(account.id) }
                                        blocks = runCatching { repository.blockedAccounts() }
                                            .getOrNull()?.getOrNull() ?: blocks
                                        busyId = null
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                if (busyId == account.id) "..." else "Unblock",
                                color = SubPageInk.Zinc100,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
    SettingsFooterNote(
        "These sync to your Pulse account and are enforced server-side - hidden last-seen also hides your online status, hidden typing ends the relay before it reaches anyone, and blocked accounts cannot DM you.",
    )
}

// REALTIME

@Composable
private fun RealtimeSection(repository: PulseRepository, connected: Boolean, onlineCount: Int) {
    val context = LocalContext.current
    val deviceOnline = remember {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(false)
    }
    SettingsGroup("Connection") {
        SettingsStaticRow(
            glyph = if (connected) "LShieldCheck" else "LTriangleAlert",
            title = if (connected) "Realtime socket" else "Reconnecting",
            caption = if (connected) {
                "Connected - messages, presence and typing stream live."
            } else {
                "Socket offline - outgoing messages queue in the offline outbox."
            },
            trailing = { SettingsStatusBadge(if (connected) "ok" else "off", if (connected) "Live" else "Down") },
        )
        SettingsStaticRow(
            glyph = "LUsersRound",
            title = "People online now",
            caption = "Live presence snapshot from the socket server.",
            trailing = { SettingsStatusBadge("info", onlineCount.toString()) },
        )
        SettingsStaticRow(
            glyph = if (deviceOnline) "LWifi" else "LCloudOff",
            title = "Device network",
            caption = if (deviceOnline) {
                "This device is online - delivery is instant."
            } else {
                "This device is offline - messages wait in the queue."
            },
            trailing = { SettingsStatusBadge(if (deviceOnline) "ok" else "warn", if (deviceOnline) "Online" else "Offline") },
        )
    }
    SettingsGroup("Voice") {
        SettingsStaticRow(
            glyph = "LMic",
            title = "Voice rooms",
            caption = "Studio capture with echo cancellation, noise suppression and auto gain. Quality presets are not configurable yet.",
        )
    }
}

// ACCESSIBILITY

@Composable
private fun AccessibilitySection(
    prefs: ResolvedPrefs,
    hapticsOn: Boolean,
    onPrefs: (WirePulsePrefs) -> Unit,
    onHaptics: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // The honest Android signal for "reduce motion": the system animator scale.
    val sysReduced = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    SettingsGroup("Motion") {
        SettingsToggleRow(
            glyph = "LAccessibility",
            title = "Reduced motion",
            description = "Calm the interface - instant transitions, no parallax or message effects.",
            checked = prefs.reducedMotion,
            onCheckedChange = { onPrefs(WirePulsePrefs(reducedMotion = it)) },
        )
        SettingsStaticRow(
            glyph = "LMonitor",
            title = "System preference",
            caption = if (sysReduced) "Your OS asks apps to reduce motion." else "Your OS has no motion restriction set.",
            trailing = { SettingsStatusBadge(if (sysReduced) "info" else "ok", if (sysReduced) "Reduce" else "Full") },
        )
    }
    SettingsGroup("Touch feedback") {
        SettingsToggleRow(
            glyph = "LVibrate",
            title = "Haptics",
            description = "Vibration on taps, sends and incoming alerts - where the device supports it.",
            checked = hapticsOn,
            onCheckedChange = onHaptics,
        )
    }
}

// DATA & STORAGE

@Composable
private fun DataSection(
    repository: PulseRepository,
    viewerId: String,
    drafts: Int,
    outbox: Int,
    onOpenHub: () -> Unit,
) {
    var stats by remember { mutableStateOf<UserStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val context = LocalContext.current

    suspend fun loadStats() {
        loading = true
        failed = false
        val result = runCatching { repository.userStats(viewerId) }.getOrNull()?.getOrNull()
        stats = result
        failed = result == null
        loading = false
    }
    LaunchedEffect(viewerId) {
        if (viewerId.isNotBlank()) loadStats()
        else loading = false
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
    ) {
        Text(
            "YOUR FOOTPRINT",
            color = SubPageInk.Zinc500,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(SubPageInk.Panel)
                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(24.dp))
                .padding(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsIconTile("LDatabase")
                Column(Modifier.weight(1f)) {
                    Text("Live counts", color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Straight from the Pulse database.", color = SubPageInk.Zinc500, fontSize = 12.sp)
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            CoroutineScope(Dispatchers.IO).launch { loadStats() }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LRefreshCw", tint = SubPageInk.Zinc500, modifier = Modifier.size(16.dp))
                }
            }
            when {
                viewerId.isBlank() -> {
                    Text(
                        "Sign in to see your stats.",
                        color = SubPageInk.Zinc400,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                loading -> {
                    Text(
                        "Loading your footprint...",
                        color = SubPageInk.Zinc500,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                failed || stats == null -> {
                    Text(
                        "Couldn't load your stats.",
                        color = SubPageInk.Zinc400,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                else -> {
                    val s = stats!!
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsStatTile("LMessagesSquare", s.messages, "Messages sent", Modifier.weight(1f))
                        SettingsStatTile("LImage", s.photos, "Photos", Modifier.weight(1f))
                        SettingsStatTile("LMic", s.voiceNotes, "Voice notes", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsStatTile("LUserRound", s.chats, "Chats", Modifier.weight(1f))
                        SettingsStatTile("LUsersRound", s.groups, "Groups", Modifier.weight(1f))
                        SettingsStatTile("LCalendarDays", s.days, "Days active", Modifier.weight(1f))
                    }
                }
            }
        }
    }

    SettingsGroup("Local data") {
        SettingsActionRow(
            glyph = "LFileText",
            title = "Composer drafts",
            caption = "$drafts saved on this device - clearing frees their storage.",
            actionLabel = "Clear",
            enabled = drafts > 0,
            onAction = {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { repository.clearAllDrafts() }
                }
            },
        )
        SettingsActionRow(
            glyph = "LCloudOff",
            title = "Offline queue",
            caption = if (outbox > 0) {
                "$outbox waiting - discarding drops them without sending."
            } else {
                "Empty - nothing queued right now."
            },
            actionLabel = "Discard",
            enabled = outbox > 0,
            onAction = {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { repository.clearOutbox() }
                }
            },
        )
    }

    SettingsGroup("Install") {
        SettingsStaticRow(
            glyph = "LSmartphone",
            title = "Install Pulse",
            caption = "This is the native app - it is already installed on your device.",
        )
    }

    SettingsGroup("Explore") {
        SettingsPickerRow(
            glyph = "LInfo",
            title = "The Hub",
            caption = "Wallet · Tasks · Market · Swap · Apps · Logs",
            value = "Open",
            onClick = onOpenHub,
        )
    }
}

@Composable
private fun SettingsStatTile(glyph: String, value: Long, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x0AFFFFFF))
            .border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(16.dp))
            .padding(10.dp),
    ) {
        MirrorLucideIcon(glyph, tint = SubPageInk.Amber400, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            value.toString(),
            color = SubPageInk.Zinc50,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(label, color = SubPageInk.Zinc500, fontSize = 10.5.sp, lineHeight = 13.sp)
    }
}

// ABOUT

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SubPageInk.Panel)
            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(24.dp))
            .padding(16.dp)
            .padding(bottom = 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFBBF24), Color(0xFFEA580C)))),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LSparkles", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Pulse", color = SubPageInk.Zinc50, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Real-time chat with a built-in economy", color = SubPageInk.Zinc500, fontSize = 12.sp)
            }
            SettingsStatusBadge("info", "v$SETTINGS_VERSION")
        }
        Text(
            "Chats, groups, topics, threads, polls, games, red packets, voice rooms, stages and the Hub economy all run on the real Pulse API with zero mock data - and every control in these settings is backed by live state.",
            color = SubPageInk.Zinc500,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    SettingsGroup("Build") {
        SettingsStaticRow(
            glyph = "LInfo",
            title = "Version",
            caption = SETTINGS_VERSION,
            trailing = { SettingsStatusBadge("info", "Stable") },
        )
        SettingsStaticRow(
            glyph = "LComponent",
            title = "Framework",
            caption = "Kotlin · Jetpack Compose · Material 3",
        )
        SettingsStaticRow(
            glyph = "LCommand",
            title = "Realtime",
            caption = "socket.io relay on port 3003 via the gateway",
        )
        SettingsStaticRow(
            glyph = "LDatabase",
            title = "Data",
            caption = "Prisma ORM + SQLite on the server, zero mock data",
        )
    }

    SettingsGroup("Project") {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_URL)))
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsIconTile("LGithub")
            Column(Modifier.weight(1f)) {
                Text("GitHub repository", color = SubPageInk.Zinc50, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "GrapseeAgency/the-mystrious-chat-app",
                    color = SubPageInk.Zinc500,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MirrorLucideIcon("LExternalLink", tint = SubPageInk.Zinc600, modifier = Modifier.size(16.dp))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MirrorLucideIcon("LHeart", tint = SubPageInk.Amber500, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(6.dp))
        Text("Made with Pulse", color = SubPageInk.Zinc300, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Version $SETTINGS_VERSION · chats, hub economy and settings sync live",
            color = SubPageInk.Zinc600,
            fontSize = 11.sp,
        )
    }
}
