package app.pulse.feature.voice

import app.pulse.feature.voice.engine.Base64Codec
import app.pulse.feature.voice.engine.VoicePcm
import app.pulse.feature.voice.engine.WavEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The /api/voice/transcribe endpoint 400s on anything but the exact 44-byte
 * RIFF/WAVE header (spec §1.1 VR-7) — these are the byte-exact field proofs.
 */
class WavEncoderTest {

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun ascii(b: ByteArray, at: Int, len: Int) = String(b, at, len, Charsets.US_ASCII)

    @Test
    fun `header is exactly 44 bytes with RIFF WAVE fmt magic`() {
        val wav = WavEncoder.encode(ShortArray(4000))
        assertEquals(44 + 8000, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("data", ascii(wav, 36, 4))
    }

    @Test
    fun `fmt fields - PCM mono 16000Hz byteRate 32000 blockAlign 2 bits 16`() {
        val wav = WavEncoder.encode(ShortArray(4000))
        assertEquals(16, u32(wav, 16)) // fmt chunk size
        assertEquals(1, u16(wav, 20)) // PCM format tag
        assertEquals(1, u16(wav, 22)) // mono channels
        assertEquals(16000, u32(wav, 24)) // sample rate
        assertEquals(32000, u32(wav, 28)) // byteRate = 16000 × 2
        assertEquals(2, u16(wav, 32)) // blockAlign = mono × 16-bit
        assertEquals(16, u16(wav, 34)) // bits per sample
    }

    @Test
    fun `sizes - riff chunk and data chunk carry the PCM byte count`() {
        val wav = WavEncoder.encode(ShortArray(4000))
        assertEquals(36 + 8000, u32(wav, 4)) // RIFF size
        assertEquals(8000, u32(wav, 40)) // data size
        val odd = WavEncoder.encode(ShortArray(3))
        assertEquals(44 + 6, odd.size)
        assertEquals(6, u32(odd, 40))
    }

    @Test
    fun `custom sample rate recomputes byteRate`() {
        val wav = WavEncoder.encode(ShortArray(1000), sampleRate = 8000)
        assertEquals(8000, u32(wav, 24))
        assertEquals(16000, u32(wav, 28))
    }

    @Test
    fun `round trip - wav payload decodes back to the exact samples`() {
        val samples = shortArrayOf(0, -1, 32767, -32768, 12345, -12345, 258)
        val wav = WavEncoder.encode(samples)
        val decoded = VoicePcm.fromBytesLE(wav.copyOfRange(44, wav.size))
        assertTrue(samples.contentEquals(decoded))
    }

    @Test
    fun `base64 round trip survives the wire`() {
        val samples = shortArrayOf(-5000, 5000, 1234, -1234)
        val b64 = WavEncoder.toBase64(samples) // base64 of the FULL WAV: 44B header + PCM
        val decoded = VoicePcm.decodeBase64(b64) // shorts incl. the 22 header shorts
        assertEquals(samples.size + 22, decoded.size)
        assertTrue(samples.contentEquals(decoded.copyOfRange(22, decoded.size)))
        // RFC 4648: "QUJD" → "ABC" = 3 bytes; "QQ==" → "A" = 1 byte.
        assertEquals(3, Base64Codec.decode("QUJD").size)
        assertEquals(1, Base64Codec.decode("QQ==").size)
    }
}
