package app.pulse.feature.calls

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.domain.call.CallSnapshot
import app.pulse.domain.model.CallDirection
import app.pulse.domain.model.CallState
import app.pulse.ui.initialsOf

/**
 * Wave 3 — the full-screen native call surface. One composable covers the
 * whole machine (incoming ring → outgoing ring → connecting → connected →
 * ended), exactly like the web call-overlay. All actions flow through
 * [CallViewModel] → [CallEngine] → the pure state machine.
 */
@Composable
fun CallOverlay(vm: CallViewModel) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val micMuted by vm.micMuted.collectAsStateWithLifecycle()
    val speakerOn by vm.speakerOn.collectAsStateWithLifecycle()

    if (snapshot.state == CallState.IDLE) return

    // RECORD_AUDIO gate — every mic-starting action passes through here and
    // is honest about denial (the call cannot proceed without it).
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            when (snapshot.state) {
                CallState.INCOMING_RINGING -> vm.accept()
                else -> Unit
            }
        } else if (snapshot.state == CallState.INCOMING_RINGING) {
            // Can't answer without a mic — declining is the honest action.
            vm.decline()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6121212))
            .semantics { contentDescription = "Call screen" },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(96.dp))
            PeerAvatar(snapshot.peer?.name)
            Spacer(Modifier.height(20.dp))
            Text(
                text = snapshot.peer?.name ?: "Unknown",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusLine(snapshot, micMuted),
                color = Color(0xFFB9B9C0),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))

            when (snapshot.state) {
                CallState.INCOMING_RINGING -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 72.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Decline
                        CallButton(
                            icon = Icons.Filled.CallEnd,
                            description = "Decline call",
                            container = Color(0xFFE11D48),
                        ) { vm.decline() }
                        // Accept — the mic permission must be live to answer.
                        CallButton(
                            icon = Icons.Filled.Call,
                            description = "Accept call",
                            container = Color(0xFF10B981),
                        ) {
                            micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }

                CallState.ENDED -> {
                    CallButton(
                        icon = Icons.Filled.CallEnd,
                        description = "Dismiss",
                        container = Color(0xFF3F3F46),
                    ) { vm.dismiss() }
                    Spacer(Modifier.height(72.dp))
                }

                else -> {
                    // Active call controls: mute, speaker, hangup.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 72.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CallButton(
                            icon = if (micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            description = if (micMuted) "Unmute microphone" else "Mute microphone",
                            container = if (micMuted) Color.White else Color(0xFF3F3F46),
                            tint = if (micMuted) Color.Black else Color.White,
                        ) { vm.toggleMute() }
                        CallButton(
                            icon = Icons.Filled.CallEnd,
                            description = "End call",
                            container = Color(0xFFE11D48),
                        ) {
                            if (snapshot.direction == CallDirection.OUTGOING &&
                                (snapshot.state == CallState.OUTGOING_RINGING)
                            ) {
                                vm.cancel()
                            } else {
                                vm.hangup()
                            }
                        }
                        CallButton(
                            icon = if (speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                            description = if (speakerOn) "Switch to earpiece" else "Switch to speaker",
                            container = if (speakerOn) Color.White else Color(0xFF3F3F46),
                            tint = if (speakerOn) Color.Black else Color.White,
                        ) { vm.toggleSpeaker() }
                    }
                }
            }
        }
    }
}

private fun statusLine(snapshot: CallSnapshot, micMuted: Boolean): String {
    if (!snapshot.error.isNullOrBlank()) return snapshot.error
    if (!snapshot.summary.isNullOrBlank() && snapshot.state == CallState.ENDED) return snapshot.summary
    return when (snapshot.state) {
        CallState.OUTGOING_RINGING -> "Ringing…"
        CallState.INCOMING_RINGING -> "Incoming voice call"
        CallState.CONNECTING -> "Connecting…"
        CallState.CONNECTED ->
            formatDuration(snapshot.durationSec) + if (micMuted) " · muted" else ""
        else -> ""
    }
}

internal fun formatDuration(totalSec: Long): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
}

@Composable
private fun PeerAvatar(name: String?) {
    Box(
        modifier = Modifier
            .size(112.dp)
            .background(Color(0xFF2A2A30), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name ?: "?").uppercase().take(2).ifBlank { "?" },
            color = Color(0xFF10B981),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    container: Color,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(72.dp)
            .background(container, CircleShape)
            .semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}
