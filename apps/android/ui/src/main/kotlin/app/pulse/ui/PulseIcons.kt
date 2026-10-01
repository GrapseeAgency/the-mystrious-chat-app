package app.pulse.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * PULSE EMBER icon set (Task EMB-A).
 *
 * Hand-drawn line icons on a 24dp viewport, SF-Symbols-calibrated stroke:
 * every glyph is a single stroked path with width 1.9, round caps and round
 * joins, built through the ImageVector builder (no font, no Material Icons).
 * Vectors carry a Color.Black stroke; callers tint white at the call site
 * (Icon(vector, tint = Color.White)).
 */
object PulseIcons {

    // ── builder helpers ────────────────────────────────────────────────

    /** One stroked 24dp glyph; every icon in this file routes through here. */
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "Pulse.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** Full circle centered at (cx, cy) with radius r (two half arcs). */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx, cy - r)
        arcTo(r, r, 0f, true, true, cx, cy + r)
        arcTo(r, r, 0f, true, true, cx, cy - r)
        close()
    }

    /** Near-solid dot (the stroke closes the tiny ring): kebab eyes, star fields. */
    private fun PathBuilder.dot(cx: Float, cy: Float) = circle(cx, cy, 0.95f)

    /** Rounded rectangle from (left, top) to (right, bottom), radius r. */
    private fun PathBuilder.roundedRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        r: Float,
    ) {
        moveTo(left + r, top)
        lineTo(right - r, top)
        arcToRelative(r, r, 0f, false, true, r, r)
        lineTo(right, bottom - r)
        arcToRelative(r, r, 0f, false, true, -r, r)
        lineTo(left + r, bottom)
        arcToRelative(r, r, 0f, false, true, -r, -r)
        lineTo(left, top + r)
        arcToRelative(r, r, 0f, false, true, r, -r)
        close()
    }

    // ── the glyphs ─────────────────────────────────────────────────────

    /** Magnifier: lens circle plus a diagonal handle. */
    val Search: ImageVector by lazy {
        icon("Search") {
            circle(11f, 11f, 6.4f)
            moveTo(15.7f, 15.7f)
            lineTo(20.2f, 20.2f)
        }
    }

    /** Camera body with a raised top plate and a round lens. */
    val Camera: ImageVector by lazy {
        icon("Camera") {
            roundedRect(3.2f, 6.9f, 20.8f, 19f, 2.6f)
            moveTo(8.6f, 6.9f)
            lineTo(9.7f, 4.4f)
            arcToRelative(1.2f, 1.2f, 0f, false, true, 1.1f, -0.7f)
            lineTo(13.2f, 3.7f)
            arcToRelative(1.2f, 1.2f, 0f, false, true, 1.1f, 0.7f)
            lineTo(15.4f, 6.9f)
            circle(12f, 12.7f, 3.4f)
        }
    }

    /** Vertical three-dot overflow. */
    val KebabVertical: ImageVector by lazy {
        icon("KebabVertical") {
            dot(12f, 5.2f)
            dot(12f, 12f)
            dot(12f, 18.8f)
        }
    }

    /** Horizontal three-dot overflow. */
    val KebabHorizontal: ImageVector by lazy {
        icon("KebabHorizontal") {
            dot(5.2f, 12f)
            dot(12f, 12f)
            dot(18.8f, 12f)
        }
    }

    /** Plain plus. */
    val Plus: ImageVector by lazy {
        icon("Plus") {
            moveTo(12f, 4.8f)
            lineTo(12f, 19.2f)
            moveTo(4.8f, 12f)
            lineTo(19.2f, 12f)
        }
    }

    val ChevronLeft: ImageVector by lazy {
        icon("ChevronLeft") {
            moveTo(14.6f, 5.2f)
            lineTo(7.8f, 12f)
            lineTo(14.6f, 18.8f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") {
            moveTo(9.4f, 5.2f)
            lineTo(16.2f, 12f)
            lineTo(9.4f, 18.8f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        icon("ChevronDown") {
            moveTo(5.2f, 9.4f)
            lineTo(12f, 16.2f)
            lineTo(18.8f, 9.4f)
        }
    }

    /** Close cross. */
    val X: ImageVector by lazy {
        icon("X") {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }

    /** Rounded chat bubble with a soft bottom-left tail. */
    val ChatBubble: ImageVector by lazy {
        icon("ChatBubble") {
            moveTo(12f, 4.4f)
            curveTo(7.2f, 4.4f, 3.6f, 7.4f, 3.6f, 11.3f)
            curveTo(3.6f, 13.4f, 4.7f, 15.2f, 6.5f, 16.4f)
            lineTo(5.6f, 19.8f)
            lineTo(9.4f, 17.8f)
            curveTo(10.2f, 18f, 11.1f, 18.1f, 12f, 18.1f)
            curveTo(16.8f, 18.1f, 20.4f, 15.1f, 20.4f, 11.3f)
            curveTo(20.4f, 7.4f, 16.8f, 4.4f, 12f, 4.4f)
            close()
        }
    }

    /** Classic handset. */
    val Phone: ImageVector by lazy {
        icon("Phone") {
            moveTo(20.6f, 16.6f)
            lineTo(20.6f, 19.2f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, -2.1f, 1.9f)
            arcToRelative(18.6f, 18.6f, 0f, false, true, -8.1f, -2.9f)
            arcToRelative(18.3f, 18.3f, 0f, false, true, -5.6f, -5.6f)
            arcToRelative(18.6f, 18.6f, 0f, false, true, -2.9f, -8.2f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, 1.9f, -2.1f)
            lineTo(6.4f, 2.3f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, 1.9f, 1.6f)
            arcToRelative(12.2f, 12.2f, 0f, false, false, 0.7f, 2.6f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, -0.4f, 2f)
            lineTo(7.5f, 9.6f)
            arcToRelative(15f, 15f, 0f, false, false, 5.6f, 5.6f)
            lineToRelative(1.1f, -1.1f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, 2f, -0.4f)
            arcToRelative(12.2f, 12.2f, 0f, false, false, 2.6f, 0.7f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, 1.8f, 2.2f)
            close()
        }
    }

    /** Video: rounded body plus the lens wedge. */
    val Video: ImageVector by lazy {
        icon("Video") {
            roundedRect(2.8f, 6.6f, 15.4f, 17.4f, 2.8f)
            moveTo(20.6f, 8.6f)
            lineTo(15.4f, 12f)
            lineTo(20.6f, 15.4f)
            close()
        }
    }

    /** Notification bell. */
    val Bell: ImageVector by lazy {
        icon("Bell") {
            moveTo(18f, 8.4f)
            arcToRelative(6f, 6f, 0f, false, false, -12f, 0f)
            curveTo(6f, 14.4f, 3.6f, 15.9f, 3.6f, 15.9f)
            lineTo(20.4f, 15.9f)
            curveTo(20.4f, 15.9f, 18f, 14.4f, 18f, 8.4f)
            close()
            moveTo(10.1f, 19.4f)
            arcToRelative(2.1f, 2.1f, 0f, false, false, 3.8f, 0f)
        }
    }

    /** Photo frame: rect, sun dot and mountain ridge. */
    val ImageIcon: ImageVector by lazy {
        icon("ImageIcon") {
            roundedRect(3.4f, 4.4f, 20.6f, 19.6f, 2.6f)
            circle(8.7f, 9.4f, 1.5f)
            moveTo(20.6f, 15.4f)
            lineTo(15.7f, 10.5f)
            lineTo(7.2f, 19f)
        }
    }

    /** Bookmark ribbon. */
    val Bookmark: ImageVector by lazy {
        icon("Bookmark") {
            moveTo(6.4f, 19.8f)
            lineTo(6.4f, 5.8f)
            arcToRelative(1.3f, 1.3f, 0f, false, true, 1.3f, -1.3f)
            lineTo(16.3f, 4.5f)
            arcToRelative(1.3f, 1.3f, 0f, false, true, 1.3f, 1.3f)
            lineTo(17.6f, 19.8f)
            lineTo(12f, 16.3f)
            close()
        }
    }

    /** Padlock. */
    val Lock: ImageVector by lazy {
        icon("Lock") {
            moveTo(7.6f, 10.8f)
            lineTo(7.6f, 8.1f)
            arcToRelative(4.4f, 4.4f, 0f, false, true, 8.8f, 0f)
            lineTo(16.4f, 10.8f)
            roundedRect(5.2f, 10.8f, 18.8f, 19.6f, 2.2f)
        }
    }

    /** Gear: hub circle plus eight elegant teeth spokes. */
    val Gear: ImageVector by lazy {
        icon("Gear") {
            circle(12f, 12f, 3.3f)
            // 8 teeth, 45 degrees apart, from r=5.9 to r=7.9
            moveTo(12f, 4.7f); lineTo(12f, 2.7f)
            moveTo(12f, 19.3f); lineTo(12f, 21.3f)
            moveTo(4.7f, 12f); lineTo(2.7f, 12f)
            moveTo(19.3f, 12f); lineTo(21.3f, 12f)
            moveTo(17.83f, 6.17f); lineTo(19.25f, 4.75f)
            moveTo(6.17f, 17.83f); lineTo(4.75f, 19.25f)
            moveTo(6.17f, 6.17f); lineTo(4.75f, 4.75f)
            moveTo(17.83f, 17.83f); lineTo(19.25f, 19.25f)
        }
    }

    /** Person: head plus shoulder arc. */
    val Person: ImageVector by lazy {
        icon("Person") {
            circle(12f, 7.8f, 3.7f)
            moveTo(4.8f, 20.2f)
            curveTo(4.8f, 16.3f, 8f, 13.9f, 12f, 13.9f)
            curveTo(16f, 13.9f, 19.2f, 16.3f, 19.2f, 20.2f)
        }
    }

    /** Two people. */
    val Users: ImageVector by lazy {
        icon("Users") {
            circle(9.4f, 8f, 3.5f)
            moveTo(3.4f, 19.8f)
            curveTo(3.4f, 16.4f, 6f, 14.2f, 9.4f, 14.2f)
            curveTo(12.8f, 14.2f, 15.4f, 16.4f, 15.4f, 19.8f)
            moveTo(16f, 4.9f)
            arcToRelative(3.2f, 3.2f, 0f, false, true, 0f, 6.2f)
            moveTo(17.8f, 14.6f)
            curveTo(19.7f, 15.3f, 20.9f, 17f, 20.9f, 19.8f)
        }
    }

    /** Globe with meridians. */
    val Globe: ImageVector by lazy {
        icon("Globe") {
            circle(12f, 12f, 8.6f)
            moveTo(3.4f, 12f)
            lineTo(20.6f, 12f)
            moveTo(12f, 3.4f)
            curveTo(14.9f, 5.7f, 14.9f, 18.3f, 12f, 20.6f)
            curveTo(9.1f, 18.3f, 9.1f, 5.7f, 12f, 3.4f)
            close()
        }
    }

    /** Send paper plane. */
    val PaperPlane: ImageVector by lazy {
        icon("PaperPlane") {
            moveTo(21.2f, 2.8f)
            lineTo(10.9f, 13.1f)
            moveTo(21.2f, 2.8f)
            lineTo(14.6f, 21.2f)
            lineTo(10.9f, 13.1f)
            lineTo(2.8f, 9.4f)
            close()
        }
    }

    /** Smiley. */
    val Smile: ImageVector by lazy {
        icon("Smile") {
            circle(12f, 12f, 8.6f)
            dot(8.9f, 9.7f)
            dot(15.1f, 9.7f)
            moveTo(8.1f, 14f)
            arcToRelative(4.7f, 4.7f, 0f, false, false, 7.8f, 0f)
        }
    }

    /** Microphone capsule on a stand. */
    val Mic: ImageVector by lazy {
        icon("Mic") {
            roundedRect(9.1f, 3.2f, 14.9f, 13.6f, 2.9f)
            moveTo(5.4f, 11.2f)
            arcToRelative(6.6f, 6.6f, 0f, false, false, 13.2f, 0f)
            moveTo(12f, 17.8f)
            lineTo(12f, 20.8f)
            moveTo(8.4f, 20.8f)
            lineTo(15.6f, 20.8f)
        }
    }

    /** Paperclip. */
    val Paperclip: ImageVector by lazy {
        icon("Paperclip") {
            moveTo(20.9f, 11.4f)
            lineToRelative(-8.9f, 8.9f)
            arcToRelative(5.8f, 5.8f, 0f, false, true, -8.2f, -8.2f)
            lineToRelative(8.9f, -8.9f)
            arcToRelative(3.9f, 3.9f, 0f, false, true, 5.5f, 5.5f)
            lineToRelative(-8.9f, 8.9f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, -2.8f, -2.8f)
            lineToRelative(8.3f, -8.2f)
        }
    }

    /** Single check. */
    val Check: ImageVector by lazy {
        icon("Check") {
            moveTo(4.8f, 12.9f)
            lineTo(9.7f, 17.6f)
            lineTo(19.2f, 6.9f)
        }
    }

    /** Double check (seen). */
    val Checks: ImageVector by lazy {
        icon("Checks") {
            moveTo(2.2f, 13f)
            lineTo(6.6f, 17.2f)
            lineTo(15.2f, 8.1f)
            moveTo(10.3f, 15.9f)
            lineTo(12.2f, 17.7f)
            lineTo(21.8f, 7.6f)
        }
    }

    /** Pushpin. */
    val Pin: ImageVector by lazy {
        icon("Pin") {
            moveTo(9f, 4.4f)
            lineTo(15f, 4.4f)
            lineTo(15.9f, 9.4f)
            lineTo(18.6f, 12.1f)
            lineTo(5.4f, 12.1f)
            lineTo(8.1f, 9.4f)
            close()
            moveTo(12f, 12.1f)
            lineTo(12f, 20f)
        }
    }

    /** Trash can. */
    val Trash: ImageVector by lazy {
        icon("Trash") {
            moveTo(4.4f, 6.6f)
            lineTo(19.6f, 6.6f)
            moveTo(8.2f, 6.6f)
            lineTo(8.2f, 5.2f)
            arcToRelative(1.4f, 1.4f, 0f, false, true, 1.4f, -1.4f)
            lineTo(14.4f, 3.8f)
            arcToRelative(1.4f, 1.4f, 0f, false, true, 1.4f, 1.4f)
            lineTo(15.8f, 6.6f)
            moveTo(6.2f, 6.6f)
            lineToRelative(0.8f, 12.2f)
            arcToRelative(1.6f, 1.6f, 0f, false, false, 1.6f, 1.5f)
            lineTo(15.4f, 20.3f)
            arcToRelative(1.6f, 1.6f, 0f, false, false, 1.6f, -1.5f)
            lineToRelative(0.8f, -12.2f)
            moveTo(10f, 10.6f)
            lineTo(10f, 16.4f)
            moveTo(14f, 10.6f)
            lineTo(14f, 16.4f)
        }
    }

    /** Crescent moon. */
    val Moon: ImageVector by lazy {
        icon("Moon") {
            moveTo(20.8f, 13f)
            arcToRelative(8.7f, 8.7f, 0f, true, true, -9.8f, -9.8f)
            arcToRelative(6.9f, 6.9f, 0f, false, false, 9.8f, 9.8f)
            close()
        }
    }

    /** Sun (theme cycle counterpart of Moon). */
    val Sun: ImageVector by lazy {
        icon("Sun") {
            circle(12f, 12f, 4f)
            moveTo(12f, 2.6f); lineTo(12f, 4.8f)
            moveTo(12f, 19.2f); lineTo(12f, 21.4f)
            moveTo(2.6f, 12f); lineTo(4.8f, 12f)
            moveTo(19.2f, 12f); lineTo(21.4f, 12f)
            moveTo(5.35f, 5.35f); lineTo(6.9f, 6.9f)
            moveTo(17.1f, 17.1f); lineTo(18.65f, 18.65f)
            moveTo(5.35f, 18.65f); lineTo(6.9f, 17.1f)
            moveTo(17.1f, 6.9f); lineTo(18.65f, 5.35f)
        }
    }

    /** Five-point star outline. */
    val Star: ImageVector by lazy {
        icon("Star") {
            moveTo(12f, 2.8f)
            lineTo(15f, 8.8f)
            lineTo(21.6f, 9.8f)
            lineTo(16.8f, 14.4f)
            lineTo(17.9f, 21f)
            lineTo(12f, 17.9f)
            lineTo(6.1f, 21f)
            lineTo(7.2f, 14.4f)
            lineTo(2.4f, 9.8f)
            lineTo(9f, 8.8f)
            close()
        }
    }

    /** Play triangle. */
    val Play: ImageVector by lazy {
        icon("Play") {
            moveTo(8.2f, 5.2f)
            lineTo(18.6f, 12f)
            lineTo(8.2f, 18.8f)
            close()
        }
    }

    /** Pause bars. */
    val Pause: ImageVector by lazy {
        icon("Pause") {
            moveTo(9.2f, 5.2f)
            lineTo(9.2f, 18.8f)
            moveTo(14.8f, 5.2f)
            lineTo(14.8f, 18.8f)
        }
    }

    /** Reply arrow (hook left). */
    val Reply: ImageVector by lazy {
        icon("Reply") {
            moveTo(9.4f, 6.8f)
            lineTo(4.2f, 12f)
            lineTo(9.4f, 17.2f)
            moveTo(4.2f, 12f)
            lineTo(13.6f, 12f)
            arcTo(5.4f, 5.4f, 0f, false, true, 19f, 17.4f)
            lineTo(19f, 19.2f)
        }
    }

    /** Pencil. */
    val Pencil: ImageVector by lazy {
        icon("Pencil") {
            moveTo(16.9f, 3.4f)
            arcToRelative(2.6f, 2.6f, 0f, false, true, 3.7f, 3.7f)
            lineTo(7.5f, 20.2f)
            lineTo(2.8f, 21.6f)
            lineTo(4.2f, 16.9f)
            close()
        }
    }

    /** Sparkle: four-point star with concave sides. */
    val Sparkle: ImageVector by lazy {
        icon("Sparkle") {
            moveTo(12f, 3.2f)
            curveTo(12.8f, 8f, 14.4f, 9.6f, 20.8f, 12f)
            curveTo(14.4f, 14.4f, 12.8f, 16f, 12f, 20.8f)
            curveTo(11.2f, 16f, 9.6f, 14.4f, 3.2f, 12f)
            curveTo(9.6f, 9.6f, 11.2f, 8f, 12f, 3.2f)
            close()
        }
    }

    /** At-sign (mentions). */
    val AtSign: ImageVector by lazy {
        icon("AtSign") {
            circle(12f, 12f, 4f)
            moveTo(16f, 8f)
            lineTo(16f, 13.1f)
            arcToRelative(2.4f, 2.4f, 0f, false, false, 4.8f, -0.4f)
            arcToRelative(8.8f, 8.8f, 0f, true, false, -3.4f, 6.6f)
        }
    }

    /** Broadcast waves around a dot (channels). */
    val Radio: ImageVector by lazy {
        icon("Radio") {
            circle(12f, 12f, 1.8f)
            moveTo(7.8f, 16.2f)
            arcToRelative(6f, 6f, 0f, false, true, 0f, -8.4f)
            moveTo(16.2f, 7.8f)
            arcToRelative(6f, 6f, 0f, false, true, 0f, 8.4f)
            moveTo(4.9f, 19.1f)
            arcToRelative(10f, 10f, 0f, false, true, 0f, -14.2f)
            moveTo(19.1f, 4.9f)
            arcToRelative(10f, 10f, 0f, false, true, 0f, 14.2f)
        }
    }

    /** Archive drawer. */
    val Archive: ImageVector by lazy {
        icon("Archive") {
            roundedRect(3.4f, 3.8f, 20.6f, 8.2f, 1.6f)
            moveTo(5f, 8.2f)
            lineTo(5f, 18.6f)
            arcToRelative(1.8f, 1.8f, 0f, false, false, 1.8f, 1.8f)
            lineTo(17.2f, 20.4f)
            arcToRelative(1.8f, 1.8f, 0f, false, false, 1.8f, -1.8f)
            lineTo(19f, 8.2f)
            moveTo(9.8f, 12.2f)
            lineTo(14.2f, 12.2f)
        }
    }

    /** Copy: two overlapping rounded rects. */
    val Copy: ImageVector by lazy {
        icon("Copy") {
            roundedRect(8.8f, 8.8f, 20.4f, 20.4f, 2.2f)
            moveTo(5.6f, 15.2f)
            lineTo(4.4f, 15.2f)
            arcToRelative(1.8f, 1.8f, 0f, false, true, -1.8f, -1.8f)
            lineTo(2.6f, 4.4f)
            arcToRelative(1.8f, 1.8f, 0f, false, true, 1.8f, -1.8f)
            lineTo(13.4f, 2.6f)
            arcToRelative(1.8f, 1.8f, 0f, false, true, 1.8f, 1.8f)
            lineTo(15.2f, 5.6f)
        }
    }

    /** Sign-out: door with an arrow leaving. */
    val Logout: ImageVector by lazy {
        icon("Logout") {
            moveTo(14.4f, 4.4f)
            lineTo(18.8f, 4.4f)
            arcToRelative(1.8f, 1.8f, 0f, false, true, 1.8f, 1.8f)
            lineTo(20.6f, 17.8f)
            arcToRelative(1.8f, 1.8f, 0f, false, true, -1.8f, 1.8f)
            lineTo(14.4f, 19.6f)
            moveTo(9.4f, 8.2f)
            lineTo(13.2f, 12f)
            lineTo(9.4f, 15.8f)
            moveTo(13.2f, 12f)
            lineTo(3.4f, 12f)
        }
    }

    /** Info circle. */
    val Info: ImageVector by lazy {
        icon("Info") {
            circle(12f, 12f, 8.6f)
            dot(12f, 8.1f)
            moveTo(12f, 11.3f)
            lineTo(12f, 16.4f)
        }
    }

    /** Equalizer bars (voice room live). */
    val Waveform: ImageVector by lazy {
        icon("Waveform") {
            moveTo(4f, 10f)
            lineTo(4f, 14f)
            moveTo(8f, 7f)
            lineTo(8f, 17f)
            moveTo(12f, 4.4f)
            lineTo(12f, 19.6f)
            moveTo(16f, 7f)
            lineTo(16f, 17f)
            moveTo(20f, 10f)
            lineTo(20f, 14f)
        }
    }

    /** Shield (safety). */
    val Shield: ImageVector by lazy {
        icon("Shield") {
            moveTo(12f, 3.2f)
            lineTo(19.4f, 6f)
            lineTo(19.4f, 11.2f)
            curveTo(19.4f, 15.9f, 16.4f, 19.3f, 12f, 20.8f)
            curveTo(7.6f, 19.3f, 4.6f, 15.9f, 4.6f, 11.2f)
            lineTo(4.6f, 6f)
            close()
        }
    }

    /** Calendar/clock reminder. */
    val Clock: ImageVector by lazy {
        icon("Clock") {
            circle(12f, 12f, 8.6f)
            moveTo(12f, 7f)
            lineTo(12f, 12f)
            lineTo(15.2f, 13.8f)
        }
    }

    // ── purge batch: glyphs replacing every remaining Material icon ────

    /** Eye. */
    val Eye: ImageVector by lazy {
        icon("Eye") {
            moveTo(2.9f, 12f)
            curveTo(5.6f, 7.6f, 8.6f, 5.6f, 12f, 5.6f)
            curveTo(15.4f, 5.6f, 18.4f, 7.6f, 21.1f, 12f)
            curveTo(18.4f, 16.4f, 15.4f, 18.4f, 12f, 18.4f)
            curveTo(8.6f, 18.4f, 5.6f, 16.4f, 2.9f, 12f)
            close()
            circle(12f, 12f, 3f)
        }
    }

    /** Broken eye with a strike-through (hidden). */
    val EyeOff: ImageVector by lazy {
        icon("EyeOff") {
            moveTo(4.6f, 8.3f)
            curveTo(6.7f, 6.4f, 9.1f, 5.4f, 12f, 5.4f)
            curveTo(15f, 5.4f, 17.6f, 6.5f, 19.7f, 8.4f)
            moveTo(4.9f, 15.5f)
            curveTo(6.9f, 17.2f, 9.2f, 18.2f, 12f, 18.2f)
            curveTo(14.9f, 18.2f, 17.3f, 17f, 19.3f, 15.2f)
            moveTo(4.2f, 4.2f)
            lineTo(19.8f, 19.8f)
        }
    }

    /** Circular refresh arrow. */
    val Refresh: ImageVector by lazy {
        icon("Refresh") {
            moveTo(14.5f, 5.2f)
            arcTo(7.2f, 7.2f, 0f, true, true, 9.5f, 5.2f)
            lineTo(6.8f, 4.7f)
            moveTo(9.5f, 5.2f)
            lineTo(7.7f, 7.4f)
        }
    }

    /** Pennant flag on a pole (report). */
    val Flag: ImageVector by lazy {
        icon("Flag") {
            moveTo(5.8f, 21f)
            lineTo(5.8f, 3.9f)
            moveTo(5.8f, 4.7f)
            lineTo(18.4f, 4.7f)
            lineTo(14.9f, 8.6f)
            lineTo(18.4f, 12.5f)
            lineTo(5.8f, 12.5f)
            close()
        }
    }

    /** Hang-up receiver: horizontal crescent. */
    val PhoneDown: ImageVector by lazy {
        icon("PhoneDown") {
            moveTo(3.8f, 12.6f)
            arcTo(9.6f, 9.6f, 0f, false, false, 20.2f, 12.6f)
            arcTo(11.6f, 11.6f, 0f, false, true, 3.8f, 12.6f)
            close()
        }
    }

    /** Video body with a strike-through (camera off). */
    val VideoSlash: ImageVector by lazy {
        icon("VideoSlash") {
            roundedRect(2.8f, 6.6f, 15.4f, 17.4f, 2.8f)
            moveTo(20.6f, 8.6f)
            lineTo(15.4f, 12f)
            lineTo(20.6f, 15.4f)
            close()
            moveTo(4f, 3.9f)
            lineTo(20f, 20.1f)
        }
    }

    /** Two curved arrows cycling (flip camera). */
    val FlipCamera: ImageVector by lazy {
        icon("FlipCamera") {
            moveTo(5.2f, 12f)
            arcTo(6.8f, 6.8f, 0f, false, true, 18.8f, 12f)
            moveTo(18.8f, 12f)
            lineTo(17.4f, 9.6f)
            moveTo(18.8f, 12f)
            lineTo(20.2f, 9.6f)
            moveTo(18.8f, 12f)
            arcTo(6.8f, 6.8f, 0f, false, true, 5.2f, 12f)
            moveTo(5.2f, 12f)
            lineTo(6.6f, 14.4f)
            moveTo(5.2f, 12f)
            lineTo(3.8f, 14.4f)
        }
    }

    /** Microphone with a strike-through (muted). */
    val MicOff: ImageVector by lazy {
        icon("MicOff") {
            roundedRect(9.1f, 3.2f, 14.9f, 13.6f, 2.9f)
            moveTo(5.4f, 11.2f)
            arcToRelative(6.6f, 6.6f, 0f, false, false, 13.2f, 0f)
            moveTo(4.4f, 3.8f)
            lineTo(19.6f, 20.2f)
        }
    }

    /** Speaker with sound waves. */
    val VolumeUp: ImageVector by lazy {
        icon("VolumeUp") {
            moveTo(4.2f, 9.4f)
            lineTo(7.8f, 9.4f)
            lineTo(12.6f, 5.4f)
            lineTo(12.6f, 18.6f)
            lineTo(7.8f, 14.6f)
            lineTo(4.2f, 14.6f)
            close()
            moveTo(15.9f, 9.3f)
            arcToRelative(3.9f, 3.9f, 0f, false, true, 0f, 5.4f)
            moveTo(18.5f, 6.9f)
            arcToRelative(7.3f, 7.3f, 0f, false, true, 0f, 10.2f)
        }
    }

    /** Speaker crossed out. */
    val VolumeOff: ImageVector by lazy {
        icon("VolumeOff") {
            moveTo(4.2f, 9.4f)
            lineTo(7.8f, 9.4f)
            lineTo(12.6f, 5.4f)
            lineTo(12.6f, 18.6f)
            lineTo(7.8f, 14.6f)
            lineTo(4.2f, 14.6f)
            close()
            moveTo(16.1f, 9.7f)
            lineTo(20.7f, 14.3f)
            moveTo(20.7f, 9.7f)
            lineTo(16.1f, 14.3f)
        }
    }

    /** Diagonal arrow up-right (outgoing, open externally). */
    val ArrowUpRight: ImageVector by lazy {
        icon("ArrowUpRight") {
            moveTo(6.6f, 17.4f)
            lineTo(17.4f, 6.6f)
            moveTo(9.2f, 6.6f)
            lineTo(17.4f, 6.6f)
            lineTo(17.4f, 14.8f)
        }
    }

    /** Diagonal arrow down-left (incoming). */
    val ArrowDownLeft: ImageVector by lazy {
        icon("ArrowDownLeft") {
            moveTo(17.4f, 6.6f)
            lineTo(6.6f, 17.4f)
            moveTo(6.6f, 9.2f)
            lineTo(6.6f, 17.4f)
            lineTo(14.8f, 17.4f)
        }
    }

    /** Straight arrow to the right. */
    val ArrowRight: ImageVector by lazy {
        icon("ArrowRight") {
            moveTo(4.4f, 12f)
            lineTo(19.2f, 12f)
            moveTo(13.2f, 6f)
            lineTo(19.2f, 12f)
            lineTo(13.2f, 18f)
        }
    }

    /** Lightning bolt. */
    val Bolt: ImageVector by lazy {
        icon("Bolt") {
            moveTo(13.2f, 2.9f)
            lineTo(5.9f, 13.4f)
            lineTo(11.1f, 13.4f)
            lineTo(10.8f, 21.1f)
            lineTo(18.1f, 10.6f)
            lineTo(12.9f, 10.6f)
            close()
        }
    }

    /** Bell with a strike-through (muted). */
    val BellOff: ImageVector by lazy {
        icon("BellOff") {
            moveTo(18f, 8.4f)
            arcToRelative(6f, 6f, 0f, false, false, -12f, 0f)
            curveTo(6f, 14.4f, 3.6f, 15.9f, 3.6f, 15.9f)
            lineTo(20.4f, 15.9f)
            curveTo(20.4f, 15.9f, 18f, 14.4f, 18f, 8.4f)
            close()
            moveTo(10.1f, 19.4f)
            arcToRelative(2.1f, 2.1f, 0f, false, false, 3.8f, 0f)
            moveTo(4.4f, 3.6f)
            lineTo(19.6f, 20.4f)
        }
    }

    /** Painter palette with paint dots. */
    val Palette: ImageVector by lazy {
        icon("Palette") {
            moveTo(19.4f, 16.3f)
            arcTo(8.6f, 8.6f, 0f, true, false, 16.3f, 19.4f)
            arcTo(2.3f, 2.3f, 0f, false, true, 19.4f, 16.3f)
            close()
            dot(8.6f, 9.7f)
            dot(12.3f, 7.9f)
            dot(15.9f, 9.9f)
        }
    }

    /** Chain link pair. */
    val Link: ImageVector by lazy {
        icon("Link") {
            moveTo(14.6f, 7.2f)
            lineTo(16.4f, 7.2f)
            arcTo(4.8f, 4.8f, 0f, false, true, 16.4f, 16.8f)
            lineTo(14.6f, 16.8f)
            moveTo(9.4f, 16.8f)
            lineTo(7.6f, 16.8f)
            arcTo(4.8f, 4.8f, 0f, false, true, 7.6f, 7.2f)
            lineTo(9.4f, 7.2f)
            moveTo(9.4f, 12f)
            lineTo(14.6f, 12f)
        }
    }

    /** Megaphone (announcements). */
    val Megaphone: ImageVector by lazy {
        icon("Megaphone") {
            moveTo(3.2f, 10.7f)
            lineTo(20.8f, 5.2f)
            lineTo(20.8f, 18.8f)
            lineTo(3.2f, 13.3f)
            close()
            moveTo(11.4f, 16.9f)
            arcToRelative(3f, 3f, 0f, true, true, -5.7f, -1.5f)
        }
    }

    /** Person with a small cross (remove member). */
    val PersonRemove: ImageVector by lazy {
        icon("PersonRemove") {
            circle(9.8f, 7.8f, 3.5f)
            moveTo(3.2f, 20.2f)
            curveTo(3.2f, 16.4f, 6.2f, 14f, 9.8f, 14f)
            curveTo(11.5f, 14f, 13f, 14.5f, 14.1f, 15.4f)
            moveTo(16.6f, 16.4f)
            lineTo(20.2f, 20f)
            moveTo(20.2f, 16.4f)
            lineTo(16.6f, 20f)
        }
    }

    /** Sign-in: bracket with an arrow entering. */
    val Login: ImageVector by lazy {
        icon("Login") {
            moveTo(10.2f, 4.4f)
            lineTo(5.4f, 4.4f)
            arcToRelative(1.8f, 1.8f, 0f, false, false, -1.8f, 1.8f)
            lineTo(3.6f, 17.8f)
            arcToRelative(1.8f, 1.8f, 0f, false, false, 1.8f, 1.8f)
            lineTo(10.2f, 19.6f)
            moveTo(15.8f, 12f)
            lineTo(6f, 12f)
            moveTo(9.8f, 8.2f)
            lineTo(6f, 12f)
            lineTo(9.8f, 15.8f)
        }
    }

    /** Six-dot drag grip. */
    val Grip: ImageVector by lazy {
        icon("Grip") {
            dot(9.6f, 6.6f)
            dot(14.4f, 6.6f)
            dot(9.6f, 12f)
            dot(14.4f, 12f)
            dot(9.6f, 17.4f)
            dot(14.4f, 17.4f)
        }
    }

    /** Gift box with a bow. */
    val Gift: ImageVector by lazy {
        icon("Gift") {
            moveTo(3.8f, 7.8f)
            lineTo(20.2f, 7.8f)
            moveTo(4.9f, 7.8f)
            lineTo(4.9f, 17.9f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, 1.9f, 1.9f)
            lineTo(17.2f, 19.8f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, 1.9f, -1.9f)
            lineTo(19.1f, 7.8f)
            moveTo(12f, 7.6f)
            lineTo(12f, 19.8f)
            moveTo(12f, 7.6f)
            curveTo(11.7f, 4.9f, 9.7f, 3.3f, 8.2f, 4.2f)
            curveTo(6.7f, 5.1f, 7.5f, 7.6f, 12f, 7.6f)
            moveTo(12f, 7.6f)
            curveTo(12.3f, 4.9f, 14.3f, 3.3f, 15.8f, 4.2f)
            curveTo(17.3f, 5.1f, 16.5f, 7.6f, 12f, 7.6f)
        }
    }

    /** Trophy cup with handles. */
    val Trophy: ImageVector by lazy {
        icon("Trophy") {
            moveTo(7.4f, 4.2f)
            lineTo(7.4f, 9.6f)
            arcTo(4.6f, 4.6f, 0f, false, false, 16.6f, 9.6f)
            lineTo(16.6f, 4.2f)
            close()
            moveTo(7.4f, 5.8f)
            arcToRelative(2f, 2f, 0f, false, false, 0f, 3.6f)
            moveTo(16.6f, 5.8f)
            arcToRelative(2f, 2f, 0f, false, true, 0f, 3.6f)
            moveTo(12f, 14.2f)
            lineTo(12f, 18.5f)
            moveTo(10.1f, 18.5f)
            lineTo(13.9f, 18.5f)
            moveTo(8.8f, 20.5f)
            lineTo(15.2f, 20.5f)
        }
    }

    /** Speed gauge: dial arc with a needle. */
    val Gauge: ImageVector by lazy {
        icon("Gauge") {
            moveTo(3.8f, 15.6f)
            arcTo(8.2f, 8.2f, 0f, false, true, 20.2f, 15.6f)
            moveTo(12f, 15.6f)
            lineTo(16.1f, 10.3f)
            dot(12f, 15.6f)
        }
    }

    /** Hash sign (topics). */
    val Hash: ImageVector by lazy {
        icon("Hash") {
            moveTo(9.5f, 4.4f)
            lineTo(7.5f, 19.6f)
            moveTo(16.5f, 4.4f)
            lineTo(14.5f, 19.6f)
            moveTo(4.7f, 9.2f)
            lineTo(19.7f, 9.2f)
            moveTo(4.3f, 14.8f)
            lineTo(19.3f, 14.8f)
        }
    }

    /** Circle with a slash (blocked). */
    val Ban: ImageVector by lazy {
        icon("Ban") {
            circle(12f, 12f, 8.6f)
            moveTo(6f, 6f)
            lineTo(18f, 18f)
        }
    }

    /** Document page with a folded corner. */
    val File: ImageVector by lazy {
        icon("File") {
            moveTo(14.6f, 3.2f)
            lineTo(6.2f, 3.2f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, -1.9f, 1.9f)
            lineTo(4.3f, 18.9f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, 1.9f, 1.9f)
            lineTo(17.8f, 20.8f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, 1.9f, -1.9f)
            lineTo(19.7f, 8.3f)
            close()
            moveTo(14.6f, 3.2f)
            lineTo(14.6f, 6.4f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, 1.9f, 1.9f)
            lineTo(19.7f, 8.3f)
        }
    }

    /** Map pin with a hole (location). */
    val MapPin: ImageVector by lazy {
        icon("MapPin") {
            moveTo(12f, 21.2f)
            curveTo(11.2f, 20.3f, 5.4f, 14.4f, 5.4f, 9.9f)
            arcTo(6.6f, 6.6f, 0f, false, true, 18.6f, 9.9f)
            curveTo(18.6f, 14.4f, 12.8f, 20.3f, 12f, 21.2f)
            close()
            circle(12f, 9.9f, 2.5f)
        }
    }

    /** Map pin with a strike-through (location off). */
    val MapPinSlash: ImageVector by lazy {
        icon("MapPinSlash") {
            moveTo(12f, 21.2f)
            curveTo(11.2f, 20.3f, 5.4f, 14.4f, 5.4f, 9.9f)
            arcTo(6.6f, 6.6f, 0f, false, true, 18.6f, 9.9f)
            curveTo(18.6f, 14.4f, 12.8f, 20.3f, 12f, 21.2f)
            close()
            moveTo(4.3f, 3.7f)
            lineTo(19.7f, 20.3f)
        }
    }

    /** Three bottom-aligned poll bars. */
    val Poll: ImageVector by lazy {
        icon("Poll") {
            moveTo(6.2f, 11.9f)
            lineTo(6.2f, 18.2f)
            moveTo(12f, 5.8f)
            lineTo(12f, 18.2f)
            moveTo(17.8f, 9.3f)
            lineTo(17.8f, 18.2f)
        }
    }

    /** Calendar page. */
    val Calendar: ImageVector by lazy {
        icon("Calendar") {
            roundedRect(3.8f, 5.4f, 20.2f, 19.6f, 2.4f)
            moveTo(3.8f, 9.7f)
            lineTo(20.2f, 9.7f)
            moveTo(8.4f, 3.4f)
            lineTo(8.4f, 7.4f)
            moveTo(15.6f, 3.4f)
            lineTo(15.6f, 7.4f)
        }
    }

    /** Gamepad: rounded body, d-pad cross, two buttons. */
    val Gamepad: ImageVector by lazy {
        icon("Gamepad") {
            roundedRect(2.8f, 7.6f, 21.2f, 16.4f, 4.4f)
            moveTo(7.5f, 10.3f)
            lineTo(7.5f, 13.7f)
            moveTo(5.8f, 12f)
            lineTo(9.2f, 12f)
            dot(15.3f, 10.9f)
            dot(18f, 13.1f)
        }
    }

    /** Kanban board columns. */
    val Columns: ImageVector by lazy {
        icon("Columns") {
            roundedRect(3.6f, 4.4f, 20.4f, 19.6f, 2.2f)
            moveTo(9.2f, 4.4f)
            lineTo(9.2f, 19.6f)
            moveTo(14.8f, 4.4f)
            lineTo(14.8f, 19.6f)
        }
    }

    /** Flame: teardrop with an inner tongue. */
    val Flame: ImageVector by lazy {
        icon("Flame") {
            moveTo(12f, 3.2f)
            curveTo(10.2f, 6.1f, 6.2f, 9.6f, 6.2f, 13.7f)
            arcTo(5.8f, 5.8f, 0f, false, false, 17.8f, 13.7f)
            curveTo(17.8f, 9.6f, 13.8f, 6.1f, 12f, 3.2f)
            close()
            moveTo(12f, 12.5f)
            curveTo(10.8f, 14f, 10.1f, 15.1f, 10.1f, 16.3f)
            arcTo(1.9f, 1.9f, 0f, false, false, 13.9f, 16.3f)
            curveTo(13.9f, 15.1f, 13.2f, 14f, 12f, 12.5f)
            close()
        }
    }

    /** Download: arrow into a tray. */
    val Download: ImageVector by lazy {
        icon("Download") {
            moveTo(12f, 4.4f)
            lineTo(12f, 14.8f)
            moveTo(7.9f, 10.9f)
            lineTo(12f, 15f)
            lineTo(16.1f, 10.9f)
            moveTo(4.4f, 15.7f)
            lineTo(4.4f, 17.8f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, 1.9f, 1.9f)
            lineTo(17.7f, 19.7f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, 1.9f, -1.9f)
            lineTo(19.6f, 15.7f)
        }
    }

    /** Folder with a tab. */
    val Folder: ImageVector by lazy {
        icon("Folder") {
            moveTo(2.6f, 18.5f)
            lineTo(2.6f, 5.6f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, 1.9f, -1.9f)
            lineTo(9.1f, 3.7f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, 1.6f, 0.9f)
            lineTo(12f, 6.3f)
            lineTo(19.5f, 6.3f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, 1.9f, 1.9f)
            lineTo(21.4f, 18.5f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, -1.9f, 1.9f)
            lineTo(4.5f, 20.4f)
            arcToRelative(1.9f, 1.9f, 0f, false, true, -1.9f, -1.9f)
            close()
        }
    }

    /** Raised hand (raise hand). */
    val Hand: ImageVector by lazy {
        icon("Hand") {
            moveTo(8f, 13f)
            lineTo(8f, 5.5f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 3f, 0f)
            lineTo(11f, 12f)
            moveTo(11f, 11.5f)
            lineTo(11f, 9.5f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 3f, 0f)
            lineTo(14f, 12f)
            moveTo(14f, 12f)
            lineTo(14f, 10.5f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 3f, 0f)
            lineTo(17f, 15f)
            moveTo(17f, 13.5f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 3f, 0f)
            lineTo(20f, 14.5f)
            arcToRelative(6f, 6f, 0f, false, true, -6f, 6f)
            lineTo(12f, 20.5f)
            arcToRelative(6f, 6f, 0f, false, true, -6f, -6f)
            lineTo(6f, 14.5f)
            lineTo(6f, 11.5f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 3f, 0f)
        }
    }

    /** Check inside a circle (verified). */
    val BadgeCheck: ImageVector by lazy {
        icon("BadgeCheck") {
            circle(12f, 12f, 8.6f)
            moveTo(8.2f, 12.2f)
            lineTo(11f, 15f)
            lineTo(16f, 9.4f)
        }
    }

    /** Exclamation in a circle (error). */
    val Alert: ImageVector by lazy {
        icon("Alert") {
            circle(12f, 12f, 8.6f)
            moveTo(12f, 7.4f)
            lineTo(12f, 13.1f)
            dot(12f, 16.5f)
        }
    }

    /** Picture in picture: small rect in a big rect. */
    val PiP: ImageVector by lazy {
        icon("PiP") {
            roundedRect(3.2f, 5f, 20.8f, 19f, 2.4f)
            roundedRect(11.8f, 11.6f, 17.6f, 15.8f, 1.2f)
        }
    }

    /** Share: arrow leaving a tray. */
    val ShareArrow: ImageVector by lazy {
        icon("ShareArrow") {
            moveTo(12f, 3.6f)
            lineTo(12f, 13.6f)
            moveTo(8.1f, 7.1f)
            lineTo(12f, 3.2f)
            lineTo(15.9f, 7.1f)
            moveTo(7f, 10.4f)
            lineTo(5.4f, 10.4f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, -1.9f, 1.9f)
            lineTo(3.5f, 17.9f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, 1.9f, 1.9f)
            lineTo(18.6f, 19.8f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, 1.9f, -1.9f)
            lineTo(20.5f, 12.3f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, -1.9f, -1.9f)
            lineTo(17f, 10.4f)
        }
    }

    /** Heart - the classic rounded-cardioid line heart. */
    val Heart: ImageVector by lazy {
        icon("Heart") {
            moveTo(12f, 20.2f)
            curveTo(11.2f, 20.2f, 3.4f, 15.6f, 3.4f, 9.6f)
            arcToRelative(4.6f, 4.6f, 0f, false, true, 8.6f, -2.3f)
            lineTo(12f, 7.6f)
            lineTo(12.1f, 7.3f)
            arcToRelative(4.6f, 4.6f, 0f, false, true, 8.6f, 2.3f)
            curveTo(20.6f, 15.6f, 12.8f, 20.2f, 12f, 20.2f)
            close()
        }
    }

    /** Paw - four toe beans over a main pad. */
    val Paw: ImageVector by lazy {
        icon("Paw") {
            circle(5.4f, 9.2f, 1.9f)
            circle(18.6f, 9.2f, 1.9f)
            circle(9.2f, 5.2f, 1.9f)
            circle(14.8f, 5.2f, 1.9f)
            moveTo(12f, 11.4f)
            curveTo(15.2f, 11.4f, 18f, 13.9f, 18f, 16.6f)
            curveTo(18f, 18.9f, 16.2f, 20.2f, 14.4f, 19.6f)
            curveTo(13.6f, 19.3f, 12.8f, 19.2f, 12f, 19.2f)
            curveTo(11.2f, 19.2f, 10.4f, 19.3f, 9.6f, 19.6f)
            curveTo(7.8f, 20.2f, 6f, 18.9f, 6f, 16.6f)
            curveTo(6f, 13.9f, 8.8f, 11.4f, 12f, 11.4f)
            close()
        }
    }

    /** Thumbs up - the web pulse-thumb voice. */
    val Thumb: ImageVector by lazy {
        icon("Thumb") {
            moveTo(7.2f, 10.6f)
            lineTo(7.2f, 20.2f)
            moveTo(7.2f, 20.2f)
            lineTo(17.2f, 20.2f)
            arcToRelative(2.1f, 2.1f, 0f, false, false, 2f, -1.6f)
            lineTo(20.4f, 13.4f)
            arcToRelative(1.7f, 1.7f, 0f, false, false, -1.7f, -2.1f)
            lineTo(14.2f, 11.3f)
            lineTo(15f, 6.2f)
            arcToRelative(1.6f, 1.6f, 0f, false, false, -3f, -1f)
            lineTo(7.2f, 10.6f)
            moveTo(7.2f, 10.6f)
            lineTo(3.6f, 10.6f)
            lineTo(3.6f, 20.2f)
            lineTo(7.2f, 20.2f)
        }
    }
}