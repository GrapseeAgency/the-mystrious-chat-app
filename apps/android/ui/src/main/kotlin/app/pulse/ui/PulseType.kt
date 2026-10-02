package app.pulse.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * R24 NATIVE type system.
 *
 * The app speaks the platform voice: system Roboto (FontFamily.Default)
 * carries the UI and the platform monospace face speaks for timestamps,
 * handles, IDs and stat numerals. The previous Google Fonts downloadable
 * families (Space Grotesk / JetBrains Mono) read as foreign chrome on a
 * native app and added first-launch font-provider latency; the platform
 * default is the native look AND the honest offline-first choice.
 *
 * The family slots keep their historical names so no call site changes.
 */

/** Display/UI face - the platform sans-serif (Roboto on Android). */
val PulseDisplayFamily: FontFamily = FontFamily.Default

/** Mono face for timestamps, handles, IDs and stat numerals - platform monospace. */
val PulseMonoFamily: FontFamily = FontFamily.Monospace

/**
 * The Material typography with every style riding [PulseDisplayFamily].
 * MaterialTheme seeds LocalTextStyle from bodyLarge, so plain Text calls
 * that only set fontSize/color inherit the system face app-wide without
 * each screen having to pass a fontFamily.
 */
fun pulseTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = PulseDisplayFamily),
        displayMedium = base.displayMedium.copy(fontFamily = PulseDisplayFamily),
        displaySmall = base.displaySmall.copy(fontFamily = PulseDisplayFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = PulseDisplayFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = PulseDisplayFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = PulseDisplayFamily),
        titleLarge = base.titleLarge.copy(fontFamily = PulseDisplayFamily),
        titleMedium = base.titleMedium.copy(fontFamily = PulseDisplayFamily),
        titleSmall = base.titleSmall.copy(fontFamily = PulseDisplayFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = PulseDisplayFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = PulseDisplayFamily),
        bodySmall = base.bodySmall.copy(fontFamily = PulseDisplayFamily),
        labelLarge = base.labelLarge.copy(fontFamily = PulseDisplayFamily),
        labelMedium = base.labelMedium.copy(fontFamily = PulseDisplayFamily),
        labelSmall = base.labelSmall.copy(fontFamily = PulseDisplayFamily),
    )
}
