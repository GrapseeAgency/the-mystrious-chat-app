package app.pulse.feature.stories

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The 8 story gradient stages — Tailwind palette values matching the web
 * AVATAR_GRADIENTS (from-*-400 to-*-600, `bg-gradient-to-br`):
 *   emerald-400 #34d399 / emerald-600 #059669, rose #fb7185/#e11d48,
 *   amber #fbbf24/#d97706, violet #a78bfa/#7c3aed, teal #2dd4bf/#0d9488,
 *   orange #fb923c/#ea580c, pink #f472b6/#db2777, cyan #22d3ee/#0891b2.
 * Unknown keys degrade to emerald (server default) — never a crash.
 */
object StoryPalette {

    private data class Stage(val from: Color, val to: Color)

    private val stages = mapOf(
        "emerald" to Stage(Color(0xFF34D399), Color(0xFF059669)),
        "rose" to Stage(Color(0xFFFB7185), Color(0xFFE11D48)),
        "amber" to Stage(Color(0xFFFBBF24), Color(0xFFD97706)),
        "violet" to Stage(Color(0xFFA78BFA), Color(0xFF7C3AED)),
        "teal" to Stage(Color(0xFF2DD4BF), Color(0xFF0D9488)),
        "orange" to Stage(Color(0xFFFB923C), Color(0xFFEA580C)),
        "pink" to Stage(Color(0xFFF472B6), Color(0xFFDB2777)),
        "cyan" to Stage(Color(0xFF22D3EE), Color(0xFF0891B2)),
    )

    val keys: List<String> get() = ComposerState.BACKGROUNDS

    /** Full-bleed text-story stage gradient (to bottom-right, web parity). */
    fun gradient(key: String): Brush {
        val stage = stages[key] ?: stages.getValue(ComposerState.DEFAULT_BACKGROUND)
        return Brush.linearGradient(listOf(stage.from, stage.to))
    }

    /** Composer swatch gradient (same pair, same order). */
    fun swatchColors(key: String): List<Color> {
        val stage = stages[key] ?: stages.getValue(ComposerState.DEFAULT_BACKGROUND)
        return listOf(stage.from, stage.to)
    }
}
