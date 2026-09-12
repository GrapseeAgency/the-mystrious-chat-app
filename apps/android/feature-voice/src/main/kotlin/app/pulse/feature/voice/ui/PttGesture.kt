package app.pulse.feature.voice.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput

/** Press shorter than this LATCHES the mic (tap-to-latch); holding past it STOPS on release. */
const val PTT_LATCH_THRESHOLD_MS = 260L

/**
 * Push-to-talk gesture (spec §1.1 VR-3, web parity):
 *  • press → [onDown] fires once (the engine latches transmit on; a second tap
 *    while latched is the engine's job to read as unlatch);
 *  • release ≥ [PTT_LATCH_THRESHOLD_MS] → [onUp](heldMs) fires — hold-to-talk;
 *  • release < threshold → NO [onUp] — tap-to-latch (transmission continues).
 *
 * A cancelled/dragged-off pointer counts as release (honest stop, never a
 * ghost hot mic), matching the web's pointer-leave rule.
 */
fun Modifier.pttHoldGesture(
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: (heldMs: Long) -> Unit,
    latchThresholdMs: Long = PTT_LATCH_THRESHOLD_MS,
): Modifier = composed {
    if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val startedAt = System.currentTimeMillis()
                onDown()
                while (true) {
                    val event = awaitPointerEvent()
                    val allReleased = event.changes.all { !it.pressed }
                    if (allReleased) break
                }
                val heldMs = System.currentTimeMillis() - startedAt
                if (heldMs >= latchThresholdMs) onUp(heldMs)
            }
        }
    }
}
