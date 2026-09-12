package app.pulse.feature.voice.engine

/**
 * 44-byte RIFF/WAVE writer for the live-caption windows (spec §1.1 VR-7):
 * mono, 16kHz, 16-bit PCM16LE — byteRate 32000, blockAlign 2. The exact
 * header bytes are unit-tested (the ASR endpoint 400s on anything else).
 */
object WavEncoder {

    const val SAMPLE_RATE = VoicePcm.SAMPLE_RATE
    private const val HEADER_BYTES = 44

    /** Encodes [samples] as a complete WAV file image. */
    fun encode(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArray(HEADER_BYTES + dataBytes)

        fun putAscii(at: Int, text: String) {
            for (i in text.indices) out[at + i] = text[i].code.toByte()
        }

        fun putU32Le(at: Int, value: Int) {
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value ushr 8) and 0xFF).toByte()
            out[at + 2] = ((value ushr 16) and 0xFF).toByte()
            out[at + 3] = ((value ushr 24) and 0xFF).toByte()
        }

        fun putU16Le(at: Int, value: Int) {
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        val blockAlign = 2 // mono × 16-bit
        val byteRate = sampleRate * blockAlign

        putAscii(0, "RIFF")
        putU32Le(4, 36 + dataBytes)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putU32Le(16, 16) // fmt chunk size
        putU16Le(20, 1) // PCM
        putU16Le(22, 1) // mono
        putU32Le(24, sampleRate)
        putU32Le(28, byteRate)
        putU16Le(32, blockAlign)
        putU16Le(34, 16) // bits per sample
        putAscii(36, "data")
        putU32Le(40, dataBytes)

        val bytes = VoicePcm.toBytesLE(samples)
        System.arraycopy(bytes, 0, out, HEADER_BYTES, bytes.size)
        return out
    }

    /** WAV image → base64 (the /api/voice/transcribe `audioBase64` field). */
    fun toBase64(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): String =
        Base64Codec.encode(encode(samples, sampleRate))
}
