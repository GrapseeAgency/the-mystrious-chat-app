package app.pulse.feature.voice.engine

import app.pulse.protocol.StagePersonDto
import app.pulse.protocol.StageRoomState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure stage reducer (spec §1.2 ST-1..ST-8). Roles derive from every
 * `stage:state` + myId; the host seat is trust-derived by the RELAY (first
 * joiner of a fresh room), never claimed locally.
 *
 * WEB DEFECT FIX #3 seam: [needsVoiceSeat] stays TRUE for every joined role
 * (host/speaker/hand/listener) — the audience holds a voice seat too, so it
 * receives chunks and glows. A host stage:mute force-removes a demoted
 * speaker's VOICE seat server-side; [noteVoiceSeatLost] re-arms the re-join
 * (rate-limited) so the demoted member keeps HEARING while losing the mic.
 */
class StageRoomStateMachine(
    selfId: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    enum class Status { IDLE, JOINING, JOINED, ENDED }
    enum class Role { NONE, HOST, SPEAKER, HAND, LISTENER }

    private var selfId: String = selfId

    /** Identity adoption — the session engine re-binds when the viewer id changes. */
    fun setSelfId(id: String) {
        selfId = id
    }

    data class State(
        val status: Status = Status.IDLE,
        val conversationId: String = "",
        val host: StagePersonDto? = null,
        val speakers: List<StagePersonDto> = emptyList(),
        val hands: List<StagePersonDto> = emptyList(),
        val listeners: List<StagePersonDto> = emptyList(),
        val listenerCount: Int = 0,
        val myRole: Role = Role.NONE,
        val handRaised: Boolean = false,
        val wasHost: Boolean = false,
        val speakingIds: Set<String> = emptySet(),
        val connected: Boolean = true,
        val error: String? = null,
    ) {
        val joined: Boolean get() = status == Status.JOINING || status == Status.JOINED
        /** Host+speaker arm the mic; hand/listener do not (ST-6). */
        val canTransmit: Boolean get() = joined && (myRole == Role.HOST || myRole == Role.SPEAKER)
        val isHost: Boolean get() = joined && myRole == Role.HOST
        /** Claim-host is legal only while the host SEAT is empty and we are joined (ST-7). */
        val canClaimHost: Boolean get() = status == Status.JOINED && host == null
        val firstStateArrived: Boolean get() = status == Status.JOINED
    }

    sealed interface Effect {
        /** stage:hand raise/lower for me (ST-3). */
        data class EmitHand(val raised: Boolean) : Effect

        /** Re-join after reconnect (asHost = remembered role, ST-8). */
        data class ResyncJoin(val conversationId: String, val asHost: Boolean) : Effect

        /**
         * Forced voice-seat removal (demote) — re-emit voice:join so the
         * demoted member keeps hearing (WEB DEFECT FIX #3, rate-limited).
         */
        data class EmitVoiceRejoin(val conversationId: String) : Effect
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Voice-seat re-arm rate limit — mirrors the voice-room resync budget. */
    private var lastVoiceResyncMs = 0L

    /** Missing-stage-me resync budget (ST-8: 2500ms). */
    private var lastStageResyncMs = 0L

    private var endArmedAtMs = 0L

    fun dispatchJoinRequested(conversationId: String) {
        _state.value = State(status = Status.JOINING, conversationId = conversationId, connected = _state.value.connected)
    }

    fun dispatchConnection(connected: Boolean): State {
        _state.value = _state.value.copy(connected = connected)
        return _state.value
    }

    /**
     * stage:state arrived — wholesale replace, derive my role, and produce the
     * effects (missing-me resync, forced voice-seat re-arm). Returns the
     * effects for the engine to execute.
     */
    fun dispatchState(conversationId: String, room: StageRoomState): List<Effect> {
        val effects = mutableListOf<Effect>()
        val current = _state.value
        if (conversationId != current.conversationId || !current.joined) return effects

        val everyone = buildList {
            room.host?.let { add(it) }
            addAll(room.speakers)
            addAll(room.hands)
            addAll(room.listeners)
        }
        val myRole = when {
            room.host?.id == selfId -> Role.HOST
            room.speakers.any { it.id == selfId } -> Role.SPEAKER
            room.hands.any { it.id == selfId } -> Role.HAND
            room.listeners.any { it.id == selfId } -> Role.LISTENER
            else -> Role.NONE
        }

        var next = current.copy(
            status = Status.JOINED,
            host = room.host,
            speakers = room.speakers,
            hands = room.hands,
            listeners = room.listeners,
            listenerCount = room.listenerCount,
            myRole = myRole,
            handRaised = myRole == Role.HAND,
            wasHost = current.wasHost || myRole == Role.HOST,
            // Glow pruned to live speaker ids on EVERY state (ST-6).
            speakingIds = current.speakingIds.filterTo(mutableSetOf()) { id -> room.speakers.any { it.id == id } },
            error = null,
        )
        _state.value = next

        // Missing-me resync (ST-8): rate-limited 2500ms.
        val now = clock()
        if (myRole == Role.NONE && now - lastStageResyncMs >= STAGE_RESYNC_MS) {
            lastStageResyncMs = now
            effects += Effect.ResyncJoin(conversationId, next.wasHost)
            // Rejoin with remembered host intent until the relay re-seats us.
            next = next.copy(status = Status.JOINED)
            _state.value = next
        }
        return effects
    }

    fun dispatchPtt(conversationId: String, userId: String, on: Boolean) {
        val current = _state.value
        if (conversationId != current.conversationId) return
        val speaking = current.speakingIds.toMutableSet()
        if (on) speaking += userId else speaking -= userId
        _state.value = current.copy(speakingIds = speaking)
    }

    fun dispatchEnded(conversationId: String) {
        val current = _state.value
        if (conversationId != current.conversationId) return
        _state.value = State(status = Status.ENDED, conversationId = conversationId, connected = current.connected)
    }

    fun leave() {
        _state.value = State(connected = _state.value.connected)
        endArmedAtMs = 0
        lastVoiceResyncMs = 0
        lastStageResyncMs = 0
    }

    /** Raise/lower my hand (listener-only — the UI gates; the relay enforces too). */
    fun toggleHand(): List<Effect> {
        val current = _state.value
        if (!current.joined || current.canTransmit) return emptyList()
        val raised = !current.handRaised
        _state.value = current.copy(handRaised = raised)
        return listOf(Effect.EmitHand(raised))
    }

    /**
     * Voice-seat tracking for FIX #3: called when the voice:roster for the
     * stage conversation no longer contains me while I am stage-joined.
     * Returns an EmitVoiceRejoin effect when the rate-limit allows one.
     */
    fun noteVoiceSeatLost(): List<Effect> {
        val current = _state.value
        if (!current.joined) return emptyList()
        val now = clock()
        if (now - lastVoiceResyncMs < VOICE_RESYNC_MS) return emptyList()
        lastVoiceResyncMs = now
        return listOf(Effect.EmitVoiceRejoin(current.conversationId))
    }

    /** FIX #3 contract: every joined role holds a voice seat. */
    val needsVoiceSeat: Boolean get() = _state.value.joined

    /**
     * Two-tap End confirm (ST-5): first tap arms (2600ms window), second tap
     * inside the window FIRES. Returns true when the host should emit stage:end.
     */
    fun armEndConfirm(nowMs: Long = clock()): Boolean {
        val current = _state.value
        if (!current.isHost) return false
        return if (endArmedAtMs != 0L && nowMs - endArmedAtMs <= END_CONFIRM_MS) {
            endArmedAtMs = 0
            true
        } else {
            endArmedAtMs = nowMs
            false
        }
    }

    /** UI mirror of the armed confirm (stale arms expire after 2600ms). */
    fun isEndConfirmArmed(nowMs: Long = clock()): Boolean =
        endArmedAtMs != 0L && nowMs - endArmedAtMs <= END_CONFIRM_MS

    fun disarmEndConfirm() {
        endArmedAtMs = 0
    }

    companion object {
        const val STAGE_RESYNC_MS = 2_500L
        const val VOICE_RESYNC_MS = 2_000L
        const val END_CONFIRM_MS = 2_600L
    }
}
