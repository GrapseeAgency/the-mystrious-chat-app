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
    @Test fun `decoded media ceiling matches the server encoded cap implication`() {
        // R42 parity: images/audio are capped at 3,375,000 DECODED bytes - the
        // same bound the 4,500,000-char encoded media ceiling implies. One byte
        // past is refused (the encoded cap fires first here; the decoded check
        // is defense-in-depth for encodings that slip under it).
        val over = java.util.Base64.getEncoder().encodeToString(ByteArray(3_375_001)).removeSuffix("=")
        val draft = AttachmentDraft("attachment-test-0001", "room", "image", "data:image/jpeg;base64,$over")
        assertTrue(runCatching { AttachmentValidation.validated(draft) }.isFailure)
        val under = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(ByteArray(16))
        assertEquals(16L, AttachmentValidation.validated(draft.copy(dataUrl = under)).fileSize)
    }
    @Test fun `caption limit matches gateway UTF16 length before upload`() {
        val draft = AttachmentDraft("attachment-test-0001", "room", "image", "data:image/jpeg;base64,YQ==")
        assertEquals(2000, AttachmentValidation.validated(draft.copy(caption = "🌿".repeat(1000))).caption.length)
        assertTrue(runCatching { AttachmentValidation.validated(draft.copy(caption = "🌿".repeat(1001))) }.isFailure)
    }

    @Test fun `frozen metadata survives validation and local pending reply IDs are refused`() {
        val draft = AttachmentDraft("attachment-test-0001", "room", "image", "data:image/jpeg;base64,YQ==",
            caption = "kept", viewOnce = true, topicId = "topic", replyToId = "remote-message", anon = true)
        val valid = AttachmentValidation.validated(draft)
        assertEquals(draft.copy(fileSize = 1), valid)
        val reopened = app.pulse.protocol.PulseJson.decodeFromString(AttachmentDraft.serializer(),
            app.pulse.protocol.PulseJson.encodeToString(AttachmentDraft.serializer(), valid))
        assertEquals(valid, reopened)
        assertTrue(runCatching { AttachmentValidation.validated(draft.copy(replyToId = "local_pending")) }.isFailure)
        assertTrue(runCatching { AttachmentValidation.validated(draft.copy(replyToId = "")) }.isFailure)
    }

}
