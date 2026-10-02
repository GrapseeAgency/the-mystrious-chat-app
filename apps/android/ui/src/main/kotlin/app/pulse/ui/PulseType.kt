package app.pulse.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont
import androidx.compose.ui.text.googlefonts.GoogleFont

/**
 * R35 Neo type system - the native mirror of the web's next/font setup
 * (src/app/layout.tsx: Space Grotesk carries the whole UI, JetBrains Mono
 * speaks for timestamps, handles, IDs and stat numerals).
 *
 * Both families load through the Google Play Services downloadable-fonts
 * provider (certs: res/values/font_certs.xml). The trailing platform faces
 * in each chain are the graceful offline fallback: if the provider cannot
 * fetch the font, Compose walks down the family to sans-serif / monospace
 * instead of crashing or blocking - the app is honest offline-first.
 */

private val pulseFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val spaceGrotesk = GoogleFont("Space Grotesk")
private val jetBrainsMono = GoogleFont("JetBrains Mono")

/** Display/UI face (weights 400/500/600/700) - fallback: platform sans-serif. */
val PulseDisplayFamily: FontFamily = FontFamily(
    GoogleFontFont(googleFont = spaceGrotesk, fontProvider = pulseFontProvider, weight = FontWeight.Normal),
    GoogleFontFont(googleFont = spaceGrotesk, fontProvider = pulseFontProvider, weight = FontWeight.Medium),
    GoogleFontFont(googleFont = spaceGrotesk, fontProvider = pulseFontProvider, weight = FontWeight.SemiBold),
    GoogleFontFont(googleFont = spaceGrotesk, fontProvider = pulseFontProvider, weight = FontWeight.Bold),
    // Platform fallback chain: DeviceFontFamilyName resolves the platform
    // face by name at draw time (API-safe at every minSdk, no Typeface
    // constants, and degrades to the system default if the face is absent).
    Font(DeviceFontFamilyName("sans-serif"), FontWeight.Normal),
    Font(DeviceFontFamilyName("sans-serif"), FontWeight.Medium),
    Font(DeviceFontFamilyName("sans-serif"), FontWeight.SemiBold),
    Font(DeviceFontFamilyName("sans-serif"), FontWeight.Bold),
)

/** Mono face for timestamps, handles, IDs and stat numerals - fallback: monospace. */
val PulseMonoFamily: FontFamily = FontFamily(
    GoogleFontFont(googleFont = jetBrainsMono, fontProvider = pulseFontProvider, weight = FontWeight.Normal),
    GoogleFontFont(googleFont = jetBrainsMono, fontProvider = pulseFontProvider, weight = FontWeight.Medium),
    GoogleFontFont(googleFont = jetBrainsMono, fontProvider = pulseFontProvider, weight = FontWeight.SemiBold),
    GoogleFontFont(googleFont = jetBrainsMono, fontProvider = pulseFontProvider, weight = FontWeight.Bold),
    Font(DeviceFontFamilyName("monospace"), FontWeight.Normal),
    Font(DeviceFontFamilyName("monospace"), FontWeight.Bold),
)

/**
 * The Material typography with every style riding [PulseDisplayFamily].
 * MaterialTheme seeds LocalTextStyle from bodyLarge, so plain Text calls
 * that only set fontSize/color inherit the Neo face app-wide without each
 * screen having to pass a fontFamily.
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
