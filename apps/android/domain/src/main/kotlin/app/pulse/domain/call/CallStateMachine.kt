package app.pulse.domain.call

import app.pulse.domain.model.CallCancelReason
import app.pulse.domain.model.CallDirection
import app.pulse.domain.model.CallKind
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.CallOutcome
import app.pulse.domain.model.CallPeer
import app.pulse.domain.model.CallState
import app.pulse.domain.model.CallStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Injectable time source — pure-Kotlin tests drive the machine with a fake
 * clock, production uses the wall clock.
 */
fun interface CallClock {
    fun nowMs(): Long
}

/**
 * Immutable view of the call session — the single thing the UI renders.
 * [summary]/[error] are the ended-card and honest error-card texts
 * (web call-overlay parity); [durationSec] ticks while CONNECTED.
 */
data class CallSnapshot(
    val state: CallState = CallState.IDLE,
    val direction: CallDirection? = null,
    val callId: String? = null,
    val conversationId: String? = null,
    val kind: CallKind = CallKind.VOICE,
    val peer: CallPeer? = null,
    val summary: String? = null,
    val error: String? = null,
    val outcome: CallOutcome? = null,
    val durationSec: Long = 0,
    val connectedAtMs: Long = 0,
) {
    companion object {
        val IDLE = CallSnapshot()
    }

    /** Wire-facing convenience — true once the answer leg has begun. */
    val isAnswered: Boolean get() = state == CallState.CONNECTING || state == CallState.CONNECTED
}

/**
 * Wave-3 shared design (W3-PLAN) — the COMPLETE 1:1 call flow as a pure,
 * synchronous reducer. Every dispatch/poll returns the side effects the
 * owner (CallViewModel + CallEngine) must perform; the machine itself never
 * touches WebRTC, the socket or the network, so the full matrix is
 * unit-testable with a fake clock.
 *
 * States (pinned design): idle → outgoingRinging → connecting → connected →
 * ended; idle → incomingRinging → connecting → connected → ended.
 *
 * Timeouts (all defensive — the server stays authoritative):
 *  - ring 40s (the server arms 30s and sends call:cancel first in every
 *    real case; this only guards a lost cancel);
 *  - peer-connection 15s after the answer leg starts;
 *  - disconnected grace 10s while connected before declaring the call over;
 *  - stale-state 45s with NO signaling at all (ringing/connecting only — a
 *    healthy connected call is kept alive by its duration ticker polls).
 *
 * Idempotency: every remote event is guarded per callId AND current state —
 * duplicates and post-terminal events are dropped without effects.
 *
 * Terminal mapping (pinned): answered-then-ended=completed(durationSec),
 * reject=declined, cancel-while-ringing/self-cancel=missed. The CALLER is
 * the single log writer — WriteLog effects only ever fire for outgoing
 * calls (CallLogMapper enforces it again at the row level).
 */
class CallStateMachine(
    private val clock: CallClock = CallClock { System.currentTimeMillis() },
) {

    // ── wiring ──────────────────────────────────────────────────
    private val _snapshot = MutableStateFlow(CallSnapshot.IDLE)
    val snapshot: StateFlow<CallSnapshot> = _snapshot.asStateFlow()

    /** Binds the viewer identity (once, idempotent). Events before bind are ignored. */
    @Volatile
    private var meId: String? = null

    fun bind(viewerId: String) {
        if (meId == null) meId = viewerId
    }

    // ── session bookkeeping ─────────────────────────────────────
    private var callId: String? = null
    private var conversationId: String? = null
    private var peer: CallPeer? = null
    private var kind: CallKind = CallKind.VOICE
    private var direction: CallDirection? = null
    private var callerName: String = ""
    private var callerColor: String? = null
    private var callerAvatar: String? = null

    private var answered = false
    private var connectedAt = 0L
    private var offerSent = false
    private var answerSent = false
    private var logWritten = false
    private var mediaRequested = false
    private var remoteOfferSdp: String? = null

    // deadlines (epoch ms; 0 = not armed)
    private var ringDeadline = 0L
    private var connectDeadline = 0L
    private var disconnectedAt = 0L
    private var staleDeadline = 0L

    // ── events ──────────────────────────────────────────────────
    sealed interface CallEvent {
        /** Caller start — the RECORD_AUDIO permission is granted upstream. */
        data class StartOutgoing(
            val conversationId: String,
            val peer: CallPeer,
            val kind: CallKind,
            val callerName: String,
            val callerColor: String?,
            val callerAvatar: String?,
        ) : CallEvent

        /** call:offer arrived. */
        data class IncomingOffer(
            val callId: String,
            val conversationId: String,
            val from: String,
            val kind: CallKind,
            val sdp: String,
            val callerName: String?,
            val callerColor: String?,
            val callerAvatar: String?,
        ) : CallEvent

        /** Engine produced the local offer SDP. */
        data class OfferReady(val sdp: String) : CallEvent

        /** Callee accepted the ring (UI button). */
        object Accept : CallEvent

        /** Mic acquired (or already granted) — the answer leg may proceed. */
        object MediaReady : CallEvent

        /** Mic denied / device failure — honest terminal, no fake UI. */
        data class MediaFailed(val reason: String) : CallEvent

        /** Engine produced the local answer SDP. */
        data class AnswerReady(val sdp: String) : CallEvent

        /** Callee pressed decline. */
        object Decline : CallEvent

        /** Caller canceled while ringing. */
        object Cancel : CallEvent

        /** Either side ended a call (maps decline/cancel while ringing). */
        object Hangup : CallEvent

        /** call:answer from the peer. */
        data class AnswerReceived(val callId: String, val sdp: String) : CallEvent

        /** call:ice from the peer. */
        data class IceReceived(
            val callId: String,
            val candidate: String,
            val sdpMid: String?,
            val sdpMLineIndex: Int?,
        ) : CallEvent

        /** Engine produced a local ICE candidate (trickle). */
        data class IceProduced(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?) : CallEvent

        /** WebRTC PeerConnectionState CONNECTED. */
        object PeerConnected : CallEvent

        /** WebRTC PeerConnectionState DISCONNECTED — the grace window opens. */
        object PeerDisconnected : CallEvent

        /** WebRTC PeerConnectionState FAILED/CLOSED — the media leg is dead. */
        object PeerFailed : CallEvent

        /** call:reject from the callee. */
        data class RemoteReject(val callId: String) : CallEvent

        /** call:cancel (peer abort, server ring timeout/offline/busy). */
        data class RemoteCancel(val callId: String, val reason: CallCancelReason) : CallEvent

        /** call:hangup from the peer on an active call. */
        data class RemoteHangup(val callId: String, val durationSec: Long?) : CallEvent

        /** Ended card dismissed → back to idle. */
        object Dismissed : CallEvent
    }

    // ── effects ─────────────────────────────────────────────────
    sealed interface CallEffect {
        data class AcquireMedia(val kind: CallKind) : CallEffect
        data class CreateOffer(val kind: CallKind) : CallEffect
        data class ApplyRemoteOffer(val sdp: String) : CallEffect
        data class CreateAnswer(val remoteSdp: String) : CallEffect
        data class ApplyRemoteAnswer(val sdp: String) : CallEffect
        data class ApplyRemoteIce(
            val callId: String,
            val candidate: String,
            val sdpMid: String?,
            val sdpMLineIndex: Int?,
        ) : CallEffect

        data class SendOffer(
            val callId: String,
            val conversationId: String,
            val from: String,
            val to: String,
            val kind: CallKind,
            val sdp: String,
            val callerName: String,
            val callerColor: String?,
            val callerAvatar: String?,
        ) : CallEffect

        data class SendAnswer(
            val callId: String,
            val conversationId: String,
            val from: String,
            val to: String,
            val kind: CallKind,
            val sdp: String,
        ) : CallEffect

        data class SendIce(
            val callId: String,
            val conversationId: String,
            val from: String,
            val to: String,
            val kind: CallKind,
            val candidate: String,
            val sdpMid: String?,
            val sdpMLineIndex: Int?,
        ) : CallEffect

        data class SendReject(
            val callId: String,
            val conversationId: String,
            val from: String,
            val to: String,
            val kind: CallKind,
        ) : CallEffect

        data class SendCancel(
            val callId: String,
            val conversationId: String,
            val from: String,
            val to: String,
            val kind: CallKind,
        ) : CallEffect

        data class SendHangup(
            val callId: String,
            val conversationId: String,
            val from: String,
            val to: String,
            val kind: CallKind,
            val durationSec: Long,
        ) : CallEffect

        /** Caller-only terminal row write (the single-writer rule). */
        data class WriteLog(val entry: CallLogEntry) : CallEffect
        object ReleaseMedia : CallEffect
    }

    // ── dispatch ────────────────────────────────────────────────

    /** Reduces one event; returns the effects to perform, in order. */
    fun dispatch(event: CallEvent): List<CallEffect> {
        if (meId == null) return emptyList()
        val effects = reduce(event)
        // Any dispatch is a liveness proof — but only ringing/connecting can
        // go stale (a healthy connected call must not die from quietness).
        if (_snapshot.value.state in RINGING_OR_CONNECTING) staleDeadline = clock.nowMs() + STALE_TIMEOUT_MS
        push()
        return effects
    }

    /**
     * Drives every deadline. The owner polls ~1–2×/s (the same ticker ticks
     * the connected duration); returns any effects the deadlines fired.
     */
    fun poll(): List<CallEffect> {
        if (meId == null) return emptyList()
        val now = clock.nowMs()
        val effects = mutableListOf<CallEffect>()

        // Live duration while connected — the UI ticker reads the snapshot.
        if (_snapshot.value.state == CallState.CONNECTED && connectedAt > 0L) push()

        when (_snapshot.value.state) {
            CallState.OUTGOING_RINGING, CallState.INCOMING_RINGING ->
                if (ringDeadline in 1..now) {
                    effects += endUnanswered(
                        summary = if (direction == CallDirection.OUTGOING) "No answer" else "Missed call",
                        logStatus = if (direction == CallDirection.OUTGOING) CallStatus.MISSED else null,
                    )
                }

            CallState.CONNECTING ->
                if (connectDeadline in 1..now) {
                    // Answered but the media leg never came up — pinned
                    // mapping: answered-then-ended = completed(durationSec).
                    // The hangup frees the peer instead of leaving it hanging.
                    effects += endAnswered(summary = "Call failed")
                }

            CallState.CONNECTED ->
                if (disconnectedAt in 1..now && now >= disconnectedAt + DISCONNECT_GRACE_MS) {
                    effects += endAnswered(summary = "Call ended")
                }

            else -> Unit
        }

        if (_snapshot.value.state in RINGING_OR_CONNECTING && staleDeadline in 1..now) {
            effects += if (answered) {
                endAnswered(summary = "Call ended")
            } else {
                endUnanswered(
                    summary = if (direction == CallDirection.OUTGOING) "No answer" else "Missed call",
                    logStatus = if (direction == CallDirection.OUTGOING) CallStatus.MISSED else null,
                )
            }
        }
        if (effects.isNotEmpty()) push()
        return effects
    }

    // ── reducer ─────────────────────────────────────────────────
    private fun reduce(event: CallEvent): List<CallEffect> {
        val me = meId ?: return emptyList()
        val state = _snapshot.value.state
        val now = clock.nowMs()

        return when (event) {
            is CallEvent.StartOutgoing -> {
                if (state != CallState.IDLE) return emptyList()
                if (event.peer.id.isBlank() || event.peer.id == me) return emptyList()
                if (event.conversationId.isBlank()) return emptyList()
                callId = newCallId(now)
                conversationId = event.conversationId
                peer = event.peer
                kind = event.kind
                direction = CallDirection.OUTGOING
                callerName = event.callerName
                callerColor = event.callerColor
                callerAvatar = event.callerAvatar
                answered = false
                connectedAt = 0L
                offerSent = false
                answerSent = false
                logWritten = false
                mediaRequested = false
                remoteOfferSdp = null
                ringDeadline = now + RING_TIMEOUT_MS
                connectDeadline = 0L
                disconnectedAt = 0L
                staleDeadline = now + STALE_TIMEOUT_MS
                _snapshot.value = CallSnapshot(
                    state = CallState.OUTGOING_RINGING,
                    direction = direction,
                    callId = callId,
                    conversationId = conversationId,
                    kind = kind,
                    peer = peer,
                )
                listOf(CallEffect.AcquireMedia(event.kind), CallEffect.CreateOffer(event.kind))
            }

            is CallEvent.IncomingOffer -> {
                if (event.from.isBlank() || event.from == me) return emptyList()
                if (event.callId.isBlank() || event.sdp.isBlank()) return emptyList()
                if (state != CallState.IDLE) {
                    // Busy — reject immediately (the caller logs 'declined').
                    return listOf(
                        CallEffect.SendReject(
                            callId = event.callId,
                            conversationId = event.conversationId,
                            from = me,
                            to = event.from,
                            kind = event.kind,
                        ),
                    )
                }
                callId = event.callId
                conversationId = event.conversationId
                peer = CallPeer(
                    id = event.from,
                    name = event.callerName?.takeIf { it.isNotBlank() } ?: "Someone",
                    color = event.callerColor?.takeIf { it.isNotBlank() } ?: "emerald",
                    avatar = event.callerAvatar,
                )
                kind = event.kind
                direction = CallDirection.INCOMING
                answered = false
                connectedAt = 0L
                offerSent = false
                answerSent = false
                logWritten = false
                mediaRequested = false
                remoteOfferSdp = event.sdp
                ringDeadline = now + RING_TIMEOUT_MS
                connectDeadline = 0L
                disconnectedAt = 0L
                staleDeadline = now + STALE_TIMEOUT_MS
                _snapshot.value = CallSnapshot(
                    state = CallState.INCOMING_RINGING,
                    direction = direction,
                    callId = callId,
                    conversationId = conversationId,
                    kind = kind,
                    peer = peer,
                )
                emptyList() // the ring UI is snapshot-driven; the mic waits for Accept
            }

            is CallEvent.OfferReady -> {
                if (state != CallState.OUTGOING_RINGING || offerSent || event.sdp.isBlank()) return emptyList()
                offerSent = true
                val p = checkNotNull(peer)
                listOf(
                    CallEffect.SendOffer(
                        callId = checkNotNull(callId),
                        conversationId = checkNotNull(conversationId),
                        from = me,
                        to = p.id,
                        kind = kind,
                        sdp = event.sdp,
                        callerName = callerName,
                        callerColor = callerColor,
                        callerAvatar = callerAvatar,
                    ),
                )
            }

            CallEvent.Accept -> {
                if (state != CallState.INCOMING_RINGING || mediaRequested) return emptyList()
                mediaRequested = true
                listOf(CallEffect.AcquireMedia(kind))
            }

            CallEvent.MediaReady -> when {
                state == CallState.INCOMING_RINGING && mediaRequested -> {
                    val offer = checkNotNull(remoteOfferSdp)
                    listOf(CallEffect.ApplyRemoteOffer(offer), CallEffect.CreateAnswer(offer))
                }
                // Outgoing: the mic came through — CreateOffer already ran at start.
                else -> emptyList()
            }

            is CallEvent.MediaFailed -> when {
                state == CallState.OUTGOING_RINGING -> {
                    // Honest error card; the attempt still counts as an
                    // unanswered outgoing call (web dismissError parity).
                    val cancel = callId?.takeIf { offerSent }?.let { id ->
                        listOf(
                            CallEffect.SendCancel(
                                callId = id,
                                conversationId = checkNotNull(conversationId),
                                from = me,
                                to = checkNotNull(peer).id,
                                kind = kind,
                            ),
                        )
                    }.orEmpty()
                    cancel + CallEffect.ReleaseMedia + endUnanswered(
                        summary = "Call canceled",
                        logStatus = CallStatus.MISSED,
                        error = MIC_DENIED_MESSAGE,
                    )
                }
                state == CallState.INCOMING_RINGING && mediaRequested -> {
                    // Callee could not join → reject politely; the caller logs.
                    listOf(
                        CallEffect.SendReject(
                            callId = checkNotNull(callId),
                            conversationId = checkNotNull(conversationId),
                            from = me,
                            to = checkNotNull(peer).id,
                            kind = kind,
                        ),
                        CallEffect.ReleaseMedia,
                    ) + terminate(
                        outcome = null,
                        summary = null,
                        error = MIC_DENIED_MESSAGE,
                    )
                }
                else -> emptyList()
            }

            is CallEvent.AnswerReady -> {
                if (state != CallState.INCOMING_RINGING || answerSent || event.sdp.isBlank()) return emptyList()
                answerSent = true
                answered = true
                connectDeadline = now + CONNECT_TIMEOUT_MS
                _snapshot.value = _snapshot.value.copy(state = CallState.CONNECTING)
                listOf(
                    CallEffect.SendAnswer(
                        callId = checkNotNull(callId),
                        conversationId = checkNotNull(conversationId),
                        from = me,
                        to = checkNotNull(peer).id,
                        kind = kind,
                        sdp = event.sdp,
                    ),
                )
            }

            is CallEvent.AnswerReceived -> {
                if (!sameCall(event.callId)) return emptyList()
                if (state != CallState.OUTGOING_RINGING || answered || event.sdp.isBlank()) return emptyList()
                answered = true
                connectDeadline = now + CONNECT_TIMEOUT_MS
                _snapshot.value = _snapshot.value.copy(state = CallState.CONNECTING)
                listOf(CallEffect.ApplyRemoteAnswer(event.sdp))
            }

            CallEvent.Decline -> {
                if (state != CallState.INCOMING_RINGING) return emptyList()
                listOf(
                    CallEffect.SendReject(
                        callId = checkNotNull(callId),
                        conversationId = checkNotNull(conversationId),
                        from = me,
                        to = checkNotNull(peer).id,
                        kind = kind,
                    ),
                    CallEffect.ReleaseMedia,
                ) + terminate(outcome = CallOutcome.DECLINED, summary = "Declined")
            }

            CallEvent.Cancel -> {
                if (state != CallState.OUTGOING_RINGING) return emptyList()
                val effects = mutableListOf<CallEffect>()
                callId?.let {
                    effects += CallEffect.SendCancel(
                        callId = it,
                        conversationId = checkNotNull(conversationId),
                        from = me,
                        to = checkNotNull(peer).id,
                        kind = kind,
                    )
                }
                effects += CallEffect.ReleaseMedia
                effects += endUnanswered(summary = "Call canceled", logStatus = CallStatus.MISSED)
                effects
            }

            CallEvent.Hangup -> when (state) {
                CallState.CONNECTED, CallState.CONNECTING -> {
                    val elapsed = elapsedSec(now)
                    val effects = mutableListOf<CallEffect>()
                    callId?.let {
                        effects += CallEffect.SendHangup(
                            callId = it,
                            conversationId = checkNotNull(conversationId),
                            from = me,
                            to = checkNotNull(peer).id,
                            kind = kind,
                            durationSec = elapsed,
                        )
                    }
                    effects += CallEffect.ReleaseMedia
                    effects += endAnswered(elapsedSec = elapsed, summary = "Call ended")
                    effects
                }
                CallState.INCOMING_RINGING -> reduce(CallEvent.Decline)
                CallState.OUTGOING_RINGING -> reduce(CallEvent.Cancel)
                else -> emptyList()
            }

            is CallEvent.IceReceived -> {
                if (!sameCall(event.callId)) return emptyList()
                if (state !in SIGNALING_STATES) return emptyList()
                if (event.candidate.isBlank()) return emptyList()
                listOf(
                    CallEffect.ApplyRemoteIce(
                        callId = checkNotNull(callId),
                        candidate = event.candidate,
                        sdpMid = event.sdpMid,
                        sdpMLineIndex = event.sdpMLineIndex,
                    ),
                )
            }

            is CallEvent.IceProduced -> {
                if (state !in SIGNALING_STATES) return emptyList()
                val id = callId ?: return emptyList()
                val p = peer ?: return emptyList()
                if (event.candidate.isBlank()) return emptyList()
                listOf(
                    CallEffect.SendIce(
                        callId = id,
                        conversationId = checkNotNull(conversationId),
                        from = me,
                        to = p.id,
                        kind = kind,
                        candidate = event.candidate,
                        sdpMid = event.sdpMid,
                        sdpMLineIndex = event.sdpMLineIndex,
                    ),
                )
            }

            CallEvent.PeerConnected -> {
                if (state != CallState.CONNECTING && state != CallState.CONNECTED) return emptyList()
                if (connectedAt == 0L) connectedAt = now
                connectDeadline = 0L
                disconnectedAt = 0L
                _snapshot.value = _snapshot.value.copy(state = CallState.CONNECTED, connectedAtMs = connectedAt)
                emptyList()
            }

            CallEvent.PeerDisconnected -> {
                if (state != CallState.CONNECTED || disconnectedAt != 0L) return emptyList()
                disconnectedAt = now
                emptyList()
            }

            CallEvent.PeerFailed -> when (state) {
                CallState.CONNECTING -> endAnswered(summary = "Call failed")
                CallState.CONNECTED -> {
                    disconnectedAt = clock.nowMs()
                    endAnswered(summary = "Call ended")
                }
                else -> emptyList()
            }

            is CallEvent.RemoteReject -> {
                if (!sameCall(event.callId)) return emptyList()
                if (state != CallState.OUTGOING_RINGING) return emptyList()
                listOf(CallEffect.ReleaseMedia) + endUnanswered(
                    summary = "Declined",
                    logStatus = CallStatus.DECLINED,
                    outcomeOverride = CallOutcome.DECLINED,
                )
            }

            is CallEvent.RemoteCancel -> {
                if (!sameCall(event.callId)) return emptyList()
                when (state) {
                    CallState.OUTGOING_RINGING -> {
                        listOf(CallEffect.ReleaseMedia) + endUnanswered(
                            summary = CANCEL_SUMMARY.getValue(event.reason),
                            logStatus = CallStatus.MISSED,
                        )
                    }
                    CallState.INCOMING_RINGING -> {
                        // Callee NEVER writes the row (single-writer rule).
                        listOf(CallEffect.ReleaseMedia) + terminate(
                            outcome = CallOutcome.MISSED,
                            summary = "Missed call",
                        )
                    }
                    else -> emptyList()
                }
            }

            is CallEvent.RemoteHangup -> {
                if (!sameCall(event.callId)) return emptyList()
                if (state != CallState.CONNECTED && state != CallState.CONNECTING) return emptyList()
                listOf(CallEffect.ReleaseMedia) + endAnswered(elapsedSec = elapsedSec(now), summary = "Call ended")
            }

            CallEvent.Dismissed -> {
                if (state != CallState.ENDED) return emptyList()
                resetSession()
                _snapshot.value = CallSnapshot.IDLE
                emptyList()
            }
        }
    }

    // ── terminal helpers ────────────────────────────────────────

    /** Unanswered ending: missed (or declined via logStatus); caller writes the row. */
    private fun endUnanswered(
        summary: String,
        logStatus: CallStatus? = CallStatus.MISSED,
        outcomeOverride: CallOutcome? = null,
        error: String? = null,
    ): List<CallEffect> {
        val effects = mutableListOf<CallEffect>()
        if (logStatus != null) effects += writeLogEffect(logStatus, 0L)
        effects += terminate(outcome = outcomeOverride ?: CallOutcome.MISSED, summary = summary, error = error)
        return effects
    }

    /** Answered ending: completed(durationSec); the caller writes the row. */
    private fun endAnswered(elapsedSec: Long = elapsedSec(clock.nowMs()), summary: String): List<CallEffect> =
        writeLogEffect(CallStatus.COMPLETED, elapsedSec) +
            terminate(outcome = CallOutcome.COMPLETED, summary = summary)

    /** The single-writer guard lives HERE: outgoing calls only, exactly once. */
    private fun writeLogEffect(status: CallStatus, durationSec: Long): List<CallEffect> {
        if (logWritten) return emptyList()
        if (direction != CallDirection.OUTGOING) return emptyList()
        val p = peer ?: return emptyList()
        val conv = conversationId ?: return emptyList()
        val me = meId ?: return emptyList()
        val row = CallLogMapper.terminalRow(
            viewerId = me,
            conversationId = conv,
            peer = p,
            kind = kind,
            status = status,
            durationSec = durationSec,
        ) ?: return emptyList()
        logWritten = true
        return listOf(CallEffect.WriteLog(row))
    }

    private fun terminate(outcome: CallOutcome?, summary: String?, error: String? = null): List<CallEffect> {
        ringDeadline = 0L
        connectDeadline = 0L
        staleDeadline = 0L
        _snapshot.value = _snapshot.value.copy(
            state = CallState.ENDED,
            outcome = outcome,
            summary = summary,
            error = error,
            durationSec = elapsedSec(clock.nowMs()),
        )
        return emptyList()
    }

    private fun resetSession() {
        callId = null
        conversationId = null
        peer = null
        direction = null
        answered = false
        connectedAt = 0L
        offerSent = false
        answerSent = false
        logWritten = false
        mediaRequested = false
        remoteOfferSdp = null
        ringDeadline = 0L
        connectDeadline = 0L
        disconnectedAt = 0L
        staleDeadline = 0L
    }

    // ── helpers ─────────────────────────────────────────────────

    private fun sameCall(remote: String): Boolean = remote.isNotBlank() && remote == callId

    private fun elapsedSec(now: Long): Long =
        if (connectedAt <= 0L) 0L else ((now - connectedAt) / 1000L).coerceAtLeast(0L)

    private fun push() {
        val s = _snapshot.value
        _snapshot.value = s.copy(durationSec = elapsedSec(clock.nowMs()))
    }

    private fun newCallId(now: Long): String =
        "call-" + now.toString(36) + "-" + (0..999999).random().toString(36)

    companion object {
        /** Client-defensive ring timeout — the server arms 30s (authoritative). */
        const val RING_TIMEOUT_MS = 40_000L

        /** PeerConnection must reach CONNECTED within 15s of the answer leg. */
        const val CONNECT_TIMEOUT_MS = 15_000L

        /** ICE disconnected → grace window before the call is declared over. */
        const val DISCONNECT_GRACE_MS = 10_000L

        /** Ringing/connecting with NO signaling at all for 45s → cleanup. */
        const val STALE_TIMEOUT_MS = 45_000L

        /** Honest mic-denied copy (web error-card parity). */
        const val MIC_DENIED_MESSAGE =
            "Microphone access is needed for calls. Check the app permissions and try again."

        /** Ended-card text per call:cancel reason (web CANCEL_SUMMARY parity). */
        val CANCEL_SUMMARY: Map<CallCancelReason, String> = mapOf(
            CallCancelReason.TIMEOUT to "No answer",
            CallCancelReason.CANCEL to "Call canceled",
            CallCancelReason.BUSY to "Peer is busy",
            CallCancelReason.OFFLINE to "Peer is offline",
        )

        private val RINGING_OR_CONNECTING = setOf(
            CallState.OUTGOING_RINGING,
            CallState.INCOMING_RINGING,
            CallState.CONNECTING,
        )

        /** States where call:ice is meaningful. */
        private val SIGNALING_STATES = setOf(
            CallState.OUTGOING_RINGING,
            CallState.INCOMING_RINGING,
            CallState.CONNECTING,
            CallState.CONNECTED,
        )
    }
}
