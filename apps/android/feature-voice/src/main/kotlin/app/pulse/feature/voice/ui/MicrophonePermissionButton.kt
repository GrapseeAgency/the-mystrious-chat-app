package app.pulse.feature.voice.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import app.pulse.feature.voice.vm.VoiceRoomsViewModel

/** Joining/listening is permission-free. Capture requires a current explicit user request. */
@Composable
internal fun MicrophonePermissionButton(vm: VoiceRoomsViewModel, conversationId: String, maySpeak: Boolean) {
    val lifecycle = LocalLifecycleOwner.current
    val ready by vm.microphoneEnabled.collectAsStateWithLifecycle()
    var pending by remember(conversationId) { mutableStateOf<Long?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.let {
            if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.resolveMicrophone(it, granted)
            else vm.cancelMicrophoneRequest()
        }
        pending = null
    }
    if (maySpeak && !ready) {
        Button(enabled = pending == null, onClick = {
            pending = vm.requestMicrophone(conversationId)
            if (pending != null) launcher.launch(Manifest.permission.RECORD_AUDIO)
        }) { Text(if (pending != null) "Waiting for permission…" else "Enable microphone") }
    }
}
