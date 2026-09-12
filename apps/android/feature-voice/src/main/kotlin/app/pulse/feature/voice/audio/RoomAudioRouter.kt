package app.pulse.feature.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Wave 5 — slim audio router for voice rooms (the AUDIO-SESSION half of the
 * room stack). Own recipe, mirrors the shape of the Wave-3 call manager but
 * lives entirely in :feature-voice (feature-calls untouched):
 *  • audio focus (AUDIOFOCUS_GAIN) for the room, released on teardown;
 *  • MODE_IN_COMMUNICATION while a room is live (echo-cancelled voice path);
 *  • speaker toggle (API 31+ setCommunicationDevice, legacy setSpeakerphoneOn).
 *
 * PHYSICAL DEVICE: PENDING — route changes and focus arbitration need real
 * hardware evidence (emulator surfaces are not proof).
 */
class RoomAudioRouter(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var active = false

    /** Acquire focus + communication mode. Idempotent per room session. */
    fun acquire() {
        if (active) return
        active = true
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { /* transient losses tolerated; the room UI shows real state */ }
            .build()
        focusRequest = request
        runCatching { am.requestAudioFocus(request) }
    }

    /** Release focus, restore normal mode, park the route on the earpiece default. */
    fun release() {
        if (!active) return
        active = false
        runCatching {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
        setSpeakerOn(false)
        runCatching { am.mode = AudioManager.MODE_NORMAL }
    }

    /** Loud-speaker toggle — true once applied. */
    fun setSpeakerOn(on: Boolean): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = am.availableCommunicationDevices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (target != null) am.setCommunicationDevice(target) else false
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
            true
        }
    }.getOrDefault(false)

    /** Current loud-speaker state (UI toggle mirror). */
    fun isSpeakerOn(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn
        }
    }.getOrDefault(false)
}
