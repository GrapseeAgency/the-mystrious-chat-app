package app.pulse.feature.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import app.pulse.domain.call.CallWorkFence
import app.pulse.domain.call.CallClock
import app.pulse.domain.call.CallStateMachine.CallEffect
import app.pulse.domain.call.CallStateMachine.CallEvent
import app.pulse.domain.call.CallLogMapper
import app.pulse.domain.call.CallSnapshot
import app.pulse.domain.call.CallStateMachine
import app.pulse.domain.model.CallCancelReason
import app.pulse.domain.model.CallDirection
import app.pulse.domain.model.CallKind
import app.pulse.domain.model.CallLogEntry
import app.pulse.domain.model.CallPeer
import app.pulse.domain.model.CallSignalOut
import app.pulse.domain.model.CallState
import app.pulse.domain.model.Conversation
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import app.pulse.core.PulseEndpoints
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wave 3 - the native call engine: executes the PURE [CallStateMachine]'s
 * effects against the REAL platform (WebRTC peer connection, Android audio,
 * socket signaling, single-writer call log) and feeds real-world events back
 * in. One engine per process (Hilt @Singleton) - every screen overlays the
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
    private var callOwner: String? = null
    private var callRepository: PulseRepository? = null
    private var relayReady = false
    internal var groupCallBusy: () -> Boolean = { false }
    private val preparationWork = CallWorkFence()
    private var preparationTask: Job? = null
    private val peerWork = CallWorkFence()
    private var peerTicket: CallWorkFence.Ticket? = null

    private fun cancelPreparation() {
        preparationWork.invalidate()
        preparationTask?.cancel()
        preparationTask = null
    }

    /** The machine cannot process any action until bound; never reuse another owner's binding. */
    private fun ensureOwner(): Boolean {
        val owner = repo.viewerId
        if (owner == callOwner) return owner != null
        cancelPreparation()
        if (callOwner != null) {
            perform(machine.dispatch(CallEvent.Hangup))
            releaseMedia()
        }
        machine.resetViewer(owner)
        callOwner = owner
        callRepository = owner?.let { repo.boundToCurrentAccount() }
        return owner != null
    }

    private fun peerIsCurrent(ticket: CallWorkFence.Ticket): Boolean =
        repo.viewerId == callOwner && peerWork.owns(ticket, callOwner, machine.snapshot.value.callId) &&
            machine.snapshot.value.state !in setOf(CallState.IDLE, CallState.ENDED)

    private fun onPeer(ticket: CallWorkFence.Ticket, action: () -> Unit) {
        main.post { if (peerIsCurrent(ticket)) action() }
    }

    private val _speakerOn = MutableStateFlow(false)
    /** UI mirror of the loud-speaker route (source of truth: CallAudioManager). */
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    /** UI mirror of the mic mute toggle (source of truth: the audio track). */
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    // video mirrors (Wave R1-W2D - real camera path, web call-overlay parity)

    /** Process-lifetime EGL base - the renderers MUST share this context. */
    private val eglBase: EglBase by lazy { EglBase.create() }

    /** Shared EGL context for the encoder/decoder factories + renderers. */
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    /** The local camera track once capture actually started (null = audio-only). */
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    /** The peer's video track once it arrives over the negotiated m-line. */
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _videoCaptureActive = MutableStateFlow(false)
    /** True iff a real camera capturer is running (drives video-only controls). */
    val videoCaptureActive: StateFlow<Boolean> = _videoCaptureActive.asStateFlow()

    private val _cameraEnabled = MutableStateFlow(false)
    /** UI mirror of the camera (video) toggle - web track.enabled parity. */
    val cameraEnabled: StateFlow<Boolean> = _cameraEnabled.asStateFlow()

    private val _videoNotice = MutableStateFlow<String?>(null)
    /** One-shot honest notice when a wanted video call degrades to voice (web toast parity). */
    val videoNotice: StateFlow<String?> = _videoNotice.asStateFlow()

    private val audio = CallAudioManager(context)

    // WebRTC handles (lazy - the factory is created on first call)
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var localSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var remoteDescSet = false

    // video handles (audio path untouched - video is strictly additive)
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTextureHelper: SurfaceTextureHelper? = null
    private var localVideoTrackRef: VideoTrack? = null
    private var remoteVideoTrackRef: VideoTrack? = null
    private val pendingIce = mutableListOf<IceCandidateWire>()
    private val pendingLocalSignals = mutableListOf<CallSignalOut>()
    private var localSdpDispatched = false
    private var micReleasedOnError = false
    /** The live incoming call's raw offer SDP - gates the CALLEE's camera attach (m=video check). */
    private var lastOfferSdp: String? = null

    private var ticker: Job? = null

    val snapshot: StateFlow<CallSnapshot> = machine.snapshot

    init {
        audio.onFocusLost = { transient ->
            scope.launch {
                // R58 (E2E finding): a TRANSIENT loss while the call is still
                // RINGING is the stack's own ringback churn - the telecom
                // mirror registers the call and the system's phone ring steals
                // focus for a moment, which was killing the call ~120ms after
                // dialing. A PERMANENT loss, or ANY loss once the call is past
                // the ring, still ends it (the R56 privacy policy: end, never
                // silently resume a microphone).
                val ringing = machine.snapshot.value.state.let {
                    it == CallState.OUTGOING_RINGING || it == CallState.INCOMING_RINGING
                }
                if (transient && ringing) return@launch
                perform(machine.dispatch(CallEvent.PreparationFailed("Call audio was interrupted.")))
            }
        }
        // Relay call:* signals → the state machine (offer/answer/ice/…).
        scope.launch {
            repo.events().collect { event ->
                if (event is PulseEvent.CallSignal) onCallSignal(event)
            }
        }
        scope.launch {
            repo.observeConnected().collect { ready ->
                ensureOwner()
                relayReady = ready
                if (!ready) {
                    cancelPreparation()
                    perform(machine.dispatch(CallEvent.PreparationFailed("Connection lost. Reconnect before calling again.")))
                }
            }
        }
        // Deadlines + duration tick - 1s cadence (machine drains its own timers).
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                ensureOwner()
                perform(machine.poll())
            }
        }
    }

    // public actions (UI surface)

    /**
     * Caller entry - resolves (or creates) the DM with [peerId], then opens
     * the ring. The RECORD_AUDIO permission must already be granted upstream
     * (the state machine's honest MediaFailed path is the denied fallback).
     *
     * [kind] = the wanted wire kind ('voice' | 'video', web CallKind). For
     * VIDEO the engine runs the web acquireMedia capability probe FIRST
     * (call-overlay.tsx:262-282): no usable camera ⇒ the call degrades to
     * VOICE before StartOutgoing is dispatched, so the wire kind always
     * carries the ACTUAL kind.
     */
    fun startOutgoing(
        peerId: String,
        name: String,
        color: String?,
        avatar: String?,
        callerName: String? = null,
        callerColor: String? = null,
        kind: CallKind = CallKind.VOICE,
    ) {
        if (!ensureOwner() || machine.snapshot.value.state != CallState.IDLE) return
        if (groupCallBusy()) { _videoNotice.value = "Finish your group call first."; return }
        if (!relayReady) { _videoNotice.value = "Connect to realtime before starting a call."; return }
        cancelPreparation()
        val owner = checkNotNull(callOwner)
        val bound = checkNotNull(callRepository)
        val ticket = preparationWork.begin(owner)
        preparationTask = scope.launch {
            val conversation = try { resolveDm(bound, peerId) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { Log.w(TAG, "Could not open call conversation", error); null }
            if (!preparationWork.owns(ticket, repo.viewerId) || !relayReady || groupCallBusy() || machine.snapshot.value.state != CallState.IDLE) return@launch
            if (conversation == null) {
                _videoNotice.value = "Couldn't open this conversation. The call wasn't started."
                return@launch
            }
            val cameraCapable = cameraCapable()
            val resolvedKind = CallVideoPolicy.resolveOutgoingKind(kind, cameraCapable)
            if (kind == CallKind.VIDEO && resolvedKind == CallKind.VOICE) {
                Log.w(TAG, "video requested but no usable camera - falling back to voice")
                _videoNotice.value = "Camera unavailable - starting a voice call"
            }
            perform(
                machine.dispatch(
                    CallEvent.StartOutgoing(
                        conversationId = conversation.id,
                        peer = CallPeer(id = peerId, name = name, color = color, avatar = avatar),
                        kind = resolvedKind,
                        // The callee's ring UI renders THIS identity - the
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
        cancelPreparation()
        if (!ensureOwner()) return
        if (!relayReady) {
            perform(machine.dispatch(CallEvent.PreparationFailed("Connection lost. This call could not be answered.")))
            return
        }
        perform(machine.dispatch(CallEvent.Accept))
    }

    fun decline() {
        cancelPreparation()
        if (!ensureOwner()) return
        perform(machine.dispatch(CallEvent.Decline))
    }

    fun cancel() {
        cancelPreparation()
        if (!ensureOwner()) return
        perform(machine.dispatch(CallEvent.Cancel))
    }

    fun hangup() {
        cancelPreparation()
        if (!ensureOwner()) return
        perform(machine.dispatch(CallEvent.Hangup))
    }

    fun dismiss() {
        cancelPreparation()
        if (!ensureOwner()) return
        perform(machine.dispatch(CallEvent.Dismissed))
    }

    /** Mute/unmute the local mic track - the wire never learns about this. */
    fun toggleMute(): Boolean {
        val track = localTrack
        val next = !_micMuted.value
        runCatching { track?.setEnabled(!next) }
        _micMuted.value = next
        return next
    }

    /**
     * Camera (video) toggle - web toggleCamera parity (track.enabled flip,
     * NOT capturer stop; the wire never learns about this either).
     * No-op when no camera is attached (voice call / audio-only fallback).
     */
    fun toggleVideo(): Boolean {
        if (!_videoCaptureActive.value) return false
        val next = !_cameraEnabled.value
        runCatching { localVideoTrackRef?.setEnabled(!next) }
        _cameraEnabled.value = next
        return next
    }

    /** Front ⇄ back camera flip (web has no equivalent - native bonus, honest no-op when N/A). */
    fun switchCamera(): Boolean {
        val capturer = videoCapturer ?: return false
        if (!_videoCaptureActive.value) return false
        return runCatching { capturer.switchCamera(null); true }.getOrDefault(false)
    }

    /** Loud-speaker route toggle (Android audio manager). */
    fun toggleSpeaker(): Boolean {
        val next = !_speakerOn.value
        val applied = audio.setSpeakerOn(next)
        _speakerOn.value = applied && next
        return _speakerOn.value
    }

    // relay signal intake

    private fun onCallSignal(event: PulseEvent.CallSignal) {
        if (!ensureOwner()) return
        val s = event.signal
        // Offline/busy refusals echo the caller's original from/to envelope.
        if (s.to != callOwner && !(s.event == "call:cancel" && s.from == callOwner &&
                s.callId == machine.snapshot.value.callId)) return
        val callEvent = when (s.event) {
            "call:offer" -> {
                if (groupCallBusy()) {
                    val bound = callRepository ?: return
                    val reject = CallSignalOut(event = "call:reject", callId = s.callId, conversationId = s.conversationId,
                        from = checkNotNull(callOwner), to = s.from, kind = s.kind)
                    scope.launch { emitCallSafely(bound, reject) }
                    return
                }
                // Stash the offer SDP for the callee's video decision - only
                // when this offer actually opens a ring (never mid-call).
                if (machine.snapshot.value.state == CallState.IDLE) {
                    cancelPreparation()
                    lastOfferSdp = s.sdp
                }
                CallEvent.IncomingOffer(
                    callId = s.callId,
                    conversationId = s.conversationId,
                    from = s.from,
                    kind = CallKind.of(s.kind.wire),
                    sdp = s.sdp ?: return,
                    callerName = s.callerName,
                    callerColor = s.callerColor,
                    callerAvatar = s.callerAvatar,
                )
            }
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

    // effect execution

    private fun perform(effects: List<CallEffect>) {
        val bound = callRepository ?: return
        for (effect in effects) {
            Log.d(TAG, "effect $effect")
            when (effect) {
                is CallEffect.AcquireMedia -> acquireMedia(effect)
                is CallEffect.CreateOffer -> createOffer()
                is CallEffect.ApplyRemoteOffer -> Unit // CreateAnswer applies it once and awaits success.
                is CallEffect.CreateAnswer -> createAnswer(effect.remoteSdp)
                is CallEffect.ApplyRemoteAnswer -> applyRemoteAnswer(effect.sdp)
                is CallEffect.ApplyRemoteIce -> applyRemoteIce(effect)
                is CallEffect.SendOffer -> scope.launch {
                    emitCallSafely(bound,
                        CallSignalOut(
                            event = "call:offer", callId = effect.callId, conversationId = effect.conversationId,
                            from = effect.from, to = effect.to, kind = effect.kind, sdp = effect.sdp,
                            callerName = effect.callerName, callerColor = effect.callerColor, callerAvatar = effect.callerAvatar,
                        ),
                    )
                }
                is CallEffect.SendAnswer -> scope.launch {
                    emitCallSafely(bound,
                        CallSignalOut(
                            event = "call:answer", callId = effect.callId, conversationId = effect.conversationId,
                            from = effect.from, to = effect.to, kind = effect.kind, sdp = effect.sdp,
                        ),
                    )
                }
                is CallEffect.SendIce -> scope.launch {
                    emitCallSafely(bound,
                        CallSignalOut(
                            event = "call:ice", callId = effect.callId, conversationId = effect.conversationId,
                            from = effect.from, to = effect.to, kind = effect.kind,
                            candidate = effect.candidate, sdpMid = effect.sdpMid, sdpMLineIndex = effect.sdpMLineIndex,
                        ),
                    )
                }
                is CallEffect.SendReject -> scope.launch {
                    emitCallSafely(bound,
                        CallSignalOut(
                            event = "call:reject", callId = effect.callId, conversationId = effect.conversationId,
                            from = effect.from, to = effect.to, kind = effect.kind,
                        ),
                    )
                }
                is CallEffect.SendCancel -> scope.launch {
                    emitCallSafely(bound,
                        CallSignalOut(
                            event = "call:cancel", callId = effect.callId, conversationId = effect.conversationId,
                            from = effect.from, to = effect.to, kind = effect.kind,
                        ),
                    )
                }
                is CallEffect.SendHangup -> scope.launch {
                    emitCallSafely(bound,
                        CallSignalOut(
                            event = "call:hangup", callId = effect.callId, conversationId = effect.conversationId,
                            from = effect.from, to = effect.to, kind = effect.kind, durationSec = effect.durationSec,
                        ),
                    )
                }
                is CallEffect.WriteLog -> writeLog(bound, effect.entry)
                CallEffect.ReleaseMedia -> releaseMedia()
            }
        }
    }

    private suspend fun emitCallSafely(bound: PulseRepository, signal: CallSignalOut) {
        if (signal.event in setOf("call:offer", "call:answer", "call:ice") &&
            (machine.snapshot.value.callId != signal.callId || machine.snapshot.value.state in setOf(CallState.IDLE, CallState.ENDED))) return
        if (signal.event == "call:ice" && !localSdpDispatched) {
            if (pendingLocalSignals.size < 256) pendingLocalSignals += signal
            return
        }
        try {
            bound.emitCall(signal)
            if (signal.event == "call:offer" || signal.event == "call:answer") {
                localSdpDispatched = true
                val queued = pendingLocalSignals.toList()
                pendingLocalSignals.clear()
                for (ice in queued) emitCallSafely(bound, ice)
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
            Log.w(TAG, "Call signal was not dispatched", error)
            if (machine.snapshot.value.callId == signal.callId && signal.event in setOf("call:offer", "call:answer")) {
                perform(machine.dispatch(CallEvent.PreparationFailed("Connection lost. The call could not be signaled.")))
            }
        }
    }

    private fun writeLog(bound: PulseRepository, entry: CallLogEntry) {
        val me = bound.viewerId ?: return
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
            bound.writeCallLog(row.copy(startedAt = java.time.OffsetDateTime.now().toString()))
            bound.refreshCallLog() // reconcile server ids + callee-side rows
        }
    }

    // WebRTC plumbing

    /**
     * ICE servers (Wave 3-HW): the deployment manifest `ice` array (TURN with
     * credentials) wins when adopted via [PulseEndpoints.applyIceOverride];
     * otherwise the built-in Google STUN pair stays (permissive networks).
     * Invalid manifest JSON degrades to the defaults - never a crash.
     */
    private fun iceServers(): List<PeerConnection.IceServer> {
        val raw = PulseEndpoints.iceServersJson
        if (raw.isNotBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                val servers = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val urls: List<String> = when (val u = o.opt("urls")) {
                        is JSONArray -> (0 until u.length()).mapNotNull { j ->
                            u.optString(j).takeIf { it.isNotBlank() }
                        }
                        is String -> listOfNotNull(u.takeIf { it.isNotBlank() })
                        else -> return@mapNotNull null
                    }
                    if (urls.isEmpty()) return@mapNotNull null
                    val builder = PeerConnection.IceServer.builder(urls)
                    if (o.has("username") && !o.isNull("username")) builder.setUsername(o.getString("username"))
                    if (o.has("credential") && !o.isNull("credential")) builder.setPassword(o.getString("credential"))
                    builder.createIceServer()
                }
                if (servers.isNotEmpty()) return servers
            }.onFailure { Log.w(TAG, "manifest ice parse failed; keeping built-in STUN", it) }
        }
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        )
    }

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
        // Video codec engines share the renderers' EGL context - required for
        // real HW-accelerated video (the audio-only path never touched these).
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(module)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun ensurePeerConnection(): PeerConnection? {
        if (machine.snapshot.value.state in setOf(CallState.IDLE, CallState.ENDED)) return null
        if (pc != null) return pc
        val owner = callOwner ?: return null
        val callId = machine.snapshot.value.callId ?: return null
        if (repo.viewerId != owner) return null
        ensureFactory()
        if (factory == null) return null
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val ticket = peerWork.begin(owner, callId)
        peerTicket = ticket
        pc = factory?.createPeerConnection(rtcConfig, PeerObserver(ticket))
        localTrack?.let { track -> pc?.addTrack(track, listOf("pulse-audio")) }
        // The video track (when capture started) rides its own sendrecv
        // m-line - UNIFIED_PLAN associates it with the peer's video m-line.
        localVideoTrackRef?.let { track -> pc?.addTrack(track, listOf("pulse-video")) }
        return pc
    }

    /**
     * Mic (+ camera when the call's kind says video) capture - dispatches
     * MediaReady/MediaFailed back into the machine. Camera failure NEVER
     * fails the call: the audio path is already live and the m-line simply
     * stays audio/recvonly - the honest audio-only fallback (web parity,
     * call-overlay.tsx callee without a camera).
     */
    private fun acquireMedia(effect: CallEffect.AcquireMedia) {
        if (machine.snapshot.value.state in setOf(CallState.IDLE, CallState.ENDED)) return
        if (!relayReady) {
            perform(machine.dispatch(CallEvent.PreparationFailed("Connect to realtime before starting a call.")))
            return
        }
        runCatching {
            check(audio.acquire()) { "Audio is unavailable or in use by another live session." }
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
            // Real camera path - direction decides the gate (same policy for
            // both sides):
            //   CALLER - kind already resolved against camera capability at
            //     startOutgoing, so attach iff this is a VIDEO call;
            //   CALLEE - additionally require the offer SDP to declare a
            //     usable m=video line ([CallVideoPolicy.shouldAttachVideo]).
            // Camera failure NEVER fails the call - audio continues (web parity).
            val capable = cameraCapable()
            val wantVideo = if (machine.snapshot.value.direction == CallDirection.INCOMING) {
                CallVideoPolicy.shouldAttachVideo(effect.kind, lastOfferSdp, capable)
            } else {
                effect.kind == CallKind.VIDEO && capable
            }
            if (wantVideo && !attachLocalVideo()) {
                Log.w(TAG, "camera unavailable - call continues audio-only (web fallback parity)")
            }
            CallForegroundService.ensureChannel(context)
            val snapshotNow = machine.snapshot.value
            val label = snapshotNow.peer?.name ?: "Voice call"
            CallForegroundService.start(context, label, video = _videoCaptureActive.value)
            // The peer connection must exist BEFORE CreateOffer/CreateAnswer
            // effects run - both effects are synchronous siblings of
            // AcquireMedia in the machine's effect lists.
            ensurePeerConnection()
        }.onSuccess {
            perform(machine.dispatch(CallEvent.MediaReady))
        }.onFailure { e ->
            Log.e(TAG, "mic acquisition failed", e)
            micReleasedOnError = true
            val denied = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            perform(machine.dispatch(if (denied) CallEvent.MediaFailed("permission")
                else CallEvent.PreparationFailed(e.message ?: "Audio could not be started.")))
        }
    }

    private fun cameraPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Capability probe feeding [CallVideoPolicy] - permission, Camera2 API
     * support and at least one camera device (web: ANY usable camera).
     */
    private fun cameraCapable(): Boolean = runCatching {
        CallVideoPolicy.isCameraCapable(
            hasCameraPermission = cameraPermissionGranted(),
            cameraApiSupported = Camera2Enumerator.isSupported(context),
            deviceNames = Camera2Enumerator(context).deviceNames.toList(),
        )
    }.getOrDefault(false)

    /** Front camera first (web default), any camera as fallback. */
    private fun createCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val name = front ?: enumerator.deviceNames.firstOrNull() ?: return null
        return runCatching { enumerator.createCapturer(name, null) }.getOrNull()
    }

    /**
     * REAL camera capture start: Camera2 capturer → VideoSource → VideoTrack.
     * Returns false when no camera exists / the start fails - callers keep
     * the audio path (never rethrows).
     */
    private fun attachLocalVideo(): Boolean {
        if (_videoCaptureActive.value) return true
        val factoryNow = factory ?: return false
        val capturer = createCapturer() ?: return false
        return try {
            val source = factoryNow.createVideoSource(capturer.isScreencast)
            val helper = SurfaceTextureHelper.create("pulse-video-capture", eglBaseContext)
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(CallVideoPolicy.VIDEO_WIDTH, CallVideoPolicy.VIDEO_HEIGHT, CallVideoPolicy.VIDEO_FPS)
            val track = factoryNow.createVideoTrack("pulse-video0", source)
            track.setEnabled(true)
            videoCapturer = capturer
            videoSource = source
            videoTextureHelper = helper
            localVideoTrackRef = track
            _localVideoTrack.value = track
            _videoCaptureActive.value = true
            _cameraEnabled.value = true
            _videoNotice.value = null
            true
        } catch (e: Throwable) {
            Log.e(TAG, "camera attach failed", e)
            runCatching { capturer.dispose() }
            false
        }
    }

    /** Remote video intake - idempotent, ignores our own local track echo. */
    private fun attachRemoteVideo(track: VideoTrack) {
        if (track === localVideoTrackRef) return
        if (remoteVideoTrackRef === track) return
        remoteVideoTrackRef = track
        _remoteVideoTrack.value = track
        Log.d(TAG, "remote video track attached")
    }

    private fun createOffer() {
        if (machine.snapshot.value.state != CallState.OUTGOING_RINGING) return
        val connection = ensurePeerConnection() ?: run { runPeerFailure(); return }
        val ticket = peerTicket ?: return
        connection.createOffer(observer(ticket, onCreated = { sdp ->
            connection.setLocalDescription(observer(ticket, onSet = {
                perform(machine.dispatch(CallEvent.OfferReady(sdp.description)))
            }), SessionDescription(SessionDescription.Type.OFFER, sdp.description))
        }), MediaConstraints())
    }

    private fun createAnswer(remoteSdp: String) {
        if (machine.snapshot.value.state != CallState.INCOMING_RINGING) return
        val connection = ensurePeerConnection() ?: run { runPeerFailure(); return }
        val ticket = peerTicket ?: return
        fun create() {
            connection.createAnswer(observer(ticket, onCreated = { sdp ->
                connection.setLocalDescription(observer(ticket, onSet = {
                    perform(machine.dispatch(CallEvent.AnswerReady(sdp.description)))
                }), SessionDescription(SessionDescription.Type.ANSWER, sdp.description))
            }), MediaConstraints())
        }
        if (remoteDescSet) create() else {
            connection.setRemoteDescription(observer(ticket, onSet = {
                remoteDescSet = true
                drainPendingIce()
                create()
            }), SessionDescription(SessionDescription.Type.OFFER, remoteSdp))
        }
    }

    private fun applyRemoteAnswer(sdp: String) {
        val connection = pc ?: return
        val ticket = peerTicket ?: return
        connection.setRemoteDescription(observer(ticket, onSet = {
            remoteDescSet = true
            drainPendingIce()
        }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun applyRemoteIce(effect: CallEffect.ApplyRemoteIce) {
        val candidate = IceCandidate(effect.sdpMid, effect.sdpMLineIndex ?: 0, effect.candidate)
        val connection = pc
        if (connection == null || !remoteDescSet) {
            // R56 (item 2): bounded early-ICE buffer - same 256-entry cap the
            // group engine and the local-send queue already enforce.
            if (pendingIce.size < 256) {
                pendingIce.add(IceCandidateWire(effect.candidate, effect.sdpMid, effect.sdpMLineIndex ?: 0))
            }
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

    /** Tear the media leg down (idempotent - the machine releases exactly once). */
    private fun releaseMedia() {
        // Invalidate BEFORE close: WebRTC may post CLOSED/ICE callbacks during teardown.
        peerWork.invalidate()
        peerTicket = null
        localSdpDispatched = false
        pendingLocalSignals.clear()
        val retired = pc
        pc = null
        runCatching { retired?.close() }
        runCatching { retired?.dispose() }
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        runCatching { videoTextureHelper?.dispose() }
        videoTextureHelper = null
        runCatching { localVideoTrackRef?.dispose() }
        localVideoTrackRef = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        remoteVideoTrackRef = null
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        _videoCaptureActive.value = false
        _cameraEnabled.value = false
        _videoNotice.value = null
        runCatching { localTrack?.dispose() }
        localTrack = null
        runCatching { localSource?.dispose() }
        localSource = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { adm?.release() }
        adm = null
        remoteDescSet = false
        pendingIce.clear()
        lastOfferSdp = null
        micReleasedOnError = false
        // A failed native dispose must never skip microphone focus/service cleanup.
        runCatching { audio.release() }
        runCatching { CallForegroundService.stop(context) }
        _micMuted.value = false
        _speakerOn.value = false
    }

    private fun runPeerFailure() {
        perform(machine.dispatch(CallEvent.PreparationFailed("The call's media negotiation failed. Try again.")))
    }

    private fun observer(
        ticket: CallWorkFence.Ticket,
        onCreated: (SessionDescription) -> Unit = {},
        onSet: () -> Unit = {},
    ): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = onPeer(ticket) { onCreated(desc) }
        override fun onSetSuccess() = onPeer(ticket, onSet)
        override fun onCreateFailure(error: String?) = onPeer(ticket) { runPeerFailure() }
        override fun onSetFailure(error: String?) = onPeer(ticket) { runPeerFailure() }
    }

    private inner class PeerObserver(private val ticket: CallWorkFence.Ticket) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            onPeer(ticket) {
                perform(
                    machine.dispatch(
                        CallEvent.IceProduced(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex),
                    ),
                )
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            onPeer(ticket) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED ->
                        perform(machine.dispatch(CallEvent.PeerConnected))
                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        perform(machine.dispatch(CallEvent.PeerDisconnected))
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED,
                    -> runPeerFailure()
                    else -> Unit
                }
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            // UNIFIED_PLAN exposes the richer state - map the same truth.
            onPeer(ticket) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED ->
                        perform(machine.dispatch(CallEvent.PeerConnected))
                    PeerConnection.PeerConnectionState.DISCONNECTED ->
                        perform(machine.dispatch(CallEvent.PeerDisconnected))
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED,
                    -> runPeerFailure()
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

        /**
         * UNIFIED_PLAN remote-track intake: fires when the remote description
         * adds/associates a transceiver. Only VIDEO transceivers are promoted
         * to the renderer flow (audio output goes through the ADM speaker path).
         */
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            val track = transceiver.receiver?.track() as? VideoTrack ?: return
            onPeer(ticket) { attachRemoteVideo(track) }
        }
    }

    private data class IceCandidateWire(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

    // DM resolution (caller entry)

    private suspend fun resolveDm(bound: PulseRepository, peerId: String): Conversation? {
        val cached = bound.observeConversations().first().firstOrNull { c ->
            c.kind == Conversation.Kind.DM && peerId in c.memberIds
        }
        if (cached != null) return cached
        return bound.createDm(peerId).getOrNull()
    }

    private companion object {
        const val TAG = "CallEngine"
    }
}
