package app.pulse.android.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import app.pulse.domain.repository.PulseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * R8 Task 3-c — the FCM remote-push wiring, FAIL-CLOSED by design.
 *
 * The repo ships with EMPTY `pulse_fcm_*` string credentials and NO
 * google-services.json / google-services plugin, so the build stays green
 * with zero Firebase config. [init] only builds a [FirebaseApp] when all
 * credential strings exist (a CI/release overlay bakes them); otherwise the
 * whole feature is honestly DISABLED — no token is fetched, nothing is
 * registered, no crash, no fake "push enabled" state anywhere.
 *
 * Registration rides the app's own repository (POST /api/push/register
 * { userId, platform: 'android', token }), retried whenever a viewer identity
 * becomes available — the shell calls [syncRegistration] on login.
 */
object PulsePush {

    private const val TAG = "PulsePush"

    /** Notification channel for message pushes (default importance). */
    const val MESSAGES_CHANNEL_ID = "pulse_messages"

    /** Armed = a real FirebaseApp exists and tokens may be fetched. */
    @Volatile
    var armed: Boolean = false
        private set

    /** The FCM token once handed over (pending until a viewer identity exists). */
    @Volatile
    var token: String? = null

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fail-closed Firebase init. Reads the credential strings; every value
     * missing → disabled, logged once, zero crash. Called from
     * PulseApplication.onCreate BEFORE any Firebase API use.
     */
    fun init(context: Context) {
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            armed = true
            Log.i(TAG, "Firebase already initialized — push armed")
            return
        }
        val res = context.resources
        fun credential(name: String): String =
            res.getString(res.getIdentifier(name, "string", context.packageName))

        val appId = credential("pulse_fcm_google_app_id")
        val apiKey = credential("pulse_fcm_api_key")
        val projectId = credential("pulse_fcm_project_id")
        val senderId = credential("pulse_fcm_gcm_sender_id")
        if (appId.isBlank() || apiKey.isBlank() || projectId.isBlank() || senderId.isBlank()) {
            Log.i(
                TAG,
                "Remote push DISABLED — pulse_fcm_* credentials are empty " +
                    "(fail-closed: no google-services config shipped)",
            )
            return
        }
        runCatching {
            val options = FirebaseOptions.Builder()
                .setApplicationId(appId)
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .setGcmSenderId(senderId)
                .build()
            FirebaseApp.initializeApp(context, options)
            armed = true
            Log.i(TAG, "Firebase initialized from baked credentials — push armed")
        }.onFailure {
            Log.w(TAG, "Firebase init failed — push stays disabled (honest)", it)
        }
    }

    /** Message pushes get their channel here; the calls channel lives in CallRingNotifier. */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(MESSAGES_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    MESSAGES_CHANNEL_ID,
                    "Messages",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "New Pulse messages"
                },
            )
        }
        // The calls channel (high priority, sound + vibration) is owned by
        // CallRingNotifier and created on demand.
        app.pulse.feature.calls.CallRingNotifier.ensureChannel(context)
    }

    /**
     * Fetch (or reuse) the FCM token and upsert it for the viewer. Safe to
     * call repeatedly; no-ops while the feature is unarmed or no viewer
     * identity exists.
     */
    fun syncRegistration(repository: PulseRepository) {
        if (!armed) return
        val viewer = repository.viewerId ?: return
        val tokenNow = token
        if (tokenNow != null) {
            register(repository, viewer, tokenNow)
            return
        }
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    val fetched = task.result
                    if (task.isSuccessful && !fetched.isNullOrBlank()) {
                        token = fetched
                        register(repository, viewer, fetched)
                    } else {
                        Log.w(TAG, "token fetch failed: ${task.exception?.message}")
                    }
                }
        }.onFailure { Log.w(TAG, "token fetch not possible", it) }
    }

    /** A new token arrived (rotation) — persist + upsert when a viewer exists. */
    fun onNewToken(repository: PulseRepository, fresh: String) {
        token = fresh
        val viewer = repository.viewerId
        if (!armed || viewer == null) {
            Log.i(TAG, "token stored pending identity (armed=$armed, viewer=${viewer != null})")
            return
        }
        register(repository, viewer, fresh)
    }

    /** Best-effort upsert — failures are logged, never surfaced as success. */
    private fun register(repository: PulseRepository, viewer: String, token: String) {
        io.launch {
            runCatching { repository.registerPushToken(viewer, token) }
                .onSuccess { Log.i(TAG, "push token registered for viewer") }
                .onFailure { Log.w(TAG, "push register failed (will retry on next sync)", it) }
        }
    }
}
