package app.pulse.android.mirror

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Artboard dock: glass capsule (Chats / Calls / Updates / Profile, 10sp labels)
 * + the SEPARATE 52dp ember FAB. Geometry copied from nav-router.tsx.
 */
@Composable
internal fun MirrorDock(
    activeTab: MirrorTab,
    unread: Int,
    onTab: (MirrorTab) -> Unit,
    onFab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(64.dp)
                .clip(CircleShape)
                .background(MirrorArt.Panel),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { MirrorDockItem(MirrorTab.Chats, "PChats", "Chats", activeTab == MirrorTab.Chats, badge = unread, onTab = onTab) }
            Box(Modifier.weight(1f)) { MirrorDockItem(MirrorTab.Calls, "PPhone", "Calls", activeTab == MirrorTab.Calls, badge = 0, onTab = onTab) }
            Box(Modifier.weight(1f)) { MirrorDockItem(MirrorTab.Updates, "PHub", "Updates", activeTab == MirrorTab.Updates, badge = 0, onTab = onTab) }
            Box(Modifier.weight(1f)) { MirrorDockItem(MirrorTab.Profile, "PProfile", "Profile", activeTab == MirrorTab.Profile, badge = 0, onTab = onTab) }
        }
        // Separate 52dp ember FAB (new chat)
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MirrorArt.FabGradient)
                .clickable(onClick = onFab),
            contentAlignment = Alignment.Center,
        ) {
            MirrorLucideIcon("LPlus", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

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
            .height(64.dp)
            .clickable { onTab(tab) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                MirrorPhosphorIcon(phosphor, tint = tint, modifier = Modifier.size(22.dp))
                if (badge > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-3).dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MirrorArt.BadgeGradient),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            badge.coerceAtMost(9).toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}
