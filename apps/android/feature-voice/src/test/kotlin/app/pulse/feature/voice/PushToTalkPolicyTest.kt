package app.pulse.feature.voice

import app.pulse.feature.voice.engine.PushToTalkPolicy
import org.junit.Assert.*
import org.junit.Test

class PushToTalkPolicyTest {
    @Test fun `press starts and hold release stops at the boundary`() {
        val ptt = PushToTalkPolicy()
        assertTrue(ptt.press(false))
        assertFalse(ptt.release(260))
    }
    @Test fun `short tap latches and second tap unlatches`() {
        val ptt = PushToTalkPolicy()
        assertTrue(ptt.press(false))
        assertTrue(ptt.release(80))
        assertFalse(ptt.press(true))
        assertFalse(ptt.release(80))
    }
    @Test fun `cancellation of short press never latches`() {
        val ptt = PushToTalkPolicy()
        assertTrue(ptt.press(false))
        ptt.cancel()
        assertFalse(ptt.release(10))
        assertTrue(ptt.press(false))
        assertTrue(ptt.release(10))
    }
}
