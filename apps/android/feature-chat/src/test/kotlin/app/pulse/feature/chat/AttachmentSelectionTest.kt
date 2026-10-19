package app.pulse.feature.chat

import org.junit.Assert.*
import org.junit.Test

class AttachmentSelectionTest {
    @Test fun `selections of the same URI are distinct but an upload update preserves ownership`() {
        val first = StagedMedia(StagedMedia.Kind.IMAGE, "content://same", null, null)
        val second = StagedMedia(StagedMedia.Kind.IMAGE, "content://same", null, null)
        assertNotEquals(first.selectionId, second.selectionId)
        assertEquals(first.selectionId, first.copy(uploadedPath = "uploaded.jpg", uploading = false).selectionId)
        assertEquals(first.selectionId, first.copy(viewOnce = true, sending = true).selectionId)
    }
    @Test fun `voice retry metadata preserves its chosen topic and duration`() {
        val voice = StagedMedia(StagedMedia.Kind.AUDIO, "/voice.m4a", "Voice note.m4a", 200L,
            durationMs = 1700L, intendedTopicId = "original-topic")
        val retry = voice.copy(uploading = true, error = null)
        assertEquals(1700L, retry.durationMs)
        assertEquals("original-topic", retry.intendedTopicId)
        assertEquals(voice.selectionId, retry.selectionId)
    }
    @Test fun `document reads stop at the limit instead of reading an unbounded provider stream`() {
        val source = object : java.io.InputStream() {
            var supplied = 0
            override fun read(): Int { supplied++; return 42 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(42, offset, offset + length); supplied += length; return length
            }
        }
        assertTrue(runCatching { MediaSupport.readBounded(source, 100_000) }.isFailure)
        assertTrue(source.supplied <= 100_000 + 64 * 1024)
        assertArrayEquals(byteArrayOf(1, 2, 3), MediaSupport.readBounded(byteArrayOf(1, 2, 3).inputStream(), 3))
    }

    @Test fun `generic provider MIME does not prevent a supported document import`() {
        assertEquals("application/pdf", MediaSupport.documentMime("application/octet-stream", "receipt.pdf"))
        assertEquals("text/csv", MediaSupport.documentMime("text/csv; charset=utf-8", "report"))
        assertEquals("application/zip", MediaSupport.documentMime(null, "ARCHIVE.ZIP"))
        assertTrue(runCatching { MediaSupport.documentMime("application/octet-stream", "unknown.bin") }.isFailure)
    }

}
