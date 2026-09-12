package app.pulse.feature.voice

import android.content.Context
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
 * Wave 5 — the session-scoped voice-rooms engine (the CallEngine pattern):
 * ONE @Singleton that owns the three pure machines (voice room / stage /
 * space), the real [VoiceRoomAudioEngine], the caption pipeline and the
 * foreground service; consumes the 7 voice/stage/space [PulseEvent] cases +
 * relay connection truth + the persisted captions pref; executes every emit
 * through [PulseRepository]. ViewModels come and go with surfaces — the room
 * membership survives (VR-1); the engine tears down on leave/process death.
 *
 * HARDWARE GATE: capture, playback and routing here are CODE VERIFIED only —
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

    private val voiceMachine = VoiceRoomStateMachine()
    private val stageMachine = StageRoomStateMachine(selfId = repo.viewerId ?: "")
    private val spaceMachine = SpaceBoardStateMachine()
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

    /** PTT press bookkeeping (hold ≥260ms vs tap-latch — web parity, VR-3). */
    private var pttPressAtMs = 0L
    private var lastVoiceResyncMs = 0L

    init {
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
                    is PulseEvent.VoiceChunk ->
                        audio.onPeerChunk(event.payload.userId, event.payload.seq, event.payload.data)
                    is PulseEvent.VoiceTranscript ->
                        addCaption(event.payload.userId, event.payload.text)
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
        // Caption TTL sweep — 1s cadence, 7s TTL (VR-7).
        scope.launch {
            while (isActive) {
                delay(1_000)
                sweepCaptions()
            }
        }

        // Audio callbacks — capture → chunker → voice:chunk, caption sink.
        audio.onChunk = { seq, base64 ->
            val cid = voiceMachine.state.value.conversationId
            if (cid.isNotBlank()) scope.launch { repo.emitVoiceChunk(cid, identity.id, seq, base64) }
        }
        audio.captionSink = { samples -> onCaptionSamples(samples) }
        audio.onTransmitEnd = {
            when (val offer = captions.onTransmitEnd(_captionsEnabled.value)) {
                is CaptionWindowAccumulator.Offer.Window -> transcribeWindow(offer.samples)
                else -> Unit
            }
        }
        audio.onCaptureError = { message ->
            voiceMachine.dispatch(VoiceRoomStateMachine.Event.Failed(message))
        }
    }

    // ── identity (adopted from MainActivity, CallEngine pattern) ──

    fun setIdentity(id: String?, name: String?, color: String?) {
        val cleanId = id.orEmpty()
        val changed = cleanId != identity.id
        identity = PulseVoiceUser(id = cleanId, name = name.orEmpty(), username = null, color = color.orEmpty())
        stageMachine.setSelfId(cleanId)
        if (changed) lastVoiceResyncMs = 0
    }

    // ── surface control (the overlay host) ──────────────────────

    /** Opens the voice room surface — membership itself needs an explicit Join (VR-1). */
    fun openVoice(conversationId: String) {
        _surface.value = OpenSurface(conversationId, RoomKind.VOICE)
    }

    /** Opening the stage begins the join ALWAYS as listener (ST-1). */
    fun openStage(conversationId: String) {
        _surface.value = OpenSurface(conversationId, RoomKind.STAGE)
        if (stageMachine.state.value.conversationId != conversationId || !stageMachine.state.value.joined) {
            stageMachine.dispatchJoinRequested(conversationId)
        }
    }

    /** Opening the space joins on connect (SP-1) — the machine gates the emit. */
    fun openSpace(conversationId: String) {
        _surface.value = OpenSurface(conversationId, RoomKind.SPACE)
        spaceMachine.dispatch(SpaceBoardStateMachine.Event.JoinRequested(conversationId, identity.id))
        if (_connected.value) dispatchSpaceConnected(conversationId)
    }

    /** Surface close: voice membership SURVIVES (VR-1); stage/space leave (ST-7/SP-1). */
    fun closeSurface() {
        val open = _surface.value ?: return
        when (open.kind) {
            RoomKind.VOICE -> Unit
            RoomKind.STAGE -> leaveStage(open.conversationId)
            RoomKind.SPACE -> leaveSpace(open.conversationId)
        }
        _surface.value = null
    }

    // ── voice room actions ──────────────────────────────────────

    fun joinVoice(conversationId: String) {
        voiceMachine.dispatch(VoiceRoomStateMachine.Event.JoinRequested(conversationId))
        emitVoiceJoin(conversationId)
        audio.start()
        VoiceForegroundService.ensureChannel(context)
        VoiceForegroundService.start(context, "Live voice room active")
    }

    fun leaveVoice() {
        val cid = voiceMachine.state.value.conversationId
        stopTransmit(emit = cid.isNotBlank())
        if (cid.isNotBlank()) scope.launch { runCatching { repo.emitVoiceLeave(cid) } }
        audio.teardown()
        voiceMachine.dispatch(VoiceRoomStateMachine.Event.Left)
        captions.clear()
        _captionList.value = emptyList()
        VoiceForegroundService.stop(context)
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
        scope.launch { runCatching { prefs.setVoiceCaptions(enabled) } }
        _captionsEnabled.value = enabled
        if (!enabled) captions.clear()
    }

    /** PTT press began — the hold/tap decision happens on release (VR-3). */
    fun pttDown() {
        pttPressAtMs = System.currentTimeMillis()
    }

    /**
     * PTT press ended after [heldMs]: ≥260ms was a HOLD (stop on release);
     * a shorter tap TOGGLES the latch (and a latched tap stops).
     */
    fun pttUp(heldMs: Long) {
        val gate = currentPttGate() ?: return
        if (heldMs >= PTT_HOLD_MS) {
            setTransmitting(false)
        } else {
            setTransmitting(!gate.transmitting)
        }
    }

    /** Accessibility/keyboard entry — a pure toggle (VR-3). */
    fun togglePtt() {
        val gate = currentPttGate() ?: return
        setTransmitting(!gate.transmitting)
    }

    private fun currentPttGate(): VoiceRoomStateMachine.State? {
        val surface = _surface.value ?: return null
        return when (surface.kind) {
            RoomKind.VOICE -> voiceMachine.state.value.takeIf { it.pttEnabled }
            RoomKind.STAGE ->
                stageMachine.state.value.takeIf { it.canTransmit && _connected.value }?.let {
                    voiceMachine.state.value.takeIf { v -> !v.micMuted }
                }
            RoomKind.SPACE -> null // space has no mic
        }
    }

    private fun setTransmitting(on: Boolean) {
        val state = voiceMachine.state.value
        if (on == state.transmitting) return
        if (on && (!state.pttEnabled && !stageCanTransmit())) return
        voiceMachine.dispatch(VoiceRoomStateMachine.Event.TransmitChanged(on))
        val cid = state.conversationId.ifBlank { stageMachine.state.value.conversationId }
        if (on) {
            audio.startTransmit(state.micMuted)
            if (cid.isNotBlank()) scope.launch { runCatching { repo.emitVoicePtt(cid, identity.id, true) } }
        } else {
            audio.stopTransmit()
            if (cid.isNotBlank()) scope.launch { runCatching { repo.emitVoicePtt(cid, identity.id, false) } }
        }
    }

    private fun stageCanTransmit(): Boolean {
        val s = stageMachine.state.value
        return s.canTransmit && _connected.value
    }

    /** Gate-driven force-stop (mute/disconnect effects) — never emits for a blank room. */
    private fun stopTransmit(emit: Boolean) {
        if (voiceMachine.state.value.transmitting) {
            voiceMachine.dispatch(VoiceRoomStateMachine.Event.TransmitChanged(false))
        }
        audio.stopTransmit()
        val cid = voiceMachine.state.value.conversationId
        if (emit && cid.isNotBlank()) scope.launch { runCatching { repo.emitVoicePtt(cid, identity.id, false) } }
    }

    // ── stage actions ───────────────────────────────────────────

    fun joinStage(conversationId: String) {
        stageMachine.dispatchJoinRequested(conversationId)
        scope.launch {
            runCatching { repo.emitStageJoin(conversationId, identity, asHost = false) }
            // FIX #3: EVERY joined stage member holds a voice seat — join it
            // alongside the stage seat so the audience receives audio.
            runCatching { repo.emitVoiceJoin(conversationId, identity) }
        }
        audio.start()
    }

    /** ST-7: the host seat is empty and I am joined — claim it via asHost:true. */
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
        val cid = stageMachine.state.value.conversationId
        scope.launch { runCatching { repo.emitStageApprove(cid, identity.id, targetUserId) } }
    }

    /** Host-only demote (speaker → listener) or hand dismissal (ST-3/ST-4). */
    fun demote(targetUserId: String) {
        val cid = stageMachine.state.value.conversationId
        scope.launch { runCatching { repo.emitStageMute(cid, identity.id, targetUserId) } }
    }

    /**
     * Two-tap End confirm (ST-5). Returns true when the second tap fired —
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

    fun leaveStage(conversationId: String) {
        val wasJoined = stageMachine.state.value.joined
        if (!wasJoined) return
        scope.launch {
            runCatching { repo.emitStageLeave(conversationId) }
            runCatching { repo.emitVoiceLeave(conversationId) }
        }
        stopTransmit(emit = false)
        stageMachine.leave()
        audio.teardown()
        if (voiceMachine.state.value.conversationId == conversationId) {
            voiceMachine.dispatch(VoiceRoomStateMachine.Event.Left)
        }
    }

    // ── space actions ───────────────────────────────────────────

    fun joinSpace(conversationId: String) {
        dispatchSpaceConnected(conversationId)
    }

    fun moveSpace(x: Double, y: Double) {
        for (effect in spaceMachine.dispatch(SpaceBoardStateMachine.Event.LocalMove(x, y))) {
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
        val joined = spaceMachine.state.value.status != SpaceBoardStateMachine.Status.IDLE
        if (joined) scope.launch { runCatching { repo.emitSpaceLeave(conversationId) } }
        spaceMachine.reset()
    }

    // ── S→C handlers ────────────────────────────────────────────

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
        for (effect in stageMachine.dispatchState(payload.conversationId, room)) {
            when (effect) {
                is StageRoomStateMachine.Effect.ResyncJoin -> {
                    // ST-8: re-join with remembered host intent; the voice seat
                    // rides along (FIX #3).
                    val me = identity
                    scope.launch {
                        runCatching { repo.emitStageJoin(effect.conversationId, me, asHost = effect.asHost) }
                        runCatching { repo.emitVoiceJoin(effect.conversationId, me) }
                    }
                }
                is StageRoomStateMachine.Effect.EmitVoiceRejoin -> emitVoiceJoin(effect.conversationId)
                is StageRoomStateMachine.Effect.EmitHand -> Unit // not produced by dispatchState
            }
        }
    }

    private fun onStageEnded(payload: app.pulse.protocol.StageEndedPayload) {
        val stage = stageMachine.state.value
        if (stage.conversationId != payload.conversationId) return
        // Full local teardown for every member (ST-5): transmission, audio and
        // the stage machine land in ENDED (the UI closes the surface + toasts).
        stopTransmit(emit = false)
        stageMachine.dispatchEnded(payload.conversationId)
        audio.teardown()
        // The stage's FIX #3 voice seat releases too — UNLESS the plain voice
        // room is independently joined for this conversation (one seat per
        // socket; the voice surface's membership must survive a stage end).
        val voice = voiceMachine.state.value
        if (!voice.joined || voice.conversationId != payload.conversationId) {
            scope.launch { runCatching { repo.emitVoiceLeave(payload.conversationId) } }
        }
        if (_surface.value?.conversationId == payload.conversationId &&
            _surface.value?.kind == RoomKind.STAGE
        ) {
            _surface.value = null
        }
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
        // Voice machine: honest offline transmit + ptt gate.
        for (effect in voiceMachine.dispatch(VoiceRoomStateMachine.Event.ConnectionChanged(connected))) {
            if (effect is VoiceRoomStateMachine.Effect.StopTransmission) stopTransmit(emit = true)
        }
        // VR-8: on reconnect, re-emit voice:join if still joined (rate-limited).
        val voice = voiceMachine.state.value
        val now = System.currentTimeMillis()
        if (connected && voice.joined && now - lastVoiceResyncMs >= VOICE_RESYNC_MS) {
            lastVoiceResyncMs = now
            emitVoiceJoin(voice.conversationId)
        }
        // ST-8: stage re-join rides the same connection truth; the missing-me
        // resync inside dispatchState rate-limits itself.
        val stage = stageMachine.state.value
        stageMachine.dispatchConnection(connected)
        if (connected && stage.joined) {
            scope.launch {
                runCatching { repo.emitStageJoin(stage.conversationId, identity, asHost = stage.wasHost) }
                runCatching { repo.emitVoiceJoin(stage.conversationId, identity) }
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
        scope.launch { runCatching { repo.emitVoiceJoin(conversationId, identity) } }
    }

    // ── captions pipeline (VR-7) ────────────────────────────────

    private fun onCaptionSamples(samples: ShortArray) {
        when (val offer = captions.offer(samples, voiceMachine.state.value.transmitting, _captionsEnabled.value)) {
            is CaptionWindowAccumulator.Offer.Window -> transcribeWindow(offer.samples)
            else -> Unit
        }
    }

    /**
     * One 4s window → 44-byte WAV → POST /api/voice/transcribe (60s timeout)
     * → voice:transcript. Single-flight: [captions.busy] holds until done.
     * Failures are honest silence — the window is never retried.
     */
    private fun transcribeWindow(samples: ShortArray) {
        scope.launch {
            val cid = voiceMachine.state.value.conversationId
            try {
                if (cid.isBlank() || identity.id.isBlank()) return@launch
                val audioBase64 = WavEncoder.toBase64(samples)
                val transcript = runCatching {
                    repo.transcribeVoice(cid, identity.id, audioBase64)
                }.getOrNull()?.getOrNull()?.transcript?.takeIf { it.isNotBlank() } ?: return@launch
                runCatching { repo.emitVoiceTranscript(cid, identity.id, transcript.take(280)) }
            } finally {
                captions.endFlush()
            }
        }
    }

    private fun addCaption(userId: String, text: String) {
        if (text.isBlank()) return
        // The sender's own caption comes back through the same event — it IS
        // the echo (voice:transcript goes to the whole room, sender included).
        val roster = voiceMachine.state.value.roster
        val speaker = roster.firstOrNull { it.id == userId }
        val caption = VoiceCaption(
            id = "$userId-${System.currentTimeMillis()}",
            name = speaker?.name ?: userId,
            color = speaker?.color ?: "",
            text = text,
            atMs = System.currentTimeMillis(),
        )
        _captionList.value = (_captionList.value + caption).takeLast(MAX_CAPTIONS)
        sweepCaptions()
    }

    private fun sweepCaptions() {
        val list = _captionList.value
        if (list.isEmpty()) return
        val cutoff = System.currentTimeMillis() - CAPTION_TTL_MS
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

        /** Exposed for tests/diagnostics — 250ms wire blocks. */
        const val BLOCK_SAMPLES = VoicePcm.BLOCK_SAMPLES
        const val PRE_ROLL_MS = PlaybackScheduler.PRE_ROLL_MS
    }
}
