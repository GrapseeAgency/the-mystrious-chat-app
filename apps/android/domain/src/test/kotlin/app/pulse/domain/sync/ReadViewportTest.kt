package app.pulse.domain.sync

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReadViewportTest {
    @Test fun `prefetched rows and tiny clipped slices cannot acknowledge a message`() {
        assertTrue(hasReadViewportOverlap(50, 100, 0, 500, 44))
        assertFalse(hasReadViewportOverlap(550, 100, 0, 500, 44))
        assertFalse(hasReadViewportOverlap(490, 100, 0, 500, 44))
        assertFalse(hasReadViewportOverlap(-120, 100, 0, 500, 44))
        assertTrue(hasReadViewportOverlap(-100, 800, 0, 500, 44))
    }

    @Test fun `short rows must fit and density threshold uses actual viewport padding`() {
        assertTrue(hasReadViewportOverlap(200, 20, 0, 500, 44))
        assertFalse(hasReadViewportOverlap(-1, 20, 0, 500, 44))
        assertTrue(hasReadViewportOverlap(-10, 80, -20, 500, 88))
        assertFalse(hasReadViewportOverlap(450, 100, 0, 500, 88))
        assertFalse(hasReadViewportOverlap(0, 0, 0, 500, 44))
        assertFalse(hasReadViewportOverlap(0, 100, 0, 0, 44))
        assertFalse(hasReadViewportOverlap(Int.MAX_VALUE - 10, 100, 0, 500, 44))
    }
}
