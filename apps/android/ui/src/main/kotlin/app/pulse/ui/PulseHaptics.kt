package app.pulse.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * R50-e - the haptics MASTER GATE.
 *
 * The settings screen has always shipped a "Haptics" toggle, but it only
 * gated the notification preview alert - raw LocalHapticFeedback calls in
 * the UI buzzed regardless of the user's choice. Every UI haptic now flows
 * through [rememberGatedHaptics], which honors this process-wide flag.
 *
 * The flag is written by PulseApplication (which already observes the
 * prefs.hapticsOn flow for the same reason) and defaults to ON so a UI
 * render before the first prefs emission still feels alive.
 */
object PulseHapticsGate {
    @Volatile
    var enabled: Boolean = true
}

/**
 * A [HapticFeedback] wrapper that no-ops while the master gate is off.
 * Drop-in for LocalHapticFeedback.current at every interaction site.
 */
class GatedHapticFeedback(private val base: HapticFeedback) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (PulseHapticsGate.enabled) {
            base.performHapticFeedback(hapticFeedbackType)
        }
    }
}

/** The gated haptics handle every interaction site should use. */
@Composable
fun rememberGatedHaptics(): HapticFeedback {
    val base = LocalHapticFeedback.current
    return remember(base) { GatedHapticFeedback(base) }
}
