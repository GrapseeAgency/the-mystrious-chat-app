package app.pulse.android.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.pulse.domain.repository.PulsePrefsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * R10-a - biometric App lock. The classic native-app power: when the user
 * flips the Settings → Privacy & Security toggle, Pulse is gated behind a
 * system BiometricPrompt (BIOMETRIC_WEAK | DEVICE_CREDENTIAL - fingerprints/
 * face or an honest PIN/password fallback) before ANY surface is reachable.
 *
 * State model:
 *  · the enabled flag persists in DataStore (pulse.settings.appLockOn, the
 *    same local settings block as haptics/quiet-hours - never the server
 *    blob: this is a device capability, not a cross-platform preference);
 *  · [unlocked] is process-lifetime ("this session") - a fresh process locks
 *    again, an unlocked foreground stays open;
 *  · failures are honest: [failureNotice] + [attempts] drive the Compose
 *    gate overlay; after [MAX_ATTEMPTS] consecutive failures the prompt backs
 *    off for [BACKOFF_MS] (also on system LOCKOUT errors) - there is no fake
 *    unlock path, and a device WITHOUT biometrics/screen lock simply shows
 *    the honest "can't verify" notice (the gate would never open otherwise).
 *
 * MainActivity (a FragmentActivity - androidx.biometric requires one) calls
 * [onHostResume] in onResume and [presentPrompt] from the overlay button.
 */
@Singleton
class PulseAppLock @Inject constructor(
    private val prefsStore: PulsePrefsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The persisted Settings toggle. */
    val enabled: StateFlow<Boolean> = prefsStore.appLockEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Process-lifetime unlock ("for this session" - resets on process death). */
    private val _unlocked = MutableStateFlow(false)

    /** True while the whole UI must sit behind the gate overlay. */
    val locked: StateFlow<Boolean> = combine(enabled, _unlocked) { on, open -> on && !open }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Honest gate copy - why the prompt is not up / what went wrong. */
    private val _failureNotice = MutableStateFlow<String?>(null)
    val failureNotice: StateFlow<String?> = _failureNotice.asStateFlow()

    /** Epoch-ms until which the prompt refuses to re-present (attempt backoff). */
    private val _backoffUntilMs = MutableStateFlow(0L)
    val backoffUntilMs: StateFlow<Long> = _backoffUntilMs.asStateFlow()

    private val _attempts = MutableStateFlow(0)
    val attempts: StateFlow<Int> = _attempts.asStateFlow()

    /**
     * True when the device lost its screen lock/biometrics AFTER App lock was
     * enabled - verification can never succeed. The gate then offers the one
     * honest escape hatch ([disableWithoutCredentials]) instead of a fake
     * unlock (a device without ANY credential protects nothing anyway).
     */
    private val _unlockImpossible = MutableStateFlow(false)
    val unlockImpossible: StateFlow<Boolean> = _unlockImpossible.asStateFlow()

    /** Called from MainActivity.onResume - presents the prompt when gated. */
    fun onHostResume(activity: FragmentActivity) {
        if (!locked.value) return
        presentPrompt(activity)
    }

    /**
     * Present the system prompt. No-ops while backing off; shows the honest
     * device-capability notice when the device lost its screen lock/biometrics
     * after App lock was enabled (the credential gate cannot be faked open).
     */
    fun presentPrompt(activity: FragmentActivity) {
        if (!locked.value) return
        if (System.currentTimeMillis() < _backoffUntilMs.value) return
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuth = BiometricManager.from(activity).canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // biometric 1.1.0 has no BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL -
            // with the combined BIOMETRIC_WEAK|DEVICE_CREDENTIAL set,
            // NONE_ENROLLED is the honest "nothing to verify with" verdict
            // (no biometrics enrolled AND no screen lock set).
            val impossible = canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
            _unlockImpossible.value = impossible
            _failureNotice.value = if (impossible) {
                "No biometrics or screen lock enrolled - unlock is impossible. " +
                    "Turn App lock off to get back in, then set a screen lock first."
            } else {
                "Biometric unlock is unavailable right now. Try again shortly."
            }
            return
        }
        _unlockImpossible.value = false
        _failureNotice.value = null
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _unlocked.value = true
                    _attempts.value = 0
                    _failureNotice.value = null
                    _backoffUntilMs.value = 0L
                }

                override fun onAuthenticationFailed() {
                    // One wrong fingerprint/face - count it, honest message.
                    val n = _attempts.value + 1
                    _attempts.value = n
                    _failureNotice.value =
                        "Didn't recognize you ($n/$MAX_ATTEMPTS). Try again."
                    if (n >= MAX_ATTEMPTS) startBackoff()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        // User dismissed / canceled / negative button - retryable.
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED,
                        -> _failureNotice.value = "Pulse is locked - tap Unlock to try again."
                        // Hardware lockouts are the system's own backoff.
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                        -> {
                            _failureNotice.value =
                                "Too many attempts - the system blocked retries for a while."
                            startBackoff()
                        }
                        else -> _failureNotice.value = errString.toString()
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Pulse")
            .setSubtitle("Verify it's you to open Pulse")
            .setAllowedAuthenticators(authenticators)
            .build()
        runCatching { prompt.authenticate(info) }
            .onFailure { _failureNotice.value = "Couldn't open the unlock prompt. Tap Unlock to retry." }
    }

    private fun startBackoff() {
        _backoffUntilMs.value = System.currentTimeMillis() + BACKOFF_MS
    }

    /**
     * The honest escape hatch for the lost-credential case above: persist the
     * toggle OFF (the gate lifts through [enabled] → [locked]). No unlock is
     * faked - this ONLY exists when no authenticator exists to verify with.
     */
    fun disableWithoutCredentials() {
        scope.launch { runCatching { prefsStore.setAppLockEnabled(false) } }
    }

    companion object {
        /** Consecutive failures before the honest backoff. */
        const val MAX_ATTEMPTS = 3

        /** Re-try window after MAX_ATTEMPTS - the overlay counts it down live. */
        const val BACKOFF_MS = 30_000L
    }
}
