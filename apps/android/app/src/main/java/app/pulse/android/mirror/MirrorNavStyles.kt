package app.pulse.android.mirror

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.protocol.PulseNavStyle

/**
 * R78 - the REAL navigation-style system (web nav-router.tsx 1:1 port).
 * The R66 settings picker persisted a style the shell never read; every
 * pick looked like a showcase no-op (user report). This router renders all
 * 13 web architectures with the exact web tokens:
 *
 *   GLASS_PANEL (dark): bg zinc-900/65 + white/10 border + deep drop shadow
 *   Amber active pills: amber-500 gradient fills + amber ring + amber glow
 *   Edge bars:          zinc-950/85 + hairline top border
 *
 * Zones follow the web zoneFor() map: bottom / top / side (rail) / overlay.
 */

/** Web GLASS_PANEL dark recipe constants (nav-router.tsx:204-205). */
internal object NavGlass {
    val Panel = Color(0xA618181B)        // dark:bg-zinc-900/65
    val Border = Color(0x1AFFFFFF)       // dark:border-white/10
    val Bar85 = Color(0xD9090B0B)        // dark:bg-zinc-950/85
    val Bar80 = Color(0xCC09090B)        // dark:bg-zinc-950/80
    val Rail60 = Color(0x9909090B)       // dark:bg-zinc-950/60
    val Shadow = Color(0x73000000)       // 0 8px 32px rgba(0,0,0,0.45)

    // amber family (nav-router.tsx active indicators)
    val Amber500 = Color(0xFFF59E0B)
    val Amber400 = Color(0xFFFBBF24)
    val Amber600 = Color(0xFFD97706)
    val Orange600 = Color(0xFFEA580C)

    // amber pill brush: from-amber-500/20 to-amber-500/[0.06]
    val PillGradient = Brush.verticalGradient(listOf(Color(0x33F59E0B), Color(0x0FF59E0B)))
    // pill-nav active fill: from-amber-600 to-orange-600
    val FillGradient = Brush.horizontalGradient(listOf(Amber600, Orange600))
}

/** Web zoneFor() (nav-registry zones). */
internal enum class MirrorNavZone { BOTTOM, TOP, SIDE, OVERLAY }

internal fun mirrorNavZoneOf(style: PulseNavStyle): MirrorNavZone = when (style) {
    PulseNavStyle.FLOATING_TOP, PulseNavStyle.COMMAND_BAR -> MirrorNavZone.TOP
    PulseNavStyle.RAIL -> MirrorNavZone.SIDE
    PulseNavStyle.RADIAL, PulseNavStyle.GESTURE -> MirrorNavZone.OVERLAY
    else -> MirrorNavZone.BOTTOM
}

/**
 * The router: web PulseNavBar switch (nav-router.tsx:1352-1391). Rendered
 * from MirrorRoot inside the shell Box; each renderer aligns itself to its
 * own zone. The capsule delegates to MirrorDock (the R78 fixed art-panel).
 * R79 adds the contextual-dock per-tab chip actions (web CONTEXT_ACTION
 * nav-router.tsx:1245-1250) + real frosted glass on every container.
 */
@Composable
internal fun BoxScope.MirrorNavRouter(
    navStyle: PulseNavStyle,
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
    onFab: () -> Unit,
    onKebab: () -> Unit,
    onSearch: () -> Unit,
    onNewGroup: () -> Unit,
    onSettings: () -> Unit,
) {
    when (navStyle) {
        PulseNavStyle.CAPSULE -> MirrorDock(
            activeTab = activeTab,
            unread = unread,
            onTab = onTab,
            onCalls = onCalls,
            onFab = onFab,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        PulseNavStyle.FLOATING_TOP -> MirrorFloatingTopNav(activeTab, unread, onTab, onCalls, onKebab)
        PulseNavStyle.FLOATING_DOCK -> MirrorFloatingDockNav(activeTab, unread, onTab, onCalls)
        PulseNavStyle.PILL -> MirrorPillNav(activeTab, unread, onTab, onCalls)
        PulseNavStyle.BOTTOM_BAR -> MirrorBottomBarNav(activeTab, unread, onTab, onCalls)
        PulseNavStyle.TAB_BAR -> MirrorTabBarNav(activeTab, unread, onTab, onCalls)
        PulseNavStyle.FLOATING_TAB_BAR -> MirrorFloatingTabBarNav(activeTab, unread, onTab, onCalls)
        PulseNavStyle.COMMAND_BAR -> MirrorCommandBarNav(activeTab, unread, onTab, onCalls, onSearch, onKebab)
        PulseNavStyle.RAIL -> MirrorRailNav(activeTab, unread, onTab, onCalls)
        PulseNavStyle.ISLAND -> MirrorIslandNav(activeTab, unread, onTab, onCalls)
        PulseNavStyle.RADIAL -> MirrorRadialNav(activeTab, unread, onTab, onCalls)
        PulseNavStyle.GESTURE -> MirrorGestureNav(activeTab, unread, onTab, onCalls)
        PulseNavStyle.CONTEXTUAL_DOCK -> MirrorContextualDockNav(activeTab, unread, onTab, onCalls, onFab, onSearch, onNewGroup, onSettings)
    }
}

/* ───────────────────────── shared building blocks ───────────────────────── */

/** One tab destination (icon + label + optional badge). */
private data class NavDest(
    val tab: MirrorTab?,
    val phosphor: String,
    val label: String,
)

private val NAV_DESTS = listOf(
    NavDest(MirrorTab.Chats, "PChats", "Chats"),
    NavDest(null, "PPhone", "Calls"),
    NavDest(MirrorTab.Hub, "PHub", "Updates"),
    NavDest(MirrorTab.Profile, "PProfile", "Profile"),
)

/** Web CapsuleTab amber pill (nav-router.tsx:165-172): the layoutId pill
 *  shared by floating-top + contextual-dock. */
@Composable
private fun BoxScopeAmberPill(visible: Boolean, modifier: Modifier = Modifier) {
    if (visible) {
        Box(
            modifier
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(22.dp), spotColor = Color(0x8CF59E0B))
                .clip(RoundedCornerShape(22.dp))
                .background(NavGlass.PillGradient)
                .border(1.dp, Color(0x4DF59E0B), RoundedCornerShape(22.dp)),
        )
    }
}

/** Unread badge (rose-500→red-500, zinc-900 ring) shared by all styles. */
@Composable
private fun NavBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier
            .heightIn(min = 16.dp)
            .widthIn(min = 16.dp)
            .clip(CircleShape)
            .background(MirrorArt.BadgeGradient)
            .border(2.dp, MirrorArt.PresenceRing, CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp,
        )
    }
}

/** A glass container using the web GLASS_PANEL dark recipe. R79: REAL
 *  frosted backdrop (blur-2xl + zinc-900/65 tint) - the content scrolling
 *  behind the nav frosts through it exactly like the web backdrop-filter;
 *  below API 31 (and in tests) it degrades to the flat tint. */
@Composable
private fun Modifier.navGlassPanel(shape: RoundedCornerShape): Modifier {
    val haze = LocalHazeState.current
    return this
        .shadow(elevation = 24.dp, shape = shape, spotColor = NavGlass.Shadow)
        .clip(shape)
        .mirrorGlassPanel(haze, NavGlass.Panel, 24.dp)
        .border(1.dp, NavGlass.Border, shape)
}

/* ────────────────────────── the 12 renderers ────────────────────────── */

/** floating-top (nav-router.tsx:534): glass bar under the top edge; amber
 *  CapsuleTab pills; trailing "More" overflow opens the kebab. */
@Composable
private fun BoxScope.MirrorFloatingTopNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
    onKebab: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 8.dp, start = 12.dp, end = 12.dp)
            .navGlassPanel(RoundedCornerShape(26.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (dest in NAV_DESTS) {
            val active = dest.tab != null && dest.tab == activeTab
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .mirrorPressClick(onClick = { if (dest.tab != null) onTab(dest.tab!!) else onCalls() }),
                contentAlignment = Alignment.Center,
            ) {
                BoxScopeAmberPill(visible = active, modifier = Modifier.matchParentSize().padding(2.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        MirrorPhosphorIcon(
                            dest.phosphor,
                            tint = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                            modifier = Modifier.size(20.dp),
                        )
                        if (dest.tab == MirrorTab.Chats) {
                            NavBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        dest.label,
                        color = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        // trailing overflow "More" (web NavOverflowButton)
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .mirrorPressClick(onClick = onKebab),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon("LKebab", tint = MirrorArt.Dim, modifier = Modifier.size(20.dp))
        }
    }
}

/** floating-dock (nav-router.tsx:568): desktop dock, active tile magnifies
 *  1.15 with the amber rounded-2xl pill (from-amber-500/25). */
@Composable
private fun BoxScope.MirrorFloatingDockNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .navGlassPanel(RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (dest in NAV_DESTS) {
            val active = dest.tab != null && dest.tab == activeTab
            val scale by animateFloatAsState(
                targetValue = if (active) 1.15f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "dockMagnify",
            )
            Box(
                Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .mirrorPressClick(onClick = { if (dest.tab != null) onTab(dest.tab!!) else onCalls() }),
                contentAlignment = Alignment.Center,
            ) {
                if (active) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .padding(3.dp)
                            .shadow(elevation = 12.dp, shape = RoundedCornerShape(16.dp), spotColor = Color(0x99F59E0B))
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.verticalGradient(listOf(Color(0x40F59E0B), Color(0x14F59E0B))))
                            .border(1.dp, Color(0x66F59E0B), RoundedCornerShape(16.dp)),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                ) {
                    Box {
                        MirrorPhosphorIcon(
                            dest.phosphor,
                            tint = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                            modifier = Modifier.size(22.dp),
                        )
                        if (dest.tab == MirrorTab.Chats) {
                            NavBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        dest.label,
                        color = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** pill (nav-router.tsx:626): one segmented pill; the active segment fills
 *  with the amber→orange gradient and turns the label white. */
@Composable
private fun BoxScope.MirrorPillNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
            .shadow(elevation = 20.dp, shape = CircleShape, spotColor = NavGlass.Shadow)
            .clip(CircleShape)
            .background(NavGlass.Panel)
            .border(1.dp, NavGlass.Border, CircleShape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (dest in NAV_DESTS) {
            val active = dest.tab != null && dest.tab == activeTab
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .weight(1f)
                    .clip(CircleShape)
                    .then(
                        if (active) {
                            Modifier
                                .shadow(elevation = 14.dp, shape = CircleShape, spotColor = Color(0xB3F59E0B))
                                .background(NavGlass.FillGradient)
                        } else Modifier
                    )
                    .mirrorPressClick(onClick = { if (dest.tab != null) onTab(dest.tab!!) else onCalls() }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MirrorPhosphorIcon(
                    dest.phosphor,
                    tint = if (active) Color.White else MirrorArt.Dim,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    dest.label,
                    color = if (active) Color.White else MirrorArt.Dim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (dest.tab == MirrorTab.Chats) {
                    Spacer(Modifier.width(4.dp))
                    NavBadge(unread)
                }
            }
        }
    }
}

/** bottom-bar (nav-router.tsx:674): edge-to-edge zinc-950/85 bar + the 3dp
 *  amber top accent on the active slot. */
@Composable
private fun BoxScope.MirrorBottomBarNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(NavGlass.Bar85)
            .border(1.dp, NavGlass.Border),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (dest in NAV_DESTS) {
                val active = dest.tab != null && dest.tab == activeTab
                Box(Modifier.weight(1f)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .mirrorPressClick(onClick = { if (dest.tab != null) onTab(dest.tab!!) else onCalls() }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // 3dp amber accent bar (w-8 rounded-b-full)
                        Box(
                            Modifier
                                .width(32.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                                .background(if (active) NavGlass.Amber500 else Color.Transparent),
                        )
                        Spacer(Modifier.height(6.dp))
                        Box {
                            MirrorPhosphorIcon(
                                dest.phosphor,
                                tint = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                                modifier = Modifier.size(22.dp),
                            )
                            if (dest.tab == MirrorTab.Chats) {
                                NavBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp))
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            dest.label,
                            color = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/** tab-bar (nav-router.tsx:721): iOS-style edge bar; the active slot sits in
 *  a amber/10 tinted squircle with an amber/25 ring. */
@Composable
private fun BoxScope.MirrorTabBarNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(NavGlass.Bar80)
            .border(1.dp, NavGlass.Border),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (dest in NAV_DESTS) {
                val active = dest.tab != null && dest.tab == activeTab
                Box(
                    Modifier
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) Color(0x1AFBBF24) else Color.Transparent)
                        .border(1.dp, if (active) Color(0x40F59E0B) else Color.Transparent, RoundedCornerShape(16.dp))
                        .mirrorPressClick(onClick = { if (dest.tab != null) onTab(dest.tab!!) else onCalls() }),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    ) {
                        Box {
                            MirrorPhosphorIcon(
                                dest.phosphor,
                                tint = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                                modifier = Modifier.size(23.dp),
                            )
                            if (dest.tab == MirrorTab.Chats) {
                                NavBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp))
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            dest.label,
                            color = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/** floating-tab-bar (nav-router.tsx:782): detached card; the active tile is
 *  an elevated zinc-800→900 tile with the amber ring shadow. */
@Composable
private fun BoxScope.MirrorFloatingTabBarNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
            .navGlassPanel(RoundedCornerShape(24.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (dest in NAV_DESTS) {
            val active = dest.tab != null && dest.tab == activeTab
            val lift by animateDpAsState(
                targetValue = if (active) (-3).dp else 0.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "tabBarLift",
            )
            Box(
                Modifier
                    .offset(y = lift)
                    .size(58.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .then(
                        if (active) {
                            Modifier
                                .shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp), spotColor = Color(0x73F59E0B))
                                .background(Brush.verticalGradient(listOf(Color(0xFF27272A), Color(0xFF18181B))))
                                .border(1.dp, Color(0x4DF59E0B), RoundedCornerShape(20.dp))
                        } else Modifier
                    )
                    .mirrorPressClick(onClick = { if (dest.tab != null) onTab(dest.tab!!) else onCalls() }),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        MirrorPhosphorIcon(
                            dest.phosphor,
                            tint = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                            modifier = Modifier.size(21.dp),
                        )
                        if (dest.tab == MirrorTab.Chats) {
                            NavBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp))
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        dest.label,
                        color = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** command-bar (nav-router.tsx:856): compact top strip; text tabs with the
 *  amber underline + a leading search pill + trailing settings. */
@Composable
private fun BoxScope.MirrorCommandBarNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
    onSearch: () -> Unit,
    onKebab: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp)
            .navGlassPanel(RoundedCornerShape(18.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // leading search pill
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .mirrorPressClick(onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon("LSearch", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
        }
        for (dest in NAV_DESTS) {
            val active = dest.tab != null && dest.tab == activeTab
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .mirrorPressClick(onClick = { if (dest.tab != null) onTab(dest.tab!!) else onCalls() })
                    .padding(horizontal = 2.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Text(
                        dest.label,
                        color = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (dest.tab == MirrorTab.Chats && unread > 0) {
                        NavBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp))
                    }
                }
                Spacer(Modifier.height(3.dp))
                // 2.5dp amber underline (web h-[2.5px])
                Box(
                    Modifier
                        .width(26.dp)
                        .height(2.5.dp)
                        .clip(CircleShape)
                        .background(if (active) NavGlass.Amber500 else Color.Transparent),
                )
            }
        }
        // trailing settings dot
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .mirrorPressClick(onClick = onKebab),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon("LKebab", tint = MirrorArt.Dim, modifier = Modifier.size(16.dp))
        }
    }
}

/** rail (nav-router.tsx:930): persistent 68dp side rail; the active slot
 *  carries the 4dp amber left bar (rounded-r-full). Rendered as a
 *  full-height left column; the shell pads its content 68dp when active. */
@Composable
private fun BoxScope.MirrorRailNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
) {
    Column(
        Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .width(68.dp)
            .background(NavGlass.Rail60)
            .border(1.dp, NavGlass.Border),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        // the four destinations stacked vertically
        for (dest in NAV_DESTS) {
            val active = dest.tab != null && dest.tab == activeTab
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .mirrorPressClick(onClick = { if (dest.tab != null) onTab(dest.tab!!) else onCalls() }),
            ) {
                // 4dp amber left bar (h-7 w-1 rounded-r-full), absolutely at x=0
                if (active) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .width(4.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                            .background(NavGlass.Amber500),
                    )
                }
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        MirrorPhosphorIcon(
                            dest.phosphor,
                            tint = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                            modifier = Modifier.size(21.dp),
                        )
                        if (dest.tab == MirrorTab.Chats && unread > 0) {
                            NavBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp))
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        dest.label,
                        color = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** island (nav-router.tsx:1009): dynamic-island pill; collapsed shows the
 *  active destination, tap expands to the full row; amber/18 tint active. */
@Composable
private fun BoxScope.MirrorIslandNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 14.dp)
            .shadow(elevation = 24.dp, shape = RoundedCornerShape(28.dp), spotColor = NavGlass.Shadow)
            .clip(RoundedCornerShape(28.dp))
            .background(NavGlass.Panel)
            .border(1.dp, NavGlass.Border, RoundedCornerShape(28.dp))
            .mirrorPressClick(onClick = { expanded = !expanded }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (expanded) {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (dest in NAV_DESTS) {
                    val active = dest.tab != null && dest.tab == activeTab
                    Box(
                        Modifier
                            .size(56.dp)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (active) Color(0x2EF59E0B) else Color.Transparent)
                            .border(1.dp, if (active) Color(0x4DF59E0B) else Color.Transparent, RoundedCornerShape(24.dp))
                            .mirrorPressClick(onClick = {
                                expanded = false
                                if (dest.tab != null) onTab(dest.tab!!) else onCalls()
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                MirrorPhosphorIcon(
                                    dest.phosphor,
                                    tint = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                                    modifier = Modifier.size(20.dp),
                                )
                                if (dest.tab == MirrorTab.Chats) {
                                    NavBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
                                }
                            }
                            Text(
                                dest.label,
                                color = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        } else {
            // collapsed: the active destination only
            val dest = NAV_DESTS.firstOrNull { it.tab == activeTab } ?: NAV_DESTS[0]
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MirrorPhosphorIcon(dest.phosphor, tint = NavGlass.Amber400, modifier = Modifier.size(18.dp))
                Text(
                    dest.label,
                    color = NavGlass.Amber400,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (dest.tab == MirrorTab.Chats) NavBadge(unread)
                MirrorLucideIcon(
                    "LChevronDown",
                    tint = MirrorArt.Dim,
                    modifier = Modifier.size(12.dp).graphicsLayer { rotationZ = 180f },
                )
            }
        }
    }
}

/** radial (nav-router.tsx:1097-1164): a CENTERED amber FAB that fans the
 *  four destinations as 68dp glass circles across the upper hemisphere over
 *  a zinc-950/35 veil. R79 parity fix: the FAB is the web's amber-400→
 *  orange-600 gradient with a WHITE plus anchored bottom-CENTER (was a dark
 *  chip in the corner), it TOGGLES the fan (the web FAB is the nav trigger,
 *  not new-chat), the fan arcs from the screen bottom-center at radius 96
 *  step 34°, and the active fan icon is amber-600. */
@Composable
private fun BoxScope.MirrorRadialNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val haze = LocalHazeState.current
    val rotation by animateFloatAsState(
        targetValue = if (open) 45f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "radialFabRotate",
    )
    // web radial-veil (nav-router.tsx:1112): bg-zinc-950/35 full-screen
    // catcher - tap anywhere dismisses the fan.
    AnimatedVisibility(visible = open, enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x5909090B))
                .clickable { open = false },
        )
    }
    // the fan: web anchors each 68dp circle at bottom-6 left-1/2, radius 96,
    // step 34° fanning the upper hemisphere (nav-router.tsx:1115-1142).
    AnimatedVisibility(
        visible = open,
        enter = scaleIn(initialScale = 0.6f, animationSpec = MirrorMotion.snappy()) + fadeIn(tween(120)),
        exit = scaleOut(targetScale = 0.6f) + fadeOut(tween(120)),
    ) {
        Box(Modifier.fillMaxSize()) {
            NAV_DESTS.forEachIndexed { index, dest ->
                val active = dest.tab != null && dest.tab == activeTab
                // math in raw floats (Dp/Float have no Double times overload)
                val deg = -90f + (index - (NAV_DESTS.size - 1) / 2f) * 34f
                val rad = Math.toRadians(deg.toDouble())
                val x = (96f * Math.cos(rad)).toFloat()
                val y = (96f * Math.sin(rad)).toFloat()
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                        .offset(x = x.dp, y = y.dp)
                        .size(68.dp)
                        .shadow(elevation = 16.dp, shape = CircleShape, spotColor = NavGlass.Shadow)
                        .clip(CircleShape)
                        // web dark: bg-zinc-900/90 + border-white/10 + backdrop-blur-xl
                        .mirrorGlassPanel(haze, Color(0xE618181B), 24.dp)
                        .border(1.dp, NavGlass.Border, CircleShape)
                        .clickable {
                            open = false
                            if (dest.tab != null) onTab(dest.tab!!) else onCalls()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            MirrorPhosphorIcon(
                                dest.phosphor,
                                tint = if (active) NavGlass.Amber600 else MirrorArt.Text,
                                modifier = Modifier.size(20.dp),
                            )
                            if (dest.tab == MirrorTab.Chats) {
                                NavBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-6).dp))
                            }
                        }
                        Text(
                            dest.label,
                            color = MirrorArt.Text,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
    // the FAB itself (web nav-router.tsx:1146-1161): size-14 (56dp) centered,
    // bg-gradient-to-br from-amber-400 to-orange-600, WHITE Plus 24dp rotating
    // 45° while open, shadow 0_10px_36px_-6px rgba(245,158,11,0.65).
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 14.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .shadow(elevation = 18.dp, shape = CircleShape, spotColor = Color(0xA6F59E0B))
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(NavGlass.Amber400, NavGlass.Orange600)))
                .mirrorPressClick(onClick = { open = !open }),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon(
                "LPlus",
                tint = Color.White,
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

/** gesture (nav-router.tsx:1169-1241): a BARE drag handle at the bottom -
 *  h-9 w-40 with the 5px inner bar (closed: w-24 zinc-600, open: w-16
 *  amber-500) - that expands the GLASS quick-switcher above it (56dp
 *  rounded-2xl tiles, amber-500/18 active fill + amber-500/30 ring, labels
 *  9px semibold). R79 parity fix: the R78 glass dots-pill container was a
 *  native invention - the web collapsed state is JUST the handle, no panel. */
@Composable
private fun BoxScope.MirrorGestureNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val haze = LocalHazeState.current
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // the quick-switcher (web 1183-1219): GLASS_PANEL rounded-[24px] p-1.5,
        // 56dp rounded-2xl tiles with 9px semibold labels
        AnimatedVisibility(
            visible = open,
            enter = scaleIn(initialScale = 0.92f) + fadeIn(),
            exit = scaleOut(targetScale = 0.92f) + fadeOut(),
        ) {
            Row(
                Modifier
                    .padding(bottom = 10.dp)
                    .shadow(elevation = 24.dp, shape = RoundedCornerShape(24.dp), spotColor = NavGlass.Shadow)
                    .clip(RoundedCornerShape(24.dp))
                    .mirrorGlassPanel(haze, NavGlass.Panel, 24.dp)
                    .border(1.dp, NavGlass.Border, RoundedCornerShape(24.dp))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (dest in NAV_DESTS) {
                    val active = dest.tab != null && dest.tab == activeTab
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            // web active tile: bg-amber-500/18 + ring-amber-500/30
                            .background(if (active) Color(0x2EF59E0B) else Color.Transparent)
                            .border(1.dp, if (active) Color(0x4DF59E0B) else Color.Transparent, RoundedCornerShape(16.dp))
                            .mirrorPressClick(onClick = {
                                open = false
                                if (dest.tab != null) onTab(dest.tab!!) else onCalls()
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                MirrorPhosphorIcon(
                                    dest.phosphor,
                                    // web dark: inactive zinc-400, active amber-400
                                    tint = if (active) NavGlass.Amber400 else Color(0xFFA1A1AA),
                                    modifier = Modifier.size(20.dp),
                                )
                                if (dest.tab == MirrorTab.Chats) {
                                    NavBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-5).dp))
                                }
                            }
                            Text(
                                dest.label,
                                // web dark: zinc-300, active amber-400
                                color = if (active) NavGlass.Amber400 else Color(0xFFD4D4D8),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        // the collapsed state: a BARE drag handle (web 1230-1238) - h-9 w-40
        // touch target, inner 5px bar, animated width, NO container panel.
        val handleWidth by animateDpAsState(
            targetValue = if (open) 64.dp else 96.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label = "gestureHandleWidth",
        )
        Box(
            Modifier
                .size(width = 160.dp, height = 36.dp)
                .mirrorPressClick(onClick = { open = !open }),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .padding(bottom = 6.dp)
                    .size(width = handleWidth, height = 5.dp)
                    .clip(CircleShape)
                    // web dark: closed zinc-600, open amber-500
                    .background(if (open) NavGlass.Amber500 else Color(0xFF52525B)),
            )
        }
    }
}

/** contextual-dock (nav-router.tsx:1252-1306): the dock whose trailing chip
 *  morphs per active tab with the tab's OWN action - chats → New chat,
 *  hub → Search, contacts → New group, profile → Settings (web
 *  CONTEXT_ACTION map) - as a SOLID amber-500→orange-600 gradient chip
 *  with WHITE bold text (the R78 10% tint was a parity miss). */
@Composable
private fun BoxScope.MirrorContextualDockNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
    onFab: () -> Unit,
    onSearch: () -> Unit,
    onNewGroup: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .navGlassPanel(RoundedCornerShape(26.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (dest in NAV_DESTS) {
            val active = dest.tab != null && dest.tab == activeTab
            Box(
                Modifier
                    .heightIn(min = 50.dp)
                    .widthIn(min = 58.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .mirrorPressClick(onClick = { if (dest.tab != null) onTab(dest.tab!!) else onCalls() }),
                contentAlignment = Alignment.Center,
            ) {
                BoxScopeAmberPill(visible = active, modifier = Modifier.matchParentSize().padding(2.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        MirrorPhosphorIcon(
                            dest.phosphor,
                            tint = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                            modifier = Modifier.size(19.dp),
                        )
                        if (dest.tab == MirrorTab.Chats) {
                            NavBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp))
                        }
                    }
                    Text(
                        dest.label,
                        color = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        // the morphing contextual chip (web nav-router.tsx:1282-1301):
        // h-[52px] rounded-[20px] bg-gradient-to-br from-amber-500 to-orange-600
        // + WHITE 11px bold text + the amber glow shadow, running the ACTIVE
        // TAB's action (CONTEXT_ACTION, nav-router.tsx:1245-1250).
        val chipLabel: String
        val chipIcon: String
        val chipAction: () -> Unit
        when (activeTab) {
            MirrorTab.Chats -> { chipLabel = "New chat"; chipIcon = "LPlus"; chipAction = onFab }
            MirrorTab.Hub -> { chipLabel = "Search"; chipIcon = "LSearch"; chipAction = onSearch }
            MirrorTab.Contacts -> { chipLabel = "New group"; chipIcon = "PContacts"; chipAction = onNewGroup }
            MirrorTab.Profile -> { chipLabel = "Settings"; chipIcon = "LSettings"; chipAction = onSettings }
        }
        Box(
            Modifier
                .padding(start = 2.dp)
                .height(52.dp)
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp), spotColor = Color(0xB3F59E0B))
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(NavGlass.Amber500, NavGlass.Orange600)))
                .mirrorPressClick(onClick = chipAction)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (chipIcon.startsWith("P")) {
                    MirrorPhosphorIcon(chipIcon, tint = Color.White, modifier = Modifier.size(16.dp))
                } else {
                    MirrorLucideIcon(chipIcon, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Text(chipLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
