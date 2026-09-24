package app.pulse.android.notify

import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.pulse.domain.model.Conversation
import app.pulse.domain.model.Message
import java.util.concurrent.ConcurrentHashMap

/**
 * R6 — M4: incoming-message attention (web 2-note WebAudio ding honoring
 * quiet hours + per-room mute; iOS PulseSession noteIncomingAttention
 * parity). Fires ONLY while the app is foregrounded — backgrounded rooms
 * stay silent until a real push channel exists (honest limitation, same as
 * the reminder loop). Gates, in order:
 *  1. app foregrounded (MainActivity onStart/onStop flips the flag)
 *  2. NOT the viewer's own row (sender filter; optimistic echoes never ride
 *     PulseEvent.MessageReceived — the outbox swap handles those — but the
 *     viewer's own automation replies do arrive and must stay silent)
 *  3. LOCAL quiet hours (ReminderAlertPolicy.quietNow — the same window the
 *     reminder notifier honors)
 *  4. per-room mute (the conversation's mutedUntil window is in the future)
 *  then: sound if prefs notifSound, vibration if prefs notifVibrate (the
 *  same Wave-8 blob ReminderAlertPolicy mirrors).
 */
object IncomingAttention {

    /** Process-foreground flag — a single activity means app-level truth. */
    @Volatile
    var foreground: Boolean = false

    /**
     * R7 item 7 — notifPreviews mirror (Wave-8 server blob). ON → the
     * attention toast shows the full preview ("Sender: body", 80-char cap);
     * OFF → the honest generic "New message". PulseApplication keeps it live
     * alongside the ReminderAlertPolicy mirrors.
     */
    @Volatile
    var previewsOn: Boolean = true

    /** conversationId → mutedUntil epoch-ms (only future windows are kept). */
    private val mutedUntilMs = ConcurrentHashMap<String, Long>()

    /** Called on every conversations-list emission (PulseApplication hook). */
    fun syncMutes(conversations: List<Conversation>) {
        val now = System.currentTimeMillis()
        mutedUntilMs.clear()
        conversations.filter { it.mutedUntilEpoch > now }.forEach {
            mutedUntilMs[it.id] = it.mutedUntilEpoch
        }
    }

    /** One PulseEvent.MessageReceived — evaluates the gate chain live. */
    fun onMessageReceived(context: Context, conversationId: String, message: Message, viewerId: String?) {
        if (!foreground) return
        if (viewerId == null || message.authorId == viewerId) return
        if (message.isDeleted) return
        if (ReminderAlertPolicy.quietNow()) return
        if ((mutedUntilMs[conversationId] ?: 0L) > System.currentTimeMillis()) return
        // R7 item 7 — the preview line the settings row promises ("Message
        // text in notification-style toasts."): notification-banner semantics
        // with the notifPreviews gate — body preview when ON, generic "New
        // message" when OFF (PulseWave8Logic.incomingPreviewText, the helper
        // that used to have zero callers). Quiet-hours + mute gates unchanged.
        runCatching {
            android.widget.Toast.makeText(
                context,
                app.pulse.protocol.PulseWave8Logic.incomingPreviewText(previewsOn, message.authorName, message.body),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        if (ReminderAlertPolicy.soundOn) playPing(context)
        if (ReminderAlertPolicy.vibrateOn) buzz(context)
    }

    /** Default notification sound, played on the NOTIFICATION usage route. */
    private fun playPing(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
            val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
            ringtone.play()
        }
    }

    /** Short buzz — VibratorManager on 31+, legacy Vibrator service below. */
    private fun buzz(context: Context) {
        runCatching {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() != true) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(180L)
            }
        }
    }
}
