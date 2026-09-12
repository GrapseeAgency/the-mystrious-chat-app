package app.pulse.feature.voice.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure voice-room reducer (spec §1.1 VR-1..VR-10). The engine executes the
 * effects this machine produces against the real platform (socket emits,
 * playback resets, capture stop); the machine itself never touches I/O.
 *
 * Replays every roster wholesale (`voice:roster` is authoritative — joiners
 * AND leavers), prunes the speaking ring to live peers (WEB DEFECT: stale
 * glow), and — FIX #2 seam — returns the ids that DISAPPEARED so the engine
 * resets their playback state (lastSeq→0) and rejoin audio resumes instantly.
 */
class VoiceRoomStateMachine {

    enum class Status { IDLE, JOINING, JOINED, ERROR }

    data class Peer(
        val id: String,
        val name: String,
        val username: String? = null,
        val color: String = "",
    )

    data class State(
        val status: Status = Status.IDLE,
        val conversationId: String = "",
        val roster: List<Peer> = emptyList(),
        val speakingIds: Set<String> = emptySet(),
        val micMuted: Boolean = false,
        val transmitting: Boolean = false,
        val connected: Boolean = true,
        val error: String? = null,
    ) {
        val joined: Boolean get() = status == Status.JOINING || status == Status.JOINED
        val pttEnabled: Boolean get() = status == Status.JOINED && !micMuted && connected
    }

    sealed interface Event {
        /** Mic button pressed for [conversationId] — the optimistic JOINING state (VR-1). */
        data class JoinRequested(val conversationId: String) : Event

        /** S→C voice:roster — replaces the roster WHOLESALE (VR-2). */
        data class RosterArrived(val conversationId: String, val peers: List<Peer>) : Event

        /** S→C voice:ptt — echoed to the sender too, which lights the self ring (VR-3). */
        data class PttArrived(val conversationId: String, val userId: String, val on: Boolean) : Event

        /** Relay connection truth (VR-8). */
        data class ConnectionChanged(val connected: Boolean) : Event

        /** Software mute gate — muting while transmitting FORCE-STOPS PTT (VR-6). */
        data class MuteChanged(val muted: Boolean) : Event

        /** Local PTT latch state changed (the engine drives this from press/release). */
        data class TransmitChanged(val on: Boolean) : Event

        /** Mic denied / busy / relay unreachable → honest inline error (VR-10). */
        data class Failed(val message: String) : Event

        /** Explicit leave — full local reset, seq restarts at 1 on next join (VR-9). */
        data object Left : Event
    }

    sealed interface Effect {
        /** These peers vanished from the roster → reset their playback state (FIX #2). */
        data class ResetPlayback(val userIds: List<String>) : Effect

        /** A gate change (mute/disconnect) killed an active transmission. */
        data object StopTransmission : Effect
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun dispatch(event: Event): List<Effect> {
        val effects = mutableListOf<Effect>()
        _state.value = reduce(_state.value, event, effects)
        return effects
    }

    private fun reduce(current: State, event: Event, effects: MutableList<Effect>): State = when (event) {
        is Event.JoinRequested -> State(
            status = Status.JOINING,
            conversationId = event.conversationId,
            // Rejoin resets mute (VR-6); transmission is off until PTT.
            micMuted = false,
            connected = current.connected,
        )

        is Event.RosterArrived -> {
            if (event.conversationId != current.conversationId || !current.joined) {
                // A roster for another room (or after leave) must not clobber us.
                current
            } else {
                val liveIds = event.peers.map { it.id }.toSet()
                val dropped = current.roster.map { it.id }.filterNot { it in liveIds }
                if (dropped.isNotEmpty()) effects += Effect.ResetPlayback(dropped)
                current.copy(
                    status = Status.JOINED,
                    roster = event.peers,
                    // Prune the speaking ring to live peers (stale glow fix).
                    speakingIds = current.speakingIds.filterTo(mutableSetOf()) { it in liveIds },
                )
            }
        }

        is Event.PttArrived -> {
            if (event.conversationId != current.conversationId) {
                current
            } else {
                val speaking = current.speakingIds.toMutableSet()
                if (event.on) speaking += event.userId else speaking -= event.userId
                current.copy(speakingIds = speaking)
            }
        }

        is Event.ConnectionChanged -> {
            var next = current.copy(connected = event.connected)
            if (!event.connected && next.transmitting) {
                // Offline transmit is a lie — chunks would drop anyway (VR-8).
                effects += Effect.StopTransmission
                next = next.copy(transmitting = false)
            }
            next
        }

        is Event.MuteChanged -> {
            var next = current.copy(micMuted = event.muted)
            if (event.muted && next.transmitting) {
                // Mute force-stops PTT — the software gate is the source of truth (VR-6).
                effects += Effect.StopTransmission
                next = next.copy(transmitting = false)
            }
            next
        }

        is Event.TransmitChanged -> current.copy(transmitting = event.on)

        is Event.Failed -> current.copy(status = Status.ERROR, error = event.message, transmitting = false)

        is Event.Left -> State(connected = current.connected)
    }
}
