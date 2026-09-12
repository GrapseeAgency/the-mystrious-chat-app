package app.pulse.feature.voice

import app.pulse.feature.voice.engine.StageRoomStateMachine
import app.pulse.protocol.StagePersonDto
import app.pulse.protocol.StageRoomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage reducer contract (spec §1.2 ST-1..ST-8), including WEB DEFECT FIX #3:
 * every joined stage role holds a voice seat and a forced removal re-arms the
 * voice re-join (rate-limited), so the demoted member keeps HEARING.
 */
class StageRoomStateMachineTest {

    private var now = 1_000_000L
    private val machine = StageRoomStateMachine(selfId = "me", clock = { now })
    private val state get() = machine.state.value

    private fun person(id: String) = StagePersonDto(id = id, name = "User $id", color = "emerald")

    private fun room(
        host: StagePersonDto? = null,
        speakers: List<StagePersonDto> = emptyList(),
        hands: List<StagePersonDto> = emptyList(),
        listeners: List<StagePersonDto> = emptyList(),
        listenerCount: Int = listeners.size,
    ) = StageRoomState(host, speakers, hands, listeners, listenerCount)

    private fun join(cid: String = "c1") = machine.dispatchJoinRequested(cid)

    // ── role derivation (ST-2) ─────────────────────────────────

    @Test
    fun `first joiner becomes host - role derives from state`() {
        join()
        machine.dispatchState("c1", room(host = person("me")))
        assertEquals(StageRoomStateMachine.Role.HOST, state.myRole)
        assertTrue(state.isHost)
        assertTrue(state.wasHost)
        assertTrue(state.canTransmit)
    }

    @Test
    fun `speaker hand listener and audience roles derive correctly`() {
        join()
        machine.dispatchState("c1", room(host = person("h"), speakers = listOf(person("me"))))
        assertEquals(StageRoomStateMachine.Role.SPEAKER, state.myRole)
        assertTrue(state.canTransmit)
        assertFalse(state.isHost)

        machine.dispatchState("c1", room(host = person("h"), hands = listOf(person("me"))))
        assertEquals(StageRoomStateMachine.Role.HAND, state.myRole)
        assertTrue(state.handRaised)
        assertFalse(state.canTransmit)

        machine.dispatchState("c1", room(host = person("h"), listeners = listOf(person("me")), listenerCount = 7))
        assertEquals(StageRoomStateMachine.Role.LISTENER, state.myRole)
        assertFalse(state.canTransmit)
        assertEquals(7, state.listenerCount)
    }

    @Test
    fun `state rows render wholesale - host card speakers hands listeners`() {
        join()
        machine.dispatchState(
            "c1",
            room(
                host = person("h1"),
                speakers = listOf(person("s1"), person("s2")),
                hands = listOf(person("h2")),
                listeners = listOf(person("l1")),
            ),
        )
        assertEquals("h1", state.host?.id)
        assertEquals(listOf("s1", "s2"), state.speakers.map { it.id })
        assertEquals(listOf("h2"), state.hands.map { it.id })
        assertEquals(listOf("l1"), state.listeners.map { it.id })
        assertTrue(state.firstStateArrived)
        assertNull(state.error)
    }

    // ── hands (ST-3) ────────────────────────────────────────────

    @Test
    fun `listener hand toggle emits stage hand - host cannot raise a hand`() {
        join()
        machine.dispatchState("c1", room(listeners = listOf(person("me"))))
        val raise = machine.toggleHand()
        assertEquals(listOf(StageRoomStateMachine.Effect.EmitHand(true)), raise)
        assertTrue(state.handRaised)
        assertEquals(listOf(StageRoomStateMachine.Effect.EmitHand(false)), machine.toggleHand())

        machine.dispatchState("c1", room(host = person("me")))
        assertTrue(machine.toggleHand().isEmpty())
    }

    @Test
    fun `approve moves a hand to the speaker row via the next state`() {
        join()
        machine.dispatchState("c1", room(host = person("me"), hands = listOf(person("b"))))
        // The host emits stage:approve (engine executes it); the RELAY's next
        // stage:state is the truth the machine renders:
        machine.dispatchState("c1", room(host = person("me"), speakers = listOf(person("b"))))
        assertEquals(StageRoomStateMachine.Role.HOST, state.myRole) // approving host stays host
        assertEquals(listOf("b"), state.speakers.map { it.id })
        assertTrue(state.hands.isEmpty())
    }

    // ── demote + FIX #3 voice seat ──────────────────────────────

    @Test
    fun `demote moves speaker to listener and keeps the voice seat`() {
        join()
        machine.dispatchState("c1", room(host = person("h"), speakers = listOf(person("me"))))
        // Host demoted me: next state shows me in listeners; the server force-
        // removed my VOICE seat (removeVoicePeerByUser).
        machine.dispatchState("c1", room(host = person("h"), listeners = listOf(person("me"))))
        assertEquals(StageRoomStateMachine.Role.LISTENER, state.myRole)
        assertTrue(machine.needsVoiceSeat) // FIX #3: keeps hearing
        val effects = machine.noteVoiceSeatLost()
        assertEquals(listOf(StageRoomStateMachine.Effect.EmitVoiceRejoin("c1")), effects)
    }

    @Test
    fun `needsVoiceSeat stays true for EVERY joined role and false after leave`() {
        join()
        machine.dispatchState("c1", room(host = person("me")))
        assertTrue(machine.needsVoiceSeat)
        machine.dispatchState("c1", room(host = person("h"), speakers = listOf(person("me"))))
        assertTrue(machine.needsVoiceSeat)
        machine.dispatchState("c1", room(host = person("h"), hands = listOf(person("me"))))
        assertTrue(machine.needsVoiceSeat)
        machine.dispatchState("c1", room(host = person("h"), listeners = listOf(person("me"))))
        assertTrue(machine.needsVoiceSeat)
        machine.leave()
        assertFalse(machine.needsVoiceSeat)
    }

    @Test
    fun `forced voice-seat removal re-arms after the rate-limit window`() {
        join()
        machine.dispatchState("c1", room(host = person("h"), listeners = listOf(person("me"))))
        assertEquals(1, machine.noteVoiceSeatLost().size)
        // Inside the 2000ms budget: silent (already re-joining).
        now += 1_500
        assertTrue(machine.noteVoiceSeatLost().isEmpty())
        // Past the budget: the next forced removal re-arms (rejoin→demote→rejoin).
        now += 2_100
        assertEquals(1, machine.noteVoiceSeatLost().size)
        // After leave there is nothing to re-arm.
        machine.leave()
        assertTrue(machine.noteVoiceSeatLost().isEmpty())
    }

    // ── end stage (ST-5) ────────────────────────────────────────

    @Test
    fun `two-tap end confirm fires inside 2600ms and re-arms after reset`() {
        join()
        machine.dispatchState("c1", room(host = person("me")))
        assertFalse(machine.armEndConfirm(nowMs = 1_000)) // first tap arms
        assertTrue(machine.isEndConfirmArmed(nowMs = 2_000))
        assertTrue(machine.armEndConfirm(nowMs = 3_500)) // second tap fires (≤2600)
        assertFalse(machine.isEndConfirmArmed(nowMs = 3_600))
        assertFalse(machine.armEndConfirm(nowMs = 3_600)) // fresh arm after firing
    }

    @Test
    fun `stale arm expires after 2600ms - and end is host-only`() {
        join()
        machine.dispatchState("c1", room(host = person("me")))
        machine.armEndConfirm(nowMs = 1_000)
        now = 1_000
        assertFalse(machine.armEndConfirm(nowMs = 1_000 + 2_700)) // stale → re-arm, NOT fire
        assertTrue(machine.armEndConfirm(nowMs = 1_000 + 2_700 + 100)) // second tap now fires

        machine.dispatchState("c1", room(host = person("h"), listeners = listOf(person("me"))))
        assertFalse(machine.armEndConfirm(nowMs = now)) // listener can never end
    }

    // ── claim host (ST-7) ───────────────────────────────────────

    @Test
    fun `claim host is legal only while joined and the host seat is empty`() {
        join()
        machine.dispatchState("c1", room(listeners = listOf(person("me"))))
        assertTrue(state.canClaimHost)
        machine.dispatchState("c1", room(host = person("other"), listeners = listOf(person("me"))))
        assertFalse(state.canClaimHost)
        machine.leave()
        assertFalse(state.canClaimHost)
    }

    // ── resync (ST-8) ───────────────────────────────────────────

    @Test
    fun `missing-me resync is rate-limited at 2500ms and carries wasHost`() {
        join()
        machine.dispatchState("c1", room(host = person("me")))
        // Dropped off the stage (network blip): the next state lacks me.
        val first = machine.dispatchState("c1", room(speakers = listOf(person("a"))))
        assertEquals(listOf(StageRoomStateMachine.Effect.ResyncJoin("c1", asHost = true)), first)
        // Rate-limit: a second missing-me state inside 2500ms stays silent.
        now += 1_000
        assertTrue(machine.dispatchState("c1", room(speakers = listOf(person("a")))).isEmpty())
        // Past the window: re-armed.
        now += 2_600
        val second = machine.dispatchState("c1", room(speakers = listOf(person("a"))))
        assertEquals(listOf(StageRoomStateMachine.Effect.ResyncJoin("c1", asHost = true)), second)
    }

    @Test
    fun `resync does not fire for a joined member and never for another room`() {
        join()
        machine.dispatchState("c1", room(listeners = listOf(person("me"))))
        assertTrue(machine.dispatchState("c1", room(listeners = listOf(person("me")))).isEmpty())
        machine.dispatchState("c2", room()) // wrong room → ignored entirely
        assertEquals(StageRoomStateMachine.Role.LISTENER, state.myRole)
    }

    // ── glow + lifecycle ────────────────────────────────────────

    @Test
    fun `speaking glow prunes to live speakers on every state`() {
        join()
        machine.dispatchState("c1", room(host = person("h"), speakers = listOf(person("s1"), person("s2"))))
        machine.dispatchPtt("c1", "s1", on = true)
        machine.dispatchPtt("c1", "s2", on = true)
        machine.dispatchState("c1", room(host = person("h"), speakers = listOf(person("s1"))))
        assertEquals(setOf("s1"), state.speakingIds)
        machine.dispatchPtt("c2", "s1", on = false) // wrong room never bleeds
        assertEquals(setOf("s1"), state.speakingIds)
        machine.dispatchPtt("c1", "s1", on = false)
        assertTrue(state.speakingIds.isEmpty())
    }

    @Test
    fun `stage ended lands in ENDED and leave resets everything`() {
        join()
        machine.dispatchState("c1", room(host = person("me")))
        machine.dispatchEnded("c1")
        assertEquals(StageRoomStateMachine.Status.ENDED, state.status)
        assertFalse(state.joined)
        machine.dispatchEnded("c2") // wrong room ignored
        assertEquals(StageRoomStateMachine.Status.ENDED, state.status)
        machine.leave()
        assertEquals(StageRoomStateMachine.Status.IDLE, state.status)
        assertNull(state.host)
        assertFalse(state.wasHost)
    }

    @Test
    fun `state for another room or before join is ignored`() {
        machine.dispatchState("c1", room(host = person("me"))) // not joined yet
        assertEquals(StageRoomStateMachine.Status.IDLE, state.status)
        assertNull(state.host)
        join("c9")
        machine.dispatchState("c1", room(host = person("me")))
        assertNull(state.host)
        assertEquals(StageRoomStateMachine.Status.JOINING, state.status)
    }
}
