package app.pulse.feature.voice.audio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomAudioRuntimeTest {
    private class Mic : RoomMicrophone {
        var starts = 0; var stops = 0; var releases = 0
        var result = 1600
        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun release() { releases++ }
        override fun read(buffer: ShortArray): Int { buffer.fill(7); return result }
    }
    private class Speaker : RoomSpeaker {
        var starts = 0; var releases = 0; var writes = 0
        var result: Int? = null
        val samples = mutableListOf<Short>()
        override fun start() { starts++ }
        override fun stop() {}
        override fun release() { releases++ }
        override fun write(buffer: ShortArray, offset: Int, count: Int): Int {
            writes++
            val n = result ?: count
            if (n > 0) samples.addAll(buffer.slice(offset until offset + minOf(n, count)))
            return n
        }
    }
    private fun callbacks(samples: (ShortArray) -> Unit = {}, chunk: (Long, ShortArray) -> Unit = { _, _ -> },
                          error: (String) -> Unit = { throw AssertionError(it) }) =
        RoomAudioRuntime.CaptureCallbacks(chunk, samples, {}, error)

    @Test fun `cancel before capture starts never constructs a microphone`() = runTest {
        var creates = 0
        val runtime = RoomAudioRuntime(backgroundScope, { creates++; Mic() }, { Speaker() }, { testScheduler.currentTime })
        runtime.start()
        runtime.startTransmit(callbacks())
        runtime.stop()
        runCurrent()
        assertEquals(0, creates)
    }

    @Test fun `two rapid bursts release old device and preserve room sequence`() = runTest {
        val devices = mutableListOf<Mic>()
        val seq = mutableListOf<Long>()
        val runtime = RoomAudioRuntime(backgroundScope, { Mic().also { devices += it } }, { Speaker() }, { testScheduler.currentTime })
        val cb = callbacks(samples = { runtime.stopTransmit() }, chunk = { n, _ -> seq += n })
        runtime.start()
        runtime.startTransmit(cb)
        runCurrent()
        runtime.startTransmit(cb)
        runCurrent()
        assertEquals(listOf(1L, 2L), seq)
        assertEquals(listOf(1, 1), devices.map { it.releases })
        runtime.stop()
    }

    @Test fun `negative read reports once and releases instead of spinning`() = runTest {
        val mic = Mic().also { it.result = -6 }
        var errors = 0
        val runtime = RoomAudioRuntime(backgroundScope, { mic }, { Speaker() }, { testScheduler.currentTime })
        runtime.start()
        runtime.startTransmit(callbacks(error = { errors++ }))
        runCurrent()
        assertEquals(1, errors)
        assertEquals(1, mic.releases)
        runtime.stop()
    }

    @Test fun `leave during preroll cannot create a late speaker in the next session`() = runTest {
        var creates = 0
        val runtime = RoomAudioRuntime(backgroundScope, { Mic() }, { creates++; Speaker() }, { testScheduler.currentTime })
        runtime.start()
        runtime.receive("peer", 1, shortArrayOf(1)) { fail(it) }
        runCurrent() // queued at 85ms
        runtime.stop()
        runtime.start()
        advanceTimeBy(1000); runCurrent()
        assertEquals(0, creates)
        runtime.receive("peer", 1, shortArrayOf(2)) { fail(it) }
        runCurrent(); advanceTimeBy(85); runCurrent()
        assertEquals(1, creates)
        runtime.stop(); runCurrent()
    }

    @Test fun `peer drop cancels queued frames and new peer may restart at sequence one`() = runTest {
        val outputs = mutableListOf<Speaker>()
        val runtime = RoomAudioRuntime(backgroundScope, { Mic() }, { Speaker().also { outputs += it } }, { testScheduler.currentTime })
        runtime.start()
        runtime.receive("peer", 1, shortArrayOf(1)) { fail(it) }
        runCurrent()
        runtime.dropPeer("peer")
        runtime.receive("peer", 1, shortArrayOf(2)) { fail(it) }
        runCurrent(); advanceTimeBy(85); runCurrent()
        assertEquals(listOf<Short>(2), outputs.single().samples)
        runtime.stop(); runCurrent()
        assertEquals(1, outputs.single().releases)
    }

    @Test fun `partial writes finish in order and duplicates never play`() = runTest {
        val output = Speaker().also { it.result = 1 }
        val runtime = RoomAudioRuntime(backgroundScope, { Mic() }, { output }, { testScheduler.currentTime })
        runtime.start()
        runtime.receive("peer", 1, shortArrayOf(1, 2, 3)) { fail(it) }
        runtime.receive("peer", 1, shortArrayOf(9)) { fail(it) }
        runCurrent(); advanceTimeBy(85); runCurrent()
        assertEquals(listOf<Short>(1, 2, 3), output.samples)
        runtime.stop(); runCurrent()
        assertEquals(1, output.releases)
    }

    @Test fun `failed speaker write releases device and reports error`() = runTest {
        val output = Speaker().also { it.result = -6 }
        var errors = 0
        val runtime = RoomAudioRuntime(backgroundScope, { Mic() }, { output }, { testScheduler.currentTime })
        runtime.start()
        runtime.receive("peer", 1, shortArrayOf(1)) { errors++ }
        runCurrent(); advanceTimeBy(85); runCurrent()
        assertEquals(1, errors)
        assertEquals(1, output.releases)
        runtime.stop()
    }

    @Test fun `stop does not release device underneath in-flight IO`() {
        val mic = Mic()
        val slot = AudioDeviceSlot<RoomMicrophone>()
        assertTrue(slot.install(mic))
        slot.close()
        assertEquals(1, mic.stops)
        assertEquals(0, mic.releases)
        slot.releaseAfterIO()
        slot.releaseAfterIO()
        assertEquals(1, mic.releases)
        val late = Mic()
        assertFalse(slot.install(late))
        assertEquals(0, late.starts)
        assertEquals(1, late.releases)
    }
}
