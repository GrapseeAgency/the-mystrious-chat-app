package app.pulse.feature.chat

import org.junit.Assert.*
import org.junit.Test

class ReceiptOwnedDraftTest {
    @Test fun `identical text after another edit does not belong to an earlier acknowledgement`() {
        val state = ReceiptOwnedDraft()
        var draft by state
        draft = "Schedule this"
        val receipt = state.capture()
        assertTrue(state.matches(receipt))
        draft = "New text"
        draft = "Schedule this"
        assertFalse(state.matches(receipt))
        assertEquals("Schedule this", draft)
    }
    @Test fun `unchanged draft accepts its receipt but replacement composers do not`() {
        val state = ReceiptOwnedDraft()
        var draft by state
        draft = "Same"
        val receipt = state.capture()
        draft = "Same"
        assertTrue(state.matches(receipt))
        val replacement = ReceiptOwnedDraft()
        var next by replacement
        next = "Same"
        assertFalse(replacement.matches(receipt))
        assertTrue(replacement.matches(replacement.capture()))
    }
}
