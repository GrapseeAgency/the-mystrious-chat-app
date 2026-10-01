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
import app.pulse.domain.push.PulsePushStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * R8 Task 3-c - the FCM remote-push wiring, FAIL-CLOSED by design.
 *
 * The repo ships with EMPTY `pulse_fcm_*` string credentials and NO
 * google-services.json / google-services plugin, so the build stays green
 * with zero Firebase config. [init] only builds a [FirebaseApp] when all
 * credential strings exist (a CI/release overlay bakes them); otherwise the
 * whole feature is honestly DISABLED - no token is fetched, nothing is
 * registered, no crash, no fake "push enabled" state anywhere.
 *
 * Registration rides the app's own repository (POST /api/push/register
 * { userId, platform: 'android', token }), retried whenever a viewer identity
 * becomes available - the shell calls [syncRegistration] on login.
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

    /**
     * R9 - publish the honest device state for the Settings → Notifications
     * "Remote push" row. Called at every state transition below; never faked
     * (an unarmed build publishes armed=false, period).
     */
    private fun publishStatus(viewerBound: Boolean) {
        PulsePushStatus.publish(
            PulsePushStatus.Snapshot(
                armed = armed,
                hasToken = !token.isNullOrBlank(),
                viewerBound = viewerBound,
                checkedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fail-closed Firebase init. Reads the credential strings; every value
     * missing → disabled, logged once, zero crash. Called from
     * PulseApplication.onCreate BEFORE any Firebase API use.
     */
    fun init(context: Context) {
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            armed = true
            Log.i(TAG, "Firebase already initialized - push armed")
            publishStatus(viewerBound = false)
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
                "Remote push DISABLED - pulse_fcm_* credentials are empty " +
                    "(fail-closed: no google-services config shipped)",
            )
            publishStatus(viewerBound = false)
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
            Log.i(TAG, "Firebase initialized from baked credentials - push armed")
            publishStatus(viewerBound = false)
        }.onFailure {
            Log.w(TAG, "Firebase init failed - push stays disabled (honest)", it)
            publishStatus(viewerBound = false)
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
        if (!armed) {
            publishStatus(viewerBound = false)
            return
        }
        val viewer = repository.viewerId
        publishStatus(viewerBound = viewer != null)
        if (viewer == null) return
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
                        publishStatus(viewerBound = true)
                    } else {
                        Log.w(TAG, "token fetch failed: ${task.exception?.message}")
                    }
                }
        }.onFailure { Log.w(TAG, "token fetch not possible", it) }
    }

    /** A new token arrived (rotation) - persist + upsert when a viewer exists. */
    fun onNewToken(repository: PulseRepository, fresh: String) {
        token = fresh
        val viewer = repository.viewerId
        publishStatus(viewerBound = viewer != null)
        if (!armed || viewer == null) {
            Log.i(TAG, "token stored pending identity (armed=$armed, viewer=${viewer != null})")
            return
        }
        register(repository, viewer, fresh)
    }

    /**
     * Sign-out / identity teardown (Task 5-d - the privacy wire): the
     * registry row (token → THAT viewer) must die with the identity, or the
     * device keeps receiving the signed-out account's pushes. Fires
     * DELETE /api/push/register { token } - token-only body, no identity
     * needed - FIRE-AND-FORGET on the IO scope: sign-out NEVER blocks on it
     * and NEVER fails because of it (failures are logged, honestly). The
     * stored token is cleared SYNCHRONOUSLY first so a stale token cannot be
     * re-registered after a re-login before a fresh one arrives - the next
     * [syncRegistration] fetches from Firebase instead.
     *
     * Honest residue: DELETE removes OUR registry row only. If the call
     * fails (offline, server down) the row lingers server-side and FCM may
     * still deliver until the next successful register rebinds it - logged,
     * never faked as success.
     */
    fun signOut(repository: PulseRepository) {
        val stored = token
        token = null
        publishStatus(viewerBound = false)
        if (!armed || stored.isNullOrBlank()) {
            Log.i(
                TAG,
                "sign-out: nothing to unregister (armed=$armed, hadToken=${!stored.isNullOrBlank()})",
            )
            return
        }
        io.launch {
            runCatching { repository.unregisterPushToken(stored) }
                .onSuccess { Log.i(TAG, "push token unregistered on sign-out") }
                .onFailure {
                    Log.w(TAG, "push unregister failed - server row may linger until the next register", it)
                }
        }
    }

    /** Best-effort upsert - failures are logged, never surfaced as success. */
    private fun register(repository: PulseRepository, viewer: String, token: String) {
        io.launch {
            runCatching { repository.registerPushToken(viewer, token) }
                .onSuccess { Log.i(TAG, "push token registered for viewer") }
                .onFailure { Log.w(TAG, "push register failed (will retry on next sync)", it) }
        }
    }
}
