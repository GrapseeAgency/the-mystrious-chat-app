package app.pulse.feature.voice.audio

import app.pulse.feature.voice.engine.PlaybackScheduler
import app.pulse.feature.voice.engine.VoicePcm
import app.pulse.feature.voice.engine.VoicePcmChunker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Hardware seam: only the coroutine doing I/O may release its device. */
internal interface RoomAudioDevice {
    fun start()
    fun stop()
    fun release()
}
internal interface RoomMicrophone : RoomAudioDevice {
    fun read(buffer: ShortArray): Int
}
internal interface RoomSpeaker : RoomAudioDevice {
    fun write(buffer: ShortArray, offset: Int, count: Int): Int
}

/**
 * A close may interrupt I/O, but cannot release underneath read/write. Installation
 * and stop are ordered even when a device constructor completes after cancellation.
 */
internal class AudioDeviceSlot<T : RoomAudioDevice> {
    private val lock = Any()
    @Volatile var closed = false
        private set
    private var device: T? = null
    fun install(candidate: T): Boolean = synchronized(lock) {
        if (closed) { candidate.release(); false }
        else { device = candidate; candidate.start(); true }
    }
    fun close() = synchronized(lock) {
        closed = true
        runCatching { device?.stop() }
        Unit
    }
    fun releaseAfterIO() = synchronized(lock) {
        closed = true
        val previous = device
        device = null
        runCatching { previous?.stop() }
        runCatching { previous?.release() }
        Unit
    }
}

/**
 * One room generation, one serialized microphone, one bounded writer per peer.
 * This class has no Android dependencies: cancellation/order are tested with fake
 * devices rather than hoping that hardware timing hides races.
 * Public entry points are called by the main-thread room owner.
 */
internal class RoomAudioRuntime(
    private val scope: CoroutineScope,
    private val microphone: () -> RoomMicrophone,
    private val speaker: () -> RoomSpeaker,
    private val clock: () -> Long,
) {
    data class CaptureCallbacks(
        val chunk: (Long, ShortArray) -> Unit,
        val samples: (ShortArray) -> Unit,
        val ended: () -> Unit,
        val error: (String) -> Unit,
    ) {}
    private class Session {
        val closed = AtomicBoolean(false)
        val chunker = VoicePcmChunker()
        val peers = mutableMapOf<String, Peer>()
    }
    private class Capture(val owner: Session) {
        val slot = AudioDeviceSlot<RoomMicrophone>()
        var job: Job? = null
    }
    private data class Frame(val number: Long, val pcm: ShortArray, val arrivedAt: Long) {}
    private class Peer {
        val slot = AudioDeviceSlot<RoomSpeaker>()
        val chunks = Channel<Frame>(8, BufferOverflow.DROP_OLDEST)
        var job: Job? = null
        fun close() { slot.close(); chunks.close(); job?.cancel() }
    }
    private val microphoneMutex = Mutex()
    private var session: Session? = null
    private var capture: Capture? = null

    fun start() { if (session == null) session = Session() }

    fun startTransmit(callbacks: CaptureCallbacks): Boolean {
        val owner = session ?: return false
        if (capture?.slot?.closed == false) return true
        val run = Capture(owner)
        capture = run
        run.job = scope.launch {
            microphoneMutex.withLock {
                if (run.slot.closed || owner.closed.get()) return@withLock
                try {
                    val input = microphone()
                    if (!run.slot.install(input)) return@withLock
                    val buffer = ShortArray(VoicePcm.READ_SAMPLES)
                    var emptyReads = 0
                    while (isActive && !run.slot.closed && !owner.closed.get()) {
                        val n = input.read(buffer)
                        // A stop may have happened DURING the blocking read.
                        if (run.slot.closed || owner.closed.get()) break
                        check(n >= 0) { "Microphone read failed ($n)" }
                        if (n == 0) { check(++emptyReads < 3) { "Microphone stopped producing audio" }; continue }
                        emptyReads = 0
                        val samples = buffer.copyOf(n)
                        owner.chunker.consume(samples).forEach { callbacks.chunk(it.seq, it.samples) }
                        callbacks.samples(samples)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!owner.closed.get() && !run.slot.closed) callbacks.error(error.message ?: "Microphone unavailable")
                } finally {
                    // A burst ending preserves room sequence; a retired room emits nothing.
                    try {
                        if (!owner.closed.get()) {
                            owner.chunker.flush()?.let { callbacks.chunk(it.seq, it.samples) }
                            callbacks.ended()
                        }
                    } finally { run.slot.releaseAfterIO() }
                }
            }
        }
        return true
    }

    fun stopTransmit() {
        capture?.slot?.close() // stop unblocks a read; only its finally releases
        capture?.job?.cancel()
        capture = null
    }

    fun receive(userId: String, seq: Long, samples: ShortArray, onError: (String) -> Unit) {
        val owner = session ?: return
        if (samples.isEmpty() || samples.size > VoicePcm.SAMPLE_RATE || seq < 1) return
        if (userId !in owner.peers && owner.peers.size >= 64) return
        val peer = owner.peers.getOrPut(userId) {
            Peer().also { worker ->
                worker.job = scope.launch {
                    val scheduler = PlaybackScheduler()
                    var output: RoomSpeaker? = null
                    try {
                        for ((number, pcm, arrivedAt) in worker.chunks) {
                            if (owner.closed.get() || worker.slot.closed) break
                            val decision = scheduler.decide(userId, number, VoicePcm.durationMs(pcm.size), arrivedAt)
                            if (decision !is PlaybackScheduler.Decision.Schedule) continue
                            val wait = decision.atMs - clock()
                            if (wait > 0) delay(wait)
                            if (owner.closed.get() || worker.slot.closed) break
                            if (output == null) {
                                val candidate = speaker()
                                if (!worker.slot.install(candidate)) break
                                output = candidate
                            }
                            var offset = 0
                            while (offset < pcm.size && isActive && !worker.slot.closed && !owner.closed.get()) {
                                val written = checkNotNull(output).write(pcm, offset, pcm.size - offset)
                                check(written > 0 && written <= pcm.size - offset) { "Speaker write failed ($written)" }
                                offset += written
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (!owner.closed.get() && !worker.slot.closed) onError(error.message ?: "Playback unavailable")
                    } finally { worker.slot.releaseAfterIO() }
                }
            }
        }
        // A failed peer can be recreated by the next packet; never grow an unbounded queue.
        if (peer.slot.closed) { owner.peers.remove(userId); peer.close(); return }
        peer.chunks.trySend(Frame(seq, samples, clock()))
    }

    fun dropPeer(userId: String) { session?.peers?.remove(userId)?.close() }

    fun stop() {
        val owner = session ?: return
        session = null
        owner.closed.set(true) // invalidate before cancel; queued work cannot create new devices
        stopTransmit()
        owner.peers.values.forEach(Peer::close)
        owner.peers.clear()
    }
}
