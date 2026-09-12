package app.pulse.feature.voice

import app.pulse.feature.voice.engine.VoiceRoomStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure voice-room reducer contract (spec §1.1 VR-2/3/6/8/9/10).
 */
class VoiceRoomStateMachineTest {

    private val machine = VoiceRoomStateMachine()
    private val state get() = machine.state.value

    private fun peer(id: String) = VoiceRoomStateMachine.Peer(id, name = "User $id", color = "emerald")

    @Test
    fun `join request enters optimistic JOINING and resets mute`() {
        machine.dispatch(VoiceRoomStateMachine.Event.MuteChanged(true))
        assertTrue(state.micMuted)
        machine.dispatch(VoiceRoomStateMachine.Event.JoinRequested("c1"))
        assertEquals(VoiceRoomStateMachine.Status.JOINING, state.status)
        assertEquals("c1", state.conversationId)
        assertTrue(state.joined)
        assertFalse(state.micMuted) // rejoin resets mute (VR-6)
    }

    @Test
    fun `roster replaces wholesale and JOINING becomes JOINED`() {
        machine.dispatch(VoiceRoomStateMachine.Event.JoinRequested("c1"))
        machine.dispatch(VoiceRoomStateMachine.Event.RosterArrived("c1", listOf(peer("a"), peer("b"))))
        assertEquals(VoiceRoomStateMachine.Status.JOINED, state.status)
        assertEquals(listOf("a", "b"), state.roster.map { it.id })
        // Joiners AND leavers arrive via wholesale replace — never merged.
        machine.dispatch(VoiceRoomStateMachine.Event.RosterArrived("c1", listOf(peer("a"), peer("c"))))
        assertEquals(listOf("a", "c"), state.roster.map { it.id })
    }

    @Test
    fun `roster drop emits ResetPlayback and prunes the speaking ring`() {
        machine.dispatch(VoiceRoomStateMachine.Event.JoinRequested("c1"))
        machine.dispatch(VoiceRoomStateMachine.Event.RosterArrived("c1", listOf(peer("a"), peer("b"))))
        machine.dispatch(VoiceRoomStateMachine.Event.PttArrived("c1", "b", on = true))
        assertEquals(setOf("b"), state.speakingIds)
        val effects = machine.dispatch(
            VoiceRoomStateMachine.Event.RosterArrived("c1", listOf(peer("a"))),
        )
        assertEquals(listOf("b"), (effects.single() as VoiceRoomStateMachine.Effect.ResetPlayback).userIds)
        assertTrue(state.speakingIds.isEmpty()) // stale glow pruned (WEB DEFECT)
    }

    @Test
    fun `roster for another room or after leave is ignored`() {
        machine.dispatch(VoiceRoomStateMachine.Event.JoinRequested("c1"))
        machine.dispatch(VoiceRoomStateMachine.Event.RosterArrived("c2", listOf(peer("x"))))
        assertTrue(state.roster.isEmpty())
        machine.dispatch(VoiceRoomStateMachine.Event.RosterArrived("c1", listOf(peer("a"))))
        machine.dispatch(VoiceRoomStateMachine.Event.Left)
        machine.dispatch(VoiceRoomStateMachine.Event.RosterArrived("c1", listOf(peer("a"), peer("b"))))
        assertEquals(VoiceRoomStateMachine.Status.IDLE, state.status)
        assertTrue(state.roster.isEmpty())
    }

    @Test
    fun `ptt echo lights and clears the ring - including the self ring`() {
        machine.dispatch(VoiceRoomStateMachine.Event.JoinRequested("c1"))
        machine.dispatch(VoiceRoomStateMachine.Event.RosterArrived("c1", listOf(peer("me"))))
        machine.dispatch(VoiceRoomStateMachine.Event.PttArrived("c1", "me", on = true))
        assertTrue("me" in state.speakingIds)
        machine.dispatch(VoiceRoomStateMachine.Event.PttArrived("c1", "me", on = false))
        assertTrue(state.speakingIds.isEmpty())
        // A different room's ptt never bleeds in.
        machine.dispatch(VoiceRoomStateMachine.Event.PttArrived("c2", "me", on = true))
        assertTrue(state.speakingIds.isEmpty())
    }

    @Test
    fun `mute while transmitting force-stops the push`() {
        machine.dispatch(VoiceRoomStateMachine.Event.JoinRequested("c1"))
        machine.dispatch(VoiceRoomStateMachine.Event.TransmitChanged(true))
        assertTrue(state.transmitting)
        val effects = machine.dispatch(VoiceRoomStateMachine.Event.MuteChanged(true))
        assertFalse(state.transmitting)
        assertTrue(state.micMuted)
        assertTrue(effects.contains(VoiceRoomStateMachine.Effect.StopTransmission))
        assertFalse(state.pttEnabled)
    }

    @Test
    fun `disconnect stops transmission and reconnect restores ptt`() {
        machine.dispatch(VoiceRoomStateMachine.Event.JoinRequested("c1"))
        // Roster arrival is what confirms the seat → JOINED (optimistic-then-truth, VR-2).
        machine.dispatch(VoiceRoomStateMachine.Event.RosterArrived("c1", listOf(peer("me"))))
        machine.dispatch(VoiceRoomStateMachine.Event.TransmitChanged(true))
        val effects = machine.dispatch(VoiceRoomStateMachine.Event.ConnectionChanged(false))
        assertFalse(state.connected)
        assertFalse(state.transmitting) // offline transmit is a lie (VR-8)
        assertTrue(effects.contains(VoiceRoomStateMachine.Effect.StopTransmission))
        assertFalse(state.pttEnabled)
        machine.dispatch(VoiceRoomStateMachine.Event.ConnectionChanged(true))
        assertTrue(state.connected)
        assertTrue(state.pttEnabled) // JOINED + unmuted + connected
    }

    @Test
    fun `failed lands in ERROR with the honest message`() {
        machine.dispatch(VoiceRoomStateMachine.Event.JoinRequested("c1"))
        machine.dispatch(VoiceRoomStateMachine.Event.TransmitChanged(true))
        machine.dispatch(VoiceRoomStateMachine.Event.Failed("Microphone unavailable"))
        assertEquals(VoiceRoomStateMachine.Status.ERROR, state.status)
        assertEquals("Microphone unavailable", state.error)
        assertFalse(state.transmitting)
        assertFalse(state.joined)
    }

    @Test
    fun `left fully resets but keeps connection truth`() {
        machine.dispatch(VoiceRoomStateMachine.Event.ConnectionChanged(false))
        machine.dispatch(VoiceRoomStateMachine.Event.JoinRequested("c1"))
        machine.dispatch(VoiceRoomStateMachine.Event.RosterArrived("c1", listOf(peer("a"))))
        machine.dispatch(VoiceRoomStateMachine.Event.MuteChanged(true))
        machine.dispatch(VoiceRoomStateMachine.Event.Left)
        assertEquals(VoiceRoomStateMachine.Status.IDLE, state.status)
        assertEquals("", state.conversationId)
        assertTrue(state.roster.isEmpty())
        assertFalse(state.micMuted)
        assertFalse(state.connected) // connection truth is not room state
        assertNull(state.error)
    }

    @Test
    fun `ptt disabled while muted or not joined`() {
        assertFalse(state.pttEnabled) // IDLE
        machine.dispatch(VoiceRoomStateMachine.Event.JoinRequested("c1"))
        assertFalse(state.pttEnabled) // JOINING (no roster yet)
        machine.dispatch(VoiceRoomStateMachine.Event.RosterArrived("c1", listOf(peer("a"))))
        assertTrue(state.pttEnabled)
        machine.dispatch(VoiceRoomStateMachine.Event.MuteChanged(true))
        assertFalse(state.pttEnabled)
    }
}
