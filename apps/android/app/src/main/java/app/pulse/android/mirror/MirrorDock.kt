package app.pulse.android.mirror

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R78 - the artboard dock EXACTLY as nav-router.tsx CapsuleNav renders it:
 * the container IS the art-panel glass pill (globals.css .art-panel:
 * rgba(24,18,13,0.66) bg + 1px hairline border + blur, rounded-full,
 * px-1.5 py-1) and the active tab is a COLOR-ONLY swap
 * (text #f5efe8 vs dim #9b8c7b) - the web capsule has NO pill, NO glow,
 * NO per-item background. The R73 white sliding pill was a native
 * invention (user report: "every individual has a background... web glows
 * with the theme color not white") - removed for 1:1 web parity.
 * Motion kept: active icon pops 1.08 with a -1dp lift (bouncy), unread
 * badge scales in, every slot carries the whileTap press physics.
 */
@Composable
internal fun MirrorDock(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
    onFab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .navigationBarsPadding()
            // web: inset-x-3 + mb-[safe+12px], centered with gap-2.5
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // art-panel pill (R79 real frost): web .art-panel = backdrop
        // blur(22px) saturate(1.4) + rgba(24,18,13,0.66) + 1px hairline,
        // rounded-full, px-1.5 py-1, gap-0.5. The frosted content scrolling
        // behind it is what makes the panel read as GLASS with substance -
        // the flat tint remains the API<31 fallback (user report: "the glass
        // has no color itself... like there is no background in it").
        val haze = LocalHazeState.current
        Box(
            Modifier
                .clip(CircleShape)
                .mirrorGlassPanel(haze, MirrorArt.Panel, 22.dp)
                .border(1.dp, MirrorArt.Hairline, CircleShape),
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MirrorDockItem(MirrorTab.Chats, "PChats", "Chats", activeTab == MirrorTab.Chats, badge = unread, onTab = onTab)
                // web: the Calls slot NEVER activates - it opens the calls sub-page
                Box(
                    Modifier
                        .width(64.dp)
                        .heightIn(min = 54.dp)
                        .mirrorPressClick(onClick = onCalls),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MirrorPhosphorIcon("PPhone", tint = MirrorArt.Dim, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(2.dp))
                        Text("Calls", color = MirrorArt.Dim, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
                MirrorDockItem(MirrorTab.Hub, "PHub", "Updates", activeTab == MirrorTab.Hub, badge = 0, onTab = onTab)
                MirrorDockItem(MirrorTab.Profile, "PProfile", "Profile", activeTab == MirrorTab.Profile, badge = 0, onTab = onTab)
            }
        }
        // art-fab (R79 real frost): web .art-fab = rgba(255,255,255,0.07) bg +
        // hairline + backdrop blur(18px) + the two-layer box-shadow. 52dp,
        // cream Plus 24dp - NOT an ember bomb.
        Box(
            Modifier
                .size(52.dp)
                .shadow(elevation = 18.dp, shape = CircleShape, spotColor = Color(0x8C000000))
                .clip(CircleShape)
                .mirrorGlassPanel(haze, MirrorArt.Chip, 18.dp)
                .border(1.dp, MirrorArt.Hairline, CircleShape)
                .mirrorPressClick(onClick = onFab),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon("LPlus", tint = MirrorArt.Text, modifier = Modifier.size(24.dp))
        }
    }
}

/** Dock tab - web DockTab: w-[64px] min-h-[54px], 22dp icon, 10sp label. */
@Composable
private fun MirrorDockItem(
    tab: MirrorTab,
    phosphor: String,
    label: String,
    active: Boolean,
    badge: Int,
    onTab: (MirrorTab) -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = if (active) MirrorArt.Text else MirrorArt.Dim,
        animationSpec = spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
        ),
        label = "dockTint",
    )
    // web nav-router.tsx: active icon scale 1.08 / y -1 with the bouncy spring
    val iconScale by animateFloatAsState(
        targetValue = if (active) 1.08f else 1f,
        animationSpec = MirrorMotion.press(),
        label = "dockIconScale",
    )
    val iconLift by animateDpAsState(
        targetValue = if (active) (-1).dp else 0.dp,
        animationSpec = MirrorMotion.press(),
        label = "dockIconLift",
    )
    Box(
        Modifier
            .width(64.dp)
            .heightIn(min = 54.dp)
            .mirrorPressClick(onClick = { onTab(tab) }),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                MirrorPhosphorIcon(
                    phosphor,
                    tint = tint,
                    modifier = Modifier
                        .offset(y = iconLift)
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                )
                // web badge pop: scale 0.4 -> 1 (bouncy) on appear, swap on change
                androidx.compose.animation.AnimatedVisibility(
                    visible = badge > 0,
                    enter = androidx.compose.animation.scaleIn(
                        initialScale = 0.4f,
                        animationSpec = MirrorMotion.press(),
                    ),
                    exit = androidx.compose.animation.scaleOut(targetScale = 0.4f),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Box(
                        Modifier
                            .offset(x = 8.dp, y = (-4).dp)
                            .heightIn(min = 16.dp)
                            .widthIn(min = 16.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.BadgeGradient)
                            // web UnreadBadge carries ring-2 ring-zinc-900 in dark
                            .border(2.dp, MirrorArt.PresenceRing, CircleShape)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (badge > 99) "99+" else badge.toString(),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}
