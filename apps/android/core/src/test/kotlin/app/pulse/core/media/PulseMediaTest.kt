package app.pulse.core.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure media helper pins — Wave 1 spec §1.1 upload rules. */
class PulseMediaTest {

    @Test
    fun `mime mapping covers the wire whitelist and degrades honestly`() {
        assertEquals("application/pdf", PulseMedia.mimeForFileName("contract.PDF"))
        assertEquals("application/zip", PulseMedia.mimeForFileName("bundle.zip"))
        assertEquals("text/plain", PulseMedia.mimeForFileName("notes.txt"))
        assertEquals("text/csv", PulseMedia.mimeForFileName("data.csv"))
        // unknown extension → octet-stream (server refuses; the UI surfaces it)
        assertEquals("application/octet-stream", PulseMedia.mimeForFileName("virus.exe"))
        assertEquals("application/octet-stream", PulseMedia.mimeForFileName(null))
        assertEquals("application/octet-stream", PulseMedia.mimeForFileName("noext"))
    }

    @Test
    fun `human file size reads like the web bubble meta`() {
        assertEquals("", PulseMedia.humanFileSize(null))
        assertEquals("", PulseMedia.humanFileSize(0L))
        assertEquals("2.0 KB", PulseMedia.humanFileSize(2048L))
        assertEquals("850 KB", PulseMedia.humanFileSize(870_400L))
        assertEquals("2 MB", PulseMedia.humanFileSize(2L * 1024 * 1024))
        assertEquals("3.2 MB", PulseMedia.humanFileSize((3.2 * 1024 * 1024).toLong()))
    }

    @Test
    fun `data url mime parses and rejects malformed input`() {
        assertEquals("image/jpeg", PulseMedia.mimeOfDataUrl("data:image/jpeg;base64,QUJD"))
        assertEquals("application/pdf", PulseMedia.mimeOfDataUrl("data:application/pdf;base64,QUJD"))
        assertNull(PulseMedia.mimeOfDataUrl("https://example.com/file.pdf"))
        assertNull(PulseMedia.mimeOfDataUrl("data:"))
        assertNull(PulseMedia.mimeOfDataUrl("data:image/png"))
    }

    @Test
    fun `document cap is 10MB inclusive`() {
        assertTrue(PulseMedia.documentFits(1L))
        assertTrue(PulseMedia.documentFits(PulseMedia.MAX_DOCUMENT_BYTES))
        assertFalse(PulseMedia.documentFits(PulseMedia.MAX_DOCUMENT_BYTES + 1))
        assertFalse(PulseMedia.documentFits(0L))
    }

    @Test
    fun `document mime whitelist matches the spec set`() {
        assertEquals(
            listOf("application/pdf", "application/zip", "text/plain", "text/csv"),
            PulseMedia.DOCUMENT_MIMES,
        )
        assertEquals(PulseMedia.DOCUMENT_MIMES.size, PulseMedia.DOCUMENT_MIME_ARRAY.size)
    }
}
