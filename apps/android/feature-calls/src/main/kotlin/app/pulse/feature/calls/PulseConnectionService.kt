package app.pulse.feature.calls

import android.net.Uri
import android.os.Build
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import app.pulse.domain.model.CallState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * R8 Task 3-c - the self-managed [ConnectionService]. The system instantiates
 * this class (manifest-registered, BIND_TELECOM_CONNECTION_SERVICE) for every
 * Telecom call the controller presents; Hilt injects the [CallEngine] the
 * connections drive. Created connections are state MIRRORS of the engine:
 *   onAnswer    → engine.accept()
 *   onReject    → engine.decline()
 *   onDisconnect/onAbort → decline while ringing / hangup once live
 *   onMuteStateChanged → engine mic toggle sync
 * The engine snapshot flows back through [TelecomCallController] (setActive /
 * setDisconnected + destroy) so the OS call UI always shows real state.
 */
@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.O)
class PulseConnectionService : ConnectionService() {

    @Inject lateinit var engine: CallEngine

    @Inject lateinit var controller: TelecomCallController

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: android.telecom.PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val extras = request.extras ?: Bundle()
        val peerName = extras.getString(TelecomCallController.EXTRA_PULSE_PEER_NAME) ?: "Pulse call"
        val peerId = extras.getString(TelecomCallController.EXTRA_PULSE_PEER_ID).orEmpty()
        val conversationId = extras.getString(TelecomCallController.EXTRA_PULSE_CONVERSATION_ID).orEmpty()
        val callId = extras.getString(TelecomCallController.EXTRA_PULSE_CALL_ID).orEmpty()
        val kind = extras.getString(TelecomCallController.EXTRA_PULSE_KIND) ?: "voice"

        // The ring may have already been answered/declined in-app (or expired)
        // by the time the system builds the connection - never fake a ring.
        if (engine.snapshot.value.state != CallState.INCOMING_RINGING) {
            Log.w(TAG, "incoming connection for a non-ringing engine - failing honestly")
            return failedConnection(request)
        }
        val connection = PulseConnection(engine, controller).apply {
            this.conversationId = conversationId
            this.callId = callId
            audioMode = kind
            setAddress(TelecomRegistrar.handleUri(peerId), TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(peerName, TelecomManager.PRESENTATION_ALLOWED)
            setRinging()
        }
        controller.attachConnection(connection)
        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: android.telecom.PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val extras = request.extras ?: Bundle()
        val peerName = extras.getString(TelecomCallController.EXTRA_PULSE_PEER_NAME) ?: "Pulse call"
        val peerId = extras.getString(TelecomCallController.EXTRA_PULSE_PEER_ID).orEmpty()
        val conversationId = extras.getString(TelecomCallController.EXTRA_PULSE_CONVERSATION_ID).orEmpty()
        val callId = extras.getString(TelecomCallController.EXTRA_PULSE_CALL_ID).orEmpty()
        val kind = extras.getString(TelecomCallController.EXTRA_PULSE_KIND) ?: "voice"
        // The real dial already happened in the engine (in-app start path);
        // this connection only mirrors it into the OS surface.
        val connection = PulseConnection(engine, controller).apply {
            this.conversationId = conversationId
            this.callId = callId
            audioMode = kind
            setAddress(TelecomRegistrar.handleUri(peerId), TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(peerName, TelecomManager.PRESENTATION_ALLOWED)
            setDialing()
        }
        controller.attachConnection(connection)
        return connection
    }

    private fun failedConnection(request: ConnectionRequest): Connection {
        val connection = PulseConnection(engine, controller)
        runCatching {
            connection.setAddress(request.address ?: Uri.EMPTY, TelecomManager.PRESENTATION_ALLOWED)
            connection.setDisconnected(DisconnectCause(DisconnectCause.MISSED))
            connection.destroy()
        }
        return connection
    }

    private companion object {
        const val TAG = "PulseConnectionService"
    }
}
