package app.pulse.feature.hub

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette

private data class HubTile(val label: String, val icon: ImageVector, val from: androidx.compose.ui.graphics.Color, val to: androidx.compose.ui.graphics.Color)

private val TILES = listOf(
    HubTile("Stories", Icons.Filled.AutoStories, PulsePalette.Emerald, PulsePalette.Teal),
    HubTile("Calls", Icons.Filled.PhoneInTalk, PulsePalette.Teal, PulsePalette.TealLight),
    HubTile("Games", Icons.Filled.Gamepad, PulsePalette.Violet, PulsePalette.Rose),
    HubTile("Leaderboard", Icons.Filled.Leaderboard, PulsePalette.Amber, PulsePalette.Rose),
    HubTile("Wallet", Icons.Filled.AccountBalanceWallet, PulsePalette.EmeraldDeep, PulsePalette.Emerald),
    HubTile("Mini apps", Icons.Filled.Extension, PulsePalette.Teal, PulsePalette.Emerald),
)

/**
 * Hub tab — native recreation of the web hub IA at wave scope: a glass
 * wallet/streak hero and staggered native tiles for the hub surfaces that
 * graduate to full native screens in later waves (honest labels).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(viewerName: String?) {
    var sheetTile by remember { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) { entered = true }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Hub", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                if (viewerName.isNullOrBlank()) "Everything around your chats" else "Welcome back, $viewerName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Hero card — streak/wallet glass, live colors, bouncy entrance
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(PulsePalette.EmeraldDeep, PulsePalette.Emerald)))
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text("Pulse Wallet", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(
                            "XP · streaks · mini apps — going native next wave",
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(TILES, key = { _, t -> t.label }) { index, tile ->
                val alpha by animateFloatAsState(
                    targetValue = if (entered) 1f else 0f,
                    animationSpec = tween(240, delayMillis = index * 45),
                    label = "tileAlpha",
                )
                val scale by animateFloatAsState(
                    targetValue = if (entered) 1f else 0.92f,
                    animationSpec = PulseMotion.soft(),
                    label = "tileScale",
                )
                androidx.compose.ui.Modifier.let { }
                Surface(                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f * alpha),
                    tonalElevation = 1.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.25f)
                        .alpha(alpha),
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                sheetTile = tile.label
                            }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.linearGradient(listOf(tile.from, tile.to))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(tile.icon, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(tile.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Opening next wave",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    sheetTile?.let { label ->
        ModalBottomSheet(onDismissRequest = { sheetTile = null }, sheetState = rememberModalBottomSheetState()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "This hub surface ships in the next native wave. The chat core — inbox, rooms, live relay, reactions — is already native.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}
