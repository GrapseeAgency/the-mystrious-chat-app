package app.pulse.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Pulse design tokens — the native translation of the web design language
 * (emerald primary, warm neutrals; zero emojis, Lucide-equivalent icons).
 * Dark/light parity with the web `ui-theme.ts` palette.
 */
private val Emerald = Color(0xFF10B981)
private val EmeraldDeep = Color(0xFF047857)
private val Ink = Color(0xFF09090B)
private val Mist = Color(0xFFFAFAF9)

private val LightColors = lightColorScheme(
    primary = EmeraldDeep,
    onPrimary = Color.White,
    secondary = Color(0xFF14B8A6),
    background = Mist,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = Emerald,
    onPrimary = Ink,
    secondary = Color(0xFF2DD4BF),
    background = Ink,
    onBackground = Color(0xFFE7E5E4),
    surface = Color(0xFF18181B),
    onSurface = Color(0xFFE7E5E4),
)

@Composable
fun PulseTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
