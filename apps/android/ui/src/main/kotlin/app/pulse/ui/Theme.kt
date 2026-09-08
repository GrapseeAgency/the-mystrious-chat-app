package app.pulse.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Pulse design tokens — the native translation of the web design language
 * (emerald primary, warm neutrals; zero emojis, Lucide-equivalent SF/Material
 * icons). Dark/light parity with the web `ui-theme.ts` palette; glass-era
 * rounded shapes.
 */
private val Emerald = Color(0xFF10B981)
private val EmeraldDeep = Color(0xFF047857)
private val Ink = Color(0xFF09090B)
private val Mist = Color(0xFFFAFAF9)

private val LightColors = lightColorScheme(
    primary = EmeraldDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF14B8A6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFF8B5CF6),
    error = Color(0xFFE11D48),
    background = Mist,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0F0EE),
    onSurfaceVariant = Color(0xFF52525B),
    outlineVariant = Color(0xFFE4E4E7),
)

private val DarkColors = darkColorScheme(
    primary = Emerald,
    onPrimary = Ink,
    primaryContainer = Color(0xFF065F46),
    onPrimaryContainer = Color(0xFFD1FAE5),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Ink,
    secondaryContainer = Color(0xFF134E4A),
    tertiary = Color(0xFFA78BFA),
    error = Color(0xFFFB7185),
    background = Ink,
    onBackground = Color(0xFFE7E5E4),
    surface = Color(0xFF18181B),
    onSurface = Color(0xFFE7E5E4),
    surfaceVariant = Color(0xFF232326),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outlineVariant = Color(0xFF2E2E32),
)

private val PulseShapes = Shapes(
    extraSmall = RoundedCornerShape(8),
    small = RoundedCornerShape(12),
    medium = RoundedCornerShape(16),
    large = RoundedCornerShape(20),
    extraLarge = RoundedCornerShape(28),
)

@Composable
fun PulseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = PulseShapes,
        content = content,
    )
}
