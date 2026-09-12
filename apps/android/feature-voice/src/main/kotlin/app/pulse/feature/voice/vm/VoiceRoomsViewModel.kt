package app.pulse.feature.voice.vm

import androidx.lifecycle.ViewModel
import app.pulse.feature.voice.VoiceRoomsEngine
import app.pulse.feature.voice.engine.SpaceBoardStateMachine
import app.pulse.feature.voice.engine.StageRoomStateMachine
import app.pulse.feature.voice.engine.VoiceRoomStateMachine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Wave 5 — thin activity-scoped bridge between the voice-room surfaces and
 * the [VoiceRoomsEngine] singleton (the CallViewModel pattern: the engine
 * owns ALL room state; ViewModels come and go with surfaces).
 */
@HiltViewModel
class VoiceRoomsViewModel @Inject constructor(
    val engine: VoiceRoomsEngine,
) : ViewModel() {

    val voiceState: StateFlow<VoiceRoomStateMachine.State> = engine.voiceState
    val stageState: StateFlow<StageRoomStateMachine.State> = engine.stageState
    val spaceState: StateFlow<SpaceBoardStateMachine.State> = engine.spaceState
    val surface: StateFlow<VoiceRoomsEngine.OpenSurface?> = engine.surface
    val captions: StateFlow<List<VoiceRoomsEngine.VoiceCaption>> = engine.captionList
    val captionsEnabled: StateFlow<Boolean> = engine.captionsEnabled
    val connected: StateFlow<Boolean> = engine.connected

    fun setIdentity(id: String?, name: String?, color: String?) = engine.setIdentity(id, name, color)

    // ── surface control ─────────────────────────────────────────

    fun openVoice(conversationId: String) = engine.openVoice(conversationId)
    fun openStage(conversationId: String) = engine.openStage(conversationId)
    fun openSpace(conversationId: String) = engine.openSpace(conversationId)
    fun closeSurface() = engine.closeSurface()

    // ── voice room ──────────────────────────────────────────────

    fun joinVoice(conversationId: String) = engine.joinVoice(conversationId)
    fun leaveVoice() = engine.leaveVoice()
    fun setMuted(muted: Boolean) = engine.setMuted(muted)
    fun setCaptions(enabled: Boolean) = engine.setCaptions(enabled)
    fun pttDown() = engine.pttDown()
    fun pttUp(heldMs: Long) = engine.pttUp(heldMs)
    fun togglePtt() = engine.togglePtt()

    // ── stage ───────────────────────────────────────────────────

    fun joinStage(conversationId: String) = engine.joinStage(conversationId)
    fun claimHost() = engine.claimHost()
    fun raiseHand() = engine.raiseHand()
    fun approveHand(targetUserId: String) = engine.approveHand(targetUserId)
    fun demote(targetUserId: String) = engine.demote(targetUserId)

    /** Two-tap end confirm — true when the second tap FIRED the end. */
    fun endStage(): Boolean = engine.endStage()
    fun isEndConfirmArmed(): Boolean = engine.isEndConfirmArmed()
    fun disarmEndConfirm() = engine.disarmEndConfirm()
    fun leaveStage(conversationId: String) = engine.leaveStage(conversationId)

    // ── space ───────────────────────────────────────────────────

    fun joinSpace(conversationId: String) = engine.joinSpace(conversationId)
    fun moveSpace(x: Double, y: Double) = engine.moveSpace(x, y)
    fun retrySpace() = engine.retrySpace()
    fun leaveSpace(conversationId: String) = engine.leaveSpace(conversationId)
}
