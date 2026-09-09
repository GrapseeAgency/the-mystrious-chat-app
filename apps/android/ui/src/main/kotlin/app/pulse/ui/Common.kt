package app.pulse.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** The web palette — one source of truth for accents (ui-theme tokens). */
object PulsePalette {
    val Emerald = Color(0xFF10B981)
    val EmeraldDeep = Color(0xFF047857)
    val Teal = Color(0xFF14B8A6)
    val TealLight = Color(0xFF2DD4BF)
    val Violet = Color(0xFF8B5CF6)
    val Rose = Color(0xFFFB7185)
    val Amber = Color(0xFFF59E0B)

    val fallbacks = listOf(Emerald, Teal, Violet, Amber, Rose)

    fun parse(hex: String?): Color? {
        if (hex.isNullOrBlank()) return null
        val cleaned = hex.removePrefix("#")
        return when (cleaned.length) {
            6 -> runCatching { Color(android.graphics.Color.parseColor("#$cleaned")) }.getOrNull()
            8 -> runCatching { Color(android.graphics.Color.parseColor("#$cleaned")) }.getOrNull()
            else -> null
        }
    }

    fun gradientFor(name: String, colorHex: String?): List<Color> {
        val base = parse(colorHex) ?: fallbacks[abs(name.hashCode()) % fallbacks.size]
        return listOf(base.copy(alpha = 0.95f), base.darken(0.35f))
    }

    private fun Color.darken(f: Float): Color = Color(
        red = red * (1f - f),
        green = green * (1f - f),
        blue = blue * (1f - f),
        alpha = alpha,
    )
}

fun initialsOf(name: String): String = name.trim()
    .split(Regex("\\s+"))
    .filter { it.isNotBlank() }
    .take(2)
    .map { it.first().uppercaseChar() }
    .joinToString("")
    .ifBlank { "?" }

/** Avatar with the web's gradient-initials fallback and a live presence dot. */
@Composable
fun PulseAvatar(
    name: String,
    colorHex: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    online: Boolean = false,
    isGroup: Boolean = false,
) {
    Box(modifier = modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .clip(if (isGroup) RoundedCornerShape(size / 2.6f) else CircleShape)
                .background(Brush.linearGradient(PulsePalette.gradientFor(name, colorHex))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initialsOf(name),
                color = Color.White,
                fontSize = (size.value * 0.34f).sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        if (online) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.26f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(PulsePalette.Emerald),
            )
        }
    }
}

/** Shimmer placeholder — the web's skeleton sheen, animated via a moving brush. */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerProgress",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val sheen = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    background(
        Brush.linearGradient(
            0f to base,
            0.5f to sheen,
            1f to base,
            start = androidx.compose.ui.geometry.Offset(progress * 600f, 0f),
            end = androidx.compose.ui.geometry.Offset((progress + 1f) * 600f, 220f),
        ),
    )
}

// ── N10 glass recipe ─────────────────────────────────────────
// Compose has no backdrop blur; the established native recipe is a
// translucent panel fill + 1dp hairline border (+ a brighter top edge when
// a deep panel asks for the specular rim). Same recipe as ChatsScreen rows.

/** True when the active Pulse theme is dark (background luminance test). */
@Composable
fun isPulseDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

object PulseGlass {
    val LightFill = Color.White.copy(alpha = 0.78f)
    val LightBorder = Color(0xFFE4E4E7).copy(alpha = 0.70f)
    val LightDeepFill = Color.White.copy(alpha = 0.88f)
    val DarkFill = Color(0xFF18181B).copy(alpha = 0.70f)
    val DarkBorder = Color.White.copy(alpha = 0.06f)
    val DarkDeepFill = Color(0xFF0C0C0E).copy(alpha = 0.80f)

    fun fill(dark: Boolean, deep: Boolean = false): Color = when {
        deep && dark -> DarkDeepFill
        deep -> LightDeepFill
        dark -> DarkFill
        else -> LightFill
    }

    fun border(dark: Boolean): Color = if (dark) DarkBorder else LightBorder
}

/** Translucent panel + 1dp hairline — the fake-glass baseline for rows and pills. */
fun Modifier.pulseGlass(dark: Boolean, shape: Shape, deep: Boolean = false): Modifier = this
    .clip(shape)
    .background(PulseGlass.fill(dark, deep), shape)
    .border(1.dp, PulseGlass.border(dark), shape)
