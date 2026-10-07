package app.pulse.android.mirror

import androidx.compose.foundation.background
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * R79 - REAL frosted glass, the web backdrop-filter parity the owner demanded
 * ("the glass kinda has no color itself... the full navigation bar is like
 * there is no background in it"). The web stacks every glass surface as
 * backdrop blur + saturate + a translucent tint:
 *
 *   .art-panel  blur(22px) saturate(1.4) + rgba(24,18,13,0.66)   (globals.css:467)
 *   GLASS_PANEL blur-2xl(24px) saturate-150 + zinc-900/65        (nav-router.tsx:204)
 *   room header backdrop-blur-2xl + #0d0906/70                   (chat-room.tsx:3917)
 *   kebab       backdrop-blur-xl + #1c1610/95                    (chat-room.tsx:4059)
 *
 * Haze renders that exact stack on API 31+ (RenderEffect); below 31 - and in
 * any composition without a source (tests/previews) - it degrades to the flat
 * translucent tint, which is byte-identical to the R78 look. So the glass
 * ALWAYS has its color, and on modern devices it also frosts the content
 * scrolling behind it.
 *
 * Architecture (one source per surface, effects are siblings ABOVE it, the
 * canonical haze layout - never an effect nested inside its own source):
 *   shell: MirrorRoot wraps the tab content + sub-pages in ONE hazeSource;
 *          the dock, the 13 nav style containers and the shell kebab read
 *          [LocalHazeState] and frost it.
 *   room:  MirrorRoom owns a second state; the message list is the source,
 *          the room header + room kebab frost it.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * Marks a node as the content that glass panels floating ABOVE it will frost.
 * No-op when no state is provided (tests/previews) - the panels then fall
 * back to their flat tint.
 */
internal fun Modifier.mirrorHazeSource(state: HazeState?): Modifier =
    if (state != null) hazeSource(state) else this

/**
 * A web frosted panel: blurred backdrop + translucent tint + (below API 31)
 * the same tint as a flat fallback scrim. Replaces `.background(tint)` in a
 * clip->background->border chain: `.clip(shape).mirrorGlassPanel(...).border(...)`.
 */
internal fun Modifier.mirrorGlassPanel(
    state: HazeState?,
    tint: Color,
    blurRadius: Dp = 24.dp,
): Modifier {
    if (state == null) return background(tint)
    return hazeEffect(
        state,
        style = HazeStyle(
            tint = HazeTint(tint),
            blurRadius = blurRadius,
            fallbackTint = HazeTint(tint),
        ),
    )
}
