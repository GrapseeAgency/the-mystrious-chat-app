package app.pulse.feature.voice

import app.pulse.feature.voice.engine.CaptionWindowAccumulator
import app.pulse.feature.voice.engine.CaptionWindowAccumulator.Companion.MIN_TAIL_SAMPLES
import app.pulse.feature.voice.engine.CaptionWindowAccumulator.Companion.WINDOW_SAMPLES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live-caption windowing contract (spec §1.1 VR-7): 4s windows at 16kHz, a
 * single-flight transcriber, an honest min-tail rule on transmit end, and a
 * toggle-off that drops pending audio.
 */
class CaptionWindowAccumulatorTest {

    private val machine = CaptionWindowAccumulator()

    private fun offer(samples: Int, transmitting: Boolean = true, captionsOn: Boolean = true) =
        machine.offer(ShortArray(samples), transmitting, captionsOn)

    @Test
    fun `flushes exactly at 64000 samples - the 4s window`() {
        assertTrue(offer(40_000) is CaptionWindowAccumulator.Offer.Appended)
        assertEquals(40_000, machine.pendingSamples)
        val result = offer(24_000)
        assertTrue(result is CaptionWindowAccumulator.Offer.Window)
        assertEquals(WINDOW_SAMPLES, (result as CaptionWindowAccumulator.Offer.Window).samples.size)
        assertEquals(0, machine.pendingSamples)
        assertTrue(machine.busy) // the window is in flight
    }

    @Test
    fun `overflow samples carry into the next window`() {
        offer(70_000)
        // First 64000 flush; the remaining 6000 wait for the next window.
        assertEquals(6_000, machine.pendingSamples)
    }

    @Test
    fun `single-flight - samples keep accumulating while busy but never double-flush`() {
        offer(64_000) // window #1 in flight
        assertTrue(machine.busy)
        val whileBusy = offer(80_000)
        assertTrue(whileBusy is CaptionWindowAccumulator.Offer.Appended) // NOT a second window
        assertEquals(80_000, machine.pendingSamples)
        machine.endFlush()
        val after = offer(1)
        assertTrue(after is CaptionWindowAccumulator.Offer.Window)
        assertEquals(WINDOW_SAMPLES, (after as CaptionWindowAccumulator.Offer.Window).samples.size)
        assertEquals(80_001L - WINDOW_SAMPLES, machine.pendingSamples.toLong())
    }

    @Test
    fun `tail below 16000 samples is discarded on transmit end`() {
        offer(10_000)
        val end = machine.onTransmitEnd(captionsOn = true)
        assertTrue(end is CaptionWindowAccumulator.Offer.Cleared)
        assertEquals(0, machine.pendingSamples) // sub-second audio → ASR garbage, dropped
    }

    @Test
    fun `tail at or above 16000 samples flushes on transmit end`() {
        offer(MIN_TAIL_SAMPLES)
        val end = machine.onTransmitEnd(captionsOn = true)
        assertTrue(end is CaptionWindowAccumulator.Offer.Window)
        assertEquals(MIN_TAIL_SAMPLES, (end as CaptionWindowAccumulator.Offer.Window).samples.size)
        assertTrue(machine.busy) // takeWindow marks the flight
    }

    @Test
    fun `busy window is never replaced by an end-of-transmit tail`() {
        offer(64_000) // window in flight
        assertTrue(machine.onTransmitEnd(captionsOn = true) is CaptionWindowAccumulator.Offer.Held)
        assertEquals(0, machine.pendingSamples) // tail empty anyway; flight continues
    }

    @Test
    fun `toggle-off clears pending audio immediately - nothing survives`() {
        offer(30_000)
        assertEquals(30_000, machine.pendingSamples)
        val cleared = offer(1_000, captionsOn = false)
        assertTrue(cleared is CaptionWindowAccumulator.Offer.Cleared)
        assertEquals(0, machine.pendingSamples)
    }

    @Test
    fun `toggle-off also releases an in-flight window lock`() {
        offer(64_000)
        assertTrue(machine.busy)
        offer(64_000, captionsOn = false) // user turned captions off mid-transcribe
        assertFalse(machine.busy)
        // After re-enable, fresh windows can flush again (no stuck single-flight).
        val fresh = offer(64_000)
        assertTrue(fresh is CaptionWindowAccumulator.Offer.Window)
    }

    @Test
    fun `gate closed while captions on - nothing is buffered`() {
        val held = offer(5_000, transmitting = false)
        assertTrue(held is CaptionWindowAccumulator.Offer.Held)
        assertEquals(0, machine.pendingSamples)
    }

    @Test
    fun `clear is safe when empty and endFlush is idempotent`() {
        machine.clear()
        assertEquals(0, machine.pendingSamples)
        machine.endFlush()
        assertFalse(machine.busy)
        offer(64_000)
        machine.endFlush()
        machine.endFlush()
        assertFalse(machine.busy)
    }
}
