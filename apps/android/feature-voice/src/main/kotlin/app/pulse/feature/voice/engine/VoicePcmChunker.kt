package app.pulse.feature.voice.engine

/**
 * The 250ms transmit block builder (spec §1.1 VR-4 / WEB DEFECT FIX #1).
 *
 * The web's CRITICAL defect was a transmit path that never armed its block
 * buffer — no voice:chunk ever left the browser. This chunker is the native
 * equivalent and its unit tests are the PROOF that chunks actually flow:
 * 16kHz mono Int16 in, 4000-sample base64-bound blocks out, seq starting at 1,
 * a proportional partial flush on release, and a hard reset for new sessions.
 *
 * Seq semantics: seq is CONTINUOUS for the whole room session (join → leave),
 * NOT per transmission — restarting at 1 on every push would trip every
 * receiver's `seq <= lastSeq` dedupe and blackhole the audio (the exact web
 * rejoin defect the roster-reset fix answers on the receive side).
 */
class VoicePcmChunker(private val blockSize: Int = VoicePcm.BLOCK_SAMPLES) {

    /** One ready-to-emit block: [seq] starts at 1; [samples] ≤ [blockSize]. */
    class Chunk(val seq: Long, val samples: ShortArray)

    private var nextSeq = 1L
    private var window = ShortArray(blockSize * 2)
    private var windowSize = 0

    /** Total samples accepted since the last [reset] — test/diagnostic exposure. */
    var consumedSamples: Long = 0L
        private set

    /**
     * Feeds captured samples; returns every FULL block that became available.
     * Remainders stay buffered until they fill or [flush] is called.
     */
    fun consume(samples: ShortArray): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var offset = 0
        while (offset < samples.size) {
            val space = blockSize - windowSize
            val take = minOf(space, samples.size - offset)
            if (windowSize + take > window.size) {
                window = window.copyOf(maxOf(blockSize, (windowSize + take) * 2))
            }
            System.arraycopy(samples, offset, window, windowSize, take)
            windowSize += take
            offset += take
            if (windowSize == blockSize) {
                chunks += Chunk(nextSeq++, window.copyOf(blockSize))
                windowSize = 0
            }
        }
        consumedSamples += samples.size
        return chunks
    }

    /**
     * Release-time flush: any PARTIAL window emits as one proportionally
     * smaller block (never padded with silence), then the window resets and
     * the sequence keeps counting. Null when there is nothing partial.
     */
    fun flush(): Chunk? {
        if (windowSize == 0) return null
        val chunk = Chunk(nextSeq++, window.copyOf(windowSize))
        windowSize = 0
        return chunk
    }

    /** New session (join): clears the window and restarts the sequence at 1 (VR-9). */
    fun reset() {
        nextSeq = 1
        windowSize = 0
        consumedSamples = 0
    }
}
