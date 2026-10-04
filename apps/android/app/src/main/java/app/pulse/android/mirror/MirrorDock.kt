package app.pulse.android.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R66 - the artboard dock EXACTLY as nav-router.tsx CapsuleNav renders it:
 * a HUGGING centered glass pill (each tab 64dp wide, 54dp tall) plus the
 * SEPARATE 52dp dark glass art-fab on the right, gap 10dp, inset 12dp.
 * The web CapsuleNav HARD-CODES four slots: Chats (badge) / Calls (ALWAYS
 * inactive - tapping it opens the zinc-900 calls SUB-PAGE and stays on
 * chats) / Updates (which IS the hub tab) / Profile. The mirror follows
 * that wiring 1:1 - Calls is an action, Updates lights up on the hub tab.
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
        // art-panel pill: px-1.5 py-1, gap-0.5, rounded-full, hairline ring
        Row(
            Modifier
                .clip(CircleShape)
                .background(MirrorArt.Panel)
                .border(1.dp, MirrorArt.Hairline, CircleShape)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MirrorDockItem(MirrorTab.Chats, "PChats", "Chats", activeTab == MirrorTab.Chats, badge = unread, onTab = onTab)
            // web: the Calls slot NEVER activates - it opens the calls sub-page
            Box(
                Modifier
                    .width(64.dp)
                    .heightIn(min = 54.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCalls),
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
        // art-fab: 52dp dark glass circle, white Plus 24dp - NOT an ember bomb
        Box(
            Modifier
                .size(52.dp)
                .shadow(elevation = 18.dp, shape = CircleShape, spotColor = Color(0x8C000000))
                .clip(CircleShape)
                .background(MirrorArt.Chip)
                .border(1.dp, MirrorArt.Hairline, CircleShape)
                .clickable(onClick = onFab),
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
    val tint = if (active) MirrorArt.Text else MirrorArt.Dim
    Box(
        Modifier
            .width(64.dp)
            .heightIn(min = 54.dp)
            .clip(CircleShape)
            .clickable { onTab(tab) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                MirrorPhosphorIcon(phosphor, tint = tint, modifier = Modifier.size(22.dp))
                if (badge > 0) {
                    // web DockTab badge override: -right-2 -top-1 h-4 min-w-4 text-[9px]
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
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
