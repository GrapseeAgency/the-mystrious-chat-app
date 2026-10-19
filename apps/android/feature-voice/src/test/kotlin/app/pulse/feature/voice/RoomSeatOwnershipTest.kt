package app.pulse.feature.voice

import app.pulse.feature.voice.engine.RoomSeatOwnership
import org.junit.Assert.*
import org.junit.Test

class RoomSeatOwnershipTest {
    @Test fun `listener needs no microphone and promotion does not silently enable it`() {
        val seat = RoomSeatOwnership()
        assertTrue(seat.joinStage("room"))
        assertNull(seat.requestMicrophone())
        seat.stageRole(true)
        assertFalse(seat.mayTransmit)
        assertTrue(seat.resolveMicrophone(seat.requestMicrophone()!!, true))
        assertTrue(seat.mayTransmit)
    }
    @Test fun `demotion wins even with independent voice membership and re-promotion`() {
        val seat = RoomSeatOwnership()
        seat.joinVoice("room"); seat.joinStage("room"); seat.stageRole(true)
        val pending = seat.requestMicrophone()!!
        seat.stageRole(false); seat.stageRole(true)
        assertFalse(seat.resolveMicrophone(pending, true))
        assertFalse(seat.mayTransmit)
        seat.resolveMicrophone(seat.requestMicrophone()!!, true)
        seat.stageRole(false)
        assertFalse(seat.mayTransmit)
    }
    @Test fun `stage exit preserves explicit voice seat and last exit releases`() {
        val seat = RoomSeatOwnership()
        seat.joinVoice("room"); seat.joinStage("room")
        assertFalse(seat.leaveStage())
        assertEquals("room", seat.roomId)
        assertTrue(seat.voiceJoined)
        assertTrue(seat.leaveVoice())
        assertNull(seat.roomId)
    }
    @Test fun `voice exit preserves stage listener but revokes permission`() {
        val seat = RoomSeatOwnership()
        seat.joinVoice("room"); seat.joinStage("room"); seat.stageRole(true)
        val pending = seat.requestMicrophone()!!
        assertFalse(seat.leaveVoice())
        assertFalse(seat.resolveMicrophone(pending, true))
        assertTrue(seat.stageJoined)
        assertTrue(seat.leaveStage())
    }
    @Test fun `same room rejoin cannot reuse permission and other room cannot replace owner`() {
        val seat = RoomSeatOwnership()
        seat.joinVoice("room")
        val old = seat.requestMicrophone()!!
        assertFalse(seat.joinStage("other"))
        assertEquals("room", seat.roomId)
        seat.clear(); seat.joinVoice("room")
        assertFalse(seat.resolveMicrophone(old, true))
        val current = seat.requestMicrophone()!!
        assertTrue(seat.resolveMicrophone(current, true))
        assertFalse(seat.resolveMicrophone(current, true))
    }
    @Test fun `closing permission surface invalidates pending result`() {
        val seat = RoomSeatOwnership()
        seat.joinVoice("room")
        val old = seat.requestMicrophone()!!
        seat.cancelPermission()
        assertFalse(seat.resolveMicrophone(old, true))
    }
}
