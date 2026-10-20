package app.pulse.feature.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioRoutingTest {
    @Test fun `only the owned room can deliver peer audio`() {
        assertTrue(acceptsRoomAudio("room", "room", "me", "peer"))
        assertFalse(acceptsRoomAudio(null, "room", "me", "peer"))
        assertFalse(acceptsRoomAudio("next", "room", "me", "peer"))
        assertFalse(acceptsRoomAudio("room", "room", "me", "me"))
        assertFalse(acceptsRoomAudio("", "", "me", "peer"))
    }
}
