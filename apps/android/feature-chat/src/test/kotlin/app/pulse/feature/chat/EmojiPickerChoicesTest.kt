package app.pulse.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R5-B ITEM 1 — the composer emoji strip must be BYTE-IDENTICAL to the web's
 * EMOJI_PICKER_CHOICES (src/lib/pulse-utils.ts:146-149): exactly 24 glyphs in
 * the same order, grid = 8 columns (chat-room.tsx:5398 grid-cols-8).
 */
class EmojiPickerChoicesTest {

    @Test
    fun `picker holds exactly 24 emoji`() {
        assertEquals(24, EMOJI_PICKER_CHOICES.size)
    }

    @Test
    fun `picker order is byte-identical to web EMOJI_PICKER_CHOICES`() {
        val expected = listOf(
            "😀", "😂", "🥹", "😍", "😎", "🤔", "😴", "🥳",
            "👍", "🙏", "👏", "🔥", "❤️", "💜", "✨", "🎉",
            "🚀", "🌈", "☀️", "🌙", "☕", "🍕", "🎂", "⚽",
        )
        assertEquals(expected, EMOJI_PICKER_CHOICES)
    }

    @Test
    fun `grid columns match web grid-cols-8`() {
        assertEquals(8, EMOJI_GRID_COLUMNS)
        assertEquals(3, EMOJI_PICKER_CHOICES.chunked(EMOJI_GRID_COLUMNS).size)
        // every row after the chunk split keeps 8 cells (24 = 3 × 8)
        assertEquals(listOf(8, 8, 8), EMOJI_PICKER_CHOICES.chunked(EMOJI_GRID_COLUMNS).map { it.size })
    }
}
