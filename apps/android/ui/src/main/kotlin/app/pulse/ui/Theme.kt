package app.pulse.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pulse design tokens — the native translation of the web design language.
 *
 * R2-C item 3 — the FIVE locked design languages of the web (R25) now ride
 * this file too: `PulseUiTheme` is the port of src/lib/ui-theme.ts:35-76 +
 * the per-theme token blocks in globals.css (`[data-ui='ui-*']`: accent,
 * accent-2, radius-panel, page-bg, panel-bg, panel-border + the motion
 * personality). The selection persists under the SAME web key
 * (`pulse.uiTheme.v2`, same value strings) via PulsePrefsLocalStore; the
 * app shell reads it and hands it to [PulseTheme]. Light/dark stays
 * orthogonal, exactly like the web's next-themes split.
 */
enum class PulseUiThemeMotion { ELASTIC, CRISP, QUIET, PLAYFUL, GLIDE }

/**
 * One radial wash of the theme page backdrop (a CSS `radial-gradient`
 * layer): center/radius are FRACTIONS of the canvas, resolved at draw time.
 */
data class PulsePageRadial(
    val colors: List<Color>,
    val centerX: Float,
    val centerY: Float,
    val radiusFactor: Float,
)

/**
 * The five design languages (web UI_THEMES order verbatim). Colors are the
 * web tokens: `accent`/`accent2` are the ui-theme.ts swatch pairs; page/panel
 * values come from the globals.css light/dark token blocks per theme.
 */
enum class PulseUiTheme(
    /** The web value string — persisted verbatim under `pulse.uiTheme.v2`. */
    val id: String,
    val label: String,
    val tagline: String,
    /** One-line description for pickers (web `detail`). */
    val detail: String,
    /** Motion personality (web `motion`). */
    val motion: PulseUiThemeMotion,
    val accentLight: Color,
    val accent2Light: Color,
    val accentDark: Color,
    val accent2Dark: Color,
    /** The shared panel corner radius (web --ui-radius-panel, 28dp base). */
    val radiusPanel: Dp,
    /** Flat or linear base of the page backdrop, per mode. */
    val pageBaseLight: Color,
    val pageBaseDark: Color,
    val pageBaseLightTop: Color?,
    val pageBaseDarkTop: Color?,
    /** Radial washes layered above the base (glass/dynamic/aero). */
    val radialsLight: List<PulsePageRadial>,
    val radialsDark: List<PulsePageRadial>,
    /** Panel fill/border (web --ui-panel-bg / --ui-panel-border). */
    val panelBgLight: Color,
    val panelBorderLight: Color,
    val panelBgDark: Color,
    val panelBorderDark: Color,
) {
    GLASS(
        id = "glass",
        label = "Immersive Glass",
        tagline = "Glassmorphic Chat UI",
        detail = "Layered frosted glass, aurora backdrop, specular edges, elastic motion.",
        motion = PulseUiThemeMotion.ELASTIC,
        accentLight = Color(0xFF10B981),
        accent2Light = Color(0xFF0EA5E9),
        accentDark = Color(0xFF10B981),
        accent2Dark = Color(0xFF0EA5E9),
        radiusPanel = 28.dp,
        pageBaseLight = Color(0xFFF3F9F5),
        pageBaseDark = Color(0xFF09090B),
        pageBaseLightTop = null,
        pageBaseDarkTop = null,
        radialsLight = listOf(
            PulsePageRadial(listOf(Color(0x4210B981), Color.Transparent), 0.12f, -0.08f, 1.20f),
            PulsePageRadial(listOf(Color(0x330EA5E9), Color.Transparent), 0.96f, 0.12f, 1.00f),
            PulsePageRadial(listOf(Color(0x3314B8A6), Color.Transparent), 0.50f, 1.12f, 0.90f),
        ),
        radialsDark = listOf(
            PulsePageRadial(listOf(Color(0x3310B981), Color.Transparent), 0.12f, -0.08f, 1.20f),
            PulsePageRadial(listOf(Color(0x2638BDF8), Color.Transparent), 0.96f, 0.12f, 1.00f),
            PulsePageRadial(listOf(Color(0x2914B8A6), Color.Transparent), 0.50f, 1.12f, 0.90f),
        ),
        panelBgLight = Color(0x8CFFFFFF),
        panelBorderLight = Color(0x1A092A1F),
        panelBgDark = Color(0x14FFFFFF),
        panelBorderDark = Color(0x24FFFFFF),
    ),
    KINETIC(
        id = "kinetic",
        label = "Kinetic",
        tagline = "Kinetic UI",
        detail = "High-contrast ink, sharp corners, bold type, whip-crack springs.",
        motion = PulseUiThemeMotion.CRISP,
        accentLight = Color(0xFF18181B),
        accent2Light = Color(0xFFF43F5E),
        accentDark = Color(0xFFFAFAFA),
        accent2Dark = Color(0xFFF43F5E),
        radiusPanel = 14.dp,
        pageBaseLight = Color(0xFFF4F4F5),
        pageBaseDark = Color(0xFF111113),
        pageBaseLightTop = Color(0xFFFAFAFA),
        pageBaseDarkTop = Color(0xFF0A0A0A),
        radialsLight = emptyList(),
        radialsDark = emptyList(),
        panelBgLight = Color(0xFFFFFFFF),
        panelBorderLight = Color(0xD1000000),
        panelBgDark = Color(0xFF101012),
        panelBorderDark = Color(0xD9FFFFFF),
    ),
    MINIMAL(
        id = "minimal",
        label = "Quiet Minimal",
        tagline = "Motion-Driven Minimalist UI",
        detail = "Hairlines and whitespace. Motion whispers, structure speaks.",
        motion = PulseUiThemeMotion.QUIET,
        accentLight = Color(0xFF52525B),
        accent2Light = Color(0xFFA1A1AA),
        accentDark = Color(0xFF52525B),
        accent2Dark = Color(0xFFA1A1AA),
        radiusPanel = 22.dp,
        pageBaseLight = Color(0xFFFBFBFC),
        pageBaseDark = Color(0xFF0C0C0E),
        pageBaseLightTop = null,
        pageBaseDarkTop = null,
        radialsLight = emptyList(),
        radialsDark = emptyList(),
        panelBgLight = Color(0xEBFFFFFF),
        panelBorderLight = Color(0x0F000000),
        panelBgDark = Color(0xE6141417),
        panelBorderDark = Color(0x12FFFFFF),
    ),
    DYNAMIC(
        id = "dynamic",
        label = "Dynamic",
        tagline = "Dynamic Minimalism",
        detail = "Soft neutrals with vivid gradient accents and playful bounce.",
        motion = PulseUiThemeMotion.PLAYFUL,
        accentLight = Color(0xFFF59E0B),
        accent2Light = Color(0xFFEC4899),
        accentDark = Color(0xFFF59E0B),
        accent2Dark = Color(0xFFEC4899),
        radiusPanel = 26.dp,
        pageBaseLight = Color(0xFFFAF9F7),
        pageBaseDark = Color(0xFF0C0B0D),
        pageBaseLightTop = null,
        pageBaseDarkTop = null,
        radialsLight = listOf(
            PulsePageRadial(listOf(Color(0x1AF59E0B), Color.Transparent), 0.08f, 0.00f, 1.10f),
            PulsePageRadial(listOf(Color(0x1AEC4899), Color.Transparent), 0.92f, 1.00f, 1.10f),
        ),
        radialsDark = listOf(
            PulsePageRadial(listOf(Color(0x14F59E0B), Color.Transparent), 0.08f, 0.00f, 1.10f),
            PulsePageRadial(listOf(Color(0x17EC4899), Color.Transparent), 0.92f, 1.00f, 1.10f),
        ),
        panelBgLight = Color(0xD1FFFFFF),
        panelBorderLight = Color(0x0F000000),
        panelBgDark = Color(0xC718161A),
        panelBorderDark = Color(0x14FFFFFF),
    ),
    AERO(
        id = "aero",
        label = "Aero Kinetic",
        tagline = "Kinetic Minimalist Interface",
        detail = "Frost-stroke panels on cool graphite, gliding inertia.",
        motion = PulseUiThemeMotion.GLIDE,
        accentLight = Color(0xFF38BDF8),
        accent2Light = Color(0xFF818CF8),
        accentDark = Color(0xFF38BDF8),
        accent2Dark = Color(0xFF818CF8),
        radiusPanel = 24.dp,
        pageBaseLight = Color(0xFFEEF1F6),
        pageBaseDark = Color(0xFF0E1118),
        pageBaseLightTop = Color(0xFFF4F7FA),
        pageBaseDarkTop = Color(0xFF0B0E13),
        radialsLight = listOf(
            PulsePageRadial(listOf(Color(0x1A38BDF8), Color.Transparent), 0.50f, 0.00f, 1.30f),
        ),
        radialsDark = listOf(
            PulsePageRadial(listOf(Color(0x1738BDF8), Color.Transparent), 0.50f, 0.00f, 1.30f),
        ),
        panelBgLight = Color(0x8CFFFFFF),
        panelBorderLight = Color(0x38818CF8),
        panelBgDark = Color(0x9911151E),
        panelBorderDark = Color(0x33818CF8),
    ),
    ;

    fun accent(dark: Boolean): Color = if (dark) accentDark else accentLight
    fun accent2(dark: Boolean): Color = if (dark) accent2Dark else accent2Light
    fun pageBase(dark: Boolean): Color = if (dark) pageBaseDark else pageBaseLight
    fun pageBaseTop(dark: Boolean): Color? = if (dark) pageBaseDarkTop else pageBaseLightTop
    fun radials(dark: Boolean): List<PulsePageRadial> = if (dark) radialsDark else radialsLight
    fun panelBg(dark: Boolean): Color = if (dark) panelBgDark else panelBgLight
    fun panelBorder(dark: Boolean): Color = if (dark) panelBorderDark else panelBorderLight

    /** Opaque surface for Material panels: the translucent panel fill over the base. */
    fun surface(dark: Boolean): Color = panelBg(dark).compositeOver(pageBase(dark))

    /** The page backdrop — vertical base (web linear-gradients) + radial washes. */
    fun pageBrush(dark: Boolean): Brush {
        val top = pageBaseTop(dark)
        val base = if (top != null) {
            Brush.verticalGradient(listOf(top, pageBase(dark)))
        } else {
            SolidColor(pageBase(dark))
        }
        val washes = radials(dark)
        if (washes.isEmpty()) return base
        // Compose draws one Brush per node — layered washes ride
        // Modifier.pulseUiThemePageBackground below; a lone-wash fallback
        // keeps plain .background(theme.pageBrush(dark)) callers honest.
        return if (washes.size == 1) {
            Brush.radialGradient(washes[0].colors)
        } else {
            base
        }
    }

    companion object {
        fun fromId(raw: String?): PulseUiTheme = entries.firstOrNull { it.id == raw } ?: GLASS
    }
}

/** Current design language — provided by [PulseTheme], read by any surface. */
val LocalPulseUiTheme = staticCompositionLocalOf { PulseUiTheme.GLASS }

private val Ink = Color(0xFF09090B)

private fun onAccent(color: Color): Color =
    if (color.luminanceCompat() > 0.6f) Ink else Color.White

/** Stand-in for androidx.core luminance — keeps the ui module dependency-lean. */
private fun Color.luminanceCompat(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun lightScheme(theme: PulseUiTheme) = with(theme) {
    val accent = accentLight
    val accent2 = accent2Light
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent(accent),
        primaryContainer = lerp(pageBaseLight, accent, 0.18f),
        onPrimaryContainer = lerp(accent, Color.Black, 0.55f),
        secondary = accent2,
        onSecondary = onAccent(accent2),
        secondaryContainer = lerp(pageBaseLight, accent2, 0.18f),
        onSecondaryContainer = lerp(accent2, Color.Black, 0.55f),
        tertiary = Color(0xFF8B5CF6),
        error = Color(0xFFE11D48),
        background = pageBaseLight,
        onBackground = Ink,
        surface = surface(dark = false),
        onSurface = Ink,
        surfaceVariant = Color(0xFFF0F0EE),
        onSurfaceVariant = Color(0xFF52525B),
        outlineVariant = panelBorderLight,
    )
}

private fun darkScheme(theme: PulseUiTheme) = with(theme) {
    val accent = accentDark
    val accent2 = accent2Dark
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent(accent),
        primaryContainer = lerp(Color(0xFF18181B), accent, 0.30f),
        onPrimaryContainer = lerp(accent, Color.White, 0.72f),
        secondary = accent2,
        onSecondary = onAccent(accent2),
        secondaryContainer = lerp(Color(0xFF18181B), accent2, 0.30f),
        onSecondaryContainer = lerp(accent2, Color.White, 0.72f),
        tertiary = Color(0xFFA78BFA),
        error = Color(0xFFFB7185),
        background = pageBaseDark,
        onBackground = Color(0xFFE7E5E4),
        surface = surface(dark = true),
        onSurface = Color(0xFFE7E5E4),
        surfaceVariant = Color(0xFF232326),
        onSurfaceVariant = Color(0xFFA1A1AA),
        outlineVariant = panelBorderDark,
    )
}

private fun shapesFor(theme: PulseUiTheme): Shapes = Shapes(
    extraSmall = RoundedCornerShape(8),
    small = RoundedCornerShape(12),
    medium = RoundedCornerShape(16),
    large = RoundedCornerShape(20),
    // The shared panel radius IS the design language's silhouette.
    extraLarge = RoundedCornerShape(theme.radiusPanel),
)

@Composable
fun PulseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** R2-C — the selected design language (web `pulse.uiTheme.v2` value). */
    uiTheme: PulseUiTheme = PulseUiTheme.GLASS,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPulseUiTheme provides uiTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(uiTheme) else lightScheme(uiTheme),
            shapes = shapesFor(uiTheme),
            content = content,
        )
    }
}

/**
 * R2-C — the theme's page backdrop, drawn on the app-shell root: the base
 * (flat or vertical, per the web linear-gradient) plus every radial wash
 * layered in draw order (glass's aurora blobs, dynamic's amber/pink, aero's
 * top beam). Resolves fraction geometry at draw time so it tracks the canvas.
 */
fun Modifier.pulseUiThemePageBackground(theme: PulseUiTheme, dark: Boolean): Modifier =
    this.drawBehind {
        val top = theme.pageBaseTop(dark)
        if (top != null) {
            drawRect(
                Brush.verticalGradient(listOf(top, theme.pageBase(dark)), startY = 0f, endY = size.height),
            )
        } else {
            drawRect(theme.pageBase(dark))
        }
        theme.radials(dark).forEach { radial ->
            drawRect(
                Brush.radialGradient(
                    colors = radial.colors,
                    center = Offset(size.width * radial.centerX, size.height * radial.centerY),
                    radius = size.maxDimension * radial.radiusFactor,
                ),
            )
        }
    }
