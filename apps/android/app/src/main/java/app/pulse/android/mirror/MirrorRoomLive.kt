package app.pulse.android.mirror

// R69-c - live room sheets, native mirror of the web "Beyond Chat" surfaces:
//   voice-room-sheet.tsx  -> MirrorVoiceRoomSheet  (live walkie-talkie room)
//   stage-room-sheet.tsx  -> MirrorStageSheet      (host/speakers/listeners stage)
//   space-sheet.tsx       -> MirrorSpaceSheet      (spatial presence map)
//   tournament-sheet.tsx  -> MirrorTournamentSheet (season list + composer + join)
//
// Realtime state rides ONLY repository.events() (the same PulseEvent stream
// MirrorRoot collects) - VoiceRoster / VoicePtt / VoiceTranscript / StageState /
// StageEnded / SpaceState. Actions call the real repository emits
// (emitVoiceJoin/Ptt/Leave, emitStageJoin/Hand/Approve/Mute/End/Leave,
// emitSpaceJoin/Move/Leave) and the real tournaments endpoints. Zero mock data.
//
// Honest deltas vs the web engine (the web's mic capture + PCM playback pipeline
// is a browser AudioWorklet stack the repository does not expose): the native
// sheet drives the room's real control plane - join/leave seats, the PTT latch
// (press-and-hold stops on release, quick tap latches, exactly the web's
// 260ms rule), captions, hand queue, host moderation and spatial moves. The
// "Captions on/off" sender toggle is omitted (it gates the browser mic
// transcriber; there is no native mic graph to gate), and the closed-sheet
// "Voice · N live" pill is omitted (the native session lives while the sheet
// is open and emits leave on dismiss, per the R69-c contract).

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.PulseVoiceUser
import app.pulse.protocol.SpacePlayerDto
import app.pulse.protocol.VoicePeerDto
import app.pulse.protocol.parseSpaceBoardState
import app.pulse.protocol.parseStageRoomState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// web tailwind tokens the sheets paint with (exact rgba alphas) ============

private val LiveAmber300 = Color(0xFFFCD34D) // amber-300 text
private val LiveAmber400 = Color(0xFFFBBF24) // amber-400 ring / gradient from
private val LiveAmber500 = Color(0xFFF59E0B) // amber-500 base
private val LiveAmber600 = Color(0xFFD97706) // amber-600 gradient to
private val LiveRose300 = Color(0xFFFDA4AF) // rose-300
private val LiveRose400 = Color(0xFFFB7185) // rose-400 icon
private val LiveRose500 = Color(0xFFF43F5E) // rose-500
private val LiveRose200 = Color(0xFFFECDD3) // rose-200 error copy
private val LiveZinc200 = Color(0xFFE4E4E7) // zinc-200
private val LiveZinc400 = Color(0xFFA1A1AA) // zinc-400
private val LiveZinc800 = Color(0xFF27272A) // zinc-800
private val LiveZinc950 = Color(0xFF09090B) // zinc-950
private val LiveWhite2 = Color(0x05FFFFFF) // bg-white/[0.02]
private val LiveWhite3 = Color(0x08FFFFFF) // bg-white/[0.03]
private val LiveWhite5 = Color(0x0DFFFFFF) // white/5
private val LiveWhite10 = Color(0x1AFFFFFF) // white/10
private val LiveWhite20 = Color(0x33FFFFFF) // white/20
private val LiveAmberTint8 = Color(0x14F59E0B) // amber-500/[0.08]
private val LiveAmberTint10 = Color(0x1AF59E0B) // amber-500/10
private val LiveAmberTint15 = Color(0x26F59E0B) // amber-500/15
private val LiveAmberRing20 = Color(0x33FBBF24) // amber-400/20
private val LiveAmberRing40 = Color(0x66FBBF24) // amber-400/40
private val LiveRoseTint10 = Color(0x1AF43F5E) // rose-500/10
private val LiveRoseRing25 = Color(0x40F43F5E) // rose-500/25
private val LiveRoseRing30 = Color(0x4DF43F5E) // rose-500/30
private val LiveSpeakRing = Color(0xE634D399) // rgba(52,211,153,0.9) PTT ring

private val LIVE_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** Web formatListStamp (pulse-utils): HH:mm today, Yesterday, then "MMM d". */
private fun mirrorSeasonStamp(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val parsed = runCatching { Instant.parse(iso) }.getOrNull() ?: return ""
    val day = parsed.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when (day) {
        today -> LIVE_TIME_FORMAT.format(parsed)
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("MMM d").format(day)
    }
}

/** Web roster default color is "emerald" when a peer carries no color. */
private fun mirrorLiveColor(viewerColor: String?): String =
    viewerColor?.takeIf { it.isNotBlank() } ?: "emerald"

private fun mirrorLiveUser(viewerId: String, viewerName: String, viewerColor: String?): PulseVoiceUser =
    PulseVoiceUser(id = viewerId, name = viewerName, username = null, color = mirrorLiveColor(viewerColor))

/** One ephemeral live caption (web VoiceCaption - never persisted). */
private data class MirrorVoiceCaption(
    val id: String,
    val userId: String,
    val name: String,
    val color: String,
    val text: String,
    val at: Long,
)

/** Web SpeakingBars (static reduced-motion variant: 13/6/15 amber bars). */
@Composable
private fun MirrorSpeakingBars() {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (barHeight in listOf(13, 6, 15)) {
            Box(
                Modifier
                    .size(width = 3.dp, height = barHeight.dp)
                    .clip(CircleShape)
                    .background(LiveAmber300),
            )
        }
    }
}

/** Web connection-line dot (size-1.5 = 6dp, pulsing while connecting). */
@Composable
private fun MirrorLiveDot(color: Color, pulsing: Boolean) {
    val alpha: Float = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulseAlpha",
        )
        a
    } else {
        1f
    }
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

/** Web PeerRow / SpeakerTile avatar: gradient initials + speaking emerald ring. */
@Composable
private fun MirrorLivePeerAvatar(name: String, color: String, sizeDp: Int, speaking: Boolean) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .border(
                width = if (speaking) 2.dp else 1.dp,
                color = if (speaking) LiveSpeakRing else LiveWhite10,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(if (speaking) 1.dp else 0.dp)
                .clip(CircleShape)
                .background(MirrorArt.avatarBrush(color)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                MirrorArt.initials(name),
                color = Color.White,
                fontSize = (sizeDp * 0.34f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Small uppercase section label (web SectionLabel). */
@Composable
private fun MirrorLiveSectionLabel(text: String, trailing: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = MirrorArt.Faint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Text(trailing, color = MirrorArt.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Full-width text pill button used by the sheets' solid actions. */
@Composable
private fun MirrorLiveButton(
    label: String,
    container: Color,
    content: Color,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// Live Voice ==================================================================

/**
 * The web voice room sheet: live roster from voice:roster events (initial
 * empty until the relay answers), join/leave seats, PTT latch, live captions.
 */
@Composable
internal fun MirrorVoiceRoomSheet(
    repository: PulseRepository,
    conversationId: String,
    viewerId: String,
    viewerName: String,
    viewerColor: String?,
    onDismiss: () -> Unit,
) {
    var joining by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var roster by remember { mutableStateOf<List<VoicePeerDto>>(emptyList()) }
    var speaking by remember { mutableStateOf<Set<String>>(emptySet()) }
    var captions by remember { mutableStateOf<List<MirrorVoiceCaption>>(emptyList()) }
    var micMuted by remember { mutableStateOf(false) }
    var transmitting by remember { mutableStateOf(false) }
    var holdStartAt by remember { mutableStateOf(0L) }
    var lastResyncAt by remember { mutableStateOf(0L) }
    var roomTitle by remember { mutableStateOf<String?>(null) }

    fun setPtt(on: Boolean) {
        if (on) {
            if (!joined || micMuted || transmitting) return
            transmitting = true
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.emitVoicePtt(conversationId, viewerId, true) }
            }
        } else {
            if (!transmitting) return
            transmitting = false
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.emitVoicePtt(conversationId, viewerId, false) }
            }
        }
    }

    fun join() {
        if (joining || joined) return
        joining = true
        errorMsg = ""
        CoroutineScope(Dispatchers.IO).launch {
            val outcome = runCatching {
                repository.emitVoiceJoin(conversationId, mirrorLiveUser(viewerId, viewerName, viewerColor))
            }
            withContext(Dispatchers.Main) {
                joining = false
                outcome.fold(
                    onSuccess = { joined = true },
                    onFailure = { errorMsg = "Could not reach the voice relay - check your connection and retry." },
                )
            }
        }
    }

    fun leave() {
        if (!joined) return
        setPtt(false)
        joined = false
        speaking = emptySet()
        roster = emptyList()
        captions = emptyList()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.emitVoiceLeave(conversationId) }
        }
    }

    // Live wire: roster replace + PTT latch rings + ephemeral captions.
    LaunchedEffect(conversationId) {
        repository.events().collect { event ->
            when (event) {
                is PulseEvent.VoiceRoster -> if (joined && event.payload.conversationId == conversationId) {
                    roster = event.payload.roster
                    val live = event.payload.roster.map { it.userId }.toSet()
                    speaking = speaking.filter { it in live }.toSet()
                    // relay restart wiped us mid-session: re-register (rate-limited)
                    val now = System.currentTimeMillis()
                    if (roster.none { it.userId == viewerId } && now - lastResyncAt > 2_000L) {
                        lastResyncAt = now
                        CoroutineScope(Dispatchers.IO).launch {
                            runCatching {
                                repository.emitVoiceJoin(conversationId, mirrorLiveUser(viewerId, viewerName, viewerColor))
                            }
                        }
                    }
                }
                is PulseEvent.VoicePtt -> if (joined && event.payload.conversationId == conversationId) {
                    speaking = if (event.payload.active) {
                        speaking + event.payload.userId
                    } else {
                        speaking - event.payload.userId
                    }
                }
                is PulseEvent.VoiceTranscript -> if (joined && event.payload.conversationId == conversationId) {
                    val text = event.payload.text.trim()
                    if (text.isNotEmpty()) {
                        val peer = roster.firstOrNull { it.userId == event.payload.userId }
                        val caption = MirrorVoiceCaption(
                            id = "${System.currentTimeMillis()}-${event.payload.userId}",
                            userId = event.payload.userId,
                            name = peer?.name?.takeIf { it.isNotBlank() } ?: "Someone",
                            color = peer?.color?.takeIf { it.isNotBlank() } ?: "emerald",
                            text = text.take(280),
                            at = System.currentTimeMillis(),
                        )
                        captions = (captions + caption).takeLast(3)
                    }
                }
                else -> Unit
            }
        }
    }

    // Web caption TTL: lines fade after 7s (1s sweep parity).
    LaunchedEffect(captions.isNotEmpty()) {
        if (captions.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(1_000)
            val cutoff = System.currentTimeMillis() - 7_000L
            val next = captions.filter { it.at > cutoff }
            if (next.size != captions.size) captions = next
            if (next.isEmpty()) break
        }
    }

    // Room title for the subtitle (same endpoint the room header uses).
    LaunchedEffect(conversationId) {
        roomTitle = runCatching { repository.conversationDetail(conversationId).getOrNull()?.title }.getOrNull()
    }

    // Closing the sheet never leaves a ghost seat or a hot latch behind.
    DisposableEffect(Unit) {
        onDispose {
            if (transmitting) {
                transmitting = false
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { repository.emitVoicePtt(conversationId, viewerId, false) }
                }
            }
            if (joined) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { repository.emitVoiceLeave(conversationId) }
                }
            }
        }
    }

    val canTalk = joined && !micMuted

    MirrorSheet(title = "Live Voice", onDismiss = onDismiss) {
        MirrorSheetScroll {
            if (roomTitle != null) {
                Text(
                    roomTitle!!,
                    color = MirrorArt.Dim,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // connection state line
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    joining -> {
                        MirrorLiveDot(LiveAmber400, pulsing = true)
                        Text("Connecting to the voice relay…", color = MirrorArt.Dim, fontSize = 11.sp)
                    }
                    joined -> {
                        MirrorLiveDot(LiveAmber400, pulsing = false)
                        Text("Connected via gateway :3003", color = MirrorArt.Dim, fontSize = 11.sp)
                    }
                    else -> {
                        MirrorLiveDot(MirrorArt.Faint, pulsing = false)
                        Text("Standby - not connected", color = MirrorArt.Dim, fontSize = 11.sp)
                    }
                }
            }

            // roster
            if (joined) {
                if (roster.isEmpty()) {
                    Text(
                        "Syncing roster…",
                        color = MirrorArt.Faint,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (peer in roster) {
                            val isSpeaking = peer.userId in speaking
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(LiveWhite3)
                                    .border(1.dp, LiveWhite5, RoundedCornerShape(16.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                MirrorLivePeerAvatar(peer.name, peer.color, 40, isSpeaking)
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            peer.name,
                                            color = MirrorArt.Text,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (peer.userId == viewerId) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "YOU",
                                                color = LiveAmber300,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(LiveAmberTint15)
                                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        peer.username?.let { "@$it" } ?: "on stage",
                                        color = MirrorArt.Faint,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isSpeaking) {
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(LiveAmberTint10)
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        MirrorSpeakingBars()
                                        Text("LIVE", color = LiveAmber300, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text("LISTENING", color = MirrorArt.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LiveWhite2)
                        .border(1.dp, LiveWhite10, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(LiveAmberTint10),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LRadio", tint = LiveAmber300, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Nobody is on stage yet", color = MirrorArt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Join the room, hold the talk button and speak - everyone inside hears you instantly.",
                        color = MirrorArt.Faint,
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }

            // honest failure state
            if (errorMsg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LiveRoseTint10)
                        .border(1.dp, LiveRoseRing25, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorLucideIcon("LInfo", tint = LiveRose400, modifier = Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(errorMsg, color = LiveRose200, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        MirrorLiveButton("Try again", LiveRose500.copy(alpha = 0.9f), Color.White) { join() }
                    }
                }
            }

            if (joined && micMuted) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Mic is muted - unmute to talk.",
                    color = LiveAmber300,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(LiveAmberTint10)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            // live caption strip (ephemeral, fades after 7s)
            if (captions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (caption in captions) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(LiveAmberTint8)
                                .border(1.dp, LiveAmberTint15, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(caption.name, color = LiveAmber300, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text(caption.text, color = MirrorArt.Text, fontSize = 12.5.sp)
                        }
                    }
                }
            }
        }

        // PTT + controls footer
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(LiveWhite2)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // mute toggle
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (micMuted) LiveAmberTint15 else LiveWhite5)
                    .border(
                        1.dp,
                        if (micMuted) LiveAmberRing40 else LiveWhite10,
                        CircleShape,
                    )
                    .clickable(enabled = joined) {
                        val next = !micMuted
                        micMuted = next
                        if (next) setPtt(false) // a muted mic never transmits
                    },
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon(
                    if (micMuted) "LVolumeX" else "LMic",
                    tint = if (micMuted) LiveAmber300 else MirrorArt.TextSoft,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(20.dp))
            // push-to-talk: press to latch, hold >= 260ms stops on release
            Box(
                Modifier
                    .size(108.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !canTalk -> SolidColor(LiveZinc800.copy(alpha = 0.7f))
                            transmitting -> Brush.linearGradient(listOf(LiveAmber400, LiveAmber600))
                            else -> Brush.linearGradient(listOf(Color(0xFF3F3F46), Color(0xFF27272A)))
                        },
                    )
                    .then(if (!canTalk) Modifier.border(1.dp, LiveWhite5, CircleShape) else Modifier)
                    .pointerInput(canTalk) {
                        detectTapGestures(
                            onPress = {
                                if (canTalk) {
                                    holdStartAt = System.currentTimeMillis()
                                    if (transmitting) {
                                        setPtt(false) // tap while latched unlatches
                                    } else {
                                        setPtt(true)
                                    }
                                }
                                val released = tryAwaitRelease()
                                if (released && transmitting && System.currentTimeMillis() - holdStartAt >= 260) {
                                    setPtt(false)
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MirrorLucideIcon(
                        if (transmitting) "LRadio" else "LMic",
                        tint = if (canTalk) Color.White else MirrorArt.Faint,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        when {
                            transmitting -> "Live"
                            canTalk -> "Hold"
                            else -> "Off"
                        },
                        color = if (canTalk) Color.White else MirrorArt.Faint,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
            // leave / join
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (joined) LiveRoseTint10 else LiveAmber500)
                    .border(1.dp, if (joined) LiveRoseRing30 else Color.Transparent, CircleShape)
                    .clickable(enabled = !joining) {
                        if (joined) leave() else join()
                    },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    joining -> MirrorLucideIcon("LLoaderCircle", tint = Color.White, modifier = Modifier.size(20.dp))
                    joined -> MirrorLucideIcon("LPhone", tint = LiveRose400, modifier = Modifier.size(20.dp))
                    else -> MirrorLucideIcon("LRadio", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Hold to talk · tap to latch · " + (if (joined) "${roster.size} on stage" else "join to open the mic"),
            color = MirrorArt.Faint,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Voice is streamed live in 250 ms chunks and never recorded or stored.",
            color = MirrorArt.Faint,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}

// Live stage ==================================================================

/**
 * The web stage sheet: auto-joins as a listener on open (web useStageSheet),
 * derives the role from stage:state, and shows ONLY the actions the web shows
 * for the viewer's role - listeners raise/lower a hand, hosts approve hands,
 * mute speakers and end the stage (two-tap confirm), speakers+host get PTT.
 */
@Composable
internal fun MirrorStageSheet(
    repository: PulseRepository,
    conversationId: String,
    viewerId: String,
    viewerName: String,
    viewerColor: String?,
    onDismiss: () -> Unit,
) {
    var joining by remember { mutableStateOf(false) }
    var joined by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<app.pulse.protocol.StageRoomState?>(null) }
    var speaking by remember { mutableStateOf<Set<String>>(emptySet()) }
    var micMuted by remember { mutableStateOf(false) }
    var transmitting by remember { mutableStateOf(false) }
    var holdStartAt by remember { mutableStateOf(0L) }
    var lastResyncAt by remember { mutableStateOf(0L) }
    var confirmEnd by remember { mutableStateOf(false) }
    var roomTitle by remember { mutableStateOf<String?>(null) }

    val isHost = state?.host?.id == viewerId
    val isSpeaker = !isHost && state?.speakers?.any { it.id == viewerId } == true
    val handRaised = state?.hands?.any { it.id == viewerId } == true
    val canTalk = joined && (isHost || isSpeaker) && !micMuted

    fun stageJoin(asHost: Boolean) {
        if (joining) return
        if (!asHost && (joining || joined)) return
        joining = true
        errorMsg = ""
        CoroutineScope(Dispatchers.IO).launch {
            val outcome = runCatching {
                repository.emitStageJoin(conversationId, mirrorLiveUser(viewerId, viewerName, viewerColor), asHost)
            }
            withContext(Dispatchers.Main) {
                joining = false
                outcome.fold(
                    onSuccess = { joined = true },
                    onFailure = { errorMsg = "Could not reach the stage relay - check your connection and retry." },
                )
            }
        }
    }

    fun stageLeave() {
        if (!joined) return
        if (transmitting) {
            transmitting = false
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.emitVoicePtt(conversationId, viewerId, false) }
            }
        }
        joined = false
        state = null
        speaking = emptySet()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.emitStageLeave(conversationId) }
        }
    }

    fun setPtt(on: Boolean) {
        if (on) {
            if (!canTalk || transmitting) return
            transmitting = true
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.emitVoicePtt(conversationId, viewerId, true) }
            }
        } else {
            if (!transmitting) return
            transmitting = false
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.emitVoicePtt(conversationId, viewerId, false) }
            }
        }
    }

    // Web useStageSheet: the sheet opens the session (join defaults to listener).
    LaunchedEffect(conversationId) {
        stageJoin(false)
    }

    // Two-tap end confirm auto-reset (web 2.6s timeout parity).
    LaunchedEffect(confirmEnd) {
        if (confirmEnd) {
            delay(2_600)
            confirmEnd = false
        }
    }

    LaunchedEffect(conversationId) {
        roomTitle = runCatching { repository.conversationDetail(conversationId).getOrNull()?.title }.getOrNull()
    }

    // Live wire: stage:state wholesale replace, voice:ptt rings, stage:ended.
    LaunchedEffect(conversationId) {
        repository.events().collect { event ->
            when (event) {
                is PulseEvent.StageState -> if (joined && event.payload.conversationId == conversationId) {
                    // state carries the whole raw payload element - parse defensively,
                    // a malformed event never blanks a live render (null = keep previous)
                    parseStageRoomState(event.payload.state)?.let { next ->
                        state = next
                        val liveSpeakers = next.speakers.map { it.id }.toSet()
                        speaking = speaking.filter { it in liveSpeakers }.toSet()
                        // relay restart wiped us: re-register (rate-limited, web 2.5s)
                        val present = next.host?.id == viewerId ||
                            next.speakers.any { it.id == viewerId } ||
                            next.hands.any { it.id == viewerId } ||
                            next.listeners.any { it.id == viewerId }
                        val now = System.currentTimeMillis()
                        if (!present && now - lastResyncAt > 2_500L) {
                            lastResyncAt = now
                            CoroutineScope(Dispatchers.IO).launch {
                                runCatching {
                                    repository.emitStageJoin(
                                        conversationId,
                                        mirrorLiveUser(viewerId, viewerName, viewerColor),
                                        isHost,
                                    )
                                }
                            }
                        }
                    }
                }
                is PulseEvent.StageEnded -> if (joined && event.payload.conversationId == conversationId) {
                    // the host ended the stage: self-leave + close the sheet
                    if (transmitting) {
                        transmitting = false
                        CoroutineScope(Dispatchers.IO).launch {
                            runCatching { repository.emitVoicePtt(conversationId, viewerId, false) }
                        }
                    }
                    joined = false
                    state = null
                    speaking = emptySet()
                    CoroutineScope(Dispatchers.IO).launch {
                        runCatching { repository.emitStageLeave(conversationId) }
                    }
                    onDismiss()
                }
                is PulseEvent.VoicePtt -> if (joined && event.payload.conversationId == conversationId) {
                    speaking = if (event.payload.active) {
                        speaking + event.payload.userId
                    } else {
                        speaking - event.payload.userId
                    }
                }
                else -> Unit
            }
        }
    }

    // Leaving the sheet always releases the stage seat (web unmount parity).
    DisposableEffect(Unit) {
        onDispose {
            if (transmitting) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { repository.emitVoicePtt(conversationId, viewerId, false) }
                }
            }
            if (joined) {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { repository.emitStageLeave(conversationId) }
                }
            }
        }
    }

    MirrorSheet(title = "Live stage", onDismiss = onDismiss) {
        MirrorSheetScroll {
            if (roomTitle != null) {
                Text(
                    roomTitle!!,
                    color = MirrorArt.Dim,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // connection state line
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    joining -> {
                        MirrorLiveDot(LiveAmber400, pulsing = true)
                        Text("Connecting to the stage relay…", color = MirrorArt.Dim, fontSize = 11.sp)
                    }
                    joined -> {
                        MirrorLiveDot(LiveAmber400, pulsing = false)
                        Text("Connected via gateway :3003", color = MirrorArt.Dim, fontSize = 11.sp)
                    }
                    else -> {
                        MirrorLiveDot(MirrorArt.Faint, pulsing = false)
                        Text("Standby - not connected", color = MirrorArt.Dim, fontSize = 11.sp)
                    }
                }
                if (state != null) {
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(LiveWhite5)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MirrorLucideIcon("LUsersRound", tint = MirrorArt.Dim, modifier = Modifier.size(12.dp))
                        Text("${state!!.listenerCount}", color = MirrorArt.TextSoft, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // honest failure state
            if (errorMsg.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LiveRoseTint10)
                        .border(1.dp, LiveRoseRing25, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorLucideIcon("LInfo", tint = LiveRose400, modifier = Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(errorMsg, color = LiveRose200, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        MirrorLiveButton("Try again", LiveRose500.copy(alpha = 0.9f), Color.White) { stageJoin(false) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            when {
                joined && state == null -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MirrorLucideIcon("LLoaderCircle", tint = MirrorArt.Faint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Syncing stage…", color = MirrorArt.Faint, fontSize = 12.sp)
                    }
                }
                state != null -> {
                    val stage = state!!

                    // HOST
                    MirrorLiveSectionLabel("HOST")
                    if (stage.host != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(LiveAmberTint8)
                                .border(1.dp, LiveAmberRing20, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MirrorLucideIcon("LBadgeCheck", tint = LiveAmber300, modifier = Modifier.size(16.dp))
                            MirrorLivePeerAvatar(stage.host.name, stage.host.color, 40, stage.host.id in speaking)
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stage.host.name,
                                        color = MirrorArt.Text,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (stage.host.id == viewerId) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "YOU",
                                            color = LiveAmber300,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(LiveAmberTint15)
                                                .padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Text("Runs the room · approves hands", color = MirrorArt.Faint, fontSize = 11.sp)
                            }
                        }
                    } else {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(LiveWhite2)
                                .border(1.dp, LiveWhite10, RoundedCornerShape(16.dp))
                                .padding(12.dp),
                        ) {
                            Text("No host on stage right now.", color = MirrorArt.TextSoft, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                            if (joined && !isHost) {
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MirrorLucideIcon("LBadgeCheck", tint = LiveZinc950, modifier = Modifier.size(13.dp))
                                    Text("Claim host", color = LiveZinc950, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // SPEAKERS (web filters the host out of the tile row)
                    val speakersWithoutHost = stage.speakers.filter { it.id != stage.host?.id }
                    MirrorLiveSectionLabel("SPEAKERS", trailing = "${speakersWithoutHost.size}")
                    if (speakersWithoutHost.isEmpty()) {
                        Text(
                            "Only the host is on stage - approve a raised hand to add speakers.",
                            color = MirrorArt.Faint,
                            fontSize = 11.5.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(LiveWhite2)
                                .border(1.dp, LiveWhite10, RoundedCornerShape(16.dp))
                                .padding(12.dp),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (person in speakersWithoutHost) {
                                val personSpeaking = person.id in speaking
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (personSpeaking) LiveAmberTint8 else LiveWhite3)
                                        .border(
                                            1.dp,
                                            if (personSpeaking) LiveAmberRing20 else LiveWhite5,
                                            RoundedCornerShape(16.dp),
                                        )
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    MirrorLivePeerAvatar(person.name, person.color, 38, personSpeaking)
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                person.name,
                                                color = MirrorArt.Text,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            if (person.id == viewerId) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "YOU",
                                                    color = LiveAmber300,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(999.dp))
                                                        .background(LiveAmberTint15)
                                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (personSpeaking) {
                                                MirrorSpeakingBars()
                                                Text("LIVE", color = LiveAmber300, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                            } else {
                                                Text("ON STAGE", color = MirrorArt.Faint, fontSize = 10.5.sp)
                                            }
                                        }
                                    }
                                    // host-only moderation: mute a speaker (web shows it on
                                    // every non-host tile, own tile included)
                                    if (isHost) {
                                        Box(
                                            Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(LiveWhite5)
                                                .border(1.dp, LiveWhite10, RoundedCornerShape(12.dp))
                                                .clickable {
                                                    CoroutineScope(Dispatchers.IO).launch {
                                                        runCatching { repository.emitStageMute(conversationId, viewerId, person.id) }
                                                    }
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            MirrorLucideIcon("LVolumeX", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // RAISED HANDS (FIFO queue)
                    if (stage.hands.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        MirrorLiveSectionLabel("RAISED HANDS", trailing = "${stage.hands.size}")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            stage.hands.forEachIndexed { index, person ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(LiveAmberTint8)
                                        .border(1.dp, LiveAmberRing20, RoundedCornerShape(16.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(LiveAmberRing20),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("${index + 1}", color = LiveAmber300, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    MirrorLivePeerAvatar(person.name, person.color, 34, false)
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            person.name,
                                            color = MirrorArt.Text,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (person.id == viewerId) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "YOU",
                                                color = LiveAmber300,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(LiveAmberTint15)
                                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                    // host-only moderation: approve / decline the hand
                                    if (isHost) {
                                        Box(
                                            Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(LiveAmber500)
                                                .clickable {
                                                    CoroutineScope(Dispatchers.IO).launch {
                                                        runCatching { repository.emitStageApprove(conversationId, viewerId, person.id) }
                                                    }
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            MirrorLucideIcon("LCheck", tint = Color.White, modifier = Modifier.size(16.dp))
                                        }
                                        Box(
                                            Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(LiveWhite5)
                                                .border(1.dp, LiveWhite10, RoundedCornerShape(12.dp))
                                                .clickable {
                                                    CoroutineScope(Dispatchers.IO).launch {
                                                        runCatching { repository.emitStageMute(conversationId, viewerId, person.id) }
                                                    }
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            MirrorLucideIcon("LVolumeX", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // LISTENERS (collapsed)
                    MirrorLiveSectionLabel("LISTENERS", trailing = "${stage.listenerCount}")
                    if (stage.listenerCount > 0) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(LiveWhite3)
                                .border(1.dp, LiveWhite5, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                stage.listeners.take(4).forEachIndexed { index, listener ->
                                    Box(
                                        Modifier
                                            .offset(x = if (index == 0) 0.dp else (-8).dp)
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .border(2.dp, LiveZinc950, CircleShape)
                                            .background(MirrorArt.avatarBrush(listener.color)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            MirrorArt.initials(listener.name),
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            val listenerNames = stage.listeners.take(4).map { it.name }
                            val extra = stage.listenerCount - listenerNames.size
                            Text(
                                listenerNames.joinToString(", ") + if (extra > 0) " +$extra more" else "",
                                color = MirrorArt.Dim,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Text(
                            "Nobody is listening yet - share the room.",
                            color = MirrorArt.Faint,
                            fontSize = 11.5.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(LiveWhite2)
                                .border(1.dp, LiveWhite10, RoundedCornerShape(16.dp))
                                .padding(12.dp),
                        )
                    }

                    if (joined && micMuted && (isHost || isSpeaker)) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Mic is muted - unmute to talk.",
                            color = LiveAmber300,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(LiveAmberTint10)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        // controls - exactly the role's action set
        Spacer(Modifier.height(12.dp))
        if (isHost) {
            // host moderation: end stage (two-tap confirm, destructive for everyone)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (confirmEnd) LiveRose500.copy(alpha = 0.2f) else LiveRoseTint10)
                    .border(1.dp, if (confirmEnd) LiveRose500.copy(alpha = 0.5f) else LiveRoseRing25, RoundedCornerShape(12.dp))
                    .clickable {
                        if (confirmEnd) {
                            confirmEnd = false
                            CoroutineScope(Dispatchers.IO).launch {
                                runCatching { repository.emitStageEnd(conversationId, viewerId) }
                            }
                        } else {
                            confirmEnd = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MirrorLucideIcon("LRadio", tint = LiveRose400, modifier = Modifier.size(14.dp))
                    Text(
                        if (confirmEnd) "Tap again to end the stage for everyone" else "End stage",
                        color = LiveRose300,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (joined && (isHost || isSpeaker)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(LiveWhite2)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (micMuted) LiveAmberTint15 else LiveWhite5)
                        .border(1.dp, if (micMuted) LiveAmberRing40 else LiveWhite10, CircleShape)
                        .clickable {
                            val next = !micMuted
                            micMuted = next
                            if (next) setPtt(false)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon(
                        if (micMuted) "LVolumeX" else "LMic",
                        tint = if (micMuted) LiveAmber300 else MirrorArt.TextSoft,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(20.dp))
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !canTalk -> SolidColor(LiveZinc800.copy(alpha = 0.7f))
                                transmitting -> Brush.linearGradient(listOf(LiveAmber400, LiveAmber600))
                                else -> Brush.linearGradient(listOf(Color(0xFF3F3F46), Color(0xFF27272A)))
                            },
                        )
                        .pointerInput(canTalk) {
                            detectTapGestures(
                                onPress = {
                                    if (canTalk) {
                                        holdStartAt = System.currentTimeMillis()
                                        if (transmitting) setPtt(false) else setPtt(true)
                                    }
                                    val released = tryAwaitRelease()
                                    if (released && transmitting && System.currentTimeMillis() - holdStartAt >= 260) {
                                        setPtt(false)
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MirrorLucideIcon(
                            if (transmitting) "LRadio" else "LMic",
                            tint = if (canTalk) Color.White else MirrorArt.Faint,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            when {
                                transmitting -> "Live"
                                canTalk -> "Hold"
                                else -> "Off"
                            },
                            color = if (canTalk) Color.White else MirrorArt.Faint,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.width(20.dp))
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(LiveRoseTint10)
                        .border(1.dp, LiveRoseRing30, CircleShape)
                        .clickable {
                            stageLeave()
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LPhone", tint = LiveRose400, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Hold to talk · tap to latch · stage audio rides the live voice relay",
                color = MirrorArt.Faint,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Streamed in 250 ms chunks - never recorded or stored.",
                color = MirrorArt.Faint,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        } else if (joined && !isHost && !isSpeaker && state != null) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (handRaised) LiveAmber400.copy(alpha = 0.9f) else LiveAmber500)
                        .clickable {
                            CoroutineScope(Dispatchers.IO).launch {
                                runCatching { repository.emitStageHand(conversationId, viewerId, !handRaised) }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (handRaised) "Hand raised - waiting for the host" else "Raise hand",
                        color = if (handRaised) LiveZinc950 else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (handRaised) "The host will approve you to speak." else "You are listening - raise a hand to speak.",
                        color = MirrorArt.Faint,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(LiveRoseTint10)
                            .border(1.dp, LiveRoseRing30, RoundedCornerShape(12.dp))
                            .clickable {
                                stageLeave()
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("Leave", color = LiveRose300, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Space =======================================================================

/**
 * The web spatial presence sheet: joins on open, leaves on dismiss, live board
 * from space:state (wholesale replace, parsed defensively - a malformed event
 * never blanks the map), tap/drag to move with the web's 90ms client throttle
 * and optimistic target, nearby radius 0.18.
 */
@Composable
internal fun MirrorSpaceSheet(
    repository: PulseRepository,
    conversationId: String,
    viewerId: String,
    viewerName: String,
    viewerColor: String?,
    onDismiss: () -> Unit,
) {
    var connected by remember { mutableStateOf(false) }
    var players by remember { mutableStateOf<List<SpacePlayerDto>>(emptyList()) }
    var myTarget by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var lastMoveAt by remember { mutableStateOf(0L) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }

    fun sendMove(x: Double, y: Double) {
        val now = System.currentTimeMillis()
        if (now - lastMoveAt < 90) return // web CLIENT_MOVE_THROTTLE_MS
        lastMoveAt = now
        val cx = x.coerceIn(0.0, 1.0)
        val cy = y.coerceIn(0.0, 1.0)
        myTarget = cx to cy
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { repository.emitSpaceMove(conversationId, cx, cy) }
        }
    }

    // Web: join on open, leave+disconnect on unmount.
    LaunchedEffect(conversationId) {
        runCatching {
            repository.emitSpaceJoin(conversationId, mirrorLiveUser(viewerId, viewerName, viewerColor))
        }
        connected = true
    }
    DisposableEffect(Unit) {
        onDispose {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.emitSpaceLeave(conversationId) }
            }
        }
    }

    // Live wire: space:state wholesale replace.
    LaunchedEffect(conversationId) {
        repository.events().collect { event ->
            if (event is PulseEvent.SpaceState && event.payload.conversationId == conversationId) {
                parseSpaceBoardState(event.payload.state)?.let { board -> players = board.players }
            }
        }
    }

    // Resolve my dot: optimistic target wins for smoothness, server reconciles.
    val serverMe = players.firstOrNull { it.id == viewerId }
    val meX = myTarget?.first ?: serverMe?.x ?: 0.5
    val meY = myTarget?.second ?: serverMe?.y ?: 0.5
    val others = players.filter { it.id != viewerId }
    val nearbyIds = others
        .filter { hypot(it.x - meX, it.y - meY) <= 0.18 }
        .map { it.id }
        .toSet()

    MirrorSheet(title = "Space", onDismiss = onDismiss) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (connected) "Live - move around, get near people" else "Connecting to the room…",
                color = MirrorArt.Dim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (connected) LiveAmberTint15 else LiveZinc800)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MirrorLucideIcon("LUsersRound", tint = if (connected) LiveAmber300 else MirrorArt.Dim, modifier = Modifier.size(12.dp))
                Text(
                    "${players.size} in room",
                    color = if (connected) LiveAmber300 else MirrorArt.Dim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // map (web floor plan geometry + tap/drag to move)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3.4f)
                .clip(RoundedCornerShape(16.dp))
                .background(LiveZinc800)
                .border(1.dp, LiveZinc800, RoundedCornerShape(16.dp))
                .drawBehind {
                    val w = size.width
                    val h = size.height
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color(0x8C18181B), Color(0x8C27272A))),
                        size = size,
                    )
                    val wall = Color(0x80A1A1AA)
                    val accent = Color(0x59F59E0B)
                    val desk = Color(0x7371717A)
                    val stroke = maxOf(2.dp.toPx(), w * 0.006f)
                    drawRect(
                        color = wall,
                        topLeft = Offset(w * 0.04f, h * 0.05f),
                        size = Size(w * 0.92f, h * 0.9f),
                        style = Stroke(width = stroke),
                    )
                    // meeting room + door gap
                    drawLine(wall, Offset(w * 0.04f, h * 0.38f), Offset(w * 0.4f, h * 0.38f), strokeWidth = stroke)
                    drawLine(wall, Offset(w * 0.4f, h * 0.38f), Offset(w * 0.4f, h * 0.05f), strokeWidth = stroke)
                    drawLine(accent, Offset(w * 0.18f, h * 0.38f), Offset(w * 0.3f, h * 0.38f), strokeWidth = stroke)
                    // focus booth
                    drawLine(wall, Offset(w * 0.62f, h * 0.05f), Offset(w * 0.62f, h * 0.32f), strokeWidth = stroke)
                    drawLine(wall, Offset(w * 0.62f, h * 0.32f), Offset(w * 0.96f, h * 0.32f), strokeWidth = stroke)
                    drawLine(accent, Offset(w * 0.62f, h * 0.12f), Offset(w * 0.62f, h * 0.24f), strokeWidth = stroke)
                    // lounge divider
                    drawLine(wall, Offset(w * 0.04f, h * 0.7f), Offset(w * 0.34f, h * 0.7f), strokeWidth = stroke)
                    drawLine(wall, Offset(w * 0.34f, h * 0.7f), Offset(w * 0.34f, h * 0.95f), strokeWidth = stroke)
                    // desks
                    drawLine(desk, Offset(w * 0.68f, h * 0.12f), Offset(w * 0.86f, h * 0.12f), strokeWidth = stroke)
                    drawLine(desk, Offset(w * 0.68f, h * 0.2f), Offset(w * 0.86f, h * 0.2f), strokeWidth = stroke)
                    // meeting table
                    drawOval(
                        color = wall,
                        topLeft = Offset(w * 0.12f, h * 0.13f),
                        size = Size(w * 0.2f, h * 0.14f),
                        style = Stroke(width = stroke),
                    )
                    // rug
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(w * 0.5f, h * 0.78f),
                        size = Size(w * 0.34f, h * 0.12f),
                        cornerRadius = CornerRadius(w * 0.02f, w * 0.02f),
                        style = Stroke(width = stroke),
                    )
                }
                .onSizeChanged { mapSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (mapSize.width > 0 && mapSize.height > 0) {
                                sendMove(
                                    (offset.x / mapSize.width).toDouble(),
                                    (offset.y / mapSize.height).toDouble(),
                                )
                            }
                        },
                        onDrag = { change, _ ->
                            if (mapSize.width > 0 && mapSize.height > 0) {
                                sendMove(
                                    (change.position.x / mapSize.width).toDouble(),
                                    (change.position.y / mapSize.height).toDouble(),
                                )
                            }
                        },
                    )
                },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // floor labels (web canvas labels)
                Text(
                    "MEET",
                    color = Color(0x8CA1A1AA),
                    fontSize = 10.sp,
                    modifier = Modifier.offset(x = maxWidth * 0.07f, y = maxHeight * 0.08f),
                )
                Text(
                    "FOCUS",
                    color = Color(0x8CA1A1AA),
                    fontSize = 10.sp,
                    modifier = Modifier.offset(x = maxWidth * 0.68f, y = maxHeight * 0.06f),
                )
                Text(
                    "LOUNGE",
                    color = Color(0x8CA1A1AA),
                    fontSize = 10.sp,
                    modifier = Modifier.offset(x = maxWidth * 0.52f, y = maxHeight * 0.7f),
                )

                // other players: colored dots with names
                for (player in others) {
                    val isNearby = player.id in nearbyIds
                    Box(
                        Modifier
                            .offset(
                                x = maxWidth * player.x.toFloat() - 16.dp,
                                y = maxHeight * player.y.toFloat() - 16.dp,
                            )
                            .size(32.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (isNearby) LiveAmber400.copy(alpha = 0.8f) else LiveWhite20, CircleShape)
                            .background(MirrorArt.avatarBrush(player.color)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            MirrorArt.initials(player.name),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Box(
                        Modifier
                            .offset(
                                x = maxWidth * player.x.toFloat() - 42.dp,
                                y = maxHeight * player.y.toFloat() + 18.dp,
                            )
                            .width(84.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Text(
                            player.name,
                            color = LiveZinc200,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(LiveZinc950.copy(alpha = 0.85f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                // me (amber ring, "You" label)
                Box(
                    Modifier
                        .offset(
                            x = maxWidth * meX.toFloat() - 18.dp,
                            y = maxHeight * meY.toFloat() - 18.dp,
                        )
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(2.dp, LiveAmber400, CircleShape)
                        .background(MirrorArt.avatarBrush(mirrorLiveColor(viewerColor))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        MirrorArt.initials(viewerName),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Box(
                    Modifier
                        .offset(
                            x = maxWidth * meX.toFloat() - 42.dp,
                            y = maxHeight * meY.toFloat() + 20.dp,
                        )
                        .width(84.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        "You",
                        color = LiveAmber300,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(LiveZinc950.copy(alpha = 0.85f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // nearby rail
        Column(Modifier.fillMaxWidth()) {
            when {
                others.isEmpty() -> {
                    Text(
                        "No one else here right now - invite people to the room.",
                        color = MirrorArt.Faint,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
                nearbyIds.isEmpty() -> {
                    Text(
                        "${others.size} " + (if (others.size == 1) "person" else "people") + " in the space - move closer to gather.",
                        color = MirrorArt.Faint,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
                else -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(LiveAmberTint15)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            MirrorLucideIcon("LFlame", tint = LiveAmber300, modifier = Modifier.size(12.dp))
                            Text("NEARBY", color = LiveAmber300, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (person in others.filter { it.id in nearbyIds }) {
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(LiveZinc800.copy(alpha = 0.9f))
                                        .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(MirrorArt.avatarBrush(person.color)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            MirrorArt.initials(person.name),
                                            color = Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Text(person.name, color = LiveZinc200, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Tournament ==================================================================

/**
 * The web tournament surfaces in one sheet: the group-info TournamentSection
 * (season list, real player counts, Running/Finished chips, loading/error/
 * empty states) and the TournamentSheet composer (name 1..40, locked game
 * chip, live preview, Start tournament). Join rides the season card's real
 * "Join season" action. The web renders no finish control anywhere, so none
 * is drawn here.
 */
@Composable
internal fun MirrorTournamentSheet(
    repository: PulseRepository,
    conversationId: String,
    viewerId: String,
    onDismiss: () -> Unit,
) {
    var seasons by remember { mutableStateOf<List<app.pulse.protocol.TournamentSummaryDto>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var joiningId by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf("") }
    var meName by remember { mutableStateOf("") }

    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= 40

    fun refresh() {
        CoroutineScope(Dispatchers.IO).launch {
            val outcome = runCatching { repository.tournaments(conversationId) }
            withContext(Dispatchers.Main) {
                outcome.fold(
                    onSuccess = {
                        seasons = it.tournaments
                        loadError = false
                    },
                    onFailure = {
                        if (seasons == null) seasons = emptyList()
                        loadError = true
                    },
                )
            }
        }
    }

    // Fetch-on-open: seasons + the composer's "started by" name.
    LaunchedEffect(conversationId) {
        refresh()
        meName = runCatching { repository.me()?.name }.getOrNull().orEmpty()
    }

    fun submit() {
        if (!valid || pending) return
        pending = true
        actionError = ""
        CoroutineScope(Dispatchers.IO).launch {
            val outcome = runCatching { repository.createTournament(conversationId, trimmed.take(40)) }
            withContext(Dispatchers.Main) {
                pending = false
                outcome.fold(
                    onSuccess = {
                        // web: close the composer + re-sync the seasons list
                        composing = false
                        name = ""
                        refresh()
                    },
                    onFailure = { actionError = "Could not start the tournament." },
                )
            }
        }
    }

    fun join(seasonId: String) {
        if (joiningId != null) return
        joiningId = seasonId
        actionError = ""
        CoroutineScope(Dispatchers.IO).launch {
            val outcome = runCatching { repository.joinTournament(seasonId) }
            withContext(Dispatchers.Main) {
                joiningId = null
                outcome.fold(
                    onSuccess = { refresh() },
                    onFailure = { actionError = "Could not join the tournament." },
                )
            }
        }
    }

    MirrorSheet(title = if (composing) "Start tournament" else "Tournament", onDismiss = onDismiss) {
        MirrorSheetScroll {
            if (composing) {
                // back to the seasons list (the section this sheet opens from)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            composing = false
                            actionError = ""
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MirrorLucideIcon("LArrowLeft", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
                    Text("Tournament", color = MirrorArt.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(LiveAmber400, LiveAmber600))),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorLucideIcon("LTrophy", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Start tournament", color = MirrorArt.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Season ladder · +1 pt per win · +25 XP to winners", color = MirrorArt.Faint, fontSize = 11.sp)
                    }
                }

                // season name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "SEASON NAME",
                        color = MirrorArt.Faint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${trimmed.length}/40", color = MirrorArt.Faint, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                BasicTextField(
                    value = name,
                    onValueChange = { next -> name = next.take(40) },
                    singleLine = true,
                    textStyle = TextStyle(color = MirrorArt.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    cursorBrush = SolidColor(MirrorArt.Accent),
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(LiveWhite5)
                                .border(1.dp, LiveWhite10, RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (name.isEmpty()) {
                                Text("R24 Cup", color = MirrorArt.Faint, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (name.isNotEmpty() && !valid) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Season name needs at least 1 character.",
                        color = LiveRose400,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "GAME",
                    color = MirrorArt.Faint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(LiveAmberTint10)
                        .border(1.dp, LiveAmberRing40, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MirrorLucideIcon("LGamepad2", tint = LiveAmber300, modifier = Modifier.size(20.dp))
                    Text("Tic-tac-toe", color = LiveAmber300, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(LiveWhite10)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MirrorLucideIcon("LShieldCheck", tint = MirrorArt.Dim, modifier = Modifier.size(12.dp))
                        Text("ONLY GAME", color = MirrorArt.Dim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LiveWhite5)
                        .border(1.dp, LiveWhite10, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (valid) trimmed else "Season name",
                        color = MirrorArt.Text,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Tic-tac-toe · started by " + meName.ifBlank { "you" } + " · joins open to everyone in this chat",
                        color = MirrorArt.Faint,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                if (actionError.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        actionError,
                        color = LiveRose400,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))
                // submit
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (valid && !pending) {
                                Brush.linearGradient(listOf(LiveAmber500, LiveAmber600))
                            } else {
                                Brush.linearGradient(listOf(Color(0xFF3F3F46), Color(0xFF3F3F46)))
                            },
                        )
                        .clickable(enabled = valid && !pending) { submit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pending) {
                            MirrorLucideIcon("LLoaderCircle", tint = MirrorArt.Faint, modifier = Modifier.size(18.dp))
                        } else {
                            MirrorLucideIcon("LTrophy", tint = if (valid) Color.White else MirrorArt.Faint, modifier = Modifier.size(16.dp))
                        }
                        Text(
                            "Start tournament",
                            color = if (valid && !pending) Color.White else MirrorArt.Faint,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else {
                // seasons list (web TournamentSection)
                val list = seasons
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MirrorLucideIcon("LTrophy", tint = MirrorArt.Faint, modifier = Modifier.size(14.dp))
                        Text(
                            "TOURNAMENT",
                            color = MirrorArt.Faint,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        if (list == null) "…" else "${list.size}",
                        color = MirrorArt.Dim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LiveWhite2)
                        .border(1.dp, LiveWhite5, RoundedCornerShape(16.dp))
                        .padding(6.dp),
                ) {
                    when {
                        list == null -> {
                            Text(
                                "Loading season…",
                                color = MirrorArt.Faint,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                        loadError -> {
                            Text(
                                "Could not load tournaments.",
                                color = MirrorArt.Faint,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                        list.isEmpty() -> {
                            Text(
                                "No seasons yet - start one below.",
                                color = MirrorArt.Faint,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                        else -> {
                            for (season in list) {
                                val running = season.status == "running"
                                Column(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 6.dp)
                                            .height(52.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Box(
                                            Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Brush.linearGradient(listOf(LiveAmber400, LiveAmber600))),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            MirrorLucideIcon("LTrophy", tint = Color.White, modifier = Modifier.size(16.dp))
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                season.name,
                                                color = MirrorArt.Text,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            val count = season.playerCount ?: 0
                                            Text(
                                                (if (season.game == "tictactoe") "Tic-tac-toe" else season.game) +
                                                    " · $count " + (if (count == 1) "player" else "players") +
                                                    " · " + mirrorSeasonStamp(season.createdAt),
                                                color = MirrorArt.Faint,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Row(
                                            Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(if (running) LiveAmberTint15 else LiveWhite10)
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(if (running) LiveAmber500 else MirrorArt.Faint),
                                            )
                                            Text(
                                                if (running) "Running" else "Finished",
                                                color = if (running) LiveAmber300 else MirrorArt.Dim,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    // the season card's real join action (running seasons only;
                                    // the list payload carries no membership, server join is idempotent)
                                    if (running) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                                                .height(40.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Brush.linearGradient(listOf(LiveAmber500, LiveAmber600)))
                                                .clickable(enabled = joiningId == null) { join(season.id) },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (joiningId == season.id) {
                                                    MirrorLucideIcon("LLoaderCircle", tint = Color.White, modifier = Modifier.size(16.dp))
                                                } else {
                                                    MirrorLucideIcon("LGamepad2", tint = Color.White, modifier = Modifier.size(16.dp))
                                                }
                                                Text("Join season", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (actionError.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        actionError,
                        color = LiveRose400,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                // season composer launcher (web TournamentSection footer button)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(LiveAmberTint10)
                        .border(1.dp, LiveAmberRing40, RoundedCornerShape(12.dp))
                        .clickable {
                            name = ""
                            actionError = ""
                            composing = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MirrorLucideIcon("LPlus", tint = LiveAmber300, modifier = Modifier.size(16.dp))
                        Text("Start tournament", color = LiveAmber300, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
