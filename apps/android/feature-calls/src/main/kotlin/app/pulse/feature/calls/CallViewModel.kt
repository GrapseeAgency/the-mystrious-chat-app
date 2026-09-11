package app.pulse.feature.calls

import androidx.lifecycle.ViewModel
import app.pulse.domain.call.CallEffect
import app.pulse.domain.call.CallSnapshot
import app.pulse.domain.model.CallPeer
import app.pulse.domain.repository.PulseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Wave 3 — thin activity-scoped bridge between the call UI and the
 * [CallEngine] singleton (the engine owns ALL call state; ViewModels come
 * and go with screens, the call must not).
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    val engine: CallEngine,
    private val repo: PulseRepository,
) : ViewModel() {

    val snapshot: StateFlow<CallSnapshot> = engine.snapshot
    val micMuted: StateFlow<Boolean> = engine.micMuted
    val speakerOn: StateFlow<Boolean> = engine.speakerOn

    fun startOutgoing(
        peerId: String,
        name: String,
        color: String?,
        avatar: String?,
        callerName: String? = null,
        callerColor: String? = null,
    ) = engine.startOutgoing(peerId, name, color, avatar, callerName, callerColor)

    fun accept() = engine.accept()

    fun decline() = engine.decline()

    fun cancel() = engine.cancel()

    fun hangup() = engine.hangup()

    fun dismiss() = engine.dismiss()

    fun toggleMute(): Boolean = engine.toggleMute()

    fun toggleSpeaker(): Boolean = engine.toggleSpeaker()

    /** Peer entry point for the contacts list — the engine resolves the DM. */
    fun callPeer(
        peerId: String,
        name: String,
        color: String?,
        avatar: String? = null,
        callerName: String? = null,
        callerColor: String? = null,
    ) = startOutgoing(peerId, name, color, avatar, callerName, callerColor)

    companion object {
        /** Reserved for future effect-level UI hooks (the engine consumes effects). */
        val NoEffects: List<CallEffect> = emptyList()
    }
}

/** Small extension so history rows can render without leaking domain internals. */
fun CallPeer.displayLabel(): String = name.ifBlank { "Unknown" }
