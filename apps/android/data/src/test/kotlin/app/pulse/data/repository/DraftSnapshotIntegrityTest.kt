package app.pulse.data.repository

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest

class DraftSnapshotIntegrityTest {
    @Test fun anOlderEditCannotAcknowledgeTypingDuringItsSuspendedMessageRead() {
        val writes = DraftWriteCoordinator()
        val key = "composer-local-edit:v1|owner|message"
        val counter = java.util.concurrent.atomic.AtomicLong(1)
        writes.editVersions[key] = counter
        val submitted = app.pulse.protocol.ComposerLocalEditDto("same", "original")
        val captured = counter.get()
        assertEquals(submitted.revision, writes.acknowledgedEditRevision(key,captured,submitted))
        counter.incrementAndGet() // Edit away while messageDao.byId is suspended.
        counter.incrementAndGet() // Edit back to identical text, but a newer intent.
        val newer = app.pulse.protocol.ComposerLocalEditDto("same", "original")
        assertNull(writes.acknowledgedEditRevision(key,captured,newer))
        assertEquals(newer.revision,writes.acknowledgedEditRevision(key,counter.get(),newer))
    }

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

    @Test fun pendingDraftDoesNotDependOnDiskAndReadCompletionCannotHideANewKeystroke() = runTest {
        val writes = DraftWriteCoordinator()
        val revision = java.util.concurrent.atomic.AtomicLong(1)
        writes.versions["room"] = revision
        writes.bufferSnapshot("room", 1, "recovery text")
        assertEquals("recovery text", writes.readSnapshot("room") { throw java.io.IOException("disk unavailable") })
        writes.finishSnapshot("room", 1)
        assertEquals("typed during disk read", writes.readSnapshot("room") {
            revision.set(2); writes.bufferSnapshot("room", 2, "typed during disk read")
            "previous persisted draft"
        })
        revision.set(3); writes.bufferSnapshot("room", 3, "")
        assertEquals("", writes.readSnapshot("room") { error("Explicit clear must not revive disk text") })
    }

}
