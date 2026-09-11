package app.pulse.feature.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Wave 3 — native audio routing for 1:1 calls (the AUDIO-SESSION half of the
 * call stack). Hardware verification (real mic capture, speaker/Bluetooth
 * routes, focus arbitration with other apps) is a PHYSICAL-DEVICE gate:
 * emulator surfaces exist but are not proof.
 *
 * Responsibilities:
 *  • audio focus (AUDIOFOCUS_GAIN) for the whole call, released on end;
 *  • MODE_IN_COMMUNICATION while a call is live (echo-cancelled voice path);
 *  • speaker on/off (API 31+ setCommunicationDevice, legacy setSpeakerphoneOn).
 */
class CallAudioManager(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    /** true while MODE_IN_COMMUNICATION is applied (call live). */
    private var active = false

    /** Acquire focus + communication mode. Idempotent per call. */
    fun acquire() {
        if (active) return
        active = true
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { /* transient losses are tolerated; the call UI shows real state */ }
            .build()
        focusRequest = request
        runCatching { am.requestAudioFocus(request) }
    }

    /** Release focus, restore the normal mode, and park the route on the earpiece default. */
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
            val target = am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_SPEAKER }
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
            am.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn
        }
    }.getOrDefault(false)
}
