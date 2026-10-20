package app.pulse.feature.voice.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput

/** Cancellation must STOP, never masquerade as a short tap that latches capture. */
fun Modifier.pttHoldGesture(
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: (Long) -> Unit,
    onCancel: () -> Unit,
): Modifier = composed {
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val cancel by rememberUpdatedState(onCancel)
    pointerInput(enabled) {
        if (enabled) detectTapGestures(onPress = {
            val started = SystemClock.elapsedRealtime()
            var released = false
            try {
                down()
                released = tryAwaitRelease()
                if (released) up(SystemClock.elapsedRealtime() - started)
            } finally {
                if (!released) cancel()
            }
        })
    }
}
