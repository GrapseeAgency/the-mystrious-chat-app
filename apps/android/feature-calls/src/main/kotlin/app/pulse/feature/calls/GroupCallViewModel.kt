package app.pulse.feature.calls

import androidx.lifecycle.ViewModel
import app.pulse.domain.model.CallKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * R8 Task 3-c — thin activity-scoped bridge between the group call UI and
 * the [GroupCallEngine] singleton (the engine owns ALL group-call state;
 * ViewModels come and go with screens, the call must not). Mirrors
 * [CallViewModel] for the 1:1 engine.
 */
@HiltViewModel
class GroupCallViewModel @Inject constructor(
    val engine: GroupCallEngine,
) : ViewModel() {

    val snapshot: StateFlow<GroupCallEngine.Snapshot> = engine.snapshot
    val localVideoTrack: StateFlow<org.webrtc.VideoTrack?> = engine.localVideoTrack
    val remoteVideoTracks: StateFlow<Map<String, org.webrtc.VideoTrack>> = engine.remoteVideoTracks
    val notices: SharedFlow<String> = engine.notices

    /** The shell boots the engine's event collectors once per process. */
    fun start() = engine.start()

    /**
     * The shell tells the engine which conversation is OPEN — the probe +
     * outsider-banner gate (web openConversationId parity). Called on room
     * enter, cleared on room leave.
     */
    fun setActiveConversation(conversationId: String?, title: String? = null) =
        engine.setActiveConversation(conversationId, title)

    fun setIdentity(id: String?, name: String?, color: String?) = engine.setIdentity(id, name, color)

    /** Start a NEW group call in the open conversation (rings everyone). */
    fun startCall(kind: CallKind, title: String) = engine.startCall(kind, title)

    /** Accept an incoming ring (ring already sent — never re-rings). */
    fun joinCall() = engine.joinCall()

    /** Silently join the ONGOING call in the open room — never re-rings. */
    fun joinOngoing() = engine.joinOngoing()

    fun dismissRing() = engine.dismissRing()

    fun ignoreOngoing() = engine.ignoreOngoing()

    fun leaveCall() = engine.leaveCall()

    fun dismissError() = engine.dismissError()

    fun dismissSummary() = engine.dismissSummary()

    fun toggleMic(): Boolean = engine.toggleMic()

    fun toggleCamera(): Boolean = engine.toggleCamera()

    /** Front ⇄ back flip — honest no-op without an attached camera. */
    fun switchCamera(): Boolean = engine.switchCamera()
}
