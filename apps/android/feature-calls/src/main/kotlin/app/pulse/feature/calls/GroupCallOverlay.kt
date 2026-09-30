package app.pulse.feature.calls

import app.pulse.ui.PulseIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.domain.model.CallKind
import app.pulse.domain.model.GroupCallMember
// R8 Task 3-c — the pure mesh kernels (duration format + banner copy).
import app.pulse.protocol.GroupCallMesh
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * R8 Task 3-c — the full-screen native GROUP call surface, mirroring the web
 * GroupCallOverlay + GroupCallRingBanner (group-call-overlay.tsx:745-979):
 * participant grid (avatar tiles + remote video for video calls), self tile
 * with mic/camera state, header (title · duration · member count), honest
 * error/ended cards, and the emerald ring/ongoing banners.
 */

/** Web avatar palette names → hex (web user color tokens, ui-theme tokens). */
internal fun groupColorHex(name: String?): String? = when (name?.lowercase()) {
    "emerald" -> "#10B981"
    "teal" -> "#14B8A6"
    "violet" -> "#8B5CF6"
    "rose" -> "#FB7185"
    "amber" -> "#F59E0B"
    "cyan" -> "#06B6D4"
    "sky" -> "#0EA5E9"
    "zinc" -> "#71717A"
    else -> null
}

@Composable
fun GroupCallOverlay(vm: GroupCallViewModel) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val localVideo by vm.localVideoTrack.collectAsStateWithLifecycle()
    val remoteVideos by vm.remoteVideoTracks.collectAsStateWithLifecycle()

    // Idle with no cards → nothing to render (web :755).
    if (snapshot.phase == GroupCallEngine.UiPhase.IDLE && snapshot.error == null && snapshot.summary == null) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD9000000))
            .semantics { contentDescription = "Group call" },
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── header (web :775-790) ────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = snapshot.title.ifBlank { "Group call" },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = groupStatusLine(snapshot),
                        color = Color(0x99FFFFFF),
                        fontSize = 12.sp,
                    )
                }
                // Member-count chip (web :786-789).
                Row(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x1AFFFFFF))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        PulseIcons.Users,
                        contentDescription = null,
                        tint = Color(0xCCFFFFFF),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (snapshot.members.isEmpty()) "…" else "${snapshot.members.size}",
                        color = Color(0xCCFFFFFF),
                        fontSize = 12.sp,
                    )
                }
            }

            // ── body: error card / ended card / the participant grid ──
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                val error = snapshot.error
                val summary = snapshot.summary
                when {
                    error != null -> HonestCard(text = error, actionLabel = "Close") { vm.dismissError() }
                    snapshot.phase == GroupCallEngine.UiPhase.ENDED && summary != null ->
                        HonestCard(text = summary, actionLabel = "Done") { vm.dismissSummary() }
                    else -> ParticipantGrid(
                        snapshot = snapshot,
                        localVideo = localVideo,
                        remoteVideos = remoteVideos,
                        eglContext = vm.engine.eglBaseContext,
                    )
                }
            }

            // ── controls (web :860-891): mic · leave · camera ────
            if (snapshot.phase != GroupCallEngine.UiPhase.ENDED && snapshot.error == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GroupCallButton(
                        icon = if (snapshot.micEnabled) PulseIcons.Mic else PulseIcons.MicOff,
                        description = if (snapshot.micEnabled) "Mute mic" else "Unmute mic",
                        container = if (snapshot.micEnabled) Color(0x26FFFFFF) else Color.White,
                        tint = if (snapshot.micEnabled) Color.White else Color.Black,
                    ) { vm.toggleMic() }
                    Spacer(Modifier.width(18.dp))
                    GroupCallButton(
                        icon = PulseIcons.PhoneDown,
                        description = "Leave call",
                        container = Color(0xFFEF4444),
                        size = 64.dp,
                    ) { vm.leaveCall() }
                    Spacer(Modifier.width(18.dp))
                    GroupCallButton(
                        icon = if (snapshot.cameraEnabled) PulseIcons.Video else PulseIcons.VideoSlash,
                        description = if (snapshot.cameraEnabled) "Turn camera off" else "Turn camera on",
                        container = if (snapshot.cameraEnabled) Color(0x26FFFFFF) else Color.White,
                        tint = if (snapshot.cameraEnabled) Color.White else Color.Black,
                        enabled = snapshot.kind == CallKind.VIDEO,
                    ) { vm.toggleCamera() }
                }
            } else {
                Spacer(Modifier.height(84.dp))
            }
        }

        // Front ⇄ back flip (native bonus, web has no equivalent).
        if (localVideo != null && snapshot.cameraEnabled &&
            snapshot.phase == GroupCallEngine.UiPhase.ACTIVE
        ) {
            IconButton(
                onClick = { vm.switchCamera() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 16.dp)
                    .size(40.dp)
                    .background(Color(0x26FFFFFF), CircleShape)
                    .semantics { contentDescription = "Switch camera" },
            ) {
                Icon(PulseIcons.FlipCamera, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun ParticipantGrid(
    snapshot: GroupCallEngine.Snapshot,
    localVideo: VideoTrack?,
    remoteVideos: Map<String, VideoTrack>,
    eglContext: EglBase.Context,
) {
    // Roster INCLUDING me (engine stamps meId); empty roster still shows the
    // honest self tile (web :758).
    val roster = snapshot.members.ifEmpty {
        listOf(GroupCallMember(id = snapshot.meId, name = "You", color = "emerald"))
    }
    val remoteMembers = roster.filter { it.id != snapshot.meId }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Self tile (web :816-828) — video when a camera is really attached.
        item(key = "self") {
            ParticipantTile(label = "You", micMuted = !snapshot.micEnabled) {
                if (snapshot.kind == CallKind.VIDEO && localVideo != null && snapshot.cameraEnabled) {
                    VideoRenderer(
                        track = localVideo,
                        eglBaseContext = eglContext,
                        mirrored = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    TileAvatar(name = "You", colorHex = null, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        // Remote tiles (audio-only members keep honest avatar tiles, web :829-849).
        items(remoteMembers, key = { it.id }) { member ->
            ParticipantTile(label = member.name, micMuted = false) {
                val track = remoteVideos[member.id]
                if (snapshot.kind == CallKind.VIDEO && track != null) {
                    VideoRenderer(
                        track = track,
                        eglBaseContext = eglContext,
                        mirrored = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    TileAvatar(
                        name = member.name,
                        colorHex = groupColorHex(member.color),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
        if (snapshot.phase == GroupCallEngine.UiPhase.JOINING) {
            item(key = "joining") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(24.dp))
                        .background(Color(0x0DFFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Connecting…", color = Color(0x99FFFFFF), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TileAvatar(name: String, colorHex: String?, modifier: Modifier = Modifier) {
    PulseAvatar(name = name, colorHex = colorHex, size = 56.dp, modifier = modifier)
}

/** One participant tile — video fill or centered avatar + name strip (web :816-849). */
@Composable
private fun ParticipantTile(
    label: String,
    micMuted: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(24.dp))
            .background(Color(0x0DFFFFFF)),
    ) {
        content()
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (micMuted) {
                Icon(
                    PulseIcons.MicOff,
                    contentDescription = "Mic muted",
                    tint = Color(0xFFFDA4AF),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** Honest glass card — the error/ended surfaces (web :793-812). */
@Composable
private fun HonestCard(text: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 32.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(24.dp))
            .background(Color(0x1AFFFFFF))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x26FFFFFF))
                .clickable(onClick = onAction)
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .semantics { contentDescription = actionLabel },
        ) {
            Text(actionLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun GroupCallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    container: Color,
    tint: Color = Color.White,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(size)
            .background(if (enabled) container else container.copy(alpha = 0.3f), CircleShape)
            .semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}

/** Header status line — phase-aware (web :778-784). */
internal fun groupStatusLine(snapshot: GroupCallEngine.Snapshot): String {
    val summary = snapshot.summary
    if (snapshot.phase == GroupCallEngine.UiPhase.ENDED && !summary.isNullOrBlank()) return summary
    return when (snapshot.phase) {
        GroupCallEngine.UiPhase.JOINING -> "Connecting…"
        GroupCallEngine.UiPhase.ACTIVE ->
            "${if (snapshot.kind == CallKind.VIDEO) "Video" else "Voice"} call · " +
                GroupCallMesh.formatDuration(snapshot.durationSec)
        else -> summary.orEmpty()
    }
}

/** The incoming-ring + ongoing banners (web GroupCallRingBanner :899-979). */
@Composable
fun GroupCallBanner(vm: GroupCallViewModel, modifier: Modifier = Modifier) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val ring = snapshot.ring
    Box(modifier = modifier) {
        if (ring != null && snapshot.phase == GroupCallEngine.UiPhase.IDLE) {
            BannerRow(
                headline = GroupCallMesh.ringTitle(ring.title, ring.caller.name),
                subtitle = GroupCallMesh.ringSubtitle(ring.kind.wire, ring.caller.name),
                ignoreLabel = "Ignore",
                actionLabel = "Join",
                onIgnore = { vm.dismissRing() },
                onJoin = { vm.joinCall() },
                joinDescription = "Join group call",
            )
            return@Box
        }
        if (snapshot.ongoingElsewhere && snapshot.phase == GroupCallEngine.UiPhase.IDLE) {
            BannerRow(
                headline = GroupCallMesh.ongoingTitle(snapshot.ongoingMembers.size),
                // Domain roster → wire DTO shim (GroupCallMesh is pure :protocol).
                subtitle = GroupCallMesh.ongoingNames(
                    snapshot.ongoingMembers.map {
                        app.pulse.protocol.GroupCallMemberDto(id = it.id, name = it.name, color = it.color, avatar = it.avatar)
                    },
                ),
                ignoreLabel = "Ignore",
                actionLabel = "Join",
                onIgnore = { vm.ignoreOngoing() },
                onJoin = { vm.joinOngoing() },
                joinDescription = "Join ongoing group call",
            )
        }
    }
}

@Composable
private fun BannerRow(
    headline: String,
    subtitle: String,
    ignoreLabel: String,
    actionLabel: String,
    onIgnore: () -> Unit,
    onJoin: () -> Unit,
    joinDescription: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, PulsePalette.Emerald.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .background(PulsePalette.Emerald.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(PulsePalette.Emerald.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PulseIcons.Phone,
                contentDescription = null,
                tint = PulsePalette.Emerald,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                headline,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            ignoreLabel,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onIgnore)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Text(
            actionLabel,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(PulsePalette.Emerald)
                .clickable(onClick = onJoin)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = joinDescription },
        )
    }
}
