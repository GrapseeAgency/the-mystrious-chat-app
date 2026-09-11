package app.pulse.feature.chat

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import app.pulse.domain.model.Message
import app.pulse.domain.repository.PulsePrefsStore
import app.pulse.domain.repository.PulseRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ONE active voice player for the whole app (Wave 2 spec: "Only ONE
 * active player globally"). A process-wide singleton so the room timeline AND
 * thread bubbles share the same state keyed by messageId — starting playback
 * on another row stops the previous one. MediaPlayer only (no media3),
 * playback rate guarded to API 23+ (`setPlaybackParams`), speed persisted via
 * the prefs store (spec §1 row 12 `voiceRate`).
 *
 * Download-first: `message.audioPath` is a wire path — the file is fetched to
 * the cache via [PulseRepository.downloadMedia] before `setDataSource`.
 */
@Singleton
class VoicePlayer @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val repo: PulseRepository,
    private val prefs: PulsePrefsStore,
) {
    /** Playback snapshot for the active row (null = nothing loaded). */
    data class Playback(
        val messageId: String,
        val playing: Boolean,
        /** True while the source file is downloading/preparing. */
        val loading: Boolean,
        val positionMs: Long,
        val durationMs: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    private val _playback = MutableStateFlow<Playback?>(null)
    val playback: StateFlow<Playback?> = _playback.asStateFlow()

    /** Persisted speed — 1x/1.5x/2x chip reads this; applied live via [cycleRate]. */
    val rate: StateFlow<Float> = prefs.voiceRate
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 1f)

    /** Play/pause for a voice row — switching targets releases the previous player. */
    fun toggle(message: Message) {
        val current = _playback.value
        if (current?.messageId == message.id && player != null) {
            val mp = player ?: return
            if (current.playing) {
                runCatching { mp.pause() }
                ticker?.cancel()
                _playback.value = current.copy(playing = false)
            } else {
                if (current.durationMs in 1..current.positionMs) runCatching { mp.seekTo(0) }
                runCatching { mp.start() }
                _playback.value = current.copy(playing = true)
                startTicker()
            }
            return
        }
        val audioPath = message.audioPath?.takeIf { it.isNotBlank() } ?: return
        release()
        _playback.value = Playback(
            messageId = message.id,
            playing = false,
            loading = true,
            positionMs = 0,
            durationMs = message.durationMs ?: 0,
        )
        scope.launch {
            repo.downloadMedia(audioPath)
                .onSuccess { path -> prepareAndPlay(message.id, path) }
                .onFailure {
                    if (_playback.value?.messageId == message.id) _playback.value = null
                }
        }
    }

    /** Create + prepare on IO, then start on the main looper. */
    private suspend fun prepareAndPlay(messageId: String, localPath: String) {
        val prepared = withContext(Dispatchers.IO) {
            runCatching {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                mp.setDataSource(localPath)
                mp.prepare()
                mp
            }
        }
        prepared
            .onSuccess { mp ->
                if (_playback.value?.messageId != messageId) {
                    // Another toggle raced us — drop this player, keep the newest.
                    runCatching { mp.release() }
                    return@onSuccess
                }
                player = mp
                mp.setOnCompletionListener { finished ->
                    ticker?.cancel()
                    _playback.value = _playback.value
                        ?.takeIf { it.messageId == messageId }
                        ?.copy(playing = false, positionMs = finished.duration.toLong())
                }
                applyRate(rate.value)
                _playback.value = _playback.value?.copy(
                    loading = false,
                    playing = true,
                    durationMs = mp.duration.toLong(),
                    positionMs = 0,
                )
                runCatching { mp.start() }
                startTicker()
            }
            .onFailure {
                if (_playback.value?.messageId == messageId) _playback.value = null
            }
    }

    /** 100ms position ticker — drives the determinate waveform recoloring. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(100)
                val mp = player ?: break
                val snapshot = _playback.value ?: break
                if (snapshot.playing) {
                    _playback.value = snapshot.copy(positionMs = mp.currentPosition.toLong())
                }
            }
        }
    }

    /** 1x → 1.5x → 2x → 1x, persisted + applied live on the active player. */
    fun cycleRate() {
        val next = when {
            rate.value <= 1f -> 1.5f
            rate.value <= 1.5f -> 2f
            else -> 1f
        }
        scope.launch { runCatching { prefs.setVoiceRate(next) } }
        applyRate(next)
    }

    /** API 23+ `setPlaybackParams` guard — lower APIs hide the chip entirely. */
    private fun applyRate(value: Float) {
        val mp = player ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            // PlaybackParams.setSpeed (NOT setRate) — fluent builder-style API.
            mp.playbackParams = mp.playbackParams.setSpeed(value)
        }
    }

    /** Row disposal hook — the bubble leaving composition stops its audio. */
    fun releaseIfActive(messageId: String) {
        if (_playback.value?.messageId == messageId) release()
    }

    /** Stop + release everything (VM clear, room close, target switch). */
    fun release() {
        ticker?.cancel()
        ticker = null
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        _playback.value = null
    }
}
