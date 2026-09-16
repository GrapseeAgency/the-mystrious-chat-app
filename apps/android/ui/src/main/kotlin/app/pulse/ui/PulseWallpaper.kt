package app.pulse.ui

// ─────────────────────────────────────────────────────────────
// Wave 8 — chat wallpaper tokens (F-SE-02/03). The picker lives in
// feature-settings; the room background render lives in feature-chat;
// both consume THIS single source so the tokens can never drift.
// Native-native decision: subtle color washes behind the timeline —
// never a heavy photo wallpaper (P1: platform-idiomatic, restrained).
// ─────────────────────────────────────────────────────────────
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object PulseWallpaper {
    val TOKENS: List<Pair<String, String>> = listOf(
        "none" to "None",
        "aurora" to "Aurora",
        "dusk" to "Dusk",
        "forest" to "Forest",
        "mono" to "Mono",
    )

    /** Subtle vertical wash for the room timeline; null = system background. */
    fun brush(token: String?): Brush? = when (token) {
        "aurora" -> Brush.verticalGradient(listOf(Color(0xFF0E3B2E).copy(alpha = 0.10f), Color(0xFF123B33).copy(alpha = 0.04f)))
        "dusk" -> Brush.verticalGradient(listOf(Color(0xFF3B2E4F).copy(alpha = 0.12f), Color(0xFF2E2338).copy(alpha = 0.05f)))
        "forest" -> Brush.verticalGradient(listOf(Color(0xFF1E3B22).copy(alpha = 0.12f), Color(0xFF14291A).copy(alpha = 0.05f)))
        "mono" -> Brush.verticalGradient(listOf(Color(0xFF161616).copy(alpha = 0.08f), Color(0xFF101010).copy(alpha = 0.03f)))
        else -> null
    }
}
