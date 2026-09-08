package app.pulse.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Pulse motion tokens — the native (Compose physics) translation of the web
 * motion system (src/lib/motion.ts). Same physical language: springs over
 * durations, squash-and-stretch, faster exits than enters.
 *
 * Web presets (stiffness/damping/mass) → Compose (stiffness, dampingRatio):
 *   snappy 500/34/0.9 → 560 / 0.85   (chrome, tabs, press)
 *   soft   300/28/1.0 → 300 / 0.82   (sheets, cards, layout)
 *   bouncy 620/20/0.85 → 730 / 0.44  (badges, reactions, bubbles)
 *   gentle 140/22/1.1 → 120 / 0.90   (ambient drift, parallax)
 */
object PulseMotion {
    fun <T> snappy() = spring<T>(dampingRatio = 0.85f, stiffness = 560f)
    fun <T> soft() = spring<T>(dampingRatio = 0.82f, stiffness = 300f)
    fun <T> bouncy() = spring<T>(dampingRatio = 0.44f, stiffness = 730f)
    fun <T> gentle() = spring<T>(dampingRatio = 0.9f, stiffness = 120f)

    const val PRESS_SCALE = 0.94f
}

/**
 * Tactile press micro-interaction (web `whileTap: { scale: 0.94 }`).
 * Pair with a haptic at the interaction site.
 */
fun Modifier.pulsePress(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) PulseMotion.PRESS_SCALE else 1f,
        animationSpec = PulseMotion.snappy(),
        label = "pulsePress",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
inline fun rememberPressSource(): MutableInteractionSource = remember { MutableInteractionSource() }
