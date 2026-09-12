package app.pulse.feature.voice

import app.pulse.feature.voice.engine.SpaceBoardStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spatial-presence contract (spec §1.3 SP-1..SP-5) with the three web defect
 * fixes asserted: #3 the 80ms client throttle matches the server budget, #4
 * the optimistic target reconciles to server truth after a 300ms idle, #5 an
 * honest ERROR after the reconnect budget instead of an eternal spin.
 */
class SpaceBoardStateMachineTest {

    private var now = 1_000_000L
    private val machine = SpaceBoardStateMachine(clock = { now })
    private val state get() = machine.state.value

    private fun player(id: String, x: Double, y: Double) =
        SpaceBoardStateMachine.Player(id = id, name = "User $id", x = x, y = y)

    private fun connect(cid: String = "c1") {
        machine.dispatch(SpaceBoardStateMachine.Event.JoinRequested(cid, selfId = "me"))
        machine.dispatch(SpaceBoardStateMachine.Event.Connected(cid))
    }

    @Test
    fun `join then connected emits the room join once`() {
        val effects = ArrayList<List<SpaceBoardStateMachine.Effect>>()
        machine.dispatch(SpaceBoardStateMachine.Event.JoinRequested("c1", selfId = "me")).also { effects.add(it) }
        assertTrue(effects.single().isEmpty()) // join emits only once connected
        val connected = machine.dispatch(SpaceBoardStateMachine.Event.Connected("c1"))
        assertEquals(listOf(SpaceBoardStateMachine.Effect.EmitJoin), connected)
        assertEquals(SpaceBoardStateMachine.Status.CONNECTED, state.status)
        assertEquals(0, state.reconnectAttempts)
    }

    @Test
    fun `local move clamps into 0--1 and emits the clamped coords`() {
        connect()
        now += 200
        val effects = machine.dispatch(SpaceBoardStateMachine.Event.LocalMove(-0.5, 1.5))
        assertEquals(listOf(SpaceBoardStateMachine.Effect.EmitMove(0.0, 1.0)), effects)
        assertEquals(0.0, state.myTarget!!.x, 0.0)
        assertEquals(1.0, state.myTarget!!.y, 0.0)
    }

    @Test
    fun `80ms throttle drops in-between moves - client matches the server budget`() {
        connect()
        now += 200
        assertTrue(machine.dispatch(SpaceBoardStateMachine.Event.LocalMove(0.1, 0.1)).isNotEmpty())
        now += 50 // inside the 80ms window → swallowed, no emit, target untouched
        assertTrue(machine.dispatch(SpaceBoardStateMachine.Event.LocalMove(0.9, 0.9)).isEmpty())
        assertEquals(0.1, state.myTarget!!.x, 0.0)
        now += 81 // outside the window → flows
        assertTrue(machine.dispatch(SpaceBoardStateMachine.Event.LocalMove(0.9, 0.9)).isNotEmpty())
        assertEquals(0.9, state.myTarget!!.x, 0.0)
    }

    @Test
    fun `optimistic target holds under the finger then reconciles after 300ms idle`() {
        connect()
        now += 200
        machine.dispatch(SpaceBoardStateMachine.Event.LocalMove(0.2, 0.2))
        // Server state arrives while the finger is still moving (idle 100ms):
        // the optimistic target wins, server truth is only recorded.
        now += 100
        machine.dispatch(
            SpaceBoardStateMachine.Event.BoardArrived(
                "c1",
                listOf(player("me", 0.9, 0.9), player("a", 0.3, 0.3)),
            ),
        )
        assertEquals(0.2, state.myTarget!!.x, 0.0)
        assertEquals(0.9, state.myServerPos!!.x, 0.0)
        // Server state after the finger went idle ≥300ms: reconcile (FIX #4).
        now += 400
        machine.dispatch(SpaceBoardStateMachine.Event.BoardArrived("c1", listOf(player("me", 0.9, 0.9))))
        assertEquals(0.9, state.myTarget!!.x, 0.0)
        assertEquals(0.9, state.myTarget!!.y, 0.0)
    }

    @Test
    fun `board replaces wholesale and other rooms are ignored`() {
        connect()
        machine.dispatch(
            SpaceBoardStateMachine.Event.BoardArrived(
                "c1",
                listOf(player("me", 0.5, 0.5), player("a", 0.2, 0.2)),
            ),
        )
        assertEquals(2, state.count)
        machine.dispatch(SpaceBoardStateMachine.Event.BoardArrived("c2", listOf(player("ghost", 0.0, 0.0))))
        assertEquals(2, state.count) // wholesale replace, other room dropped
        machine.dispatch(
            SpaceBoardStateMachine.Event.BoardArrived("c1", listOf(player("me", 0.5, 0.5))),
        )
        assertEquals(1, state.count)
    }

    @Test
    fun `proximity is euclidean 0_18 and excludes self`() {
        assertEquals(0.18, SpaceBoardStateMachine.distance(
            SpaceBoardStateMachine.Point(0.0, 0.0),
            SpaceBoardStateMachine.Point(0.18, 0.0),
        ), 1e-12)
        assertEquals(
            0.18,
            SpaceBoardStateMachine.distance(
                SpaceBoardStateMachine.Point(0.0, 0.0),
                SpaceBoardStateMachine.Point(0.18 / Math.sqrt(2.0), 0.18 / Math.sqrt(2.0)),
            ),
            1e-9,
        )
        connect()
        machine.dispatch(
            SpaceBoardStateMachine.Event.BoardArrived(
                "c1",
                listOf(player("me", 0.5, 0.5), player("near", 0.6, 0.6), player("far", 0.75, 0.5)),
            ),
        )
        // near: √(0.1²+0.1²)≈0.141 ≤ 0.18 · far: 0.25 > 0.18 · self excluded.
        assertEquals(setOf("near"), state.nearbyIds)
        // Moving re-evaluates proximity against the clamped optimistic target.
        now += 300
        machine.dispatch(SpaceBoardStateMachine.Event.LocalMove(0.75, 0.5))
        assertEquals(setOf("far"), state.nearbyIds)
    }

    @Test
    fun `honest error after 6 failed reconnect attempts - not before`() {
        connect()
        repeat(5) { machine.dispatch(SpaceBoardStateMachine.Event.Disconnected) }
        assertEquals(5, state.reconnectAttempts)
        assertEquals(SpaceBoardStateMachine.Status.CONNECTED, state.status) // still trying
        assertNull(state.error)
        machine.dispatch(SpaceBoardStateMachine.Event.Disconnected)
        assertEquals(6, state.reconnectAttempts)
        assertEquals(SpaceBoardStateMachine.Status.ERROR, state.status)
        assertTrue(state.error!!.contains("Try again"))
        // A reconnect after the error state recovers the room (honest retry path).
        machine.dispatch(SpaceBoardStateMachine.Event.Connected("c1"))
        assertEquals(SpaceBoardStateMachine.Status.CONNECTED, state.status)
        assertNull(state.error)
    }

    @Test
    fun `leave resets the whole board`() {
        connect()
        machine.dispatch(SpaceBoardStateMachine.Event.LocalMove(0.4, 0.4).let { now += 100; it })
        machine.dispatch(SpaceBoardStateMachine.Event.BoardArrived("c1", listOf(player("me", 0.4, 0.4))))
        machine.dispatch(SpaceBoardStateMachine.Event.Left)
        assertEquals(SpaceBoardStateMachine.Status.IDLE, state.status)
        assertTrue(state.players.isEmpty())
        assertNull(state.myTarget)
        assertNull(state.myServerPos)
        assertEquals(1, state.pill) // "1 in room" floor for an empty board
    }

    @Test
    fun `moves before connected are swallowed`() {
        machine.dispatch(SpaceBoardStateMachine.Event.JoinRequested("c1", selfId = "me"))
        assertTrue(machine.dispatch(SpaceBoardStateMachine.Event.LocalMove(0.5, 0.5)).isEmpty())
        assertNull(state.myTarget)
    }
}
