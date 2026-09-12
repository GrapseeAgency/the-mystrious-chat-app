package app.pulse.feature.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.feature.voice.engine.SpaceBoardStateMachine
import app.pulse.feature.voice.vm.VoiceRoomsViewModel
import app.pulse.ui.PulsePalette
import app.pulse.ui.initialsOf
import kotlin.math.roundToInt

/**
 * Wave 5 — the spatial-presence surface (spec §1.3 SP-1..SP-5): a normalized
 * 0..1 map with tap-to-move AND drag (the 80ms client throttle matches the
 * server budget — WEB DEFECT FIX #3), an optimistic self target that
 * reconciles to server truth once the finger idles 300ms (FIX #4), a NEARBY
 * chip rail (≤0.18 euclidean) and an honest error state after the reconnect
 * budget (FIX #5).
 */
@Composable
fun SpaceScreen(
    conversationId: String,
    vm: VoiceRoomsViewModel,
    onClose: () -> Unit,
) {
    val state by vm.spaceState.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .semantics { contentDescription = "Space" },
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close space")
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Space", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when (state.status) {
                        SpaceBoardStateMachine.Status.CONNECTED -> "Connected — tap or drag to move"
                        SpaceBoardStateMachine.Status.ERROR -> state.error ?: "The room is unreachable."
                        else -> "Connecting to the room…"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (state.status) {
                        SpaceBoardStateMachine.Status.CONNECTED -> PulsePalette.Emerald
                        SpaceBoardStateMachine.Status.ERROR -> Color(0xFFE11D48)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state.status == SpaceBoardStateMachine.Status.CONNECTED) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = PulsePalette.Emerald,
                    modifier = Modifier.semantics { contentDescription = "${state.pill} people in the room" },
                ) {
                    Text(
                        "${state.pill} in room",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        when (state.status) {
            SpaceBoardStateMachine.Status.ERROR -> {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(color = Color(0x1AE11D48), shape = RoundedCornerShape(12.dp)) {
                        Text(
                            state.error ?: "Couldn't reach the room.",
                            color = Color(0xFFE11D48),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = vm::retrySpace,
                        modifier = Modifier.height(48.dp).semantics { contentDescription = "Try the space again" },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Try again")
                    }
                }
            }

            else -> {
                SpaceMap(state, vm, Modifier.weight(1f))
                Spacer(Modifier.height(14.dp))
                NearbyRail(state)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Positions are live and temporary — stand near someone to light up their NEARBY ring.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SpaceMap(
    state: SpaceBoardStateMachine.State,
    vm: VoiceRoomsViewModel,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3.4f)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .semantics {
                contentDescription = "Spatial map — tap or drag to move your dot"
                stateDescription = "${state.pill} in room"
            }
            // Tap-to-move AND drag (SP-3) — the machine throttles at 80ms and
            // clamps into 0..1; the UI only normalizes coordinates.
            .pointerInput(state.status) {
                if (state.status != SpaceBoardStateMachine.Status.CONNECTED) return@pointerInput
                detectTapGestures { off ->
                    vm.moveSpace(off.x / size.width.toDouble(), off.y / size.height.toDouble())
                }
            }
            .pointerInput(state.status) {
                if (state.status != SpaceBoardStateMachine.Status.CONNECTED) return@pointerInput
                detectDragGestures(
                    onDragStart = { off ->
                        vm.moveSpace(off.x / size.width.toDouble(), off.y / size.height.toDouble())
                    },
                ) { change, _ ->
                    change.consume()
                    vm.moveSpace(change.position.x / size.width.toDouble(), change.position.y / size.height.toDouble())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val w = maxWidth
        val h = maxHeight

        fun at(x: Double, y: Double): Modifier = Modifier.offset {
            IntOffset((x * w.toPx()).roundToInt(), (y * h.toPx()).roundToInt())
        }

        // Server-truth dots — everyone but me renders directly.
        for (player in state.players.values) {
            if (player.id == state.selfId) continue
            val nearby = player.id in state.nearbyIds
            Box(
                at(player.x, player.y).size(18.dp).semantics {
                    contentDescription = "${player.name} at ${(player.x * 100).roundToInt()}%, ${(player.y * 100).roundToInt()}%" +
                        if (nearby) " — nearby" else ""
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (nearby) 22.dp else 16.dp)
                        .border(if (nearby) 3.dp else 1.dp, PulsePalette.Emerald.copy(alpha = if (nearby) 0.9f else 0.4f), CircleShape)
                        .background(Color(0xFF52525B), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        initialsOf(player.name).uppercase().take(1),
                        color = Color.White,
                        fontSize = 9.sp,
                    )
                }
            }
        }

        // My optimistic target — the ghost dot under the finger (FIX #4).
        state.myTarget?.let { target ->
            Box(
                at(target.x, target.y)
                    .size(26.dp)
                    .border(2.dp, PulsePalette.TealLight.copy(alpha = 0.7f), CircleShape)
                    .semantics { contentDescription = "Your target position" },
            )
        }

        // Myself — halo + dot at the reconciled position.
        val self = state.players[state.selfId]
        val selfPos = self?.let { SpaceBoardStateMachine.Point(it.x, it.y) } ?: state.myTarget
        selfPos?.let { pos ->
            Box(
                at(pos.x, pos.y).size(44.dp).semantics { contentDescription = "You are here" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(PulsePalette.Emerald.copy(alpha = 0.14f), CircleShape),
                )
                Box(
                    Modifier
                        .size(20.dp)
                        .border(2.dp, PulsePalette.Emerald, CircleShape)
                        .background(PulsePalette.Emerald, CircleShape),
                )
            }
        }

        if (state.status == SpaceBoardStateMachine.Status.JOINING) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "Connecting to the room…",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun NearbyRail(state: SpaceBoardStateMachine.State) {
    val nearby = state.players.values.filter { it.id in state.nearbyIds }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (nearby.isEmpty()) {
            Text(
                "Nobody is within reach right now — walk closer.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "NEARBY",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = PulsePalette.Emerald,
            )
            for (player in nearby.take(5)) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = PulsePalette.Emerald.copy(alpha = 0.16f),
                    contentColor = PulsePalette.Emerald,
                ) {
                    Text(
                        player.name,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .semantics { contentDescription = "${player.name} is nearby" },
                    )
                }
            }
        }
    }
}
