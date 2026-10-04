package app.pulse.android.mirror

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * R59 - the artboard palette, lifted verbatim from the web's globals.css
 * (R54 ARTBOARD EMBER block) plus the avatar gradient pairs from
 * pulse-utils.ts. The mirror shares the EXACT numbers the browser renders.
 */
internal object MirrorArt {

    // globals.css art tokens
    val Bg = Color(0xFF0D0906)
    val Text = Color(0xFFF5EFE8)
    val TextSoft = Color(0xFFCBC0B4)
    val Dim = Color(0xFF9B8C7B)
    val Faint = Color(0xFF7A6D5D)
    val Hairline = Color(0x14FFFFFF) // rgba(255,255,255,0.08)
    val Chip = Color(0x0FFFFFFF) // rgba(255,255,255,0.06)
    val ChipActive = Color(0x29FFFFFF) // rgba(255,255,255,0.16)
    val White10 = Color(0x1AFFFFFF) // white/10 - the idle chip count bubble
    val White20 = Color(0x33FFFFFF) // white/20 - active folder count bubble
    val White7 = Color(0x12FFFFFF) // white/[0.07] - folder idle count, search pill
    val Panel = Color(0xA818120D) // rgba(24,18,13,0.66)
    val BubbleOut = Color(0xFFF2EBDF) // --art-bubble-out bone cream
    val OnBubbleOut = Color(0xFF20150C) // --art-ink
    val Ink = Color(0xFF20150C) // --art-ink (alias used by room metas/quotes)
    val InkSoft = Color(0xA820150C) // --art-ink-soft rgba(32,21,12,.66)
    val InkFaint = Color(0x7A20150C) // --art-ink-faint rgba(32,21,12,.48)
    val BubbleIn = Color(0xFF291F16) // --art-bubble-in rgba(41,31,22,.94)
    val Accent = Color(0xFFFF7A3D)
    val Accent2 = Color(0xFFFFB86B)
    val Red = Color(0xFFFF453A)
    val SceneTop = Color(0xFF2B1C10)
    val PresenceOnline = Color(0xFF10B981) // emerald-500 (web presence truth)
    val PresenceOffline = Color(0xFF52525B) // zinc-600 dot truth
    val PresenceRing = Color(0xFF18181B) // zinc-900 - the dark ring around the dot
    val SealAmber = Color(0xFFC9762B)

    // FAB / ember gradient (art-fab)
    val FabGradient = Brush.linearGradient(listOf(Color(0xFFF0A35C), Accent, Color(0xFFD95F22)))

    // Dock badge gradient (rose-500 -> red-500)
    val BadgeGradient = Brush.linearGradient(listOf(Color(0xFFF43F5E), Color(0xFFEF4444)))

    /** avatar gradient 400 -> 600 pairs (pulse-utils AVATAR_GRADIENTS). */
    fun avatarGradient(color: String?, isGroup: Boolean = false, id: String = ""): List<Color> {
        if (isGroup) return groupGradient(id)
        return when (color) {
            "rose" -> listOf(Color(0xFFFB7185), Color(0xFFE11D48))
            "amber" -> listOf(Color(0xFFFBBF24), Color(0xFFD97706))
            "violet" -> listOf(Color(0xFFA78BFA), Color(0xFF7C3AED))
            "teal" -> listOf(Color(0xFF2DD4BF), Color(0xFF0D9488))
            "orange" -> listOf(Color(0xFFFB923C), Color(0xFFEA580C))
            "pink" -> listOf(Color(0xFFF472B6), Color(0xFFDB2777))
            "cyan" -> listOf(Color(0xFF22D3EE), Color(0xFF0891B2))
            else -> listOf(Color(0xFF34D399), Color(0xFF059669)) // emerald
        }
    }

    private val GROUP_GRADIENTS = listOf(
        Color(0xFFA78BFA) to Color(0xFF9333EA), // violet-400 -> purple-600
        Color(0xFFE879F9) to Color(0xFF9333EA), // fuchsia-400 -> purple-600
        Color(0xFFC084FC) to Color(0xFF6D28D9), // purple-400 -> violet-700
        Color(0xFFD946EF) to Color(0xFF6D28D9), // fuchsia-500 -> violet-700
    )

    fun groupGradient(id: String): List<Color> {
        val pair = GROUP_GRADIENTS[abs(hashString(id)) % GROUP_GRADIENTS.size]
        return listOf(pair.first, pair.second)
    }

    fun avatarBrush(color: String?, isGroup: Boolean = false, id: String = ""): Brush =
        Brush.verticalGradient(avatarGradient(color, isGroup, id))

    fun hashString(value: String): Int {
        var h = 0
        for (ch in value) h = (h * 31 + ch.code) or 0
        return abs(h)
    }

    /** initialsOf - first letters of the first two words, uppercase. */
    fun initials(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return "?"
        if (parts.size == 1) return parts[0].take(2).uppercase()
        return (parts[0].first() + "" + parts[parts.size - 1].first()).uppercase()
    }
}
