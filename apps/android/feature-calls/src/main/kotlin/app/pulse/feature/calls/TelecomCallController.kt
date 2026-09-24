package app.pulse.feature.calls

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.util.Log
import app.pulse.domain.call.CallSnapshot
import app.pulse.domain.model.CallDirection
import app.pulse.domain.model.CallState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * R8 Task 3-c — the bridge between the 1:1 [CallEngine] state machine and the
 * OS Telecom layer. The ENGINE stays the single source of truth; Telecom only
 * MIRRORS it into the system call UIs:
 *
 *   engine INCOMING_RINGING  → TelecomManager.addNewIncomingCall (system ring)
 *   engine OUTGOING_RINGING  → TelecomManager.placeCall            (system log/UI)
 *   engine CONNECTED         → Connection.setActive()
 *   engine ENDED/IDLE        → Connection.setDisconnected + destroy
 *
 * Connection callbacks (answer/reject/hangup/mute) drive the engine back —
 * see [PulseConnection]. When Telecom refuses (pre-O, unregistered account,
 * SecurityException from an OEM policy, missing role) the controller falls
 * back to the full-screen-intent ring notification ([CallRingNotifier]) and
 * the existing in-app ring UI — never a faked system call.
 */
@Singleton
class TelecomCallController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: CallEngine,
) {
    companion object {
        /** EXTRA keys our Connection reads back out of the request bundle. */
        const val EXTRA_PULSE_CONVERSATION_ID = "app.pulse.telecom.CONVERSATION_ID"
        const val EXTRA_PULSE_PEER_ID = "app.pulse.telecom.PEER_ID"
        const val EXTRA_PULSE_PEER_NAME = "app.pulse.telecom.PEER_NAME"
        const val EXTRA_PULSE_KIND = "app.pulse.telecom.KIND"
        const val EXTRA_PULSE_CALL_ID = "app.pulse.telecom.CALL_ID"

        /** Fallback ring notification id base — clear of the FCM call ids (700) and the foreground service. */
        private const val RING_NOTIFICATION_BASE = 800
        private const val TAG = "PulseTelecom"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    /** The live Connection the service built for the mirrored engine call. */
    @Volatile var activeConnection: PulseConnection? = null

    /** The posted fallback ring notification id (null = none posted) — cancelled on any exit. */
    @Volatile private var fallbackRingNotificationId: Int? = null

    @Volatile private var lastState: CallState = CallState.IDLE

    fun start() {
        if (started) return
        started = true
        scope.launch {
            engine.snapshot.collect { onSnapshot(it) }
        }
    }

    /** The service registers the connection it created for this mirror. */
    fun attachConnection(connection: PulseConnection) {
        activeConnection = connection
    }

    /** The service unregisters it on destroy (idempotent). */
    fun detachConnection(connection: PulseConnection) {
        if (activeConnection === connection) activeConnection = null
    }

    private fun onSnapshot(snapshot: CallSnapshot) {
        val state = snapshot.state
        if (state == lastState) return
        val previous = lastState
        lastState = state
        Log.d(TAG, "telecom mirror $previous -> $state")
        // Any exit from the ringing state clears the fallback ring notification
        // (answered in-app, declined, expired, hung up — all of them).
        if (previous == CallState.INCOMING_RINGING && state != CallState.INCOMING_RINGING) {
            cancelFallbackRing()
        }
        when (state) {
            CallState.INCOMING_RINGING -> presentIncoming(snapshot)
            CallState.OUTGOING_RINGING -> presentOutgoing(snapshot)
            CallState.CONNECTED, CallState.CONNECTING -> activeConnection?.setActiveIfSensible()
            CallState.ENDED, CallState.IDLE -> endMirror(previous)
            else -> Unit
        }
    }

    /** The honest Telecom-unavailable fallback: a full-screen-intent CALL notification. */
    private fun notifyFallback(snapshot: CallSnapshot, peer: app.pulse.domain.model.CallPeer) {
        val conversationId = snapshot.conversationId
        val notificationId = RING_NOTIFICATION_BASE +
            ((conversationId ?: peer.id).hashCode().and(0x3FFFFFFF))
        val shown = CallRingNotifier.showIncoming(
            context = context,
            title = peer.name.ifBlank { "Pulse call" },
            body = "Incoming ${if (snapshot.kind.wire == "video") "video" else "voice"} call",
            conversationId = conversationId,
            notificationId = notificationId,
        )
        if (shown) {
            fallbackRingNotificationId = notificationId
        } else {
            Log.i(TAG, "ring notification not surfaced (permission) — in-app ring only")
        }
    }

    private fun cancelFallbackRing() {
        fallbackRingNotificationId?.let { id ->
            CallRingNotifier.cancel(context, id)
            fallbackRingNotificationId = null
        }
    }

    /** Incoming ring → system ring (addNewIncomingCall), fallback → notifier. */
    private fun presentIncoming(snapshot: CallSnapshot) {
        val peer = snapshot.peer ?: return
        if (!TelecomRegistrar.isSupported()) {
            Log.i(TAG, "telecom unsupported (pre-O) — full-screen ring notification fallback")
            notifyFallback(snapshot, peer)
            return
        }
        val handle = TelecomRegistrar.phoneAccountHandle(context)
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (handle == null || telecom == null) {
            Log.i(TAG, "telecom handle/service unavailable — full-screen ring notification fallback")
            notifyFallback(snapshot, peer)
            return
        }
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            putString(EXTRA_PULSE_CONVERSATION_ID, snapshot.conversationId)
            putString(EXTRA_PULSE_PEER_ID, peer.id)
            putString(EXTRA_PULSE_PEER_NAME, peer.name)
            putString(EXTRA_PULSE_KIND, snapshot.kind.wire)
            putString(EXTRA_PULSE_CALL_ID, snapshot.callId)
        }
        try {
            telecom.addNewIncomingCall(handle, extras)
            Log.d(TAG, "telecom incoming presented for ${peer.id}")
        } catch (e: Throwable) {
            Log.w(TAG, "telecom addNewIncomingCall refused — notification fallback", e)
            notifyFallback(snapshot, peer)
        }
    }

    /**
     * Outgoing call → placeCall so the OS logs + surfaces the dial. The
     * ConnectionService builds the mirror Connection but does NOT re-dial:
     * the in-app engine already owns the real WebRTC leg.
     */
    private fun presentOutgoing(snapshot: CallSnapshot) {
        if (!TelecomRegistrar.isSupported()) return
        val peer = snapshot.peer ?: return
        val handle = TelecomRegistrar.phoneAccountHandle(context) ?: return
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            putString(EXTRA_PULSE_CONVERSATION_ID, snapshot.conversationId)
            putString(EXTRA_PULSE_PEER_ID, peer.id)
            putString(EXTRA_PULSE_PEER_NAME, peer.name)
            putString(EXTRA_PULSE_KIND, snapshot.kind.wire)
            putString(EXTRA_PULSE_CALL_ID, snapshot.callId)
        }
        try {
            telecom.placeCall(TelecomRegistrar.handleUri(peer.id), extras)
            Log.d(TAG, "telecom outgoing presented for ${peer.id}")
        } catch (e: Throwable) {
            Log.w(TAG, "telecom placeCall refused — in-app only", e)
        }
    }

    private fun endMirror(previous: CallState) {
        val connection = activeConnection ?: return
        if (previous == CallState.IDLE || previous == CallState.ENDED) return
        val cause = if (previous == CallState.CONNECTED || previous == CallState.CONNECTING) {
            DisconnectCause(DisconnectCause.LOCAL)
        } else {
            DisconnectCause(DisconnectCause.REMOTE)
        }
        runCatching {
            connection.setDisconnected(cause)
            connection.destroy()
        }
        activeConnection = null
    }
}
