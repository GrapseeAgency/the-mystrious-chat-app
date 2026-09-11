package app.pulse.core.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wave 2 depth logic — the PURE contracts the UI layers share (spec WAVE2 §1):
 * voice rounding/floor, the unfurl trigger, and the view-once state machine.
 */
class PulseWave2LogicTest {

    // ── voice notes ──────────────────────────────────────────

    @Test
    fun voiceDurationQuantizesTo100msAndNeverSendsZero() {
        assertEquals(600L, PulseMedia.voiceDurationMs(647))
        assertEquals(700L, PulseMedia.voiceDurationMs(651))
        assertEquals(1000L, PulseMedia.voiceDurationMs(1000))
        // 40ms quantizes to 0 → clamped to 1 (never send 0).
        assertEquals(1L, PulseMedia.voiceDurationMs(40))
        assertEquals(1L, PulseMedia.voiceDurationMs(0))
    }

    @Test
    fun voiceFloorMatchesWebMinVoiceMs() {
        assertEquals(600L, PulseMedia.MIN_VOICE_MS)
    }

    // ── link previews ────────────────────────────────────────

    @Test
    fun unfurlCandidateDetectsHttpAndBareWww() {
        assertTrue(PulseMedia.isUnfurlCandidate("look at https://example.com/x"))
        assertTrue(PulseMedia.isUnfurlCandidate("http://plain.org"))
        assertTrue(PulseMedia.isUnfurlCandidate("see www.pulse.chat now"))
        assertTrue(PulseMedia.isUnfurlCandidate("HTTPS://UPPER.CASE"))
        assertFalse(PulseMedia.isUnfurlCandidate("no links here"))
        assertFalse(PulseMedia.isUnfurlCandidate("htp://typo.example"))
    }

    // ── view-once state machine (spec §1 row 6) ──────────────

    @Test
    fun viewOnceStateGatesThenBurnsForNonSenders() {
        val gated = PulseMedia.viewOnceState(
            viewOnce = true, viewedAtMs = null, mine = false, hasImage = true,
        )
        assertEquals(PulseMedia.ViewOnceState.GATED, gated)

        val burned = PulseMedia.viewOnceState(
            viewOnce = true, viewedAtMs = 1_700_000_000_000, mine = false, hasImage = true,
        )
        assertEquals(PulseMedia.ViewOnceState.BURNED, burned)
    }

    @Test
    fun viewOnceSenderAlwaysSeesOwnPhoto() {
        assertEquals(
            PulseMedia.ViewOnceState.NONE,
            PulseMedia.viewOnceState(viewOnce = true, viewedAtMs = null, mine = true, hasImage = true),
        )
        assertEquals(
            PulseMedia.ViewOnceState.NONE,
            PulseMedia.viewOnceState(viewOnce = true, viewedAtMs = 42L, mine = true, hasImage = true),
        )
    }

    @Test
    fun viewOnceRequiresImageAndFlag() {
        assertEquals(
            PulseMedia.ViewOnceState.NONE,
            PulseMedia.viewOnceState(viewOnce = true, viewedAtMs = null, mine = false, hasImage = false),
        )
        assertEquals(
            PulseMedia.ViewOnceState.NONE,
            PulseMedia.viewOnceState(viewOnce = false, viewedAtMs = null, mine = false, hasImage = true),
        )
    }
}
