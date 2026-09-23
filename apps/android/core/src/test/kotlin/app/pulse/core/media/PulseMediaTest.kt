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

    @Test
    fun `voice bubble bars match the web voiceBars LCG exactly`() {
        // Vectors generated from the web implementation (chat-room.tsx:6232 +
        // pulse-utils.ts:59 hashString) via node — the natives must render the
        // SAME decorative bars as the web for the same message id.
        assertEquals(
            listOf(52, 65, 97, 88, 98, 78, 36, 47, 73, 38, 43, 55, 93, 84, 41, 51, 64, 66, 85, 34, 46, 87, 38, 33, 99, 75),
            PulseMedia.voiceBubbleBars("abc123"),
        )
        assertEquals(
            listOf(60, 76, 30, 95, 55, 45, 58, 49, 30, 48, 46, 93, 92, 66, 50, 98, 40, 77, 80, 68, 68, 76, 85, 38, 36, 61),
            PulseMedia.voiceBubbleBars("local-temp"),
        )
        // 26 bars by default (web count), heights always in the 28..100 band.
        assertEquals(26, PulseMedia.voiceBubbleBars("any-id").size)
        assertTrue(PulseMedia.voiceBubbleBars("any-id").all { it in 28..100 })
        // Deterministic — same id, same bars, every process.
        assertEquals(PulseMedia.voiceBubbleBars("abc123"), PulseMedia.voiceBubbleBars("abc123"))
    }

    @Test
    fun `record amplitude normalizes into the 0-1 meter range`() {
        assertEquals(0f, PulseMedia.normalizeRecordAmplitude(0))
        assertEquals(0f, PulseMedia.normalizeRecordAmplitude(-5))
        assertEquals(1f, PulseMedia.normalizeRecordAmplitude(32767))
        assertEquals(0.5f, PulseMedia.normalizeRecordAmplitude(16384), 0.001f)
    }

    @Test
    fun `voice caps match the wire contract`() {
        assertEquals(600L, PulseMedia.MIN_VOICE_MS)
        assertEquals(600_000L, PulseMedia.MAX_VOICE_MS)
        assertEquals(PulseMedia.RECORD_WAVEFORM_BARS, 40)
    }
}
