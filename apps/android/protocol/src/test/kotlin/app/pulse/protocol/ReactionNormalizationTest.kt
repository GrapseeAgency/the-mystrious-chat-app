package app.pulse.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReactionNormalizationTest {
    @Test fun `stable IDs and every historical glyph share their logical reaction`() {
        for (id in REACTION_IDS) assertEquals(id, reactionId(id))
        for ((glyph, id) in REACTION_LEGACY) {
            assertEquals(id, reactionId(glyph))
            assertEquals(id, reactionId(glyph + "\uFE0E"))
            assertEquals(id, reactionId(glyph + "\uFE0F"))
        }
        assertEquals("heart", reactionId("unknown"))
    }
}
