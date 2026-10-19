package app.pulse.feature.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.pulse.core.audio.AudioOwnership
import app.pulse.core.audio.communicationRouteType
import java.util.UUID

/** Compatibility-safe route owner. Device focus/interruption acceptance is still required. */
class CallAudioManager(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val token = UUID.randomUUID().toString()
    private var focusRequest: AudioFocusRequest? = null
    private var active = false
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false
    var onFocusLost: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var focusEpoch = UUID.randomUUID()
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null

    /** No media should be created until BOTH ownership and OS focus are granted. */
    @Suppress("DEPRECATION")
    fun acquire(): Boolean {
        if (active) return true
        if (!AudioOwnership.shared.acquire(token)) return false
        val ticket = UUID.randomUUID()
        focusEpoch = ticket
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                // Privacy-first policy: end, never silently resume a microphone.
                main.post { if (active && focusEpoch == ticket) onFocusLost?.invoke() }
            }
        }
        focusListener = listener
        val granted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                focusRequest = request
                am.requestAudioFocus(request)
            } else {
                am.requestAudioFocus(listener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN)
            }
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            abandonFocus()
            AudioOwnership.shared.release(token)
            return false
        }
        savedMode = am.mode
        savedSpeaker = isSpeakerOn()
        active = true
        return runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION; true }
            .getOrElse { release(); false }
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else { focusListener?.let { am.abandonAudioFocus(it) } }
        }
        focusRequest = null
        focusListener = null
        focusEpoch = UUID.randomUUID()
    }

    fun release() {
        if (!active) return
        // Restore only while this instance owns the route; never deactivate a successor.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            else { @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = savedSpeaker
            }
            am.mode = savedMode
        }
        abandonFocus()
        active = false
        AudioOwnership.shared.release(token)
    }

    fun setSpeakerOn(on: Boolean): Boolean {
        if (!active) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val type = communicationRouteType(on, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                val target = am.availableCommunicationDevices.firstOrNull { it.type == type }
                if (target != null) am.setCommunicationDevice(target)
                else if (!on) { am.clearCommunicationDevice(); !isSpeakerOn() }
                else false
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
                true
            }
        }.getOrDefault(false)
    }

    fun isSpeakerOn(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        else { @Suppress("DEPRECATION")
            am.isSpeakerphoneOn
        }
    }.getOrDefault(false)
}
