package app.pulse.feature.stories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composer contract (web story-composer-sheet.tsx parity + wire validation
 * mirror): 280-char hard cap, Text/Photo modes, the 8 palette keys, canPost
 * gating that exactly mirrors POST /api/stories server validation.
 */
class ComposerStateTest {

    @Test
    fun `text mode canPost requires a non-blank caption`() {
        var cs = ComposerState()
        assertFalse("empty caption cannot post", cs.canPost)
        cs = cs.withCaption("   ")
        assertFalse("blank-only caption cannot post", cs.canPost)
        cs = cs.withCaption("hello")
        assertTrue(cs.canPost)
    }

    @Test
    fun `caption is hard-truncated at 280 - never silently 400'd`() {
        val raw = "x".repeat(400)
        val cs = ComposerState().withCaption(raw)
        assertEquals(280, cs.caption.length)
        assertEquals(280, ComposerState.CAPTION_MAX)
    }

    @Test
    fun `photo mode canPost requires an uploaded image and no in-flight work`() {
        var cs = ComposerState(mode = ComposerState.Mode.PHOTO)
        assertFalse("no photo yet", cs.canPost)
        cs = cs.withImage("abc123.jpg")
        assertTrue(cs.canPost)
        cs = cs.withUploading(true)
        assertFalse("upload in flight blocks post", cs.canPost)
        cs = cs.withUploading(false).withPosting(true)
        assertFalse("post in flight blocks re-post", cs.canPost)
    }

    @Test
    fun `picking an image flips into photo mode - clearing it returns to text`() {
        var cs = ComposerState()
        assertEquals(ComposerState.Mode.TEXT, cs.mode)
        cs = cs.withImage("a.png")
        assertEquals(ComposerState.Mode.PHOTO, cs.mode)
        cs = cs.withImage(null)
        assertEquals(ComposerState.Mode.TEXT, cs.mode)
    }

    @Test
    fun `background swatches are a text-mode-only affordance`() {
        val cs = ComposerState(mode = ComposerState.Mode.PHOTO, imagePath = "a.jpg", background = "emerald")
        assertEquals("ignored in photo mode", cs, cs.withBackground("rose"))
        val text = ComposerState().withBackground("violet")
        assertEquals("violet", text.background)
    }

    @Test
    fun `invalid palette keys are rejected - the 8 keys pass`() {
        assertFalse(ComposerState.isValidBackground("chartreuse"))
        ComposerState.BACKGROUNDS.forEach { assertTrue(ComposerState.isValidBackground(it)) }
        assertEquals(
            listOf("emerald", "rose", "amber", "violet", "teal", "orange", "pink", "cyan"),
            ComposerState.BACKGROUNDS,
        )
    }

    @Test
    fun `mode toggle clears stale errors`() {
        val cs = ComposerState().withError("Upload failed")
        assertNull(cs.withMode(ComposerState.Mode.PHOTO).error)
    }

    @Test
    fun `relative time stamp - now, minutes, hours, days`() {
        val now = 1_700_000_000_000L
        assertEquals("now", storyRelativeTime(now - 30_000L, now))
        assertEquals("5m", storyRelativeTime(now - 5 * 60_000L, now))
        assertEquals("3h", storyRelativeTime(now - 3 * 60 * 60_000L, now))
        assertEquals("2d", storyRelativeTime(now - 2L * 24 * 60 * 60_000L, now))
        assertEquals("", storyRelativeTime(0L, now))
    }
}
