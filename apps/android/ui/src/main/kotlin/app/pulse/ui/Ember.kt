package app.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pulse screen-chrome tokens.
 *
 * R24 NATIVE: the values are re-pointed to the web-truth palette
 * (src/app/globals.css) - clean green-cast carbon surfaces with the
 * emerald/mint signal family, matching the chat room's own dark-in-both-modes
 * presentation. Historical slot names are kept deliberately: every styled
 * screen re-skins through this one object without a tree-wide rename.
 */
object EmberPalette {
    /** Backdrop vertical stack: elevated carbon -> mid -> page carbon. */
    val BackdropTop = Color(0xFF0D1211)
    val BackdropMid = Color(0xFF090D0C)
    val BackdropBase = Color(0xFF07090B)

    /** The radial glow behind a chat room header (emerald, drawn at 35%). */
    val Glow = Color(0xFF10B981)

    /** The accent gradient pair (send button, unseen story rings, FAB pulse).
    *   Web send-button verbatim: from-emerald-400 to-emerald-600. */
    val Amber = Color(0xFF34D399)
    val Deep = Color(0xFF059669)
    val Gradient: List<Color> = listOf(Amber, Deep)

    /** Destructive / alert signal (web --destructive). */
    val Signal = Color(0xFFFF5C6C)

    /** Online presence dot (native green). */
    val Online = Color(0xFF22C55E)

    /** Chrome fills: floating nav pill and composer pill (NeoSurface at 95%). */
    val PillFill = Color(0xF20D1211)
    /** Dark circle FAB fill (attach button, zinc-800 at 95%). */
    val FabFill = Color(0xF227272A)

    /** Bubble fills (web chat-room verbatim: zinc-800 incoming / emerald-500 mine). */
    val BubbleIn = Color(0xFF27272A)
    val BubbleOut = Color(0xFF10B981)

    /** Grouped sender name (web group sender: emerald-400). */
    val SenderName = Color(0xFF34D399)

    /** Standard chrome fills (web dark hairline family). */
    val GlassFill = Color.White.copy(alpha = 0.08f)
    val GlassBorder = Color.White.copy(alpha = 0.10f)
    val Hairline = Color.White.copy(alpha = 0.08f)
    val CardFill = Color.White.copy(alpha = 0.06f)
    val ChipFill = Color.White.copy(alpha = 0.08f)
}

/**
 * The page backdrop: vertical carbon gradient with an optional emerald
 * radial glow centered behind the header (chat rooms). Drawn behind screen
 * content; screens layer their own chrome on top.
 */
fun Modifier.emberBackdrop(withGlow: Boolean = false): Modifier = drawBehind {
    drawRect(
        Brush.verticalGradient(
            colors = listOf(
                EmberPalette.BackdropTop,
                EmberPalette.BackdropMid,
                EmberPalette.BackdropBase,
            ),
            startY = 0f,
            endY = size.height,
        ),
    )
    if (withGlow) {
        drawRect(
            Brush.radialGradient(
                colors = listOf(
                    EmberPalette.Glow.copy(alpha = 0.35f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.5f, size.height * 0.04f),
                // Fades out by roughly 40% of the screen height.
                radius = size.height * 0.40f,
            ),
        )
    }
}

/** Full-screen backdrop host: place screen content inside. */
@Composable
fun EmberBackdrop(
    withGlow: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .emberBackdrop(withGlow),
        content = content,
    )
}

/**
 * Standard header icon button - the native chrome unit (40-44dp).
 * R24 NATIVE: the glass circle fill is gone; a plain icon with a bounded
 * ripple reads like every native messaging-app header. A subtle fill rides
 * only where the host surface needs separation (GlassFill at 50%).
 */
@Composable
fun EmberGlassButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp,
    tint: Color = Color.White,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(EmberPalette.GlassFill.copy(alpha = 0.5f))
            .clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Ember glass panel: white 5% fill + hairline border in the given shape. */
fun Modifier.emberGlass(shape: Shape): Modifier = this
    .clip(shape)
    .background(EmberPalette.CardFill, shape)
    .border(1.dp, EmberPalette.Hairline, shape)
