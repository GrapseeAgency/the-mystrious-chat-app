package app.pulse.domain.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ThreadDraftTokenTest {
    @Test fun `thread namespaces cannot collide through delimiters or with the main room`() {
        assertEquals("room", threadDraftKey("room", null))
        assertNotEquals(threadDraftKey("a|b", "c"), threadDraftKey("a", "b|c"))
        assertNotEquals(threadDraftKey("room", "root-a"), threadDraftKey("room", "root-b"))
        assertNotEquals("room", threadDraftKey("room", "root"))
        assertTrue(isThreadDraftKey(threadDraftKey("room", "root")))
    }
    @Test fun `a send token belongs to exactly one account room and thread`() {
        val token = ComposerDraftToken("room", "alice", 5, "root")
        assertTrue(token.owns("room", "alice", "root"))
        assertFalse(token.owns("room", "bob", "root"))
        assertFalse(token.owns("other", "alice", "root"))
        assertFalse(token.owns("room", "alice", "other"))
        assertFalse(token.owns("room", "alice", null))
        assertEquals(threadDraftKey("room", "root"), token.storageKey)
        assertFalse(ComposerDraftToken("room", "alice", 5).owns("room", "alice", "root"))
    }
}
