package app.pulse.feature.chat

import app.pulse.core.audio.AudioOwnership
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * thread bubbles share the same state keyed by messageId - starting playback
 * on another row stops the previous one. MediaPlayer only (no media3),
 * playback rate guarded to API 23+ (`setPlaybackParams`), speed persisted via
 * the prefs store (spec §1 row 12 `voiceRate`).
 *
 * Download-first: `message.audioPath` is a wire path - the file is fetched to
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
    private var loading: Job? = null
    private var generation = 0L
    private val audioToken = UUID.randomUUID().toString()
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices = _notices.asSharedFlow()

    private val _playback = MutableStateFlow<Playback?>(null)
    val playback: StateFlow<Playback?> = _playback.asStateFlow()

    /** Persisted speed - 1x/1.5x/2x chip reads this; applied live via [cycleRate]. */
    val rate: StateFlow<Float> = prefs.voiceRate
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 1f)

    /** Play/pause for a voice row - switching targets releases the previous player. */
    fun toggle(message: Message) {
        val current = _playback.value
        if (current?.messageId == message.id && current.loading) { release(); return }
        if (current?.messageId == message.id && player != null) {
            val mp = player ?: return
            if (current.playing) {
                if (runCatching { mp.pause() }.isFailure) { failPlayback(); return }
                ticker?.cancel()
                _playback.value = current.copy(playing = false)
                AudioOwnership.shared.release(audioToken)
            } else {
                if (!claimAudio()) return
                if (runCatching {
                    if (current.durationMs in 1..current.positionMs) mp.seekTo(0)
                    applyRate(rate.value)
                    mp.start()
                }.isFailure) { failPlayback(); return }
                _playback.value = current.copy(playing = true)
                startTicker()
            }
            return
        }
        val path = message.audioPath?.takeIf { it.isNotBlank() } ?: return
        release()
        val ticket = generation
        val api = repo.boundToCurrentAccount()
        _playback.value = Playback(message.id, false, true, 0, message.durationMs ?: 0)
        loading = scope.launch {
            val downloaded = runCatching { api.downloadMedia(path) }.getOrElse { Result.failure(it) }
            if (!isActive || ticket != generation) return@launch
            downloaded.onSuccess { prepareAndPlay(message.id, it, ticket) }
                .onFailure { failPlayback() }
        }
    }

    private fun claimAudio(): Boolean {
        if (AudioOwnership.shared.acquirePlayback(audioToken, ::release)) return true
        _notices.tryEmit("Leave your live room/call or stop recording before playing a voice note.")
        return false
    }

    /** MediaPlayer stays on its owning looper; preparation never blocks the UI. */
    private fun prepareAndPlay(messageId: String, localPath: String, ticket: Long) {
        if (ticket != generation) return
        if (!claimAudio()) { release(); return }
        val mp = try { MediaPlayer() } catch (_: Exception) { failPlayback(); return }
        player = mp // failure/exit can release even a half-prepared player
        fun current() = generation == ticket && player === mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setOnPreparedListener {
                if (current()) {
                    if (runCatching { applyRate(rate.value); mp.start() }.isFailure) failPlayback()
                    else {
                        _playback.value = Playback(messageId, true, false, 0, mp.duration.toLong())
                        startTicker()
                    }
                }
            }
            mp.setOnCompletionListener {
                if (current()) {
                    ticker?.cancel()
                    _playback.value = _playback.value?.copy(playing = false, positionMs = it.duration.toLong())
                    AudioOwnership.shared.release(audioToken)
                }
            }
            mp.setOnErrorListener { _, _, _ -> if (current()) failPlayback(); true }
            mp.setDataSource(localPath)
            mp.prepareAsync()
        } catch (_: Exception) { if (current()) failPlayback() }
    }

    private fun failPlayback() {
        release()
        _notices.tryEmit("Voice note could not play. Try again.")
    }

    /** 100ms position ticker - drives the determinate waveform recoloring. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(100)
                val mp = player ?: break
                val snapshot = _playback.value ?: break
                if (snapshot.playing) {
                    val position = runCatching { mp.currentPosition.toLong() }.getOrElse { failPlayback(); return@launch }
                    _playback.value = snapshot.copy(positionMs = position)
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
        // setPlaybackParams can start a paused player; apply only under a live lease.
        if (_playback.value?.playing == true) applyRate(next)
    }

    /** API 23+ `setPlaybackParams` guard - lower APIs hide the chip entirely. */
    private fun applyRate(value: Float) {
        val mp = player ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            // PlaybackParams.setSpeed (NOT setRate) - fluent builder-style API.
            mp.playbackParams = mp.playbackParams.setSpeed(value)
        }
    }

    /** Row disposal hook - the bubble leaving composition stops its audio. */
    fun releaseIfActive(messageId: String) {
        if (_playback.value?.messageId == messageId) release()
    }

    /** Stop + release everything (VM clear, room close, target switch). */
    fun release() {
        generation++
        loading?.cancel()
        loading = null
        ticker?.cancel()
        ticker = null
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        AudioOwnership.shared.release(audioToken)
        _playback.value = null
    }
}
