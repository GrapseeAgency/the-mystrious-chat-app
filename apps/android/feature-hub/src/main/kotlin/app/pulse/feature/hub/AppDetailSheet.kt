package app.pulse.feature.hub

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.protocol.HubLogUserDto
import app.pulse.protocol.PulseWave7Logic
import app.pulse.ui.PulseAvatar
import app.pulse.ui.PulsePalette

/**
 * R5-B ITEM 3 — the Hub app-detail sheet at web depth
 * (src/components/hub/app-detail-sheet.tsx):
 *  • Overview — hero + live install stats + InstallerStack (≤6 avatars + "+N"
 *    overflow, hidden when empty :110-133) + "Connected on <day>" + connect
 *    actions + the related-apps rail (same catalog category :924-968);
 *  • Community — the existing community join/open block (web :537-757, the
 *    native block rides the SAME joinCommunity mutation);
 *  • Connectors — the viewer's own connect card + the live connected-members
 *    roster (≤6 rows, "@username"/"Pulse member" line, relative "3h ago"
 *    stamp for the VIEWER'S OWN row :880-909) + "+N more connected".
 * Tabs mirror the web DetailTabBar :137-213 (Overview | Community | Connectors
 * with live count badges). All mutations are the EXISTING install/community
 * engine — no new endpoints, zero mocks.
 */

private enum class DetailTab(val label: String) { OVERVIEW("Overview"), COMMUNITY("Community"), CONNECTORS("Connectors") }

/** Same-category related apps, ≤10, excluding self (web RelatedRail :926). */
internal fun relatedAppsOf(catalog: HubCatalog, app: HubCatalogApp): List<HubCatalogApp> =
    catalog.apps.filter { it.category == app.category && it.n != app.n }.take(10)

/** Web InstallerStack (:110-133) — ≤6 avatars + "+N" chip, hidden when empty. */
@Composable
private fun InstallerStack(installers: List<HubLogUserDto>, extra: Int) {
    if (installers.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "Recently connected members" },
    ) {
        Box {
            installers.forEachIndexed { index, user ->
                PulseAvatar(
                    name = user.name,
                    colorHex = user.color,
                    size = 26.dp,
                    modifier = Modifier.offset(x = -(index * 6).dp),
                )
            }
        }
        if (extra > 0) {
            Box(
                Modifier
                    .offset(x = -(installers.size * 6).dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .semantics { contentDescription = "$extra more connected members" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$extra",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Web CountUp (hub-data.tsx:275-283) — 300ms ease-out count animation. */
@Composable
private fun CountUpText(value: Int, fontSize: Int = 24) {
    val animated by animateIntAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 300),
        label = "install-count",
    )
    Text(animated.toString(), fontSize = fontSize.sp, fontWeight = FontWeight.Black, color = PulsePalette.Emerald)
}

/** App icon tile — the existing gradient monogram cube. */
@Composable
private fun AppIconTile(app: HubCatalogApp, size: Int) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(PulsePalette.Emerald, Color(0xFF0B3B2C)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(app.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size / 3).sp)
    }
}

/**
 * The upgraded app detail sheet. [onOpenCommunity] keeps the EXISTING
 * join/open mutation; [onOpenRelated] swaps the detail to the tapped app
 * (the caller re-targets `expanded`, the sheet's LaunchedEffect re-fetches).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppDetailSheet(
    app: HubCatalogApp,
    catalog: HubCatalog,
    installs: Map<String, HubViewModel.AppInstallRow>,
    vm: HubViewModel,
    onDismiss: () -> Unit,
    onOpenCommunity: (appId: String, name: String) -> Unit,
    onOpenRelated: (HubCatalogApp) -> Unit,
) {
    val appId = app.n.toString()
    val row = installs[appId]
    val installed = row?.installed == true
    val viewerId = vm.viewerId

    LaunchedEffect(appId) { vm.loadInstallState(appId) }

    var tab by remember(appId) { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
            // ── hero (identity + live install card + installer stack) ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconTile(app, 44)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        app.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${catalog.taglines[appId] ?: app.category} · #${app.n.toString().padStart(3, '0')}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (installed) {
                    InstallerStack(
                        installers = row?.installers.orEmpty(),
                        extra = ((row?.installs ?: 0) - (row?.installers?.size ?: 0)).coerceAtLeast(0),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // live install stats (web AppHero :299-328)
            Row(verticalAlignment = Alignment.Bottom) {
                CountUpText(value = row?.installs ?: 0)
                Spacer(Modifier.width(6.dp))
                Text(
                    "member${if ((row?.installs ?: 0) == 1) "" else "s"} connected",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            if (installed && !row?.installedAt.isNullOrBlank()) {
                Text(
                    "Connected on ${PulseWave7Logic.formatDay(row?.installedAt ?: "")}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulsePalette.Emerald,
                )
            }
            Spacer(Modifier.height(10.dp))

            // ── tabs (web DetailTabBar :137-213) ──
            TabRow(selectedTabIndex = tab) {
                DetailTab.entries.forEachIndexed { index, t ->
                    val badge = when (t) {
                        DetailTab.CONNECTORS -> (row?.installs ?: 0).takeIf { it > 0 }
                        else -> null
                    }
                    Tab(selected = tab == index, onClick = { tab = index }, text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.label, fontSize = 13.sp)
                            badge?.let {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    it.toString(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PulsePalette.Emerald,
                                )
                            }
                        }
                    })
                }
            }
            Spacer(Modifier.height(12.dp))

            when (tab) {
                0 -> OverviewPanel(app, catalog, installed, vm, onOpenCommunity, onOpenRelated)
                1 -> CommunityPanel(app, vm, onOpenCommunity)
                else -> ConnectorsPanel(app, row, installed, viewerId, vm)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Overview — installer-stack stats + blueprint fields + connect actions + related rail. */
@Composable
private fun OverviewPanel(
    app: HubCatalogApp,
    catalog: HubCatalog,
    installed: Boolean,
    vm: HubViewModel,
    onOpenCommunity: (appId: String, name: String) -> Unit,
    onOpenRelated: (HubCatalogApp) -> Unit,
) {
    val appId = app.n.toString()
    Column {
        // blueprint fields (web OverviewPanel :487-513)
        DetailCard {
            Text("Mobile nav style", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(app.nav, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        DetailCard {
            Text("Input toolkit", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(app.input, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        DetailCard {
            Text("Secret UI architecture feature", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = PulsePalette.Emerald)
            Text(app.secret, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(12.dp))

        // connect + community — the EXISTING mutations (web AppHero actions :331-395)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (installed) vm.uninstallApp(appId, app.name) else vm.installApp(appId, app.name) },
                modifier = Modifier.weight(1f),
            ) { Text(if (installed) "Connected — disconnect?" else "Install / Connect") }
            OutlinedButton(
                onClick = { onOpenCommunity(appId, app.name) },
                modifier = Modifier.weight(1f),
            ) { Text("Community") }
        }
        Spacer(Modifier.height(14.dp))

        // related-apps rail (web RelatedRail :924-968) — same category, tap swaps detail
        val related = relatedAppsOf(catalog, app)
        if (related.isNotEmpty()) {
            Text(
                "More in ${app.category.substringBefore(" /")}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(related, key = { it.n }) { relatedApp ->
                    Column(
                        Modifier
                            .width(104.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .clickable { onOpenRelated(relatedApp) }
                            .padding(vertical = 12.dp)
                            .semantics { contentDescription = "Open ${relatedApp.name} page" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIconTile(relatedApp, 44)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            relatedApp.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "#${relatedApp.n.toString().padStart(3, '0')}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/** Community — the existing join/open block (kept verbatim semantics). */
@Composable
private fun CommunityPanel(
    app: HubCatalogApp,
    vm: HubViewModel,
    onOpenCommunity: (appId: String, name: String) -> Unit,
) {
    val appId = app.n.toString()
    Column {
        DetailCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconTile(app, 48)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "#${app.n.toString().padStart(3, '0')} · ${app.name} community",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Auto-provisioned on first join — founders become admins.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onOpenCommunity(appId, app.name) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Join / open the community chat") }
    }
}

/** Connectors — the viewer's connect card + the live roster (web :781-920). */
@Composable
private fun ConnectorsPanel(
    app: HubCatalogApp,
    row: HubViewModel.AppInstallRow?,
    installed: Boolean,
    viewerId: String?,
    vm: HubViewModel,
) {
    val appId = app.n.toString()
    val installers = row?.installers.orEmpty()
    val extra = ((row?.installs ?: 0) - installers.size).coerceAtLeast(0)

    Column {
        // your connection card (web :800-845)
        DetailCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconTile(app, 40)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Your connection", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        when {
                            installed && !row?.installedAt.isNullOrBlank() ->
                                "Connected on ${PulseWave7Logic.formatDay(row?.installedAt ?: "")}"
                            else -> "Not connected yet"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { if (installed) vm.uninstallApp(appId, app.name) else vm.installApp(appId, app.name) },
                ) { Text(if (installed) "Connected" else "Connect") }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (installers.isEmpty()) {
            DetailCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No connectors yet", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "Be the first to connect ${app.name}.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            DetailCard {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        "Connected members",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    CountUpText(value = row?.installs ?: 0, fontSize = 12)
                }
                Spacer(Modifier.height(8.dp))
                installers.forEach { user ->
                    val isViewer = user.id == viewerId
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PulseAvatar(name = user.name, colorHex = user.color, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                user.name + if (isViewer) " (you)" else "",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                user.username?.let { "@$it" } ?: "Pulse member",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("connected", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            // installedAt is per-viewer truth — others get no invented date
                            if (isViewer && installed) {
                                Text(
                                    PulseWave7Logic.relativeStamp(row?.installedAt, System.currentTimeMillis()),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
                if (extra > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "+$extra more connected",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
    ) {
        content()
    }
}
