package app.pulse.feature.voice.engine

/**
 * RFC 4648 base64 — a tiny pure-Kotlin codec so the voice pipeline never
 * touches platform codecs: android.util.Base64 does not exist on the JVM
 * test classpath and java.util.Base64 needs API 26 (minSdk is 21). The wire
 * carries base64(Int16LE PCM 16kHz) in every voice:chunk and caption WAV.
 */
internal object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n ushr 18) and 0x3F])
                .append(ALPHABET[(n ushr 12) and 0x3F])
                .append(ALPHABET[(n ushr 6) and 0x3F])
                .append(ALPHABET[n and 0x3F])
            i += 3
        }
        val rest = data.size - i
        if (rest == 1) {
            val n = (data[i].toInt() and 0xFF) shl 16
            out.append(ALPHABET[(n ushr 18) and 0x3F])
                .append(ALPHABET[(n ushr 12) and 0x3F])
                .append("==")
        } else if (rest == 2) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8)
            out.append(ALPHABET[(n ushr 18) and 0x3F])
                .append(ALPHABET[(n ushr 12) and 0x3F])
                .append(ALPHABET[(n ushr 6) and 0x3F])
                .append('=')
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val cleaned = text.trim().replace("\n", "").replace("\r", "")
        val out = ByteArray(cleaned.length * 3 / 4 + 3)
        var outLen = 0
        var buffer = 0
        var bits = 0
        for (ch in cleaned) {
            if (ch == '=' || ch == ' ') continue
            val value = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return out.copyOf(outLen) // tolerant: stop at the first foreign char
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outLen++] = ((buffer ushr bits) and 0xFF).toByte()
            }
        }
        return out.copyOf(outLen)
    }
}

/**
 * 16kHz mono Int16 PCM helpers shared by the capture and playback legs:
 * ShortArray ↔ little-endian ByteArray ↔ base64 (the wire form of voice:chunk).
 */
internal object VoicePcm {

    const val SAMPLE_RATE = 16_000
    /** 250ms transmit blocks — 4000 samples at 16kHz (spec §0). */
    const val BLOCK_SAMPLES = 4_000
    /** Capture read granularity (100ms) — the chunker re-blocks to 250ms. */
    const val READ_SAMPLES = 1_600

    fun toBytesLE(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        return out
    }

    fun fromBytesLE(data: ByteArray): ShortArray {
        val out = ShortArray(data.size / 2)
        for (i in out.indices) {
            out[i] = ((data[i * 2].toInt() and 0xFF) or
                ((data[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        return out
    }

    fun encodeBase64(samples: ShortArray): String = Base64Codec.encode(toBytesLE(samples))

    fun decodeBase64(data: String): ShortArray = fromBytesLE(Base64Codec.decode(data))

    /** Block duration in ms for a given sample count at 16kHz (integer, floor). */
    fun durationMs(samples: Int): Long = samples * 1_000L / SAMPLE_RATE.toLong()
}
