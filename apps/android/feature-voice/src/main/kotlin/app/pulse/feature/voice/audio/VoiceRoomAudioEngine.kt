package app.pulse.feature.voice.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import app.pulse.feature.voice.engine.VoicePcm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Android hardware adapter; lifetime/order live in the independently tested runtime. */
class VoiceRoomAudioEngine(context: Context) {
    var onChunk: ((Long, String) -> Unit)? = null
    var captionSink: ((ShortArray) -> Unit)? = null
    var onTransmitEnd: (() -> Unit)? = null
    var onCaptureError: ((String) -> Unit)? = null
    var onPlaybackError: ((String) -> Unit)? = null
    var onFocusLost: (() -> Unit)?
        get() = router.onFocusLost
        set(value) { router.onFocusLost = value }

    private val router = RoomAudioRouter(context)
    private val runtime = RoomAudioRuntime(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        microphone = ::createMicrophone, speaker = ::createSpeaker, clock = SystemClock::elapsedRealtime,
    )

    fun start(): Boolean {
        if (!router.acquire()) return false
        runtime.start()
        return true
    }

    fun startTransmit(muted: Boolean): Boolean {
        if (muted) return false
        val chunk = onChunk
        val samples = captionSink
        val ended = onTransmitEnd
        val error = onCaptureError
        return runtime.startTransmit(RoomAudioRuntime.CaptureCallbacks(
            chunk = { seq, pcm -> chunk?.invoke(seq, VoicePcm.encodeBase64(pcm)) },
            samples = { samples?.invoke(it) }, ended = { ended?.invoke() }, error = { error?.invoke(it) },
        ))
    }
    fun stopTransmit() = runtime.stopTransmit()

    fun onPeerChunk(userId: String, seq: Long, data: String) {
        // Bound decode allocation even if a malformed relay/client sends a huge chunk.
        if (data.length > 44_000) return
        val samples = runCatching { VoicePcm.decodeBase64(data) }.getOrNull() ?: return
        val error = onPlaybackError
        runtime.receive(userId, seq, samples) { error?.invoke(it) }
    }
    fun dropPeer(userId: String) = runtime.dropPeer(userId)
    fun teardown() { runtime.stop(); router.release() }

    @SuppressLint("MissingPermission")
    private fun createMicrophone(): RoomMicrophone {
        val minimum = AudioRecord.getMinBufferSize(VoicePcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone format unavailable" }
        val device = AudioRecord(MediaRecorder.AudioSource.MIC, VoicePcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, VoicePcm.READ_SAMPLES * 8))
        if (device.state != AudioRecord.STATE_INITIALIZED) {
            device.release()
            error("Microphone unavailable - check permission or close other recording apps")
        }
        return object : RoomMicrophone {
            override fun start() = device.startRecording()
            override fun read(buffer: ShortArray) = device.read(buffer, 0, buffer.size)
            override fun stop() = device.stop()
            override fun release() = device.release()
        }
    }

    private fun createSpeaker(): RoomSpeaker {
        val minimum = AudioTrack.getMinBufferSize(VoicePcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Speaker format unavailable" }
        // This constructor is available on API 21; AudioTrack.Builder requires 23.
        val device = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioFormat.Builder().setSampleRate(VoicePcm.SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            maxOf(minimum, VoicePcm.SAMPLE_RATE * 2), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        if (device.state != AudioTrack.STATE_INITIALIZED) { device.release(); error("Speaker unavailable") }
        return object : RoomSpeaker {
            override fun start() = device.play()
            override fun write(buffer: ShortArray, offset: Int, count: Int) = device.write(buffer, offset, count)
            override fun stop() { device.pause(); device.flush() }
            override fun release() = device.release()
        }
    }
}
