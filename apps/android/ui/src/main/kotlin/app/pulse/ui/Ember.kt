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
 * PULSE EMBER design tokens (Task EMB-A).
 *
 * The language: a warm sunset-blur backdrop instead of flat black, glass
 * circle chrome, hand-drawn white line icons (PulseIcons), ember amber as
 * the single warm signal and #FF453A reserved for unread/active dots.
 */
object EmberPalette {
    /** Backdrop vertical stack: warm brown -> mid umber -> near-black warm. */
    val BackdropTop = Color(0xFF5C4030)
    val BackdropMid = Color(0xFF33241B)
    val BackdropBase = Color(0xFF150F0B)

    /** The radial warm glow behind a chat room header (#7A4E33 at 35%). */
    val Glow = Color(0xFF7A4E33)

    /** The ember gradient pair (send button, unseen story rings, FAB pulse). */
    val Amber = Color(0xFFFFB86B)
    val Deep = Color(0xFFFF7A3D)
    val Gradient: List<Color> = listOf(Amber, Deep)

    /** Unread / active-tab signal red. */
    val Signal = Color(0xFFFF453A)

    /** Online presence dot. */
    val Online = Color(0xFFFF9F0A)

    /** Chrome fills: the floating nav pill and composer pill (#1C1410 at 92%). */
    val PillFill = Color(0xEB1C1410)
    /** Dark circle FAB fill (attach button, 92% of the same ink). */
    val FabFill = Color(0xEB1C1410)

    /** Bubble fills. */
    val BubbleIn = Color(0xFF2E2824)
    val BubbleOut = Color(0xFF17110D)

    /** Grouped sender name / warm accent text. */
    val SenderName = Color(0xFFFFB86B)

    /** Standard glass fills. */
    val GlassFill = Color.White.copy(alpha = 0.09f)
    val GlassBorder = Color.White.copy(alpha = 0.12f)
    val Hairline = Color.White.copy(alpha = 0.08f)
    val CardFill = Color.White.copy(alpha = 0.05f)
    val ChipFill = Color.White.copy(alpha = 0.08f)
}

/**
 * The Ember page backdrop: vertical sunset gradient with an optional warm
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

/** Glass circle icon button - the Ember chrome unit (40-44dp, white 9% fill). */
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
            .background(EmberPalette.GlassFill)
            .border(1.dp, EmberPalette.GlassBorder, CircleShape)
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
