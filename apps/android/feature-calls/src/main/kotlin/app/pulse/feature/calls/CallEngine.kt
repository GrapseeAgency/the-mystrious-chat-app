package app.pulse.feature.calls

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.pulse.domain.call.CallClock
import app.pulse.domain.call.CallEvent
import app.pulse.domain.call.CallEffect
import app.pulse.domain.call.CallLogMapper
import app.pulse.domain.call.CallSnapshot
import app.pulse.domain.call.CallStateMachine
import app.pulse.domain.model.CallCancelReason
import app.pulse.domain.model.CallKind
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.CallPeer
import app.pulse.domain.model.CallSignalOut
import app.pulse.domain.model.Conversation
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.IceServer
import org.webrtc.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wave 3 — the native call engine: executes the PURE [CallStateMachine]'s
 * effects against the REAL platform (WebRTC peer connection, Android audio,
 * socket signaling, single-writer call log) and feeds real-world events back
 * in. One engine per process (Hilt @Singleton) — every screen overlays the
 * same [snapshot].
 *
 * HARDWARE GATE: mic capture, AEC, audio-focus arbitration and route changes
 * here require physical-device evidence (CODE-VERIFIED ONLY in CI).
 */
@Singleton
class CallEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: PulseRepository,
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val machine = CallStateMachine(CallClock { System.currentTimeMillis() })

    private val _speakerOn = MutableStateFlow(false)
    /** UI mirror of the loud-speaker route (source of truth: CallAudioManager). */
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    /** UI mirror of the mic mute toggle (source of truth: the audio track). */
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val audio = CallAudioManager(context)

    // ── WebRTC handles (lazy — the factory is created on first call) ──
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var localSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var remoteDescSet = false
    private val pendingIce = mutableListOf<IceCandidateWire>()
    private var micReleasedOnError = false

    private var ticker: Job? = null

    val snapshot: StateFlow<CallSnapshot> = machine.snapshot

    init {
        // Relay call:* signals → the state machine (offer/answer/ice/…).
        scope.launch {
            repo.events().collect { event ->
                if (event is PulseEvent.CallSignal) onCallSignal(event)
            }
        }
        // Deadlines + duration tick — 1s cadence (machine drains its own timers).
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                perform(machine.poll())
            }
        }
    }

    // ── public actions (UI surface) ─────────────────────────────

    /**
     * Caller entry — resolves (or creates) the DM with [peerId], then opens
     * the ring. The RECORD_AUDIO permission must already be granted upstream
     * (the state machine's honest MediaFailed path is the denied fallback).
     */
    fun startOutgoing(
        peerId: String,
        name: String,
        color: String?,
        avatar: String?,
        callerName: String? = null,
        callerColor: String? = null,
    ) {
        scope.launch {
            val conversation = resolveDm(peerId)
            if (conversation == null) {
                Log.w(TAG, "no DM conversation for peer=$peerId — call aborted")
                return@launch
            }
            perform(
                machine.dispatch(
                    CallEvent.StartOutgoing(
                        conversationId = conversation.id,
                        peer = CallPeer(id = peerId, name = name, color = color, avatar = avatar),
                        kind = CallKind.VOICE,
                        // The callee's ring UI renders THIS identity — the
                        // real viewer name, never the raw id (web parity).
                        callerName = callerName ?: "Pulse user",
                        callerColor = callerColor,
                        callerAvatar = null,
                    ),
                ),
            )
        }
    }

    /** Callee accepted the ring (permission already granted upstream). */
    fun accept() {
        perform(machine.dispatch(CallEvent.Accept))
    }

    fun decline() {
        perform(machine.dispatch(CallEvent.Decline))
    }

    fun cancel() {
        perform(machine.dispatch(CallEvent.Cancel))
    }

    fun hangup() {
        perform(machine.dispatch(CallEvent.Hangup))
    }

    fun dismiss() {
        perform(machine.dispatch(CallEvent.Dismissed))
    }

    /** Mute/unmute the local mic track — the wire never learns about this. */
    fun toggleMute(): Boolean {
        val track = localTrack
        val next = !_micMuted.value
        runCatching { track?.setEnabled(!next) }
        _micMuted.value = next
        return next
    }

    /** Loud-speaker route toggle (Android audio manager). */
    fun toggleSpeaker(): Boolean {
        val next = !_speakerOn.value
        val applied = audio.setSpeakerOn(next)
        _speakerOn.value = applied && next
        return _speakerOn.value
    }

    // ── relay signal intake ─────────────────────────────────────

    private fun onCallSignal(event: PulseEvent.CallSignal) {
        val s = event.signal
        val callEvent = when (s.event) {
            "call:offer" -> CallEvent.IncomingOffer(
                callId = s.callId,
                conversationId = s.conversationId,
                from = s.from,
                kind = CallKind.of(s.kind.wire),
                sdp = s.sdp ?: return,
                callerName = s.callerName,
                callerColor = s.callerColor,
                callerAvatar = s.callerAvatar,
            )
            "call:answer" -> CallEvent.AnswerReceived(callId = s.callId, sdp = s.sdp ?: return)
            "call:ice" -> CallEvent.IceReceived(
                callId = s.callId,
                candidate = s.candidate ?: return,
                sdpMid = s.sdpMid,
                sdpMLineIndex = s.sdpMLineIndex,
            )
            "call:reject" -> CallEvent.RemoteReject(callId = s.callId)
            "call:cancel" -> CallEvent.RemoteCancel(callId = s.callId, reason = CallCancelReason.of(s.reason))
            "call:hangup" -> CallEvent.RemoteHangup(callId = s.callId, durationSec = s.durationSec)
            else -> return
        }
        perform(machine.dispatch(callEvent))
    }

    // ── effect execution ────────────────────────────────────────

    private fun perform(effects: List<CallEffect>) {
        for (effect in effects) {
            Log.d(TAG, "effect $effect")
            when (effect) {
                is CallEffect.AcquireMedia -> acquireMedia(effect)
                is CallEffect.CreateOffer -> createOffer()
                is CallEffect.ApplyRemoteOffer -> applyRemoteOffer(effect.sdp)
                is CallEffect.CreateAnswer -> createAnswer(effect.remoteSdp)
                is CallEffect.ApplyRemoteAnswer -> applyRemoteAnswer(effect.sdp)
                is CallEffect.ApplyRemoteIce -> applyRemoteIce(effect)
                is CallEffect.SendOffer -> repo.emitCall(
                    CallSignalOut(
                        event = "call:offer", callId = effect.callId, conversationId = effect.conversationId,
                        from = effect.from, to = effect.to, kind = effect.kind, sdp = effect.sdp,
                        callerName = effect.callerName, callerColor = effect.callerColor, callerAvatar = effect.callerAvatar,
                    ),
                )
                is CallEffect.SendAnswer -> repo.emitCall(
                    CallSignalOut(
                        event = "call:answer", callId = effect.callId, conversationId = effect.conversationId,
                        from = effect.from, to = effect.to, kind = effect.kind, sdp = effect.sdp,
                    ),
                )
                is CallEffect.SendIce -> repo.emitCall(
                    CallSignalOut(
                        event = "call:ice", callId = effect.callId, conversationId = effect.conversationId,
                        from = effect.from, to = effect.to, kind = effect.kind,
                        candidate = effect.candidate, sdpMid = effect.sdpMid, sdpMLineIndex = effect.sdpMLineIndex,
                    ),
                )
                is CallEffect.SendReject -> repo.emitCall(
                    CallSignalOut(
                        event = "call:reject", callId = effect.callId, conversationId = effect.conversationId,
                        from = effect.from, to = effect.to, kind = effect.kind,
                    ),
                )
                is CallEffect.SendCancel -> repo.emitCall(
                    CallSignalOut(
                        event = "call:cancel", callId = effect.callId, conversationId = effect.conversationId,
                        from = effect.from, to = effect.to, kind = effect.kind,
                    ),
                )
                is CallEffect.SendHangup -> repo.emitCall(
                    CallSignalOut(
                        event = "call:hangup", callId = effect.callId, conversationId = effect.conversationId,
                        from = effect.from, to = effect.to, kind = effect.kind, durationSec = effect.durationSec,
                    ),
                )
                is CallEffect.WriteLog -> writeLog(effect.entry)
                CallEffect.ReleaseMedia -> releaseMedia()
            }
        }
    }

    private fun writeLog(entry: CallLogEntry) {
        val me = repo.viewerId ?: return
        // Single-writer: the caller stamps its own identity + a startedAt for
        // the instant local row (the server assigns id/startedAt on POST).
        val row = CallLogMapper.terminalRow(
            viewerId = me,
            conversationId = entry.conversationId,
            peer = entry.peer ?: return,
            kind = entry.kind,
            status = entry.status,
            durationSec = entry.durationSec,
        ) ?: return
        scope.launch {
            repo.writeCallLog(row.copy(startedAt = java.time.OffsetDateTime.now().toString()))
            repo.refreshCallLog() // reconcile server ids + callee-side rows
        }
    }

    // ── WebRTC plumbing ─────────────────────────────────────────

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val module = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        adm = module
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(module)
            .createPeerConnectionFactory()
    }

    private fun ensurePeerConnection(): PeerConnection? {
        if (pc != null) return pc
        ensureFactory()
        if (factory == null) return null
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            ),
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = factory?.createPeerConnection(rtcConfig, PeerObserver())
        localTrack?.let { track -> pc?.addTrack(track, listOf("pulse-audio")) }
        return pc
    }

    /** Mic capture — dispatches MediaReady/MediaFailed back into the machine. */
    private fun acquireMedia(@Suppress("UNUSED_PARAMETER") effect: CallEffect.AcquireMedia) {
        runCatching {
            ensureFactory()
            if (localTrack == null) {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                }
                val source = factory?.createAudioSource(constraints)
                val track = factory?.createAudioTrack("pulse-mic", source)
                localSource = source
                localTrack = track
                _micMuted.value = false
            }
            audio.acquire()
            CallForegroundService.ensureChannel(context)
            val snapshotNow = machine.snapshot.value
            val label = snapshotNow.peer?.name ?: "Voice call"
            CallForegroundService.start(context, label)
            // The peer connection must exist BEFORE CreateOffer/CreateAnswer
            // effects run — both effects are synchronous siblings of
            // AcquireMedia in the machine's effect lists.
            ensurePeerConnection()
        }.onSuccess {
            perform(machine.dispatch(CallEvent.MediaReady))
        }.onFailure { e ->
            Log.e(TAG, "mic acquisition failed", e)
            micReleasedOnError = true
            perform(machine.dispatch(CallEvent.MediaFailed(e.message ?: "microphone unavailable")))
        }
    }

    private fun createOffer() {
        val connection = ensurePeerConnection() ?: return runPeerFailure()
        connection.createOffer(
            observer(
                onSuccess = { sdp ->
                    connection.setLocalDescription(SimpleObserver(), SessionDescription(SessionDescription.Type.OFFER, sdp.description))
                    perform(machine.dispatch(CallEvent.OfferReady(sdp.description)))
                },
                onFailure = { runPeerFailure() },
            ),
            MediaConstraints(),
        )
    }

    private fun applyRemoteOffer(sdp: String) {
        val connection = ensurePeerConnection() ?: return
        connection.setRemoteDescription(
            SimpleObserver(),
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
        remoteDescSet = true
        drainPendingIce()
    }

    private fun createAnswer(remoteSdp: String) {
        val connection = ensurePeerConnection() ?: return runPeerFailure()
        if (!remoteDescSet) {
            connection.setRemoteDescription(
                SimpleObserver(),
                SessionDescription(SessionDescription.Type.OFFER, remoteSdp),
            )
            remoteDescSet = true
            drainPendingIce()
        }
        connection.createAnswer(
            observer(
                onSuccess = { sdp ->
                    connection.setLocalDescription(SimpleObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp.description))
                    perform(machine.dispatch(CallEvent.AnswerReady(sdp.description)))
                },
                onFailure = { runPeerFailure() },
            ),
            MediaConstraints(),
        )
    }

    private fun applyRemoteAnswer(sdp: String) {
        val connection = pc ?: return
        connection.setRemoteDescription(
            SimpleObserver(),
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
        remoteDescSet = true
        drainPendingIce()
    }

    private fun applyRemoteIce(effect: CallEffect.ApplyRemoteIce) {
        val candidate = IceCandidate(effect.sdpMid, effect.sdpMLineIndex ?: 0, effect.candidate)
        val connection = pc
        if (connection == null || !remoteDescSet) {
            pendingIce.add(IceCandidateWire(effect.candidate, effect.sdpMid, effect.sdpMLineIndex ?: 0))
            return
        }
        connection.addIceCandidate(candidate)
    }

    private fun drainPendingIce() {
        if (!remoteDescSet) return
        val connection = pc ?: return
        for (wire in pendingIce) {
            connection.addIceCandidate(IceCandidate(wire.sdpMid, wire.sdpMLineIndex, wire.candidate))
        }
        pendingIce.clear()
    }

    /** Tear the media leg down (idempotent — the machine releases exactly once). */
    private fun releaseMedia() {
        runCatching {
            pc?.close()
            pc = null
            runCatching { localTrack?.setEnabled(true) }
            localTrack = null
            localSource?.dispose()
            localSource = null
            adm?.release()
            adm = null
            factory = null // factory owns ADM — rebuilding both next call is the safe lifecycle
            remoteDescSet = false
            pendingIce.clear()
            micReleasedOnError = false
            audio.release()
            CallForegroundService.stop(context)
            _micMuted.value = false
            _speakerOn.value = false
        }
    }

    private fun runPeerFailure(): PeerConnection? {
        perform(machine.dispatch(CallEvent.PeerFailed))
        return null
    }

    private fun observer(
        onSuccess: (SessionDescription) -> Unit,
        onFailure: (String) -> Unit,
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = onSuccess(desc)
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = onFailure(error ?: "sdp error")
        override fun onSetFailure(error: String?) = onFailure(error ?: "sdp error")
    }

    private class SimpleObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private inner class PeerObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            main.post {
                perform(
                    machine.dispatch(
                        CallEvent.IceProduced(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex),
                    ),
                )
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            main.post {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED ->
                        perform(machine.dispatch(CallEvent.PeerConnected))
                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        perform(machine.dispatch(CallEvent.PeerDisconnected))
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED,
                    -> perform(machine.dispatch(CallEvent.PeerFailed))
                    else -> Unit
                }
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            // UNIFIED_PLAN exposes the richer state — map the same truth.
            main.post {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED ->
                        perform(machine.dispatch(CallEvent.PeerConnected))
                    PeerConnection.PeerConnectionState.DISCONNECTED ->
                        perform(machine.dispatch(CallEvent.PeerDisconnected))
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED,
                    -> perform(machine.dispatch(CallEvent.PeerFailed))
                    else -> Unit
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) = Unit
    }

    private data class IceCandidateWire(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

    // ── DM resolution (caller entry) ────────────────────────────

    private suspend fun resolveDm(peerId: String): Conversation? {
        val cached = repo.observeConversations().first().firstOrNull { c ->
            c.kind == Conversation.Kind.DM && peerId in c.memberIds
        }
        if (cached != null) return cached
        return runCatching { repo.createDm(peerId) }.getOrNull()
    }

    private companion object {
        const val TAG = "CallEngine"
    }
}
