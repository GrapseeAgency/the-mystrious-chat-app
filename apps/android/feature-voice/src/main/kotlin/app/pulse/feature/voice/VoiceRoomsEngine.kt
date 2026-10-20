package app.pulse.feature.voice

import android.content.Context
import android.os.SystemClock
import app.pulse.feature.voice.engine.RoomSeatOwnership
import app.pulse.feature.voice.engine.PushToTalkPolicy
import java.util.UUID
import app.pulse.domain.repository.PulseEvent
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import app.pulse.feature.voice.audio.VoiceRoomAudioEngine
import app.pulse.feature.voice.engine.CaptionWindowAccumulator
import app.pulse.feature.voice.engine.PlaybackScheduler
import app.pulse.feature.voice.engine.SpaceBoardStateMachine
import app.pulse.feature.voice.engine.StageRoomStateMachine
import app.pulse.feature.voice.engine.VoicePcm
import app.pulse.feature.voice.engine.VoiceRoomStateMachine
import app.pulse.feature.voice.engine.WavEncoder
import app.pulse.protocol.PulseVoiceUser
import app.pulse.protocol.parseSpaceBoardState
import app.pulse.protocol.parseStageRoomState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wave 5 - the session-scoped voice-rooms engine (the CallEngine pattern):
 * ONE @Singleton that owns the three pure machines (voice room / stage /
 * space), the real [VoiceRoomAudioEngine], the caption pipeline and the
 * foreground service; consumes the 7 voice/stage/space [PulseEvent] cases +
 * relay connection truth + the persisted captions pref; executes every emit
 * through [PulseRepository]. ViewModels come and go with surfaces - the room
 * membership survives (VR-1); the engine tears down on leave/process death.
 *
 * HARDWARE GATE: capture, playback and routing require runtime and device acceptance -
 * perceptual audio claims are PHYSICAL DEVICE: PENDING (spec §3).
 */
@Singleton
class VoiceRoomsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: PulseRepository,
    private val prefs: PulsePrefsStore,
) {
    enum class RoomKind { VOICE, STAGE, SPACE }

    /** The surface the overlay currently renders (null = closed). */
    data class OpenSurface(val conversationId: String, val kind: RoomKind)

    /** One ephemeral live caption (VR-7: keep last 3, 7s TTL). */
    data class VoiceCaption(val id: String, val name: String, val color: String, val text: String, val atMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = VoiceRoomAudioEngine(context)
    private val seats = RoomSeatOwnership()
    private val _audioIssue = MutableStateFlow<String?>(null)
    val audioIssue = _audioIssue.asStateFlow()
    private val _microphoneEnabled = MutableStateFlow(false)
    val microphoneEnabled = _microphoneEnabled.asStateFlow()
    var serviceToken: String? = null
        private set
    val needsMicrophoneService get() = _microphoneEnabled.value
    val hasAudioRoom get() = seats.roomId != null
    private var sessionRepo = repo.boundToCurrentAccount()
    private val ptt = PushToTalkPolicy()

    private val voiceMachine = VoiceRoomStateMachine()
    private val stageMachine = StageRoomStateMachine(selfId = repo.viewerId ?: "", clock = SystemClock::elapsedRealtime)
    private val spaceMachine = SpaceBoardStateMachine(clock = SystemClock::elapsedRealtime)
    private val captions = CaptionWindowAccumulator()

    val voiceState: StateFlow<VoiceRoomStateMachine.State> = voiceMachine.state
    val stageState: StateFlow<StageRoomStateMachine.State> = stageMachine.state
    val spaceState: StateFlow<SpaceBoardStateMachine.State> = spaceMachine.state

    private val _surface = MutableStateFlow<OpenSurface?>(null)
    val surface: StateFlow<OpenSurface?> = _surface.asStateFlow()

    private val _captionList = MutableStateFlow<List<VoiceCaption>>(emptyList())
    val captionList: StateFlow<List<VoiceCaption>> = _captionList.asStateFlow()

    private val _captionsEnabled = MutableStateFlow(false)
    val captionsEnabled: StateFlow<Boolean> = _captionsEnabled.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** The wire identity every join carries (adopted from the session). */
    private var identity = PulseVoiceUser(id = repo.viewerId ?: "", name = "", username = null, color = "")

    /** PTT press bookkeeping (hold ≥260ms vs tap-latch - web parity, VR-3). */
    private var lastVoiceResyncMs = 0L
    private var spaceMoveJob: Job? = null
    private var audioGeneration = 0L
    private var captionGeneration = 0L

    init {
        audio.onFocusLost = {
            leaveAll()
            _audioIssue.value = "Audio was interrupted by another app. Rejoin when ready."
        }
        // Persisted captions toggle (web parity pref pulse-voice-captions →
        // voice.captions). Turning it OFF also drops pending audio.
        scope.launch {
            prefs.voiceCaptions.collect { on ->
                _captionsEnabled.value = on
                if (!on) captions.clear()
            }
        }
        // The 7 S→C voice/stage/space signals → the machines + audio.
        scope.launch {
            repo.events().collect { event ->
                when (event) {
                    is PulseEvent.VoiceRoster -> onVoiceRoster(event.payload)
                    is PulseEvent.VoicePtt -> onVoicePtt(event.payload)
                    is PulseEvent.VoiceChunk -> {
                        if (acceptsRoomAudio(activeAudioRoom(), event.payload.conversationId, identity.id, event.payload.userId)) {
                            audio.onPeerChunk(event.payload.userId, event.payload.seq, event.payload.data)
                        }
                    }
                    is PulseEvent.VoiceTranscript -> {
                        if (activeAudioRoom() == event.payload.conversationId) addCaption(event.payload.userId, event.payload.text)
                    }
                    is PulseEvent.StageState -> onStageState(event.payload)
                    is PulseEvent.StageEnded -> onStageEnded(event.payload)
                    is PulseEvent.SpaceState -> onSpaceState(event.payload)
                    else -> Unit
                }
            }
        }
        // Relay connection truth → all three machines (VR-8 / ST-8 / SP-5).
        scope.launch {
            repo.observeConnected().collect { onConnection(it) }
        }
        // Caption TTL sweep - 1s cadence, 7s TTL (VR-7).
        scope.launch {
            while (isActive) {
                delay(1_000)
                sweepCaptions()
            }
        }

    }

    /** Capture the room lease before audio threads enqueue work on the main owner. */
    private fun configureAudioCallbacks(conversationId: String) {
        val ticket = audioGeneration
        val userId = identity.id
        val api = sessionRepo
        fun ownsSession() = ticket == audioGeneration && activeAudioRoom() == conversationId && identity.id == userId
        audio.onChunk = { seq, base64 ->
            scope.launch {
                if (ownsSession() && _connected.value && _microphoneEnabled.value) runCatching {
                    api.emitVoiceChunk(conversationId, userId, seq, base64)
                }
            }
        }
        audio.captionSink = { samples ->
            scope.launch { if (ownsSession()) onCaptionSamples(samples) }
        }
        audio.onTransmitEnd = {
            scope.launch {
                if (ownsSession() && _microphoneEnabled.value) {
                    when (val offer = captions.onTransmitEnd(_captionsEnabled.value)) {
                        is CaptionWindowAccumulator.Offer.Window -> transcribeWindow(offer.samples)
                        else -> Unit
                    }
                }
            }
        }
        audio.onCaptureError = { message ->
            scope.launch {
                if (ownsSession()) {
                    stopTransmit(emit = true)
                    seats.revokeMicrophone()
                    captionGeneration++; captions.clear()
                    _microphoneEnabled.value = false
                    startRoomService()
                    _audioIssue.value = message
                }
            }
        }
    }

    // identity (adopted from MainActivity, CallEngine pattern)

    fun setIdentity(id: String?, name: String?, color: String?) {
        val cleanId = id.orEmpty()
        val changed = cleanId != identity.id
        if (changed) leaveAll()
        sessionRepo = repo.boundToCurrentAccount()
        identity = PulseVoiceUser(id = cleanId, name = name.orEmpty(), username = null, color = color.orEmpty())
        stageMachine.setSelfId(cleanId)
        if (changed) {
            audioGeneration++
            captions.clear()
            lastVoiceResyncMs = 0
        }
    }

    // surface control (the overlay host)

    /** Opens the voice room surface - membership itself needs an explicit Join (VR-1). */
    private fun canOpenAudioRoom(cid: String): Boolean {
        val owned = seats.roomId
        if (owned == null || owned == cid) return true
        _audioIssue.value = "Leave your current live room first."
        _surface.value = OpenSurface(owned, if (seats.stageJoined) RoomKind.STAGE else RoomKind.VOICE)
        return false
    }

    fun openVoice(conversationId: String) {
        if (!canOpenAudioRoom(conversationId)) return
        if (seats.stageJoined) seats.joinVoice(conversationId)
        cancelPtt()
        seats.cancelPermission()
        _surface.value = OpenSurface(conversationId, RoomKind.VOICE)
    }

    /** Opening the stage begins the join ALWAYS as listener (ST-1). */
    fun openStage(conversationId: String) {
        if (!canOpenAudioRoom(conversationId)) return
        cancelPtt()
        seats.cancelPermission()
        _surface.value = OpenSurface(conversationId, RoomKind.STAGE)
        joinStage(conversationId)
    }

    /** Opening the space joins on connect (SP-1) - the machine gates the emit. */
    fun openSpace(conversationId: String) {
        val old = spaceMachine.state.value
        if (old.status != SpaceBoardStateMachine.Status.IDLE && old.conversationId != conversationId) leaveSpace(old.conversationId)
        if (old.status != SpaceBoardStateMachine.Status.IDLE && old.conversationId == conversationId) {
            cancelPtt()
            seats.cancelPermission()
            _surface.value = OpenSurface(conversationId, RoomKind.SPACE)
            return
        }
        cancelPtt()
        seats.cancelPermission()
        spaceMoveJob?.cancel()
        _surface.value = OpenSurface(conversationId, RoomKind.SPACE)
        spaceMachine.dispatch(SpaceBoardStateMachine.Event.JoinRequested(conversationId, identity.id))
        if (_connected.value) dispatchSpaceConnected(conversationId)
    }

    /** Surface close: voice membership SURVIVES (VR-1); stage/space leave (ST-7/SP-1). */
    fun closeSurface() {
        cancelPtt()
        seats.cancelPermission()
        val open = _surface.value ?: return
        when (open.kind) {
            RoomKind.VOICE -> Unit
            RoomKind.STAGE -> leaveStage(open.conversationId)
            RoomKind.SPACE -> leaveSpace(open.conversationId)
        }
        _surface.value = null
    }

    private fun activeAudioRoom(): String? = seats.roomId

    private fun prepareAudio(conversationId: String): Boolean {
        if (conversationId.isBlank() || identity.id.isBlank()) return false
        if (seats.roomId != null) {
            if (seats.roomId == conversationId) return true
            _audioIssue.value = "Leave your current live room first."
            return false
        }
        if (!audio.start()) {
            _audioIssue.value = "Audio is unavailable or in use by another session."
            return false
        }
        _audioIssue.value = null
        serviceToken = UUID.randomUUID().toString()
        audioGeneration++
        captions.clear()
        configureAudioCallbacks(conversationId)
        val ticket = audioGeneration
        audio.onPlaybackError = { message -> scope.launch { if (ticket == audioGeneration) _audioIssue.value = message } }
        voiceMachine.dispatch(VoiceRoomStateMachine.Event.JoinRequested(conversationId))
        return true
    }

    fun joinVoice(conversationId: String) {
        if (!prepareAudio(conversationId)) return
        seats.joinVoice(conversationId)
        emitVoiceJoin(conversationId)
        startRoomService()
    }

    fun leaveVoice() {
        val cid = seats.roomId ?: return
        cancelPtt()
        if (seats.leaveVoice()) releaseAudioSeat(cid)
        _microphoneEnabled.value = false
        captionGeneration++; captions.clear()
        if (hasAudioRoom) startRoomService()
    }

    private fun releaseAudioSeat(cid: String) {
        val api = sessionRepo
        scope.launch { runCatching { api.emitVoiceLeave(cid) } }
        audioGeneration++
        serviceToken = null
        audio.teardown()
        voiceMachine.dispatch(VoiceRoomStateMachine.Event.Left)
        captions.clear()
        _captionList.value = emptyList()
        _microphoneEnabled.value = false
        VoiceForegroundService.stop(context)
    }

    fun leaveAll() {
        val stage = stageMachine.state.value
        if (stage.joined) leaveStage(stage.conversationId)
        leaveVoice()
        val space = spaceMachine.state.value
        if (space.status != SpaceBoardStateMachine.Status.IDLE) leaveSpace(space.conversationId)
        seats.clear()
        _surface.value = null
    }

    fun onServiceFailure(token: String?, message: String) {
        if (token == null || token != serviceToken) return
        leaveAll()
        _audioIssue.value = message
    }

    private fun startRoomService() {
        runCatching {
            VoiceForegroundService.ensureChannel(context)
            VoiceForegroundService.start(context)
        }.onFailure { onServiceFailure(serviceToken, "Could not keep the live room active. Try joining again.") }
    }

    fun cancelMicrophoneRequest() = seats.cancelPermission()
    fun requestMicrophone(conversationId: String): Long? {
        if (_surface.value?.conversationId != conversationId || seats.roomId != conversationId) return null
        return seats.requestMicrophone()
    }
    fun resolveMicrophone(ticket: Long, granted: Boolean) {
        if (!seats.acceptsPermission(ticket)) return
        if (seats.resolveMicrophone(ticket, granted)) {
            _microphoneEnabled.value = true
            _audioIssue.value = null
            startRoomService()
        } else if (!granted) {
            _audioIssue.value = "Microphone permission is off. You can still listen."
        }
    }

    fun setMuted(muted: Boolean) {
        for (effect in voiceMachine.dispatch(VoiceRoomStateMachine.Event.MuteChanged(muted))) {
            when (effect) {
                is VoiceRoomStateMachine.Effect.StopTransmission -> stopTransmit(emit = true)
                is VoiceRoomStateMachine.Effect.ResetPlayback -> effect.userIds.forEach(audio::dropPeer)
            }
        }
    }

    fun setCaptions(enabled: Boolean) {
        if (enabled != _captionsEnabled.value) captionGeneration++
        scope.launch { runCatching { prefs.setVoiceCaptions(enabled) } }
        _captionsEnabled.value = enabled
        if (!enabled) captions.clear()
    }

    /** Press begins actual capture; a short release latches, a hold release stops. */
    fun pttDown() {
        if (!mayTransmit()) return
        setTransmitting(ptt.press(voiceMachine.state.value.transmitting))
    }
    fun pttUp(heldMs: Long) {
        if (!ptt.release(heldMs)) stopTransmit(emit = true)
    }
    fun cancelPtt() { ptt.cancel(); stopTransmit(emit = true) }
    fun togglePtt() { if (voiceMachine.state.value.transmitting) cancelPtt() else setTransmitting(true) }
    private fun mayTransmit(): Boolean = seats.mayTransmit && _connected.value &&
        !voiceMachine.state.value.micMuted && _surface.value?.kind != RoomKind.SPACE &&
        _surface.value?.conversationId == seats.roomId

    private fun setTransmitting(on: Boolean) {
        if (!on) { stopTransmit(emit = true); return }
        if (!mayTransmit() || voiceMachine.state.value.transmitting) return
        val cid = seats.roomId ?: return
        if (!audio.startTransmit(false)) { _audioIssue.value = "Microphone could not start"; return }
        voiceMachine.dispatch(VoiceRoomStateMachine.Event.TransmitChanged(true))
        val me = identity.id
        val api = sessionRepo
        scope.launch { runCatching { api.emitVoicePtt(cid, me, true) } }
    }

    private fun stopTransmit(emit: Boolean) {
        val wasOn = voiceMachine.state.value.transmitting
        voiceMachine.dispatch(VoiceRoomStateMachine.Event.TransmitChanged(false))
        audio.stopTransmit()
        val cid = seats.roomId ?: return
        val me = identity.id
        val api = sessionRepo
        if (emit && wasOn) scope.launch { runCatching { api.emitVoicePtt(cid, me, false) } }
    }

    // stage actions

    fun joinStage(conversationId: String) {
        if (seats.stageJoined && seats.roomId == conversationId) return
        if (!prepareAudio(conversationId)) return
        cancelPtt()
        seats.joinStage(conversationId)
        captionGeneration++; captions.clear()
        _microphoneEnabled.value = false
        stageMachine.dispatchJoinRequested(conversationId)
        val api = sessionRepo
        val me = identity
        val ticket = audioGeneration
        scope.launch {
            runCatching { api.emitStageJoin(conversationId, me, asHost = false) }
            if (ticket == audioGeneration && seats.stageJoined) runCatching { api.emitVoiceJoin(conversationId, me) }
        }
        startRoomService()
    }

    /** ST-7: the host seat is empty and I am joined - claim it via asHost:true. */
    fun claimHost() {
        val s = stageMachine.state.value
        if (!s.canClaimHost) return
        scope.launch { runCatching { repo.emitStageJoin(s.conversationId, identity, asHost = true) } }
    }

    fun raiseHand() {
        for (effect in stageMachine.toggleHand()) {
            if (effect is StageRoomStateMachine.Effect.EmitHand) {
                val cid = stageMachine.state.value.conversationId
                scope.launch { runCatching { repo.emitStageHand(cid, identity.id, effect.raised) } }
            }
        }
    }

    /** Host promotes a raised hand into the speaker row (ST-3). */
    fun approveHand(targetUserId: String) {
        if (!stageMachine.state.value.isHost) return
        val cid = stageMachine.state.value.conversationId
        scope.launch { runCatching { repo.emitStageApprove(cid, identity.id, targetUserId) } }
    }

    /** Host-only demote (speaker → listener) or hand dismissal (ST-3/ST-4). */
    fun demote(targetUserId: String) {
        if (!stageMachine.state.value.isHost) return
        val cid = stageMachine.state.value.conversationId
        scope.launch { runCatching { repo.emitStageMute(cid, identity.id, targetUserId) } }
    }

    /**
     * Two-tap End confirm (ST-5). Returns true when the second tap fired -
     * the caller then closes the surface (stage:ended arrives for everyone).
     */
    fun endStage(): Boolean {
        val s = stageMachine.state.value
        if (!s.isHost) return false
        if (stageMachine.armEndConfirm()) {
            scope.launch { runCatching { repo.emitStageEnd(s.conversationId, identity.id) } }
            return true
        }
        return false
    }

    fun isEndConfirmArmed(): Boolean = stageMachine.isEndConfirmArmed()

    fun disarmEndConfirm() = stageMachine.disarmEndConfirm()

    fun leaveStage(conversationId: String) = exitStage(conversationId, ended = false)

    private fun exitStage(conversationId: String, ended: Boolean) {
        if (!seats.stageJoined || seats.roomId != conversationId) return
        cancelPtt()
        val api = sessionRepo
        if (!ended) scope.launch { runCatching { api.emitStageLeave(conversationId) } }
        if (ended) stageMachine.dispatchEnded(conversationId) else stageMachine.leave()
        if (seats.leaveStage()) releaseAudioSeat(conversationId)
        _microphoneEnabled.value = false
        captionGeneration++; captions.clear()
        if (hasAudioRoom) startRoomService()
    }

    // space actions

    fun joinSpace(conversationId: String) {
        dispatchSpaceConnected(conversationId)
    }

    fun moveSpace(x: Double, y: Double) {
        emitSpaceMoves(spaceMachine.dispatch(SpaceBoardStateMachine.Event.LocalMove(x, y)))
        spaceMoveJob?.cancel()
        val cid = spaceMachine.state.value.conversationId
        spaceMoveJob = scope.launch {
            delay(SpaceBoardStateMachine.MOVE_THROTTLE_MS)
            if (spaceMachine.state.value.conversationId == cid) {
                emitSpaceMoves(spaceMachine.dispatch(SpaceBoardStateMachine.Event.FlushMove))
            }
        }
    }

    private fun emitSpaceMoves(effects: List<SpaceBoardStateMachine.Effect>) {
        for (effect in effects) {
            if (effect is SpaceBoardStateMachine.Effect.EmitMove) {
                val cid = spaceMachine.state.value.conversationId
                scope.launch { runCatching { repo.emitSpaceMove(cid, effect.x, effect.y) } }
            }
        }
    }

    /** Honest retry after the ERROR terminal state (SP-5, FIX #5). */
    fun retrySpace() {
        val cid = spaceMachine.state.value.conversationId
        spaceMachine.reset()
        if (cid.isNotBlank()) openSpace(cid)
    }

    fun leaveSpace(conversationId: String) {
        if (spaceMachine.state.value.conversationId != conversationId) return
        spaceMoveJob?.cancel()
        val joined = spaceMachine.state.value.status != SpaceBoardStateMachine.Status.IDLE
        val api = sessionRepo
        if (joined) scope.launch { runCatching { api.emitSpaceLeave(conversationId) } }
        spaceMachine.reset()
    }

    // S→C handlers

    private fun onVoiceRoster(payload: app.pulse.protocol.VoiceRosterPayload) {
        val peers = payload.roster.map {
            VoiceRoomStateMachine.Peer(id = it.userId, name = it.name, username = it.username, color = it.color)
        }
        for (effect in voiceMachine.dispatch(VoiceRoomStateMachine.Event.RosterArrived(payload.conversationId, peers))) {
            when (effect) {
                is VoiceRoomStateMachine.Effect.ResetPlayback -> effect.userIds.forEach(audio::dropPeer)
                is VoiceRoomStateMachine.Effect.StopTransmission -> stopTransmit(emit = true)
            }
        }
        // FIX #3 watch: my stage voice seat force-removed (demote) → re-join.
        val stage = stageMachine.state.value
        if (stage.joined && stage.conversationId == payload.conversationId &&
            payload.roster.none { it.userId == identity.id }
        ) {
            for (effect in stageMachine.noteVoiceSeatLost()) {
                if (effect is StageRoomStateMachine.Effect.EmitVoiceRejoin) emitVoiceJoin(effect.conversationId)
            }
        }
    }

    private fun onVoicePtt(payload: app.pulse.protocol.VoicePttPayload) {
        voiceMachine.dispatch(
            VoiceRoomStateMachine.Event.PttArrived(payload.conversationId, payload.userId, payload.active),
        )
        stageMachine.dispatchPtt(payload.conversationId, payload.userId, payload.active)
    }

    private fun onStageState(payload: app.pulse.protocol.StageStatePayload) {
        val room = parseStageRoomState(payload.state) ?: return
        if (!seats.stageJoined || payload.conversationId != seats.roomId) return
        val wasEnabled = _microphoneEnabled.value
        val effects = stageMachine.dispatchState(payload.conversationId, room)
        seats.stageRole(stageMachine.state.value.canTransmit)
        if (!seats.mayTransmit) {
            cancelPtt()
            _microphoneEnabled.value = false
            if (wasEnabled) { captionGeneration++; captions.clear(); startRoomService() }
        }
        for (effect in effects) {
            when (effect) {
                is StageRoomStateMachine.Effect.ResyncJoin -> {
                    // ST-8: re-join with remembered host intent; the voice seat
                    // rides along (FIX #3).
                    val me = identity
                    val api = sessionRepo
                    val ticket = audioGeneration
                    scope.launch {
                        runCatching { api.emitStageJoin(effect.conversationId, me, asHost = effect.asHost) }
                        if (ticket == audioGeneration && seats.stageJoined) runCatching { api.emitVoiceJoin(effect.conversationId, me) }
                    }
                }
                is StageRoomStateMachine.Effect.EmitVoiceRejoin -> emitVoiceJoin(effect.conversationId)
                is StageRoomStateMachine.Effect.EmitHand -> Unit // not produced by dispatchState
            }
        }
    }

    private fun onStageEnded(payload: app.pulse.protocol.StageEndedPayload) {
        if (!seats.stageJoined || seats.roomId != payload.conversationId) return
        exitStage(payload.conversationId, ended = true)
        if (_surface.value?.kind == RoomKind.STAGE && _surface.value?.conversationId == payload.conversationId) _surface.value = null
    }

    private fun onSpaceState(payload: app.pulse.protocol.SpaceStatePayload) {
        val board = parseSpaceBoardState(payload.state) ?: return
        spaceMachine.dispatch(
            SpaceBoardStateMachine.Event.BoardArrived(
                payload.conversationId,
                board.players.map { SpaceBoardStateMachine.Player(it.id, it.name, it.color, it.x, it.y) },
            ),
        )
    }

    private fun onConnection(connected: Boolean) {
        _connected.value = connected
        if (!connected) { cancelPtt(); captionGeneration++; captions.clear() }
        // Voice machine: honest offline transmit + ptt gate.
        for (effect in voiceMachine.dispatch(VoiceRoomStateMachine.Event.ConnectionChanged(connected))) {
            if (effect is VoiceRoomStateMachine.Effect.StopTransmission) stopTransmit(emit = true)
        }
        // VR-8: on reconnect, re-emit voice:join if still joined (rate-limited).
        val voice = voiceMachine.state.value
        val now = SystemClock.elapsedRealtime()
        if (connected && voice.joined && now - lastVoiceResyncMs >= VOICE_RESYNC_MS) {
            lastVoiceResyncMs = now
            emitVoiceJoin(voice.conversationId)
        }
        // ST-8: stage re-join rides the same connection truth; the missing-me
        // resync inside dispatchState rate-limits itself.
        val stage = stageMachine.state.value
        stageMachine.dispatchConnection(connected)
        if (connected && stage.joined) {
            val api = sessionRepo
            val me = identity
            val ticket = audioGeneration
            scope.launch {
                runCatching { api.emitStageJoin(stage.conversationId, me, asHost = stage.wasHost) }
                if (ticket == audioGeneration && seats.stageJoined) runCatching { api.emitVoiceJoin(stage.conversationId, me) }
            }
        }
        // SP-5: reconnect attempts counted; the ERROR terminal state is the
        // honest outcome after the budget (FIX #5).
        if (connected) {
            val space = spaceMachine.state.value
            if (space.status == SpaceBoardStateMachine.Status.JOINING ||
                space.status == SpaceBoardStateMachine.Status.CONNECTED
            ) {
                dispatchSpaceConnected(space.conversationId)
            }
        } else {
            spaceMachine.dispatch(SpaceBoardStateMachine.Event.Disconnected)
        }
    }

    private fun dispatchSpaceConnected(conversationId: String) {
        if (conversationId.isBlank()) return
        for (effect in spaceMachine.dispatch(SpaceBoardStateMachine.Event.Connected(conversationId))) {
            if (effect is SpaceBoardStateMachine.Effect.EmitJoin) {
                val cid = spaceMachine.state.value.conversationId
                scope.launch { runCatching { repo.emitSpaceJoin(cid, identity) } }
            }
        }
    }

    private fun emitVoiceJoin(conversationId: String) {
        if (conversationId.isBlank() || identity.id.isBlank()) return
        val api = sessionRepo
        val me = identity
        scope.launch { runCatching { api.emitVoiceJoin(conversationId, me) } }
    }

    // captions pipeline (VR-7)

    private fun onCaptionSamples(samples: ShortArray) {
        when (val offer = captions.offer(samples, voiceMachine.state.value.transmitting, _captionsEnabled.value)) {
            is CaptionWindowAccumulator.Offer.Window -> transcribeWindow(offer.samples)
            else -> Unit
        }
    }

    /**
     * One 4s window → 44-byte WAV → POST /api/voice/transcribe (60s timeout)
     * → voice:transcript. Single-flight: [captions.busy] holds until done.
     * Failures are honest silence - the window is never retried.
     */
    private fun transcribeWindow(samples: ShortArray) {
        val captionTicket = captionGeneration
        val ticket = audioGeneration
        val cid = activeAudioRoom()
        val userId = identity.id
        val api = sessionRepo
        scope.launch {
            try {
                if (cid.isNullOrBlank() || userId.isBlank() || ticket != audioGeneration || captionTicket != captionGeneration) return@launch
                val audioBase64 = WavEncoder.toBase64(samples)
                val transcript = runCatching {
                    api.transcribeVoice(cid, userId, audioBase64)
                }.getOrNull()?.getOrNull()?.transcript?.takeIf { it.isNotBlank() } ?: return@launch
                if (ticket != audioGeneration || captionTicket != captionGeneration || cid != activeAudioRoom() || userId != identity.id || !_captionsEnabled.value || !_microphoneEnabled.value) return@launch
                runCatching { api.emitVoiceTranscript(cid, userId, transcript.take(280)) }
            } finally {
                if (ticket == audioGeneration && captionTicket == captionGeneration) captions.endFlush()
            }
        }
    }

    private fun addCaption(userId: String, text: String) {
        if (text.isBlank()) return
        // The sender's own caption comes back through the same event - it IS
        // the echo (voice:transcript goes to the whole room, sender included).
        val roster = voiceMachine.state.value.roster
        val speaker = roster.firstOrNull { it.id == userId }
        val caption = VoiceCaption(
            id = "$userId-${SystemClock.elapsedRealtime()}",
            name = speaker?.name ?: userId,
            color = speaker?.color ?: "",
            text = text,
            atMs = SystemClock.elapsedRealtime(),
        )
        _captionList.value = (_captionList.value + caption).takeLast(MAX_CAPTIONS)
        sweepCaptions()
    }

    private fun sweepCaptions() {
        val list = _captionList.value
        if (list.isEmpty()) return
        val cutoff = SystemClock.elapsedRealtime() - CAPTION_TTL_MS
        val fresh = list.filter { it.atMs >= cutoff }.takeLast(MAX_CAPTIONS)
        if (fresh.size != list.size) _captionList.value = fresh
    }

    companion object {
        /** Web parity hold/latch threshold (VR-3). */
        const val PTT_HOLD_MS = 260L
        const val VOICE_RESYNC_MS = 2_000L
        const val CAPTION_TTL_MS = 7_000L
        const val MAX_CAPTIONS = 3
        /** Two-tap End confirm window (ST-5). */
        const val STAGE_END_CONFIRM_MS = StageRoomStateMachine.END_CONFIRM_MS

        /** Exposed for tests/diagnostics - 250ms wire blocks. */
        const val BLOCK_SAMPLES = VoicePcm.BLOCK_SAMPLES
        const val PRE_ROLL_MS = PlaybackScheduler.PRE_ROLL_MS
    }
}
