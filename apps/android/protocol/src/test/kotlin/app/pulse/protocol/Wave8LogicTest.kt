package app.pulse.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wave 8 — prefs pipeline + quiet-hours parity tests.
 * Locks the 1:1 port of web src/lib/prefs-defaults.ts mergePrefs and
 * src/lib/pulse-settings.ts isQuietHoursNow/minutesOf (lines 60-83).
 */
class Wave8LogicTest {

    // ── mergePrefs: defaults + clamping parity ────────────────────

    @Test
    fun `null blank and garbage blobs decode to defaults`() {
        for (raw in listOf(null, "", "   ", "not json", "[1,2,3]", "\"text\"", "{}")) {
            val prefs = PulseWave8Logic.mergePrefs(raw)
            assertEquals(PulseWave8Logic.DEFAULTS, prefs)
        }
    }

    @Test
    fun `known values win over defaults`() {
        val prefs = PulseWave8Logic.mergePrefs(
            """{"bubbleRadius":"pill","density":"compact","wallpaper":"forest",
                "notifPreviews":false,"notifSound":false,"notifVibrate":true,
                "lastSeenVisible":false,"readReceipts":false,"typingVisible":false,
                "reducedMotion":true}""",
        )
        assertEquals("pill", prefs.bubbleRadius)
        assertEquals("compact", prefs.density)
        assertEquals("forest", prefs.wallpaper)
        assertEquals(false, prefs.notifPreviews)
        assertEquals(false, prefs.notifSound)
        assertEquals(true, prefs.notifVibrate)
        assertEquals(false, prefs.lastSeenVisible)
        assertEquals(false, prefs.readReceipts)
        assertEquals(false, prefs.typingVisible)
        assertEquals(true, prefs.reducedMotion)
    }

    @Test
    fun `unknown enum values are clamped back to defaults`() {
        val prefs = PulseWave8Logic.mergePrefs(
            """{"bubbleRadius":"xl","density":"dense","wallpaper":"sunset"}""",
        )
        // discard junk → the DEFAULT wins (web mergePrefs whitelist parity)
        assertEquals("lg", prefs.bubbleRadius)
        assertEquals("cozy", prefs.density)
        assertEquals("none", prefs.wallpaper)
    }

    @Test
    fun `wrong-typed fields are discarded like web typeof checks`() {
        val prefs = PulseWave8Logic.mergePrefs(
            """{"bubbleRadius":7,"density":true,"wallpaper":[],
                "notifPreviews":"yes","notifSound":1,
                "reducedMotion":null}""",
        )
        assertEquals("lg", prefs.bubbleRadius)
        assertEquals("cozy", prefs.density)
        assertEquals("none", prefs.wallpaper)
        assertEquals(true, prefs.notifPreviews)
        assertEquals(true, prefs.notifSound)
        assertEquals(false, prefs.reducedMotion)
    }

    @Test
    fun `unknown keys never break the decode`() {
        val prefs = PulseWave8Logic.mergePrefs(
            """{"theme":"indigo","futureFlag":true,"chat":{"convThemes":{}},"density":"compact"}""",
        )
        assertEquals("compact", prefs.density)
        assertEquals(PulseWave8Logic.DEFAULTS.copy(density = "compact"), prefs)
    }

    @Test
    fun `fx webglMode decodes only inside the whitelist and stays absent otherwise`() {
        val ok = PulseWave8Logic.mergePrefs("""{"fx.webglMode":"caustics"}""")
        assertEquals("caustics", ok.fxWebglMode)
        val junk = PulseWave8Logic.mergePrefs("""{"fx.webglMode":"indigo"}""")
        assertNull(junk.fxWebglMode)
    }

    @Test
    fun `partial blob keeps defaults for missing fields`() {
        val prefs = PulseWave8Logic.mergePrefs("""{"wallpaper":"dusk"}""")
        assertEquals("dusk", prefs.wallpaper)
        assertEquals("lg", prefs.bubbleRadius)
        assertEquals(true, prefs.readReceipts)
        assertEquals(false, prefs.notifVibrate)
    }

    @Test
    fun `mergePatch shallow-merges only carried fields`() {
        val current = PulseWave8Logic.mergePrefs("""{"wallpaper":"dusk","density":"compact"}""")
        val merged = PulseWave8Logic.mergePatch(current, WirePulsePrefs(notifSound = false))
        assertEquals("dusk", merged.wallpaper)
        assertEquals("compact", merged.density)
        assertEquals(false, merged.notifSound)
        assertEquals(true, merged.notifPreviews)
    }

    @Test
    fun `patch json serializes only carried fields with the wire keys`() {
        val body = WirePulsePrefs(reducedMotion = true, bubbleRadius = "md").toPatchJson("u1")
        val text = body.toString()
        assertTrue(text.contains("\"userId\":\"u1\""))
        assertTrue(text.contains("\"reducedMotion\":true"))
        assertTrue(text.contains("\"bubbleRadius\":\"md\""))
        // untouched fields stay OUT of the body (server shallow-merges)
        assertFalse(text.contains("density"))
        assertFalse(text.contains("notifSound"))
        assertFalse(text.contains("wallpaper"))
    }

    // ── minutesOf parity (web pulse-settings.ts:60-67) ────────────

    @Test
    fun `minutesOf parses padded and unpadded hours`() {
        assertEquals(22 * 60, PulseWave8Logic.minutesOf("22:00"))
        assertEquals(7 * 60, PulseWave8Logic.minutesOf("07:00"))
        assertEquals(7 * 60, PulseWave8Logic.minutesOf("7:00"))
        assertEquals(0, PulseWave8Logic.minutesOf("00:00"))
        assertEquals(23 * 60 + 59, PulseWave8Logic.minutesOf("23:59"))
    }

    @Test
    fun `minutesOf falls back to zero on malformed input`() {
        assertEquals(0, PulseWave8Logic.minutesOf("7:5")) // minutes must be 2 digits
        assertEquals(0, PulseWave8Logic.minutesOf("abc"))
        assertEquals(0, PulseWave8Logic.minutesOf(""))
        assertEquals(0, PulseWave8Logic.minutesOf("12"))
        // the web trims before matching, so padded-but-valid input parses
        assertEquals(22 * 60, PulseWave8Logic.minutesOf(" 22:00 "))
    }

    @Test
    fun `minutesOf clamps out-of-range parts like parseInt plus clamp`() {
        // regex admits "99:99" (1-2 digits) — the clamp pins 23:59
        assertEquals(23 * 60 + 59, PulseWave8Logic.minutesOf("99:99"))
        assertEquals(23 * 60 + 59, PulseWave8Logic.minutesOf("25:61"))
    }

    // ── isQuietHoursNow parity (web pulse-settings.ts:73-83) ──────

    @Test
    fun `toggle off is always false`() {
        assertFalse(PulseWave8Logic.isQuietHoursNow(false, "22:00", "07:00", 23 * 60 + 30))
    }

    @Test
    fun `degenerate equal window is off`() {
        assertFalse(PulseWave8Logic.isQuietHoursNow(true, "22:00", "22:00", 22 * 60))
        assertFalse(PulseWave8Logic.isQuietHoursNow(true, "00:00", "00:00", 0))
    }

    @Test
    fun `normal window is start inclusive end exclusive`() {
        val on = true
        // 22:00 → 23:00 (within, not overnight)
        assertTrue(PulseWave8Logic.isQuietHoursNow(on, "22:00", "23:00", 22 * 60))
        assertTrue(PulseWave8Logic.isQuietHoursNow(on, "22:00", "23:00", 22 * 60 + 59))
        assertFalse(PulseWave8Logic.isQuietHoursNow(on, "22:00", "23:00", 23 * 60))
        assertFalse(PulseWave8Logic.isQuietHoursNow(on, "22:00", "23:00", 21 * 60 + 59))
    }

    @Test
    fun `overnight window wraps across midnight`() {
        val on = true
        // 22:00 → 07:00 default window
        assertTrue(PulseWave8Logic.isQuietHoursNow(on, "22:00", "07:00", 22 * 60))
        assertTrue(PulseWave8Logic.isQuietHoursNow(on, "22:00", "07:00", 23 * 60 + 59))
        assertTrue(PulseWave8Logic.isQuietHoursNow(on, "22:00", "07:00", 0))
        assertTrue(PulseWave8Logic.isQuietHoursNow(on, "22:00", "07:00", 6 * 60 + 59))
        assertFalse(PulseWave8Logic.isQuietHoursNow(on, "22:00", "07:00", 7 * 60))
        assertFalse(PulseWave8Logic.isQuietHoursNow(on, "22:00", "07:00", 21 * 60 + 59))
        assertFalse(PulseWave8Logic.isQuietHoursNow(on, "22:00", "07:00", 12 * 60))
    }

    @Test
    fun `degenerate windows via clamped garbage match web behaviour`() {
        // "99:99" clamps to 23:59 on both ends → start==end → off
        assertFalse(PulseWave8Logic.isQuietHoursNow(true, "99:99", "23:59", 23 * 60))
        // malformed strings fall back to 00:00 → start==end → off
        assertFalse(PulseWave8Logic.isQuietHoursNow(true, "abc", "xyz", 13 * 60))
    }

    // ── incoming attention gate ──────────────────────────────────

    @Test
    fun `attention is gated by quiet hours and needs one armed channel`() {
        // quiet hours on → nothing, regardless of toggles
        assertFalse(PulseWave8Logic.incomingAttentionAllowed(notifSound = true, notifVibrate = true, quietNow = true))
        // outside quiet hours → sound alone / vibrate alone / both
        assertTrue(PulseWave8Logic.incomingAttentionAllowed(notifSound = true, notifVibrate = false, quietNow = false))
        assertTrue(PulseWave8Logic.incomingAttentionAllowed(notifSound = false, notifVibrate = true, quietNow = false))
        assertTrue(PulseWave8Logic.incomingAttentionAllowed(notifSound = true, notifVibrate = true, quietNow = false))
        // everything disarmed → no attention even outside quiet hours
        assertFalse(PulseWave8Logic.incomingAttentionAllowed(notifSound = false, notifVibrate = false, quietNow = false))
    }

    @Test
    fun `preview text hides the body when notifPreviews is off`() {
        assertEquals("Ada: hello world", PulseWave8Logic.incomingPreviewText(true, "Ada", "hello world"))
        assertEquals("New message", PulseWave8Logic.incomingPreviewText(false, "Ada", "hello world"))
        assertEquals(
            "Ada: ${"x".repeat(80)}…",
            PulseWave8Logic.incomingPreviewText(true, "Ada", "x".repeat(120)),
        )
    }
}
