package app.pulse.android.mirror

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
        PulseNavStyle.RADIAL -> MirrorRadialNav(activeTab, unread, onTab, onCalls, onFab)
        PulseNavStyle.GESTURE -> MirrorGestureNav(activeTab, unread, onTab, onCalls)
        PulseNavStyle.CONTEXTUAL_DOCK -> MirrorContextualDockNav(activeTab, unread, onTab, onCalls, onFab)
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

/** A glass container using the web GLASS_PANEL dark recipe. */
private fun Modifier.navGlassPanel(shape: RoundedCornerShape): Modifier = this
    .shadow(elevation = 24.dp, shape = shape, spotColor = NavGlass.Shadow)
    .clip(shape)
    .background(NavGlass.Panel)
    .border(1.dp, NavGlass.Border, shape)

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
                        .border(1.dp, if (active) Color(0x40FBBF24) else Color.Transparent, RoundedCornerShape(16.dp))
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
                // 2.5dp amber underline
                Box(
                    Modifier
                        .width(26.dp)
                        .height(2.dp)
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

/** radial (nav-router.tsx:1136): the new-chat FAB; tap fans the four
 *  destinations in an arc of glass circles; active icon is amber. */
@Composable
private fun BoxScope.MirrorRadialNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
    onFab: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp)) {
        // the fan: four circles arcing up-left
        val fanAngles = listOf(-90f, -60f, -30f, 0f) // deg from the FAB
        if (open) {
            fanAngles.forEachIndexed { index, angle ->
                val dest = NAV_DESTS.getOrNull(index) ?: return@forEachIndexed
                val active = dest.tab != null && dest.tab == activeTab
                val radius = 92.dp
                val rad = Math.toRadians(angle.toDouble() + 180) // fan to the left-up
                val x = (radius * Math.cos(rad)).toFloat()
                val y = (radius * Math.sin(rad)).toFloat()
                Box(
                    Modifier
                        .offset(x = x.dp, y = y.dp)
                        .size(52.dp)
                        .shadow(elevation = 16.dp, shape = CircleShape, spotColor = NavGlass.Shadow)
                        .clip(CircleShape)
                        .background(NavGlass.Panel)
                        .border(1.dp, NavGlass.Border, CircleShape)
                        .clickable {
                            open = false
                            if (dest.tab != null) onTab(dest.tab!!) else onCalls()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MirrorPhosphorIcon(
                            dest.phosphor,
                            tint = if (active) NavGlass.Amber400 else MirrorArt.Text,
                            modifier = Modifier.size(19.dp),
                        )
                        Text(dest.label, color = if (active) NavGlass.Amber400 else MirrorArt.Dim, fontSize = 8.sp)
                    }
                }
            }
        }
        // the FAB itself
        Box(
            Modifier
                .size(52.dp)
                .shadow(elevation = 18.dp, shape = CircleShape, spotColor = Color(0x8C000000))
                .clip(CircleShape)
                .background(MirrorArt.Chip)
                .border(1.dp, MirrorArt.Hairline, CircleShape)
                .clickable { if (open) onFab() else open = true },
            contentAlignment = Alignment.Center,
        ) {
            val rotation by animateFloatAsState(
                targetValue = if (open) 45f else 0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "radialFabRotate",
            )
            MirrorLucideIcon(
                "LPlus",
                tint = MirrorArt.Text,
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation },
            )
        }
    }
}

/** gesture (nav-router.tsx:1183): minimal bottom pill showing the active
 *  destination; tap cycles, drag switches (the web edge-swipe idiom,
 *  mobile-honest as a compact switcher pill). */
@Composable
private fun BoxScope.MirrorGestureNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = open,
            enter = scaleIn(initialScale = 0.9f) + fadeIn(),
            exit = scaleOut(targetScale = 0.9f) + fadeOut(),
        ) {
            Row(
                Modifier
                    .padding(bottom = 8.dp)
                    .navGlassPanel(RoundedCornerShape(22.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (dest in NAV_DESTS) {
                    val active = dest.tab != null && dest.tab == activeTab
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (active) Color(0x2EF59E0B) else Color.Transparent)
                            .border(1.dp, if (active) Color(0x4DF59E0B) else Color.Transparent, RoundedCornerShape(18.dp))
                            .mirrorPressClick(onClick = {
                                open = false
                                if (dest.tab != null) onTab(dest.tab!!) else onCalls()
                            }),
                        contentAlignment = Alignment.Center,
                    ) {
                        MirrorPhosphorIcon(
                            dest.phosphor,
                            tint = if (active) NavGlass.Amber400 else MirrorArt.Dim,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
        // the pill
        Row(
            Modifier
                .navGlassPanel(RoundedCornerShape(50))
                .mirrorPressClick(onClick = { open = !open })
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // four dots: the active one is amber
            for (dest in NAV_DESTS) {
                val active = dest.tab != null && dest.tab == activeTab
                Box(
                    Modifier
                        .size(if (active) 8.dp else 5.dp)
                        .clip(CircleShape)
                        .background(if (active) NavGlass.Amber500 else MirrorArt.Dim),
                )
            }
            if (unread > 0) {
                Spacer(Modifier.width(2.dp))
                NavBadge(unread)
            }
        }
    }
}

/** contextual-dock (nav-router.tsx:1267): the dock whose trailing chip morphs
 *  per active tab (chats → New chat, hub → Explore, profile → Settings). */
@Composable
private fun BoxScope.MirrorContextualDockNav(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onCalls: () -> Unit,
    onFab: () -> Unit,
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
        // the morphing contextual chip
        val chip = when (activeTab) {
            MirrorTab.Chats -> "New chat" to "LPlus"
            MirrorTab.Hub -> "Explore" to "LCompass"
            MirrorTab.Profile -> "Settings" to "LSettings"
            else -> "New chat" to "LPlus"
        }
        Box(
            Modifier
                .heightIn(min = 50.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0x1AF59E0B))
                .border(1.dp, Color(0x4DF59E0B), RoundedCornerShape(22.dp))
                .mirrorPressClick(onClick = onFab)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MirrorLucideIcon(chip.second, tint = NavGlass.Amber400, modifier = Modifier.size(16.dp))
                Text(chip.first, color = NavGlass.Amber400, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
