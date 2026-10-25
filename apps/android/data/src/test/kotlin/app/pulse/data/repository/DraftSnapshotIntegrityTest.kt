package app.pulse.data.repository

import org.junit.Assert.*
import org.junit.Test

class DraftSnapshotIntegrityTest {
    @Test fun recoverySnapshotsAreNotClippedToTheMessageOrMirrorLimit() {
        for (text in listOf("a".repeat(1999) + "🌿" + " tail".repeat(100), "🌿".repeat(1200), "  keep whitespace  ")) {
            assertEquals(text, DraftWriteCoordinator.localSnapshot(text))
        }
        assertEquals("", DraftWriteCoordinator.localSnapshot(" \n\t"))
    }
    @Test fun rapidReopenSeesPendingTextAndAnOldCommitCannotReleaseItsSuccessor() {
        val writes = DraftWriteCoordinator()
        val version = java.util.concurrent.atomic.AtomicLong(1)
        writes.versions["room"] = version
        writes.bufferSnapshot("room", 1, "newest keystroke")
        assertEquals("newest keystroke", writes.currentSnapshot("room", "old disk row"))
        version.set(2)
        writes.bufferSnapshot("room", 2, "")
        writes.bufferSnapshot("room", 1, "late writer")
        writes.finishSnapshot("room", 1)
        assertEquals("", writes.currentSnapshot("room", "old disk row"))
        writes.finishSnapshot("room", 2)
        assertEquals("", writes.currentSnapshot("room", ""))
        version.set(3); writes.bufferSnapshot("room", 3, "pending send")
        version.set(4) // A durable send/clear invalidated this version.
        assertEquals("", writes.currentSnapshot("room", ""))
        assertNull(writes.currentSnapshot("other-room", null))
    }

}
