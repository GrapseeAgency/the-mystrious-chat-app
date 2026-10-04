package app.pulse.android.mirror

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/**
 * R59 - renders the EXACT icon geometry the web renders. Phosphor parts are
 * fill-based on a 256 viewport (duotone = 0.2 alpha underlay + solid outline),
 * Lucide parts are stroke-based on a 24 viewport (width 2, round caps/joins).
 * Path data is extracted verbatim from the same npm packages the web imports,
 * so a glyph here is pixel-identical to its browser twin at equal size.
 * Callers size the glyph with Modifier.size(...) exactly like the web's
 * size-4 / size-5 / size-[22px] classes.
 */
internal object MirrorIcons {

    internal fun phosphorVector(name: String, tint: Color): ImageVector {
        val parts = MirrorIconPaths.phosphor[name] ?: return placeholder(tint)
        val builder = ImageVector.Builder(
            name = "Mirror.Phosphor.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 256f,
            viewportHeight = 256f,
        )
        for ((alpha, d) in parts) {
            builder.addPath(
                pathData = addPathNodes(d),
                pathFillType = PathFillType.NonZero,
                fill = SolidColor(tint),
                fillAlpha = alpha,
            )
        }
        return builder.build()
    }

    internal fun lucideVector(name: String, tint: Color, strokeWidth: Float = 2f): ImageVector {
        val parts = MirrorIconPaths.lucide[name] ?: return placeholder(tint)
        val builder = ImageVector.Builder(
            name = "Mirror.Lucide.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (d in parts) {
            builder.addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(tint),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            )
        }
        return builder.build()
    }

    /** Missing glyph fallback: a small filled circle so errors are visible, never crashes. */
    private fun placeholder(tint: Color): ImageVector =
        ImageVector.Builder("Mirror.Placeholder", 24.dp, 24.dp, 24f, 24f)
            .addPath(
                pathData = addPathNodes("M12,4 a8,8 0 1,0 0,16 a8,8 0 1,0 0,-16"),
                fill = SolidColor(tint),
            )
            .build()
}

/** Phosphor glyph (duotone / fill / line) tinted and sized by the caller. */
@Composable
internal fun MirrorPhosphorIcon(name: String, tint: Color, modifier: Modifier = Modifier) {
    val vector = remember(name, tint) { MirrorIcons.phosphorVector(name, tint) }
    Icon(painter = rememberVectorPainter(vector), contentDescription = null, modifier = modifier, tint = Color.Unspecified)
}

/** Lucide stroke glyph tinted and sized by the caller. */
@Composable
internal fun MirrorLucideIcon(name: String, tint: Color, modifier: Modifier = Modifier, strokeWidth: Float = 2f) {
    val vector = remember(name, tint, strokeWidth) { MirrorIcons.lucideVector(name, tint, strokeWidth) }
    Icon(painter = rememberVectorPainter(vector), contentDescription = null, modifier = modifier, tint = Color.Unspecified)
}
