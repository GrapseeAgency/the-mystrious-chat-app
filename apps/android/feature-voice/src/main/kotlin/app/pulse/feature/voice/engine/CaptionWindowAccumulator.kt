package app.pulse.feature.voice.engine

/**
 * Caption window accumulator (spec §1.1 VR-7). While I transmit with captions
 * ON, 16kHz samples accumulate; at ≥64000 samples (4s) the window flushes to
 * the transcriber. Rules, all unit-proven:
 *  • single-flight: while a window is in flight (busy), samples KEEP
 *    accumulating but never double-flush;
 *  • final tail: on transmit end a tail ≥16000 (1s) flushes; a SHORTER tail is
 *    skipped (the ASR would return garbage for sub-second audio);
 *  • toggle-off clears the pending audio immediately — nothing captured before
 *    the toggle survives.
 */
class CaptionWindowAccumulator(
    private val windowSamples: Int = WINDOW_SAMPLES,
    private val minTailSamples: Int = MIN_TAIL_SAMPLES,
) {

    /** What the caller must do with the offered block. */
    sealed interface Offer {
        /** Captions off — pending audio was dropped (toggle-off rule). */
        data object Cleared : Offer
        /** Captions on but the gate is closed / nothing to do. */
        data object Held : Offer
        /** Samples appended, no window ready. */
        data object Appended : Offer
        /** A full window is ready for WAV+transcribe (busy is now held). */
        data class Window(val samples: ShortArray) : Offer
    }

    private var buffer = ShortArray(windowSamples * 2)
    private var bufferSize = 0

    /** True while a window is being transcribed (single-flight). */
    var busy: Boolean = false
        private set

    /**
     * Offers one captured block. [transmitting] is the live PTT gate;
     * [captionsOn] the persisted toggle (a fresh-off toggle clears pending).
     */
    fun offer(samples: ShortArray, transmitting: Boolean, captionsOn: Boolean): Offer {
        if (!captionsOn) {
            clear()
            return Offer.Cleared
        }
        if (!transmitting) return Offer.Held
        append(samples)
        if (busy || bufferSize < windowSamples) return Offer.Appended
        return Offer.Window(takeWindow(windowSamples))
    }

    /**
     * Transmit ended: flush the tail when it is long enough (≥16000 samples),
     * otherwise drop it (min-tail rule). A busy window keeps accumulating.
     */
    fun onTransmitEnd(captionsOn: Boolean): Offer {
        if (!captionsOn) {
            clear()
            return Offer.Cleared
        }
        if (busy) return Offer.Held
        if (bufferSize >= minTailSamples) return Offer.Window(takeWindow(bufferSize))
        clear()
        return Offer.Cleared
    }

    /** Marks the in-flight transcribe; releases with [endFlush]. */
    fun beginFlush() {
        busy = true
    }

    fun endFlush() {
        busy = false
    }

    /** Toggle-off / leave — pending audio is dropped, never transcribed. */
    fun clear() {
        bufferSize = 0
        busy = false
    }

    /** Test/diagnostic exposure. */
    val pendingSamples: Int get() = bufferSize

    private fun append(samples: ShortArray) {
        if (bufferSize + samples.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(windowSamples * 2, (bufferSize + samples.size) * 2))
        }
        System.arraycopy(samples, 0, buffer, bufferSize, samples.size)
        bufferSize += samples.size
    }

    private fun takeWindow(size: Int): ShortArray {
        val window = buffer.copyOf(size)
        bufferSize -= size
        if (bufferSize > 0) {
            System.arraycopy(buffer, size, buffer, 0, bufferSize)
        }
        busy = true
        return window
    }

    companion object {
        /** 4s at 16kHz — the full caption window. */
        const val WINDOW_SAMPLES = 64_000
        /** 1s — the minimum flushable tail on transmit end. */
        const val MIN_TAIL_SAMPLES = 16_000
    }
}
