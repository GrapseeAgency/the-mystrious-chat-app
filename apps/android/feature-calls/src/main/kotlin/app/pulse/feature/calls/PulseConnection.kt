package app.pulse.feature.calls

import android.os.Build
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log
import androidx.annotation.RequiresApi
import app.pulse.domain.model.CallDirection
import app.pulse.domain.model.CallState

/**
 * R8 Task 3-c — one Telecom Connection mirroring one [CallEngine] session.
 * The callbacks drive the engine; the engine's snapshot (through
 * [TelecomCallController]) drives setActive/disconnected. No call logic lives
 * here — this class is a thin OS-facing adapter over the pure machine.
 */
@RequiresApi(Build.VERSION_CODES.O)
class PulseConnection(
    private val engine: CallEngine,
    private val controller: TelecomCallController,
) : Connection() {

    /** Wire metadata carried through the request extras (logging/display). */
    var conversationId: String = ""
    var callId: String = ""
    var audioMode: String = "voice"

    /** Stable mirror flags so lifecycle transitions never double-drive. */
    @Volatile private var localEnded = false

    override fun onAnswer(videoStateHint: Int) {
        Log.d(TAG, "telecom answer (callId=$callId)")
        runCatching {
            if (engine.snapshot.value.state == CallState.INCOMING_RINGING) engine.accept()
            setActive()
        }
    }

    override fun onReject() {
        Log.d(TAG, "telecom reject (callId=$callId)")
        runCatching {
            if (engine.snapshot.value.state == CallState.INCOMING_RINGING) engine.decline()
            setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
            destroy()
        }
    }

    override fun onDisconnect() {
        Log.d(TAG, "telecom disconnect (callId=$callId)")
        endLocally()
    }

    override fun onAbort() {
        Log.d(TAG, "telecom abort (callId=$callId)")
        endLocally()
    }

    /**
     * The OS asked for a mute flip (system call UI / headset buttons). The
     * engine's toggle is the single source of truth — sync once, honestly.
     */
    override fun onMuteStateChanged(muted: Boolean) {
        Log.d(TAG, "telecom mute $muted")
        runCatching {
            if (engine.micMuted.value != muted) engine.toggleMute()
        }
    }

    override fun onHold() = Unit // hold is not a Pulse feature — the OS view stays unheld
    override fun onUnhold() = Unit

    /** Engine reached CONNECTED — the controller calls this (idempotent). */
    fun setActiveIfSensible() {
        runCatching {
            // STATE_NEW (API 26) is the just-created state; the legacy
            // STATE_INITIALIZED name no longer exists in the API-35 stubs.
            if (state == STATE_RINGING || state == STATE_DIALING || state == STATE_NEW) {
                setActive()
            }
        }
    }

    private fun endLocally() {
        if (localEnded) return
        localEnded = true
        runCatching {
            val snapshot = engine.snapshot.value
            when (snapshot.state) {
                CallState.INCOMING_RINGING -> engine.decline()
                CallState.OUTGOING_RINGING -> engine.cancel()
                CallState.CONNECTING, CallState.CONNECTED -> engine.hangup()
                else -> Unit
            }
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
        }
        controller.detachConnection(this)
    }

    private companion object {
        const val TAG = "PulseConnection"
    }
}
