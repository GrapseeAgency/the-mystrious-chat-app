package app.pulse.feature.calls

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.net.Uri

/**
 * R8 Task 3-c — self-managed PhoneAccount registration for the Pulse
 * ConnectionService. CAPABILITY_SELF_MANAGED (API 26+) means Pulse owns the
 * whole call UX while the OS still shows the call in its call UIs; no dialer
 * role is stolen from the system app. Registration happens at app start and
 * is idempotent; ANY failure resolves to `false` — the callers then keep the
 * honest in-app ring + full-screen-notification fallback. Never a fake
 * success.
 */
object TelecomRegistrar {

    /** Stable account id inside the app's Telecom namespace. */
    const val ACCOUNT_ID = "pulse_calls"

    /** Custom URI scheme for Pulse call handles (telecom passes it through). */
    const val URI_SCHEME = "pulse"

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    fun phoneAccountHandle(context: Context): PhoneAccountHandle? {
        if (!isSupported()) return null
        return PhoneAccountHandle(
            ComponentName(context, PulseConnectionService::class.java),
            ACCOUNT_ID,
        )
    }

    /** A stable telecom handle for one Pulse peer. */
    fun handleUri(peerId: String): Uri = Uri.fromParts(URI_SCHEME, peerId, null)

    /**
     * Register (or refresh) the self-managed account. Returns true only when
     * the account is registered with the system.
     */
    fun register(context: Context): Boolean {
        if (!isSupported()) return false
        val handle = phoneAccountHandle(context) ?: return false
        return runCatching {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false
            val account = PhoneAccount.builder(handle, "Pulse Calls")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setShortDescription("Pulse voice and video calls")
                .build()
            telecom.registerPhoneAccount(account)
            true
        }.getOrDefault(false)
    }

    /** True when the account is registered AND Telecom will accept our calls. */
    fun isRegistered(context: Context): Boolean {
        if (!isSupported()) return false
        val handle = phoneAccountHandle(context) ?: return false
        return runCatching {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return false
            telecom.getPhoneAccount(handle) != null
        }.getOrDefault(false)
    }
}
