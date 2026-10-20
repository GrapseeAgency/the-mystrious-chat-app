package app.pulse.feature.chat

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Activity-owned bridge; Mirror and feature-chat now share the same player. */
@HiltViewModel
class VoicePlaybackViewModel @Inject constructor(val player: VoicePlayer) : ViewModel() {
    override fun onCleared() { player.release(); super.onCleared() }
}
