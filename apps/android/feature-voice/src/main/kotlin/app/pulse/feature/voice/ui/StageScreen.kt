package app.pulse.feature.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.feature.voice.VoiceRoomsEngine
import app.pulse.feature.voice.engine.StageRoomStateMachine
import app.pulse.feature.voice.vm.VoiceRoomsViewModel
import app.pulse.ui.PulsePalette
import app.pulse.ui.initialsOf
import kotlinx.coroutines.delay

/**
 * Wave 5 — the stage surface (spec §1.2 ST-1..ST-8): amber host card with a
 * crown, speaker tiles glowing from voice:ptt, a FIFO hand queue with
 * host-only Approve/Decline, the audience row + count chip, listener hand
 * raising, host/speaker-only PTT, the two-tap End confirm (2600ms reset) and
 * "Claim host" when the seat is empty (ST-7 — no auto-promotion).
 */
@Composable
fun StageScreen(
    conversationId: String,
    vm: VoiceRoomsViewModel,
    onClose: () -> Unit,
) {
    val state by vm.stageState.collectAsStateWithLifecycle()
    val relayConnected by vm.connected.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .semantics { contentDescription = "Stage" },
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close stage")
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Stage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !relayConnected -> "Reconnecting…"
                        state.status == StageRoomStateMachine.Status.JOINING -> "Connecting…"
                        state.status == StageRoomStateMachine.Status.ENDED -> "The host ended the stage"
                        state.joined -> "Connected"
                        else -> "Standby"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (relayConnected) PulsePalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.joined) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "${state.listenerCount} listeners" },
                ) {
                    Text(
                        "${state.listenerCount} listening",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        when {
            state.status == StageRoomStateMachine.Status.ENDED -> {
                EndedPanel(onClose = onClose, modifier = Modifier.weight(1f))
            }

            !state.joined -> {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (state.status == StageRoomStateMachine.Status.JOINING) {
                        Text("Syncing stage…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(
                            "Join as a listener — the first person in a fresh stage hosts it.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { vm.joinStage(conversationId) },
                            modifier = Modifier.height(48.dp).semantics { contentDescription = "Join the stage as listener" },
                        ) { Text("Join stage") }
                    }
                }
            }

            else -> {
                StageRoster(state, vm, Modifier.weight(1f, fill = false))
                Spacer(Modifier.height(16.dp))
                StageControls(state, vm, conversationId, onClose)
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "Speakers stream in 250 ms chunks · everyone holds a voice seat · never recorded or stored",
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun StageRoster(
    state: StageRoomStateMachine.State,
    vm: VoiceRoomsViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.firstStateArrived) {
            Text(
                "Syncing stage…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                textAlign = TextAlign.Center,
            )
        }

        // Claim host — the seat is empty after the host left; there is NO
        // auto-promotion (ST-7).
        if (state.canClaimHost) {
            Button(
                onClick = vm::claimHost,
                modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "Claim the empty host seat" },
            ) {
                Icon(Icons.Filled.Star, contentDescription = null, Modifier.size(18.dp), tint = PulsePalette.Amber)
                Spacer(Modifier.width(8.dp))
                Text("Claim host")
            }
        }

        state.host?.let { host ->
            PersonTile(
                name = host.name,
                glowing = host.id in state.speakingIds,
                tileColor = PulsePalette.Amber,
                leading = {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Host",
                        tint = PulsePalette.Amber,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }

        for (speaker in state.speakers) {
            PersonTile(
                name = speaker.name,
                glowing = speaker.id in state.speakingIds,
                tileColor = PulsePalette.Emerald,
                trailing = if (state.isHost) {
                    {
                        IconButton(
                            onClick = { vm.demote(speaker.id) },
                            modifier = Modifier.size(40.dp).semantics { contentDescription = "Move ${speaker.name} to listeners" },
                        ) {
                            Icon(Icons.Filled.MicOff, contentDescription = null, tint = PulsePalette.Rose, modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    null
                },
            )
        }

        val hands = state.hands
        if (hands.isNotEmpty()) {
            Text(
                "Raised hands",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        hands.forEachIndexed { index, hand ->
            PersonTile(
                name = "${index + 1}. ${hand.name}",
                glowing = false,
                tileColor = PulsePalette.Violet,
                leading = {
                    Icon(
                        Icons.Filled.PanTool,
                        contentDescription = "Raised hand",
                        tint = PulsePalette.Violet,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailing = if (state.isHost) {
                    {
                        Row {
                            IconButton(
                                onClick = { vm.approveHand(hand.id) },
                                modifier = Modifier.size(40.dp).semantics { contentDescription = "Approve ${hand.name} as speaker" },
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = PulsePalette.Emerald, modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { vm.demote(hand.id) },
                                modifier = Modifier.size(40.dp).semantics { contentDescription = "Dismiss ${hand.name}'s hand" },
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, tint = PulsePalette.Rose, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                } else {
                    null
                },
            )
        }

        val listeners = state.listeners
        if (listeners.isNotEmpty()) {
            Text(
                "Listeners",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildString {
                    listeners.take(4).forEachIndexed { i, l ->
                        if (i > 0) append(", ")
                        append(l.name)
                    }
                    if (listeners.size > 4) append(" +${listeners.size - 4} more")
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StageControls(
    state: StageRoomStateMachine.State,
    vm: VoiceRoomsViewModel,
    conversationId: String,
    onClose: () -> Unit,
) {
    // Two-tap End confirm with the 2600ms reset (ST-5) — armed state mirrors
    // the machine's window and self-expires.
    var endArmed by remember { mutableStateOf(false) }
    LaunchedEffect(endArmed) {
        if (endArmed) {
            delay(VoiceRoomsEngine.STAGE_END_CONFIRM_MS)
            endArmed = false
            vm.disarmEndConfirm()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            state.canTransmit -> {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(PulsePalette.EmeraldDeep)
                        .then(Modifier.pttHoldGesture(true, vm::pttDown, vm::pttUp))
                        .semantics {
                            contentDescription = "Push to talk on stage"
                            stateDescription = "Stage microphone"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }

            state.myRole == StageRoomStateMachine.Role.HAND || state.myRole == StageRoomStateMachine.Role.LISTENER -> {
                OutlinedButton(
                    onClick = vm::raiseHand,
                    modifier = Modifier.height(48.dp).semantics {
                        contentDescription = if (state.handRaised) "Lower your hand" else "Raise your hand"
                        stateDescription = if (state.handRaised) "Hand raised" else "Hand lowered"
                    },
                ) {
                    Icon(
                        Icons.Filled.PanTool,
                        contentDescription = null,
                        Modifier.size(18.dp),
                        tint = if (state.handRaised) PulsePalette.Violet else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.handRaised) "Lower hand" else "Raise hand")
                }
            }
        }

        Spacer(Modifier.weight(1f))

        if (state.isHost) {
            Button(
                onClick = {
                    if (vm.endStage()) {
                        endArmed = false
                        onClose() // the host's own surface closes; others get stage:ended
                    } else {
                        endArmed = true
                    }
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (endArmed) PulsePalette.Rose else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (endArmed) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier
                    .height(48.dp)
                    .semantics {
                        contentDescription = if (endArmed) "Tap again to end the stage" else "End the stage for everyone"
                        stateDescription = if (endArmed) "Confirm required" else "First tap"
                    },
            ) { Text(if (endArmed) "Tap again to end" else "End stage") }
        } else {
            OutlinedButton(
                onClick = {
                    vm.leaveStage(conversationId)
                    onClose()
                },
                modifier = Modifier.height(48.dp).semantics { contentDescription = "Leave the stage" },
            ) { Text("Leave stage") }
        }
    }
}

@Composable
private fun EndedPanel(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("The host ended the stage", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.height(48.dp).semantics { contentDescription = "Close the ended stage" },
        ) { Text("Close") }
    }
}

/** One stage person tile — the [glowing] ring is the voice:ptt speaking truth. */
@Composable
internal fun PersonTile(
    name: String,
    glowing: Boolean,
    tileColor: Color,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (glowing) 2.dp else 1.dp,
                color = if (glowing) tileColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "$name ${if (glowing) "is speaking" else ""}".trim() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(34.dp)
                .background(tileColor.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(initialsOf(name).uppercase().take(2), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Text(name, Modifier.weight(1f), maxLines = 1, fontWeight = FontWeight.Medium)
        if (trailing != null) trailing()
    }
}
