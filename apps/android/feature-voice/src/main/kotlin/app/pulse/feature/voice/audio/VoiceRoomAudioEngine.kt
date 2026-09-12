package app.pulse.feature.voice.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import app.pulse.feature.voice.engine.PlaybackScheduler
import app.pulse.feature.voice.engine.VoicePcm
import app.pulse.feature.voice.engine.VoicePcmChunker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wave 5 — the REAL raw-audio engine for voice rooms (spec §1.1 VR-4/VR-5):
 *  • capture: AudioRecord(MIC, 16kHz mono PCM16) read in 100ms blocks into the
 *    pure [VoicePcmChunker] ONLY while the software gate (transmitting &&
 *    !muted) is open — the gate is the source of truth; AudioRecord itself
 *    only runs [startRecording] while a transmission is live;
 *  • playback: per-peer AudioTrack (USAGE_VOICE_COMMUNICATION /
 *    CONTENT_TYPE_SPEECH, 16k mono PCM16 MODE_STREAM) fed by the pure
 *    [PlaybackScheduler] with an 85ms pre-roll (initial silence primes the
 *    track), duplicate/stale seq dropped, and roster-drop resets so a peer's
 *    rejoin resumes audio immediately (WEB DEFECT FIX #2);
 *  • full [teardown] releases every handle; conversation switches always go
 *    through leave → teardown → fresh instance, so no state leaks across rooms.
 *
 * HARDWARE GATE: mic capture and audible playback are PHYSICAL DEVICE: PENDING.
 */
class VoiceRoomAudioEngine(context: Context) {

    /** Emitted for every ready transmit block: (seq, base64 PCM). */
    var onChunk: ((seq: Long, base64: String) -> Unit)? = null

    /** Caption path: raw 16kHz samples of every read while captions are on. */
    var captionSink: ((samples: ShortArray) -> Unit)? = null

    /** Transmit ended — the engine flushes caption tails here. */
    var onTransmitEnd: (() -> Unit)? = null

    /** Capture could not start (mic denied / busy / absent) — honest error (VR-10). */
    var onCaptureError: ((message: String) -> Unit)? = null

    private val router = RoomAudioRouter(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chunker = VoicePcmChunker()
    private val scheduler = PlaybackScheduler()
    private val tracks = ConcurrentHashMap<String, AudioTrack>()
    private val transmitting = AtomicBoolean(false)
    private val captureMutex = Mutex()
    private var record: AudioRecord? = null
    private var captureJob: Job? = null

    /** Room session start — focus + communication mode. */
    fun start() {
        router.acquire()
    }

    // ── capture (transmit) leg ──────────────────────────────────

    /**
     * Opens the mic gate. [muted] mirrors the current mute state — a muted
     * press never opens the AudioRecord at all (the software gate, VR-6).
     */
    fun startTransmit(muted: Boolean) {
        if (muted) return
        if (!transmitting.compareAndSet(false, true)) return
        captureJob = scope.launch {
            captureMutex.withLock {
                val rec = ensureRecord()
                if (rec == null) {
                    transmitting.set(false)
                    onCaptureError?.invoke("Microphone unavailable — check permission or close other apps using it.")
                    return@withLock
                }
                runCatching { rec.startRecording() }
                    .onFailure {
                        transmitting.set(false)
                        onCaptureError?.invoke("Microphone failed to start: ${it.message ?: "unknown error"}")
                        return@withLock
                    }
                val block = ShortArray(VoicePcm.READ_SAMPLES)
                try {
                    while (transmitting.get() && isActive) {
                        val n = rec.read(block, 0, block.size)
                        if (n <= 0) continue
                        val samples = block.copyOf(n)
                        // Chunker re-blocks 100ms reads into 250ms wire blocks.
                        for (chunk in chunker.consume(samples)) {
                            onChunk?.invoke(chunk.seq, VoicePcm.encodeBase64(chunk.samples))
                        }
                        captionSink?.invoke(samples)
                    }
                } finally {
                    // Proportional partial flush on release (FIX #1 proof path).
                    chunker.flush()?.let { tail ->
                        onChunk?.invoke(tail.seq, VoicePcm.encodeBase64(tail.samples))
                    }
                    runCatching { rec.stop() }
                    onTransmitEnd?.invoke()
                }
            }
        }
    }

    /** Closes the mic gate; the capture coroutine flushes and stops itself. */
    fun stopTransmit() {
        transmitting.set(false)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the UI/permission gate upstream.
    private fun ensureRecord(): AudioRecord? = runCatching {
        record ?: run {
            val minBuf = AudioRecord.getMinBufferSize(
                VoicePcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return@run null
            val bufferBytes = maxOf(minBuf, VoicePcm.READ_SAMPLES * 2 * 4)
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                VoicePcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            ).also {
                if (it.state != AudioRecord.STATE_INITIALIZED) {
                    it.release()
                    return@run null
                }
                record = it
            }
        }
    }.getOrNull()

    // ── playback leg ────────────────────────────────────────────

    /** One peer chunk arrived: decode → dedupe/schedule → write at the jitter playhead. */
    fun onPeerChunk(userId: String, seq: Long, data: String) {
        scope.launch {
            val shorts = runCatching { VoicePcm.decodeBase64(data) }.getOrNull()
            if (shorts == null || shorts.isEmpty()) return@launch // corrupt chunk dropped silently (VR-5)
            val durationMs = VoicePcm.durationMs(shorts.size)
            val decision = scheduler.decide(userId, seq, durationMs, System.currentTimeMillis())
            when (decision) {
                is PlaybackScheduler.Decision.Drop -> Unit
                is PlaybackScheduler.Decision.Schedule -> writeScheduled(userId, shorts, decision.atMs)
            }
        }
    }

    private suspend fun writeScheduled(userId: String, shorts: ShortArray, atMs: Long) {
        val track = ensureTrack(userId) ?: return
        val wait = atMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        runCatching { track.write(shorts, 0, shorts.size) }
            .onFailure { Log.w(TAG, "AudioTrack write failed for $userId", it) }
    }

    private fun ensureTrack(userId: String): AudioTrack? = tracks.getOrPut(userId) {
        runCatching {
            val minBuf = AudioTrack.getMinBufferSize(
                VoicePcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return@runCatching null
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(VoicePcm.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, VoicePcm.SAMPLE_RATE * 2)) // ≥ 1s buffer
                .build()
            track.play()
            // 85ms pre-roll of silence primes the pipeline (VR-5 pre-roll).
            val preroll = (VoicePcm.SAMPLE_RATE * PlaybackScheduler.PRE_ROLL_MS / 1000L).toInt()
            track.write(ShortArray(preroll), 0, preroll)
            track
        }.getOrNull()
    }

    /** Roster drop — reset the peer's playhead AND its track (WEB DEFECT FIX #2). */
    fun dropPeer(userId: String) {
        scheduler.resetPeer(userId)
        tracks.remove(userId)?.let { track ->
            runCatching {
                track.pause()
                track.flush()
                track.release()
            }
        }
    }

    // ── teardown ────────────────────────────────────────────────

    /** Full teardown — capture, every peer track, focus, mode (VR-9). */
    fun teardown() {
        transmitting.set(false)
        runCatching { captureJob?.cancel() }
        captureJob = null
        runCatching {
            record?.let { rec ->
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                rec.release()
            }
        }
        record = null
        chunker.reset()
        scheduler.reset()
        tracks.values.forEach { track ->
            runCatching {
                track.pause()
                track.flush()
                track.release()
            }
        }
        tracks.clear()
        router.release()
    }

    private companion object {
        const val TAG = "VoiceRoomAudio"
    }
}
