package app.pulse.feature.voice.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Pure spatial-presence reducer (spec §1.3 SP-1..SP-5).
 *
 * WEB DEFECT FIX #3 (throttle): the client move throttle is 80ms — exactly the
 * server limit (web shipped 90 and lost every other tap).
 * WEB DEFECT FIX #4 (reconcile): the server self-position overwrites the
 * optimistic target once the finger has been idle for 300ms, so drift can
 * never stick.
 * WEB DEFECT FIX #5 (honest error): reconnect attempts are counted; after
 * MAX_RECONNECT_ATTEMPTS the machine lands in ERROR instead of spinning.
 */
class SpaceBoardStateMachine(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    enum class Status { IDLE, JOINING, CONNECTED, ERROR }

    data class Point(val x: Double, val y: Double)

    data class Player(
        val id: String,
        val name: String,
        val color: String = "",
        val x: Double = 0.5,
        val y: Double = 0.5,
    )

    data class State(
        val status: Status = Status.IDLE,
        val conversationId: String = "",
        val selfId: String = "",
        val players: Map<String, Player> = emptyMap(),
        /** Optimistic self target — shown immediately under the finger. */
        val myTarget: Point? = null,
        /** Server's latest self position (the optimistic target reconciles to it). */
        val myServerPos: Point? = null,
        val nearbyIds: Set<String> = emptySet(),
        val reconnectAttempts: Int = 0,
        val error: String? = null,
        val lastLocalMoveMs: Long = 0,
        val lastEmitMs: Long = 0,
    ) {
        val count: Int get() = players.size
        /** "N in room" pill — me plus every listed player (self is server-listed). */
        val pill: Int get() = if (players.isEmpty()) 1 else players.size
    }

    sealed interface Event {
        data class JoinRequested(val conversationId: String, val selfId: String) : Event
        data class Connected(val conversationId: String) : Event

        /** One local move gesture sample — clamped + throttled here (SP-3). */
        data class LocalMove(val x: Double, val y: Double) : Event

        /** S→C space:state — wholesale board replace + self reconcile (SP-2/SP-3). */
        data class BoardArrived(val conversationId: String, val players: List<Player>) : Event

        /** Reconnect bookkeeping — honest ERROR after the budget (SP-5, FIX #5). */
        data object ReconnectAttempt : Event
        data object Disconnected : Event
        data object Left : Event
    }

    sealed interface Effect {
        data class EmitMove(val x: Double, val y: Double) : Effect
        data object EmitJoin : Effect
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
            selfId = event.selfId,
            lastLocalMoveMs = current.lastLocalMoveMs,
            lastEmitMs = 0,
        )

        is Event.Connected -> {
            effects += Effect.EmitJoin
            current.copy(status = Status.CONNECTED, reconnectAttempts = 0, error = null)
        }

        is Event.LocalMove -> {
            if (current.status != Status.CONNECTED) {
                current
            } else {
                val now = clock()
                // 80ms client throttle == server budget (WEB DEFECT FIX #3).
                if (now - current.lastEmitMs < MOVE_THROTTLE_MS) {
                    current
                } else {
                    val clamped = Point(
                        event.x.coerceIn(0.0, 1.0),
                        event.y.coerceIn(0.0, 1.0),
                    )
                    effects += Effect.EmitMove(clamped.x, clamped.y)
                    current.copy(
                        myTarget = clamped,
                        lastLocalMoveMs = now,
                        lastEmitMs = now,
                        nearbyIds = nearbyOf(current, clamped),
                    )
                }
            }
        }

        is Event.BoardArrived -> {
            if (event.conversationId != current.conversationId) {
                current
            } else {
                val players = event.players.associateBy { it.id }
                val serverSelf = players[current.selfId]?.let { Point(it.x, it.y) }
                // Reconcile (WEB DEFECT FIX #4): adopt the server self position
                // only after the finger has been idle for 300ms — an in-flight
                // gesture never fights the user.
                val reconciled = serverSelf?.takeIf {
                    current.myTarget == null || clock() - current.lastLocalMoveMs >= RECONCILE_IDLE_MS
                } ?: current.myTarget
                current.copy(
                    status = Status.CONNECTED,
                    players = players,
                    myServerPos = serverSelf,
                    myTarget = reconciled,
                    nearbyIds = nearbyOf(current.copy(players = players), reconciled),
                )
            }
        }

        is Event.Disconnected -> {
            val attempts = current.reconnectAttempts + 1
            if (attempts >= MAX_RECONNECT_ATTEMPTS) {
                // Honest terminal state (WEB DEFECT FIX #5) — never spin forever.
                current.copy(
                    status = Status.ERROR,
                    reconnectAttempts = attempts,
                    error = "Couldn't reach the room — your connection is down. Try again.",
                )
            } else {
                current.copy(reconnectAttempts = attempts)
            }
        }

        is Event.ReconnectAttempt -> current

        is Event.Left -> State()
    }

    private fun nearbyOf(state: State, self: Point?): Set<String> {
        if (self == null) return emptySet()
        return state.players.values
            .filter { it.id != state.selfId && distance(self, Point(it.x, it.y)) <= NEARBY_RADIUS }
            .mapTo(mutableSetOf()) { it.id }
    }

    /** Leave resets the whole board (SP-1). */
    fun reset() {
        _state.value = State()
    }

    companion object {
        /** Server move throttle: 80ms — the client budget matches it exactly. */
        const val MOVE_THROTTLE_MS = 80L
        /** Idle window before the optimistic target reconciles to server truth. */
        const val RECONCILE_IDLE_MS = 300L
        /** Gather-style proximity ring (web NEARBY_RADIUS). */
        const val NEARBY_RADIUS = 0.18
        /** Honest-error budget (WEB DEFECT FIX #5). */
        const val MAX_RECONNECT_ATTEMPTS = 6

        fun distance(a: Point, b: Point): Double {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return sqrt(dx * dx + dy * dy)
        }
    }
}
