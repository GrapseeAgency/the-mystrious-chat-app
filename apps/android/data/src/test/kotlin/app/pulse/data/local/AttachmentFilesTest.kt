package app.pulse.data.local

import app.pulse.domain.model.AttachmentDraft
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AttachmentFilesTest {
    @Test fun `bytes reopen under original account and corrupted or escaping references are refused`() {
        val root = kotlin.io.path.createTempDirectory("attachment-journal").toFile()
        try {
            val first = AttachmentFiles(File(root, "account-a"))
            val value = "data:image/jpeg;base64," + "AAAA".repeat(600_000) // larger than a CursorWindow
            val id = "attachment-selection-0001"
            val key = first.put(id, value)
            assertEquals(value, AttachmentFiles(File(root, "account-a")).read(key))
            assertEquals(key, first.put(id, value))
            assertTrue(runCatching { AttachmentFiles(File(root, "account-b")).read(key) }.isFailure)
            assertTrue(runCatching { first.read("../../other") }.isFailure)
            File(root, "account-a/$key.upload").writeText("damaged")
            assertTrue(runCatching { first.read(key) }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun `cleanup keeps referenced and recently prepared files`() {
        val root = kotlin.io.path.createTempDirectory("attachment-cleanup").toFile()
        try {
            val files = AttachmentFiles(root)
            val held = files.put("attachment-held-0001", "held")
            val orphan = files.put("attachment-old-0001", "orphan")
            val fresh = files.put("attachment-fresh-0001", "fresh")
            File(root, "$held.upload").setLastModified(1)
            File(root, "$orphan.upload").setLastModified(1)
            files.prune(setOf(held))
            assertEquals("held", files.read(held))
            assertEquals("fresh", files.read(fresh))
            assertFalse(File(root, "$orphan.upload").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun `invalid media never enters the draft journal`() {
        val draft = AttachmentDraft("attachment-test-0001", "room", "file", "data:text/plain;base64,aGVsbG8=", "notes.txt")
        assertEquals(5L, AttachmentValidation.validated(draft).fileSize)
        assertTrue(runCatching { AttachmentValidation.validated(draft.copy(dataUrl = "data:application/octet-stream;base64,aGVsbG8=")) }.isFailure)
        assertTrue(runCatching { AttachmentValidation.validated(draft.copy(dataUrl = "data:text/plain;base64,")) }.isFailure)
        assertTrue(runCatching { AttachmentValidation.validated(draft.copy(viewOnce = true)) }.isFailure)
    }
}
