package app.pulse.feature.voice

import app.pulse.feature.voice.engine.VoicePcm
import app.pulse.feature.voice.engine.VoicePcmChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WEB DEFECT FIX #1 proof (spec §1.1 VR-4): the transmit path actually
 * produces 4000-sample blocks with a session-continuous sequence. The web's
 * chunker never armed its buffer (no voice:chunk ever left the browser) and
 * the block math below is the regression contract for the native one.
 */
class VoicePcmChunkerTest {

    @Test
    fun `exact 4000-sample blocks with seq starting at 1`() {
        val chunker = VoicePcmChunker()
        val chunks = chunker.consume(ShortArray(8000))
        assertEquals(2, chunks.size)
        assertEquals(1L, chunks[0].seq)
        assertEquals(2L, chunks[1].seq)
        assertEquals(VoicePcm.BLOCK_SAMPLES, chunks[0].samples.size)
        assertEquals(VoicePcm.BLOCK_SAMPLES, chunks[1].samples.size)
    }

    @Test
    fun `seq continues across transmissions - never restarts per push`() {
        val chunker = VoicePcmChunker()
        assertEquals(2, chunker.consume(ShortArray(8000)).size) // seq 1,2
        assertNull(chunker.flush()) // nothing partial between pushes
        // A fresh push keeps counting — restarting at 1 would trip every
        // receiver's `seq <= lastSeq` dedupe and blackhole the audio.
        val next = chunker.consume(ShortArray(4000))
        assertEquals(listOf(3L), next.map { it.seq })
    }

    @Test
    fun `partial window flushes proportionally - never padded with silence`() {
        val chunker = VoicePcmChunker()
        val early = chunker.consume(ShortArray(2500))
        assertTrue(early.isEmpty()) // 2500 < 4000 → nothing full yet
        val tail = chunker.flush()
        assertEquals(2500, tail!!.samples.size)
        assertEquals(1L, tail.seq)
        assertNull(chunker.flush()) // window drained
    }

    @Test
    fun `seq continues after a partial flush`() {
        val chunker = VoicePcmChunker()
        chunker.consume(ShortArray(4000))
        val tail = chunker.flush() // 0 partial → null; try a real partial
        assertNull(tail)
        chunker.consume(ShortArray(1000))
        assertEquals(2L, chunker.flush()!!.seq)
        assertEquals(3L, chunker.consume(ShortArray(4000)).single().seq)
    }

    @Test
    fun `split feeds still block exactly`() {
        val chunker = VoicePcmChunker()
        assertTrue(chunker.consume(ShortArray(1500)).isEmpty())
        val chunks = chunker.consume(ShortArray(2500))
        assertEquals(listOf(1L), chunks.map { it.seq })
        assertEquals(VoicePcm.BLOCK_SAMPLES, chunks.single().samples.size)
    }

    @Test
    fun `reset restarts the sequence at 1 and clears counters`() {
        val chunker = VoicePcmChunker()
        chunker.consume(ShortArray(9000))
        chunker.consume(ShortArray(1000))
        chunker.reset()
        assertEquals(0L, chunker.consumedSamples)
        assertNull(chunker.flush())
        val fresh = chunker.consume(ShortArray(4000))
        assertEquals(1L, fresh.single().seq)
    }

    @Test
    fun `duration math - 4000 samples is 250ms at 16k`() {
        assertEquals(250L, VoicePcm.durationMs(VoicePcm.BLOCK_SAMPLES))
        assertEquals(85L, VoicePcm.durationMs(1360)) // the jitter pre-roll scale
    }
}
