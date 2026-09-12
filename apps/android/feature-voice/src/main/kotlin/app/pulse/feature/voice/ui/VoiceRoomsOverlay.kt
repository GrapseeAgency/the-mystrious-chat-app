package app.pulse.feature.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.feature.voice.VoiceRoomsEngine
import app.pulse.feature.voice.vm.VoiceRoomsViewModel
import app.pulse.ui.PulsePalette

/**
 * Wave 5 — the single overlay host for all three room surfaces (the
 * CallOverlay hosting idiom: mounted once next to the call overlay at the
 * shell root; the [VoiceRoomsEngine] surface state selects WHICH surface
 * renders). The compact kind switcher moves between Voice/Stage/Space for
 * the SAME conversation without leaving anything: voice membership survives
 * surface switches (VR-1), stage/space leave on close (ST-7/SP-1) — the
 * engine owns all of that.
 */
@Composable
fun VoiceRoomsOverlay(vm: VoiceRoomsViewModel) {
    val surface by vm.surface.collectAsStateWithLifecycle()
    val open = surface ?: return

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF5121212))
            .semantics { contentDescription = "Voice rooms overlay" },
    ) {
        RoomKindSwitcher(open, vm)
        Box(Modifier.fillMaxSize()) {
            when (open.kind) {
                VoiceRoomsEngine.RoomKind.VOICE ->
                    VoiceRoomScreen(conversationId = open.conversationId, vm = vm, onClose = vm::closeSurface)
                VoiceRoomsEngine.RoomKind.STAGE ->
                    StageScreen(conversationId = open.conversationId, vm = vm, onClose = vm::closeSurface)
                VoiceRoomsEngine.RoomKind.SPACE ->
                    SpaceScreen(conversationId = open.conversationId, vm = vm, onClose = vm::closeSurface)
            }
        }
    }
}

@Composable
private fun RoomKindSwitcher(open: VoiceRoomsEngine.OpenSurface, vm: VoiceRoomsViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (kind in VoiceRoomsEngine.RoomKind.entries) {
            val active = kind == open.kind
            val label = when (kind) {
                VoiceRoomsEngine.RoomKind.VOICE -> "Voice"
                VoiceRoomsEngine.RoomKind.STAGE -> "Stage"
                VoiceRoomsEngine.RoomKind.SPACE -> "Space"
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (active) PulsePalette.Emerald else Color(0xFF27272A),
                contentColor = if (active) Color.White else Color(0xFFA1A1AA),
                modifier = Modifier
                    .semantics {
                        contentDescription = "Switch to the $label surface"
                        stateDescription = if (active) "Active surface" else "Inactive surface"
                    }
                    .clickable(enabled = !active) {
                        when (kind) {
                            VoiceRoomsEngine.RoomKind.VOICE -> vm.openVoice(open.conversationId)
                            VoiceRoomsEngine.RoomKind.STAGE -> vm.openStage(open.conversationId)
                            VoiceRoomsEngine.RoomKind.SPACE -> vm.openSpace(open.conversationId)
                        }
                    },
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(
            "live",
            fontSize = 11.sp,
            color = PulsePalette.Emerald,
            fontWeight = FontWeight.Medium,
        )
    }
}
