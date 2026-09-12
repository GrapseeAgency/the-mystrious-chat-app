package app.pulse.feature.voice.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.feature.voice.VoiceRoomsEngine
import app.pulse.feature.voice.engine.VoiceRoomStateMachine
import app.pulse.feature.voice.vm.VoiceRoomsViewModel
import app.pulse.ui.PulsePalette
import app.pulse.ui.initialsOf

/**
 * Wave 5 — the voice room surface (walkie-talkie PTT, spec §1.1 VR-1..VR-10):
 * roster tiles with speaking glow, the big hold/tap PTT button, mute gate +
 * banner, live captions (persisted toggle), honest connection/error states
 * and the "never recorded or stored" footer contract.
 */
@Composable
fun VoiceRoomScreen(
    conversationId: String,
    vm: VoiceRoomsViewModel,
    onClose: () -> Unit,
) {
    val state by vm.voiceState.collectAsStateWithLifecycle()
    val captions by vm.captions.collectAsStateWithLifecycle()
    val captionsOn by vm.captionsEnabled.collectAsStateWithLifecycle()
    val relayConnected by vm.connected.collectAsStateWithLifecycle()

    // RECORD_AUDIO gate — Join passes through the runtime permission (house
    // pattern; denial keeps the honest error panel on the surface).
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.joinVoice(conversationId)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .semantics { contentDescription = "Voice room" },
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close voice room")
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Voice room", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    connectionLine(state, relayConnected),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (relayConnected) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        when {
            state.status == VoiceRoomStateMachine.Status.ERROR -> {
                ErrorPanel(
                    message = state.error ?: "The room is unreachable right now.",
                    onRetry = { vm.joinVoice(conversationId) },
                    modifier = Modifier.weight(1f),
                )
            }

            !state.joined -> {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (state.status == VoiceRoomStateMachine.Status.JOINING) {
                        Text("Syncing roster…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(
                            "Talk in real time — hold to speak, release to listen.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
                            modifier = Modifier
                                .height(48.dp)
                                .semantics { contentDescription = "Join the voice room" },
                        ) {
                            Icon(Icons.Filled.GraphicEq, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Join voice room")
                        }
                    }
                }
            }

            else -> {
                if (state.micMuted) {
                    Surface(
                        color = Color(0x1AF59E0B),
                        contentColor = PulsePalette.Amber,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Mic is muted" },
                    ) {
                        Text(
                            "Mic is muted",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                RosterList(state, Modifier.weight(1f, fill = false))

                if (captionsOn) {
                    Spacer(Modifier.height(12.dp))
                    CaptionStrip(captions)
                }

                Spacer(Modifier.height(16.dp))
                ControlsRow(
                    state = state,
                    captionsOn = captionsOn,
                    onToggleMute = { vm.setMuted(!state.micMuted) },
                    onToggleCaptions = { vm.setCaptions(!captionsOn) },
                    onPttDown = vm::pttDown,
                    onPttUp = vm::pttUp,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = vm::leaveVoice,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "Leave the voice room" },
                ) { Text("Leave voice room") }
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "Hold to talk · tap to latch · streamed live in 250 ms chunks and never recorded or stored",
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun RosterList(state: VoiceRoomStateMachine.State, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.roster.isEmpty()) {
            Text(
                "Syncing roster…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                textAlign = TextAlign.Center,
            )
        }
        for (peer in state.roster) {
            val speaking = peer.id in state.speakingIds
            val tileDescription = "${peer.name} ${if (speaking) "is speaking" else "is silent"}"
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = if (speaking) 2.dp else 1.dp,
                        color = if (speaking) PulsePalette.Emerald else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .semantics { contentDescription = tileDescription },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(if (speaking) PulsePalette.Emerald else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        initialsOf(peer.name).uppercase().take(2),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(peer.name, Modifier.weight(1f), maxLines = 1)
                if (speaking) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(PulsePalette.Emerald, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptionStrip(captions: List<VoiceRoomsEngine.VoiceCaption>) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Live captions" },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                "Live captions",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            if (captions.isEmpty()) {
                Text(
                    "Captions appear as people talk.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (caption in captions.takeLast(3)) {
                    Text(
                        text = "${caption.name}: ${caption.text}",
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlsRow(
    state: VoiceRoomStateMachine.State,
    captionsOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleCaptions: () -> Unit,
    onPttDown: () -> Unit,
    onPttUp: (Long) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onToggleMute,
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = if (state.micMuted) "Unmute microphone" else "Mute microphone"
                },
        ) {
            Icon(
                if (state.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = null,
                tint = if (state.micMuted) PulsePalette.Rose else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        PttButton(state = state, onDown = onPttDown, onUp = onPttUp)
        Spacer(Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = if (captionsOn) PulsePalette.Emerald else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (captionsOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onToggleCaptions)
                .semantics {
                    contentDescription = if (captionsOn) "Turn captions off" else "Turn captions on"
                    stateDescription = if (captionsOn) "Captions on" else "Captions off"
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("CC", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The PTT button — hold ≥260ms talks (stop on release), a tap LATCHES (and a
 * latched tap stops). Disabled honestly when muted / not joined / offline.
 * The aria-equivalent [stateDescription] mirrors the transmit state (VR-3).
 */
@Composable
private fun PttButton(
    state: VoiceRoomStateMachine.State,
    onDown: () -> Unit,
    onUp: (Long) -> Unit,
) {
    val enabled = state.pttEnabled
    val label = when {
        state.transmitting -> "Release to stop"
        !enabled -> "Push to talk unavailable"
        else -> "Hold to talk"
    }
    Box(
        modifier = Modifier
            .size(112.dp)
            .clip(CircleShape)
            .background(if (state.transmitting) PulsePalette.Rose else PulsePalette.EmeraldDeep)
            .then(Modifier.pttHoldGesture(enabled, onDown, onUp))
            .semantics {
                contentDescription = "Push to talk"
                stateDescription = if (state.transmitting) "Transmitting" else "Silent"
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

/** Press/release detection feeding the engine's hold-vs-latch decision. */
internal fun Modifier.pttHoldGesture(
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: (Long) -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(enabled) {
        detectTapGestures(
            onPress = {
                val started = System.currentTimeMillis()
                onDown()
                // A cancelled gesture still ends the push — never leave a
                // latched transmitter behind on a system cancel.
                tryAwaitRelease()
                onUp(System.currentTimeMillis() - started)
            },
        )
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(color = Color(0x1AE11D48), shape = RoundedCornerShape(12.dp)) {
            Text(
                message,
                color = Color(0xFFE11D48),
                fontSize = 13.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.height(48.dp).semantics { contentDescription = "Try again" },
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Try again")
        }
    }
}

/** Honest connection line (VR-8): connected / connecting / reconnecting / standby. */
internal fun connectionLine(state: VoiceRoomStateMachine.State, relayConnected: Boolean): String = when {
    state.status == VoiceRoomStateMachine.Status.ERROR -> "Connection problem"
    !relayConnected && state.joined -> "Reconnecting…"
    !relayConnected -> "Standby"
    state.status == VoiceRoomStateMachine.Status.JOINING -> "Connecting…"
    state.status == VoiceRoomStateMachine.Status.JOINED -> "Connected"
    else -> "Standby"
}
