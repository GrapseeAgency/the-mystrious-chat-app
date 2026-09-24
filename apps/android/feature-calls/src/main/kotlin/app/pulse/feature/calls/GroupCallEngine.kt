package app.pulse.feature.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import app.pulse.core.PulseEndpoints
import app.pulse.domain.model.CallState
import app.pulse.domain.model.CallKind
import app.pulse.domain.model.GroupCallMember
import app.pulse.domain.model.GroupCallSignalData
import app.pulse.domain.model.GroupCallSignalOut
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.GroupCallEvents
import app.pulse.protocol.GroupCallMesh
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * R8 Task 3-c — the GROUP call engine: a Kotlin mirror of the web session in
 * src/components/chat/group-call-overlay.tsx (useGroupCallSession), driving
 * the `gcall:*` signaling contract served by the pulse-socket relay.
 *
 * MESH (web-verbatim): between any two members the one with the
 * lexicographically SMALLER id creates the offer ([GroupCallMesh.shouldOffer]);
 * the other answers. One [PeerConnection] per remote member, ONE local audio
 * (and optional video) track feeding all of them, per-peer ICE candidate
 * queues drained once the remote description lands.
 *
 * Joining an ONGOING call NEVER re-rings (joinCall/joinOngoing path); only
 * the starting member POSTs /calls/ring. Teardown happens on gcall:ended,
 * relay disconnect (the server removes us from the roster when our socket
 * dies) and explicit leave — the honest ended/summary cards follow.
 *
 * HARDWARE GATE: mic capture, AEC, camera capture here require
 * physical-device evidence (CODE-VERIFIED ONLY in CI).
 */
@Singleton
class GroupCallEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: PulseRepository,
    /** Busy guard — one live call per device (web main-shell.tsx:255-259). */
    private val oneToOne: CallEngine,
) {
    /** The four UI surfaces the web session exposes (web GroupCallUiState). */
    enum class UiPhase { IDLE, JOINING, ACTIVE, ENDED }

    /** One incoming group-call ring (web session.ring). */
    data class Ring(
        val conversationId: String,
        val caller: GroupCallMember,
        val kind: CallKind,
        val title: String,
    )

    /** Immutable UI snapshot — the single thing the group call overlay renders. */
    data class Snapshot(
        /** The viewer identity (roster self-split for the UI grid). */
        val meId: String = "",
        val phase: UiPhase = UiPhase.IDLE,
        val kind: CallKind = CallKind.VOICE,
        /** The conversation the session is JOINED to (empty while idle). */
        val conversationId: String = "",
        /** Display title of the joined/target conversation (overlay header). */
        val title: String = "",
        /** Roster INCLUDING me, join-ordered (while in the call). */
        val members: List<GroupCallMember> = emptyList(),
        val ring: Ring? = null,
        /** True while the OPEN conversation has a live call I am NOT in. */
        val ongoingElsewhere: Boolean = false,
        val ongoingMembers: List<GroupCallMember> = emptyList(),
        val ongoingKind: CallKind = CallKind.VOICE,
        /** Non-null = honest glass error card replaces the call UI. */
        val error: String? = null,
        val summary: String? = null,
        val durationSec: Long = 0,
        val micEnabled: Boolean = true,
        val cameraEnabled: Boolean = true,
    )

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** One-shot toast/snackbar copy — surfaced by the shell host (web toasts). */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    // ── video mirrors (renderer flow, same idiom as CallEngine) ──

    /** Process-lifetime EGL base — the group renderers MUST share this context. */
    private val eglBase: EglBase by lazy { EglBase.create() }
    val eglBaseContext: EglBase.Context get() = eglBase.eglBaseContext

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    /** Remote video tracks keyed by remote member id (the group video grid). */
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    // ── identity (set by the shell, voice-rooms idiom) ──────────

    @Volatile private var meId: String = ""
    @Volatile private var meName: String = "Pulse user"
    @Volatile private var meColor: String = "emerald"

    /** The OPEN conversation — probe target + outsider-banner gate (web openConversationId). */
    @Volatile private var activeConversationId: String = ""
    @Volatile private var activeTitle: String = ""

    /** Mutable mirrors so the signaling collector sees fresh values (web refs). */
    @Volatile private var phase: UiPhase = UiPhase.IDLE
    @Volatile private var kind: CallKind = CallKind.VOICE
    @Volatile private var joinedConvId: String? = null
    @Volatile private var joinedAtMs: Long = 0
    @Volatile private var joinIntent: Boolean = false
    @Volatile private var pendingRing: Ring? = null
    @Volatile private var currentCallId: String = ""
    @Volatile private var relayConnected: Boolean = PulseEndpoints.isConfigured
    private var probeJob: Job? = null

    // ── WebRTC handles ──────────────────────────────────────────
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTextureHelper: SurfaceTextureHelper? = null
    private var localVideoTrackRef: VideoTrack? = null
    private val peers = LinkedHashMap<String, PeerConnection>()
    private val peerRemoteDescSet = HashMap<String, Boolean>()
    private val pendingIce = HashMap<String, MutableList<IceCandidateWire>>()
    private val audio = CallAudioManager(context)

    private var started = false

    // ── lifecycle ───────────────────────────────────────────────

    fun start() {
        if (started) return
        started = true
        // gcall:* signal intake — the engine's event side.
        scope.launch {
            repo.events().collect { event ->
                if (event is PulseEvent.GroupCallSignal) onGroupCallSignal(event.signal)
            }
        }
        // Relay drop = the server removed us from the roster (leaveGroupCall
        // runs on socket disconnect) — tear down honestly, never fake a live
        // call. The same flow mirrors connection truth for the offline gate.
        scope.launch {
            repo.observeConnected().collect { connected ->
                relayConnected = connected
                if (!connected) onTransportLost()
            }
        }
        // Duration tick (web 1s interval while active).
        scope.launch {
            while (isActive) {
                delay(1_000)
                tick()
            }
        }
    }

    /** The shell tells the engine which conversation is OPEN (room open/close). */
    fun setActiveConversation(conversationId: String?, title: String? = null) {
        activeConversationId = conversationId.orEmpty()
        activeTitle = title.orEmpty()
        probeJob?.cancel()
        if (activeConversationId.isNotBlank()) {
            // Web probe parity: fire immediately + every 20s while the room is
            // open and the session is idle.
            val conv = activeConversationId
            probeJob = scope.launch {
                while (isActive && conv == activeConversationId) {
                    probeOngoing(conv)
                    delay(PROBE_INTERVAL_MS)
                }
            }
        }
    }

    fun setIdentity(id: String?, name: String?, color: String?) {
        meId = id.orEmpty()
        meName = name?.takeIf { it.isNotBlank() } ?: "Pulse user"
        meColor = color?.takeIf { it.isNotBlank() } ?: "emerald"
        _snapshot.value = _snapshot.value.copy(meId = meId)
    }

    /**
     * Web probe (group-call-overlay.tsx:621-666): while idle, ask the gateway
     * whether the OPEN conversation has a live call — a late-opening room
     * learns about it without a live ring. Roster including me → clear the
     * banner; roster NOT including me → honest ongoing-banner state.
     */
    private suspend fun probeOngoing(conversationId: String) {
        if (phase != UiPhase.IDLE || joinedConvId != null) return
        val probe = runCatching { repo.probeGroupCallState(conversationId) }.getOrNull()?.getOrNull() ?: return
        if (phase != UiPhase.IDLE || joinedConvId != null) return
        val roster = probe.members.filter { it.id.isNotBlank() }
        when {
            roster.isEmpty() ->
                _snapshot.value = _snapshot.value.copy(ongoingElsewhere = false, ongoingMembers = emptyList())
            roster.none { it.id == meId } ->
                _snapshot.value = _snapshot.value.copy(
                    ongoingElsewhere = true,
                    ongoingMembers = roster,
                    ongoingKind = probe.kind,
                )
            else ->
                _snapshot.value = _snapshot.value.copy(ongoingElsewhere = false)
        }
    }

    private fun tick() {
        val now = _snapshot.value
        if (now.phase != UiPhase.ACTIVE || joinedAtMs <= 0) return
        val elapsed = (System.currentTimeMillis() - joinedAtMs) / 1000
        _snapshot.value = now.copy(durationSec = maxOf(0L, elapsed))
    }

    // ── public actions (UI surface) ─────────────────────────────

    /**
     * Start a NEW group call in the OPEN conversation (rings everyone).
     * RECORD_AUDIO must already be granted upstream (the honest mic-denied
     * error card is the fallback).
     */
    fun startCall(wanted: CallKind, title: String) {
        startJoin(activeConversationId, wanted, ringOthers = true, title = title)
    }

    /** Join from the incoming ring — the ring already went out, NEVER re-rings. */
    fun joinCall() {
        val target = pendingRing ?: return
        if (phase != UiPhase.IDLE) return
        startJoin(target.conversationId, target.kind, ringOthers = false, title = target.title)
    }

    /** Silently join the ONGOING call in the open room — never re-rings. */
    fun joinOngoing() {
        if (phase != UiPhase.IDLE) return
        val conv = activeConversationId
        if (conv.isBlank()) return
        startJoin(conv, _snapshot.value.ongoingKind, ringOthers = false, title = activeTitle)
    }

    fun dismissRing() {
        pendingRing = null
        _snapshot.value = _snapshot.value.copy(ring = null)
    }

    fun ignoreOngoing() {
        _snapshot.value = _snapshot.value.copy(ongoingElsewhere = false)
    }

    /** Leave + honest summary ('You left · M:SS', web leaveCall verbatim). */
    fun leaveCall() {
        val conv = joinedConvId
        val currentPhase = phase
        if (currentPhase != UiPhase.JOINING && currentPhase != UiPhase.ACTIVE) return
        if (conv != null) {
            scope.launch {
                emitSignal(
                    GroupCallSignalOut(
                        event = GroupCallEvents.LEAVE,
                        conversationId = conv,
                        from = meId,
                    ),
                )
            }
        }
        val elapsedSec = if (joinedAtMs > 0) (System.currentTimeMillis() - joinedAtMs) / 1000 else 0
        teardownMedia()
        phase = UiPhase.ENDED
        _snapshot.value = _snapshot.value.copy(
            phase = UiPhase.ENDED,
            summary = GroupCallMesh.leaveSummary(elapsedSec),
        )
    }

    fun dismissError() {
        phase = UiPhase.IDLE
        _snapshot.value = _snapshot.value.copy(error = null, phase = UiPhase.IDLE)
    }

    fun dismissSummary() {
        phase = UiPhase.IDLE
        _snapshot.value = _snapshot.value.copy(summary = null, phase = UiPhase.IDLE)
    }

    /** Mute/unmute the local mic track (web toggleMic — the wire never learns). */
    fun toggleMic(): Boolean {
        val track = localAudioTrack
        val next = !(track?.enabled() ?: _snapshot.value.micEnabled)
        runCatching { track?.setEnabled(next) }
        _snapshot.value = _snapshot.value.copy(micEnabled = next)
        return next
    }

    /** Camera toggle (web toggleCamera — track.enabled flip, video kind only). */
    fun toggleCamera(): Boolean {
        if (kind != CallKind.VIDEO || localVideoTrackRef == null) return _snapshot.value.cameraEnabled
        val next = !localVideoTrackRef!!.enabled()
        runCatching { localVideoTrackRef?.setEnabled(next) }
        _snapshot.value = _snapshot.value.copy(cameraEnabled = next)
        return next
    }

    /** Front ⇄ back flip — honest no-op without an attached camera. */
    fun switchCamera(): Boolean {
        val capturer = videoCapturer ?: return false
        if (localVideoTrackRef == null) return false
        return runCatching { capturer.switchCamera(null); true }.getOrDefault(false)
    }

    // ── the join path (web startJoin verbatim) ──────────────────

    private fun startJoin(targetConvId: String, wanted: CallKind, ringOthers: Boolean, title: String) {
        if (targetConvId.isBlank()) return
        if (phase != UiPhase.IDLE) return
        if (meId.isBlank()) {
            _notices.tryEmit("Finish setting up your identity first")
            return
        }
        if (oneToOne.snapshot.value.state != CallState.IDLE) {
            // Web parity (main-shell.tsx:255-259): one live call at a time.
            _notices.tryEmit("Finish the current call first")
            return
        }
        if (!relayConnected) {
            _notices.tryEmit("You are offline — calls need a connection")
            return
        }
        joinIntent = true
        kind = wanted
        phase = UiPhase.JOINING
        _snapshot.value = _snapshot.value.copy(
            meId = meId,
            phase = UiPhase.JOINING,
            kind = wanted,
            conversationId = targetConvId,
            title = title,
            error = null,
            summary = null,
            ring = null,
            durationSec = 0,
            micEnabled = true,
        )
        pendingRing = null
        scope.launch {
            try {
                acquireMedia(wanted)
                joinedConvId = targetConvId
                emitSignal(
                    GroupCallSignalOut(
                        event = GroupCallEvents.JOIN,
                        conversationId = targetConvId,
                        kind = kind,
                        user = GroupCallMember(id = meId, name = meName, color = meColor, avatar = null),
                    ),
                )
                if (ringOthers) {
                    runCatching { repo.postGroupCallRing(targetConvId, kind) }
                        .onFailure { _notices.tryEmit("Could not ring other members") }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "group media acquisition failed", e)
                joinIntent = false
                joinedConvId = null
                audio.release()
                CallForegroundService.stop(context)
                phase = UiPhase.IDLE
                _snapshot.value = _snapshot.value.copy(
                    phase = UiPhase.IDLE,
                    error = GroupCallMesh.MIC_DENIED_ERROR,
                )
            }
        }
    }

    /**
     * Mic (+ camera when wanted) capture — web acquireMedia parity. Camera
     * failure NEVER fails the join: the call degrades to voice honestly
     * (web toast 'Camera unavailable — joining as a voice call').
     */
    private fun acquireMedia(wanted: CallKind) {
        ensureFactory()
        if (localAudioTrack == null) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            val source = factory?.createAudioSource(constraints) ?: error("no audio source")
            val track = factory?.createAudioTrack("pulse-group-mic", source) ?: error("no audio track")
            localAudioSource = source
            localAudioTrack = track
        }
        audio.acquire()
        runCatching { localAudioTrack?.setEnabled(true) }

        if (wanted == CallKind.VIDEO && !attachLocalVideo()) {
            Log.w(TAG, "camera unavailable — joining as a voice call")
            _notices.tryEmit("Camera unavailable — joining as a voice call")
            kind = CallKind.VOICE
            _snapshot.value = _snapshot.value.copy(kind = CallKind.VOICE, cameraEnabled = false)
        }
        _snapshot.value = _snapshot.value.copy(micEnabled = true, cameraEnabled = localVideoTrackRef != null)

        // Keep mic capture alive while the app is backgrounded (the app's
        // existing foreground-service idiom, shared with the 1:1 engine).
        CallForegroundService.ensureChannel(context)
        val label = _snapshot.value.title.ifBlank { "Group call" }
        CallForegroundService.start(context, label, video = kind == CallKind.VIDEO && localVideoTrackRef != null)
    }

    // ── signaling intake (web handleGroupCallEvent verbatim) ────

    private fun onGroupCallSignal(s: GroupCallSignalData) {
        when (s.event) {
            GroupCallEvents.RING -> {
                val convId = s.conversationId
                if (convId.isBlank()) return
                val ring = Ring(
                    conversationId = convId,
                    caller = GroupCallMember(
                        id = s.from,
                        name = s.callerName ?: "Someone",
                        color = s.callerColor ?: "emerald",
                        avatar = s.callerAvatar,
                    ),
                    kind = s.kind,
                    title = s.title.orEmpty(),
                )
                // Only ring when idle — a participant never sees their own ring.
                val idle = phase == UiPhase.IDLE && joinedConvId == null
                if (GroupCallMesh.shouldShowRing(sessionIdle = idle, fromSelf = ring.caller.id == meId)) {
                    pendingRing = ring
                    _snapshot.value = _snapshot.value.copy(ring = ring)
                }
            }

            GroupCallEvents.STATE -> {
                val convId = s.conversationId
                if (convId.isBlank()) return
                val joined = joinedConvId
                val memberPath = joined != null && convId == joined
                val outsiderPath = convId == activeConversationId && joined == null
                if (!memberPath && !outsiderPath) return
                val roster = s.members.filter { it.id.isNotBlank() }
                val iAmMember = roster.any { it.id == meId }
                if (memberPath && GroupCallMesh.shouldSyncRoster(iAmMember)) {
                    _snapshot.value = _snapshot.value.copy(ongoingElsewhere = false)
                    applyRoster(roster, convId, s.callId)
                } else if (GroupCallMesh.shouldShowOngoing(
                        iAmMember = iAmMember,
                        sessionIdle = phase == UiPhase.IDLE,
                        notJoinedElsewhere = joined == null,
                    )
                ) {
                    _snapshot.value = _snapshot.value.copy(
                        ongoingElsewhere = true,
                        ongoingMembers = roster,
                        ongoingKind = s.kind,
                    )
                }
            }

            GroupCallEvents.OFFER -> handleOffer(s)
            GroupCallEvents.ANSWER -> handleAnswer(s)
            GroupCallEvents.ICE -> handleIce(s)

            GroupCallEvents.ENDED -> {
                if (joinedConvId != null) {
                    teardownMedia()
                    phase = UiPhase.ENDED
                    _snapshot.value = _snapshot.value.copy(phase = UiPhase.ENDED, summary = "Call ended")
                }
                _snapshot.value = _snapshot.value.copy(ongoingElsewhere = false, ongoingMembers = emptyList())
            }

            GroupCallEvents.FULL -> {
                if (joinIntent) {
                    teardownMedia()
                    phase = UiPhase.IDLE
                    _snapshot.value = _snapshot.value.copy(phase = UiPhase.IDLE)
                    _notices.tryEmit(GroupCallMesh.FULL_ERROR)
                }
            }
        }
    }

    /**
     * Roster sync for a call I'm in ([GroupCallMesh.planRoster] carries the
     * web-verbatim decision table): departed peers close, new lower-id peers
     * get my offer, higher-id peers will offer me.
     */
    private fun applyRoster(roster: List<GroupCallMember>, rosterConvId: String, callId: String) {
        _snapshot.value = _snapshot.value.copy(members = roster, ongoingMembers = roster)

        val plan = GroupCallMesh.planRoster(meId, roster.map { it.id }, peers.keys)
        for (peerId in plan.departedPeerIds) {
            closePeer(peerId)
        }

        if (joinedAtMs == 0L) {
            joinedAtMs = System.currentTimeMillis()
            _snapshot.value = _snapshot.value.copy(durationSec = 0)
        }
        if (phase == UiPhase.JOINING) {
            phase = UiPhase.ACTIVE
            _snapshot.value = _snapshot.value.copy(phase = UiPhase.ACTIVE)
        }
        if (callId.isNotBlank()) currentCallId = callId

        for (peerId in plan.offerToPeerIds) {
            scope.launch { createOfferTo(peerId, rosterConvId) }
        }
    }

    private suspend fun createOfferTo(peerId: String, rosterConvId: String) {
        try {
            val pc = ensurePeerConnection(peerId)
            val offer = createSdp(pc, isOffer = true)
            pc.setLocalDescription(SimpleObserver(), SessionDescription(SessionDescription.Type.OFFER, offer))
            emitSignal(
                GroupCallSignalOut(
                    event = GroupCallEvents.OFFER,
                    conversationId = rosterConvId,
                    callId = currentCallId,
                    from = meId,
                    to = peerId,
                    kind = kind,
                    sdp = offer,
                ),
            )
        } catch (e: Throwable) {
            Log.e(TAG, "offer create failed: ${e.message}")
        }
    }

    /** createOffer/createAnswer bridged to a suspend point. */
    private suspend fun createSdp(pc: PeerConnection, isOffer: Boolean): String =
        suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    if (cont.isActive) cont.resumeWith(Result.success(desc.description))
                }

                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(error ?: "sdp error")))
                }

                override fun onSetFailure(error: String?) = Unit
            }
            if (isOffer) pc.createOffer(observer, MediaConstraints()) else pc.createAnswer(observer, MediaConstraints())
        }

    private fun handleOffer(s: GroupCallSignalData) {
        val joined = joinedConvId ?: return
        val from = s.from
        val sdp = s.sdp
        if (from.isBlank() || from == meId || s.conversationId != joined || sdp.isNullOrBlank()) return
        scope.launch {
            try {
                val pc = ensurePeerConnection(from)
                pc.setRemoteDescription(SimpleObserver(), SessionDescription(SessionDescription.Type.OFFER, sdp))
                peerRemoteDescSet[from] = true
                drainPendingIce(from, pc)
                val answer = createSdp(pc, isOffer = false)
                pc.setLocalDescription(SimpleObserver(), SessionDescription(SessionDescription.Type.ANSWER, answer))
                emitSignal(
                    GroupCallSignalOut(
                        event = GroupCallEvents.ANSWER,
                        conversationId = s.conversationId,
                        callId = s.callId.ifBlank { currentCallId },
                        from = meId,
                        to = from,
                        sdp = answer,
                    ),
                )
            } catch (e: Throwable) {
                Log.e(TAG, "offer apply failed: ${e.message}")
            }
        }
    }

    private fun handleAnswer(s: GroupCallSignalData) {
        val from = s.from
        val sdp = s.sdp
        if (from.isBlank() || from == meId || sdp.isNullOrBlank()) return
        val pc = peers[from] ?: return
        scope.launch {
            try {
                pc.setRemoteDescription(SimpleObserver(), SessionDescription(SessionDescription.Type.ANSWER, sdp))
                peerRemoteDescSet[from] = true
                drainPendingIce(from, pc)
            } catch (e: Throwable) {
                Log.e(TAG, "answer apply failed: ${e.message}")
            }
        }
    }

    private fun handleIce(s: GroupCallSignalData) {
        val from = s.from
        val candidate = s.candidate
        if (from.isBlank() || from == meId || candidate.isNullOrBlank()) return
        val pc = peers[from]
        val remoteSet = peerRemoteDescSet[from] == true
        if (pc != null && GroupCallMesh.applyIceNow(pcExists = true, remoteDescriptionSet = remoteSet)) {
            runCatching { pc.addIceCandidate(IceCandidate(s.sdpMid, s.sdpMLineIndex ?: 0, candidate)) }
            return
        }
        pendingIce.getOrPut(from) { mutableListOf() }.add(IceCandidateWire(candidate, s.sdpMid, s.sdpMLineIndex ?: 0))
    }

    private fun drainPendingIce(peerId: String, pc: PeerConnection) {
        val queue = pendingIce[peerId] ?: return
        for (wire in queue) {
            runCatching { pc.addIceCandidate(IceCandidate(wire.sdpMid, wire.sdpMLineIndex, wire.candidate)) }
        }
        queue.clear()
        pendingIce.remove(peerId)
    }

    /**
     * The relay died — the server already removed us from the roster
     * (leaveGroupCall fires on socket disconnect server-side). Honest end.
     */
    private fun onTransportLost() {
        if (joinedConvId == null) return
        if (phase == UiPhase.JOINING || phase == UiPhase.ACTIVE) {
            teardownMedia()
            phase = UiPhase.ENDED
            _snapshot.value = _snapshot.value.copy(
                phase = UiPhase.ENDED,
                summary = "Connection lost",
                error = null,
            )
        } else if (phase == UiPhase.IDLE) {
            // A join in flight never became real — reset quietly to idle.
            teardownMedia()
            _snapshot.value = _snapshot.value.copy(phase = UiPhase.IDLE)
        }
    }

    // ── peer connections ────────────────────────────────────────

    private fun ensurePeerConnection(peerId: String): PeerConnection {
        peers[peerId]?.let { return it }
        ensureFactory()
        val factoryNow = factory ?: error("no peer connection factory")
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val conv = joinedConvId ?: activeConversationId
        val pc = factoryNow.createPeerConnection(rtcConfig, GroupPeerObserver(peerId, conv))
            ?: error("peer connection creation failed for $peerId")
        // ONE local media flow feeds every peer (web addTrack loop parity).
        localAudioTrack?.let { track -> pc.addTrack(track, listOf("pulse-group-audio")) }
        localVideoTrackRef?.let { track -> pc.addTrack(track, listOf("pulse-group-video")) }
        peers[peerId] = pc
        peerRemoteDescSet[peerId] = false
        return pc
    }

    /**
     * ICE servers — the same deployment-manifest override the 1:1 engine
     * uses ([PulseEndpoints.iceServersJson]); built-in Google STUN otherwise.
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
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(module)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
            .createPeerConnectionFactory()
    }

    /** Front camera first (web default), any camera as fallback. */
    private fun createCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val name = front ?: enumerator.deviceNames.firstOrNull() ?: return null
        return runCatching { enumerator.createCapturer(name, null) }.getOrNull()
    }

    /**
     * REAL camera capture start — returns false when no camera exists / the
     * start fails (the join continues voice-only, never rethrows).
     */
    private fun attachLocalVideo(): Boolean {
        if (localVideoTrackRef != null) return true
        val factoryNow = factory ?: return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val capturer = createCapturer() ?: return false
        return try {
            val source = factoryNow.createVideoSource(capturer.isScreencast)
            val helper = SurfaceTextureHelper.create("pulse-group-video", eglBaseContext)
            capturer.initialize(helper, context, source.capturerObserver)
            capturer.startCapture(CallVideoPolicy.VIDEO_WIDTH, CallVideoPolicy.VIDEO_HEIGHT, CallVideoPolicy.VIDEO_FPS)
            val track = factoryNow.createVideoTrack("pulse-group-video0", source)
            track.setEnabled(true)
            videoCapturer = capturer
            videoSource = source
            videoTextureHelper = helper
            localVideoTrackRef = track
            _localVideoTrack.value = track
            true
        } catch (e: Throwable) {
            Log.e(TAG, "camera attach failed", e)
            runCatching { capturer.dispose() }
            false
        }
    }

    private fun closePeer(peerId: String) {
        peers.remove(peerId)?.let { pc ->
            runCatching { pc.close() }
        }
        peerRemoteDescSet.remove(peerId)
        pendingIce.remove(peerId)
        _remoteVideoTracks.value = _remoteVideoTracks.value - peerId
    }

    /**
     * Full media teardown (web teardownMedia verbatim): close every peer,
     * stop the local tracks, release the capturer/factory, reset the session
     * mirrors. Idempotent.
     */
    private fun teardownMedia() {
        runCatching {
            val stalePeers = LinkedHashMap(peers)
            peers.clear()
            for (pc in stalePeers.values) {
                runCatching { pc.close() }
            }
            peerRemoteDescSet.clear()
            pendingIce.clear()
            _remoteVideoTracks.value = emptyMap()

            runCatching { videoCapturer?.stopCapture() }
            videoCapturer = null
            videoTextureHelper?.dispose()
            videoTextureHelper = null
            videoSource?.dispose()
            videoSource = null
            localVideoTrackRef = null
            _localVideoTrack.value = null

            runCatching { localAudioTrack?.setEnabled(true) }
            localAudioTrack = null
            localAudioSource?.dispose()
            localAudioSource = null
            adm?.release()
            adm = null
            factory = null

            joinedConvId = null
            joinedAtMs = 0
            joinIntent = false
            currentCallId = ""
            audio.release()
            CallForegroundService.stop(context)
            _snapshot.value = _snapshot.value.copy(
                members = emptyList(),
                durationSec = 0,
                micEnabled = true,
                cameraEnabled = true,
            )
        }
    }

    private suspend fun emitSignal(signal: GroupCallSignalOut) {
        runCatching { repo.emitGroupCall(signal) }
            .onFailure { Log.w(TAG, "emit ${signal.event} failed", it) }
    }

    // ── peer observer (per remote member) ───────────────────────

    private inner class GroupPeerObserver(
        private val peerId: String,
        private val conversationId: String,
    ) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            main.post {
                scope.launch {
                    emitSignal(
                        GroupCallSignalOut(
                            event = GroupCallEvents.ICE,
                            conversationId = conversationId,
                            callId = currentCallId,
                            from = meId,
                            to = peerId,
                            kind = kind,
                            candidate = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                        ),
                    )
                }
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) {
            // Peer gone — drop their tile's video source.
            main.post { _remoteVideoTracks.value = _remoteVideoTracks.value - peerId }
        }
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit

        /** UNIFIED_PLAN remote video intake — audio goes through the ADM path. */
        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            val track = transceiver.receiver?.track() as? VideoTrack ?: return
            main.post { _remoteVideoTracks.value = _remoteVideoTracks.value + (peerId to track) }
        }
    }

    private class SimpleObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private data class IceCandidateWire(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

    private companion object {
        const val TAG = "GroupCallEngine"
        const val PROBE_INTERVAL_MS = 20_000L
    }
}
