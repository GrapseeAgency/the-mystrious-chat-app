package app.pulse.android.mirror

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.AppCommunityDto
import app.pulse.protocol.AppInstallStateDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * R76 - the app detail FULL PAGE (web #/hub/app/<n>, app-detail-sheet.tsx
 * AppDetailPage, 997-1141): the hub's third hash surface. Slides over the hub
 * tab exactly like the category page, sub-header title = app name with the
 * category as the subtitle and the ConnectedBadge trailing, then the three
 * detail tabs (Overview / Community / Connectors) over REAL routes:
 *   GET/POST/DELETE /api/hub/apps/<id>/install  - install state + toggle
 *   GET/POST        /api/hub/apps/<id>/community - provisioning + join
 *   GET             /api/hub/wallet              - the hero wallet pill
 * The old R66 install-toggle sheet is retired the same way the web retired
 * app-detail-sheet's sheet variant: every entry point routes here.
 */
@Composable
internal fun MirrorHubAppPage(
    appId: Int,
    repository: PulseRepository,
    viewerId: String,
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenApp: (Int) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val app = HubCatalog.MATRIX.firstOrNull { it.n == appId }
    if (app == null) {
        MirrorHubAppUnknown(appId, onBack)
        return
    }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val appIdStr = app.n.toString()

    // ---- live state (web useAppInstallStatus / useAppCommunity / useWalletMini)
    var status by remember(appId) { mutableStateOf<AppInstallStateDto?>(null) }
    var statusLoading by remember(appId) { mutableStateOf(true) }
    var statusError by remember(appId) { mutableStateOf(false) }
    var community by remember(appId) { mutableStateOf<AppCommunityDto?>(null) }
    var communityLoading by remember(appId) { mutableStateOf(true) }
    var communityError by remember(appId) { mutableStateOf(false) }
    var walletCoins by remember(appId) { mutableStateOf<Long?>(null) }
    var walletGems by remember(appId) { mutableStateOf<Long?>(null) }
    var busy by remember(appId) { mutableStateOf(false) }
    var joining by remember(appId) { mutableStateOf(false) }
    var menuOpen by remember(appId) { mutableStateOf(false) }
    var tab by remember(appId) { mutableStateOf(0) } // 0 overview / 1 community / 2 connectors

    fun loadStatus() {
        statusLoading = status == null
        scope.launch(Dispatchers.IO) {
            val fresh = runCatching { repository.appInstallState(appIdStr).getOrNull() }.getOrNull()
            launch(Dispatchers.Main) {
                status = fresh
                statusError = fresh == null
                statusLoading = false
            }
        }
    }
    fun loadCommunity() {
        scope.launch(Dispatchers.IO) {
            val fresh = runCatching { repository.appCommunity(appIdStr).getOrNull() }.getOrNull()
            launch(Dispatchers.Main) {
                community = fresh
                communityError = fresh == null
                communityLoading = false
            }
        }
    }
    LaunchedEffect(appId) {
        scope.launch(Dispatchers.IO) {
            val fresh = runCatching { repository.appInstallState(appIdStr).getOrNull() }.getOrNull()
            launch(Dispatchers.Main) {
                status = fresh
                statusError = fresh == null
                statusLoading = false
            }
            val comm = runCatching { repository.appCommunity(appIdStr).getOrNull() }.getOrNull()
            launch(Dispatchers.Main) {
                community = comm
                communityError = comm == null
                communityLoading = false
            }
            val wallet = runCatching { repository.wallet().getOrNull() }.getOrNull()
            launch(Dispatchers.Main) {
                walletCoins = wallet?.wallet?.coins
                walletGems = wallet?.wallet?.gems
            }
        }
    }

    val installed = status?.installed == true

    // web useInstallToggle L105-125: POST/DELETE then the fresh state refetch
    fun toggleConnect() {
        if (busy) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        busy = true
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (installed) repository.uninstallApp(appIdStr) else repository.installApp(appIdStr)
            }
            val fresh = runCatching { repository.appInstallState(appIdStr).getOrNull() }.getOrNull()
            launch(Dispatchers.Main) {
                status = fresh
                statusError = fresh == null
                statusLoading = false
                busy = false
            }
        }
    }

    // web join.mutate (L1017-1019): onJoined hands the conversation id to the
    // main chat surface - the page pops itself (backHash('/hub')) on the way
    fun joinCommunity() {
        if (joining) return
        joining = true
        scope.launch(Dispatchers.IO) {
            val joined = runCatching { repository.joinAppCommunity(appIdStr).getOrNull() }
            val fresh = runCatching { repository.appCommunity(appIdStr).getOrNull() }.getOrNull()
            launch(Dispatchers.Main) {
                community = fresh
                communityError = fresh == null
                communityLoading = false
                joining = false
                val cid = joined.getOrNull()?.conversation?.id ?: fresh?.conversation?.id
                if (cid != null) {
                    onBack()
                    onOpenConversation(cid)
                }
            }
        }
    }

    fun openChat() {
        val convo = community?.conversation
        if (convo != null) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onBack()
            onOpenConversation(convo.id)
        } else {
            joinCommunity()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SubPageInk.Page)
            .statusBarsPadding(),
    ) {
        // HubSubHeader (web 1074-1082): title = app name, subtitle = category,
        // trailing ConnectedBadge once installed
        Row(
            Modifier
                .fillMaxWidth()
                .background(SubPageInk.Panel)
                .border(1.dp, SubPageInk.PanelBorder)
                .padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x14FFFFFF))
                    .mirrorPressClick(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onBack()
                    }),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LChevronLeft", tint = SubPageInk.Zinc300, modifier = Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    app.name,
                    color = SubPageInk.Zinc50,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    app.category,
                    color = SubPageInk.Zinc500,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (installed) HubConnectedBadge()
        }

        // DetailTabBar (web 143-215): h-11 flex rows, amber selected text,
        // sliding gradient underline, pop-in count badges
        val tabLabels = listOf("Overview", "Community", "Connectors")
        val memberCount = community?.memberCount?.toInt() ?: 0
        val installs = status?.installs ?: 0
        // constraints drive the underline math directly (Dp arithmetic)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val barThird = maxWidth / 3f
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SubPageInk.Page)
                    .padding(horizontal = 8.dp),
            ) {
                tabLabels.forEachIndexed { index, label ->
                    val selected = tab == index
                    val badge = when (index) {
                        1 -> if (memberCount > 0) memberCount else null
                        2 -> if (installs > 0) installs else null
                        else -> null
                    }
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .mirrorPressClick(onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                tab = index
                            })
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            label,
                            color = if (selected) SubPageInk.Amber400 else SubPageInk.Zinc500,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (badge != null) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                Modifier
                                    .clip(CircleShape)
                                    .background(Color(0x1AFFFFFF))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    badge.toString(),
                                    color = SubPageInk.Zinc300,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
            // sliding underline (web layoutId gradient bar, stiffness 500 damping 36)
            val underlineFraction by animateFloatAsState(
                targetValue = tab.toFloat(),
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
                label = "hubAppUnderline",
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = barThird * underlineFraction + barThird * 0.19f)
                    .width(barThird * 0.62f)
                    .height(2.5.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(listOf(SubPageInk.Amber400, SubPageInk.Amber600)),
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0x1AFFFFFF)),
            )
        }

        // tab panels (web AnimatePresence mode=wait, 14dp slide)
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (slideInHorizontally(tween(160)) { 14 } + fadeIn(tween(160)))
                    .togetherWith(slideOutHorizontally(tween(140)) { -10 } + fadeOut(tween(120)))
            },
            label = "hubAppPanel",
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        ) { panel ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (panel) {
                    0 -> {
                        // AppHero (web 215-445)
                        val accentColors = HubCatalog.accentColors(HubCatalog.appAccent(app))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(SubPageInk.Panel)
                                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp))
                                .padding(16.dp),
                        ) {
                            // accent wash blob (web -right-12 -top-14 size-44 blur)
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 34.dp, y = -40.dp)
                                    .size(176.dp)
                                    .alpha(0.28f)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(accentColors[0], accentColors[1], Color.Transparent),
                                        ),
                                    ),
                            )
                            Column(Modifier.fillMaxWidth()) {
                                // identity row
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HubAccentTile(
                                        gradient = accentColors,
                                        glyph = null,
                                        initials = MirrorArt.initials(app.name),
                                        sizeDp = 60,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            app.name,
                                            color = SubPageInk.Zinc50,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Black,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            HubCatalog.appTagline(app),
                                            color = SubPageInk.Zinc300,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            lineHeight = 16.sp,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                // chips: category (navigates) / #00n / wallet
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier
                                            .heightIn(min = 28.dp)
                                            .clip(CircleShape)
                                            .background(SubPageInk.GlassPill)
                                            .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                                            .mirrorPressClick(onClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onOpenCategory(HubCatalog.slugForCategory(app.category) ?: app.category)
                                            })
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    ) {
                                        Text(
                                            app.category,
                                            color = SubPageInk.Zinc300,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Box(
                                        Modifier
                                            .heightIn(min = 28.dp)
                                            .clip(CircleShape)
                                            .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    ) {
                                        Text(
                                            "#" + app.n.toString().padStart(3, '0'),
                                            color = SubPageInk.Zinc400,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    if (walletCoins != null) {
                                        Row(
                                            Modifier
                                                .heightIn(min = 28.dp)
                                                .clip(CircleShape)
                                                .background(SubPageInk.GlassPill)
                                                .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            MirrorLucideIcon("LCoins", tint = SubPageInk.Amber400, modifier = Modifier.size(12.dp))
                                            Text(
                                                (walletCoins ?: 0).toString() + " PC",
                                                color = SubPageInk.Amber400,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            MirrorLucideIcon("LGem", tint = SubPageInk.Violet400, modifier = Modifier.size(12.dp))
                                            Text(
                                                (walletGems ?: 0).toString(),
                                                color = SubPageInk.Violet400,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    } else {
                                        HubSkeletonDots()
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                // live install stats (web 297-326)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        when {
                                            statusLoading -> Row(Modifier.heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) { HubSkeletonDots() }
                                            statusError || status == null -> Text(
                                                "Connection stats unavailable",
                                                color = SubPageInk.Rose400,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            else -> {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    HubCountText(installs)
                                                    MirrorLucideIcon("LUsers", tint = SubPageInk.Amber400, modifier = Modifier.size(16.dp))
                                                }
                                                Text(
                                                    "member" + if (installs == 1) "" else "s" + " connected",
                                                    color = SubPageInk.Zinc500,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                )
                                            }
                                        }
                                    }
                                    // InstallerStack (web 109-141): overlap avatars + extra
                                    val installers = status?.installers.orEmpty()
                                    if (installers.isNotEmpty()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), verticalAlignment = Alignment.CenterVertically) {
                                                installers.forEach { u ->
                                                    Box(
                                                        Modifier
                                                            .size(26.dp)
                                                            .clip(CircleShape)
                                                            .border(2.dp, Color(0xFF18181B), CircleShape),
                                                    ) {
                                                        MirrorAvatar(
                                                            name = u.name,
                                                            color = u.color,
                                                            isGroup = false,
                                                            groupId = "",
                                                            online = false,
                                                            showPresence = false,
                                                            sizeDp = 22,
                                                            cornerDp = 11,
                                                            modifier = Modifier.align(Alignment.Center),
                                                        )
                                                    }
                                                }
                                                val extra = (status?.installs ?: 0) - installers.size
                                                if (extra > 0) {
                                                    Box(
                                                        Modifier
                                                            .size(26.dp)
                                                            .clip(CircleShape)
                                                            .background(SubPageInk.Zinc800)
                                                            .border(2.dp, Color(0xFF18181B), CircleShape),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(
                                                            "+" + extra,
                                                            color = SubPageInk.Zinc300,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (installed && !status?.installedAt.isNullOrBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Connected on " + hubAppFormatDay(status?.installedAt),
                                        color = SubPageInk.Amber400,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                // actions row (web 328-442)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // connect toggle
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .heightIn(min = 44.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .then(
                                                if (installed) {
                                                    Modifier
                                                        .background(Color(0x1AF59E0B))
                                                        .border(1.dp, Color(0x66F59E0B), RoundedCornerShape(12.dp))
                                                } else {
                                                    Modifier
                                                        .background(Brush.horizontalGradient(listOf(SubPageInk.Amber600, Color(0xFFB45309))))
                                                },
                                            )
                                            .mirrorPressClick(enabled = !busy, onClick = { toggleConnect() })
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        if (busy) {
                                            MirrorLucideIcon("LLoaderCircle", tint = if (installed) SubPageInk.Amber400 else Color.White, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                if (installed) "Disconnecting" else "Connecting",
                                                color = if (installed) SubPageInk.Amber400 else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        } else if (installed) {
                                            HubPingDot()
                                            Spacer(Modifier.width(6.dp))
                                            MirrorLucideIcon("LCheck", tint = SubPageInk.Amber400, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(2.dp))
                                            Text(
                                                "Connected",
                                                color = SubPageInk.Amber400,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        } else {
                                            MirrorLucideIcon("LPlus", tint = Color.White, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "Connect",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    // open / join community
                                    val convo = community?.conversation
                                    Row(
                                        Modifier
                                            .heightIn(min = 44.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0x14FFFFFF))
                                            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(12.dp))
                                            .mirrorPressClick(enabled = !joining, onClick = { openChat() })
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        if (joining) {
                                            MirrorLucideIcon("LLoaderCircle", tint = SubPageInk.Zinc100, modifier = Modifier.size(16.dp))
                                        } else {
                                            MirrorLucideIcon(
                                                if (convo != null) "LMessagesSquare" else "LUserPlus",
                                                tint = SubPageInk.Zinc100,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                if (convo != null) "Open" else "Join",
                                                color = SubPageInk.Zinc100,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    // overflow
                                    Box {
                                        Box(
                                            Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(SubPageInk.GlassPill)
                                                .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                                                .mirrorPressClick(onClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    menuOpen = !menuOpen
                                                }),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            MirrorLucideIcon("LEllipsis", tint = SubPageInk.Zinc300, modifier = Modifier.size(18.dp))
                                        }
                                        if (menuOpen) {
                                            Popup(
                                                alignment = Alignment.TopEnd,
                                                onDismissRequest = { menuOpen = false },
                                                properties = PopupProperties(focusable = true),
                                            ) {
                                                Column(
                                                    Modifier
                                                        // web GlassMenu default min-w-[228px] -
                                                        // FIXED 228dp, same blowout guard as the
                                                        // other corner kebabs.
                                                        .width(228.dp)
                                                        .padding(top = 50.dp)
                                                        .clip(RoundedCornerShape(16.dp))
                                                        .background(Color(0xF21C1610))
                                                        .border(1.dp, MirrorArt.Hairline, RoundedCornerShape(16.dp))
                                                        .padding(vertical = 6.dp),
                                                ) {
                                                    Text(
                                                        app.name,
                                                        color = SubPageInk.Zinc500,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 1.sp,
                                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                                    )
                                                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
                                                    Row(
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .mirrorPressClick(onClick = { menuOpen = false })
                                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    ) {
                                                        MirrorLucideIcon("LBadgeCheck", tint = SubPageInk.Amber400, modifier = Modifier.size(16.dp))
                                                        Text(
                                                            if (installed) "Connection active" else "Not connected",
                                                            color = SubPageInk.Zinc100,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                        Text(
                                                            if (installed) "on" else "off",
                                                            color = SubPageInk.Zinc400,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                        )
                                                    }
                                                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x14FFFFFF)))
                                                    Row(
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .alpha(if (installed && !busy) 1f else 0.4f)
                                                            .mirrorPressClick(enabled = installed && !busy, onClick = {
                                                                menuOpen = false
                                                                toggleConnect()
                                                            })
                                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    ) {
                                                        MirrorLucideIcon("LCheck", tint = SubPageInk.Rose400, modifier = Modifier.size(16.dp))
                                                        Text(
                                                            "Remove connection",
                                                            color = SubPageInk.Rose400,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Medium,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // FeatureList (web 449-481)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(SubPageInk.Panel)
                                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp)),
                        ) {
                            Text(
                                "What it ships",
                                color = SubPageInk.Zinc500,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0x0DFFFFFF))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            val features = HubCatalog.appFeatures(app)
                            features.forEachIndexed { index, feature ->
                                if (index > 0) {
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
                                }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    MirrorLucideIcon(
                                        if (index == features.lastIndex) "LSparkles" else "LCheck",
                                        tint = SubPageInk.Amber400,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        feature,
                                        color = SubPageInk.Zinc100,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        lineHeight = 16.sp,
                                    )
                                }
                            }
                        }
                        // RelatedRail (web 922-966)
                        val related = HubCatalog.MATRIX.filter { it.category == app.category && it.n != app.n }.take(10)
                        if (related.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "More in " + app.category.substringBefore(" /"),
                                        color = SubPageInk.Zinc500,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.6.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "See all",
                                        color = SubPageInk.Amber400,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.mirrorPressClick(onClick = {
                                            onOpenCategory(HubCatalog.slugForCategory(app.category) ?: app.category)
                                        }),
                                    )
                                }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    related.forEach { r ->
                                        Column(
                                            Modifier
                                                .width(104.dp)
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(SubPageInk.Panel)
                                                .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp))
                                                .mirrorPressClick(onClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onOpenApp(r.n)
                                                })
                                                .padding(horizontal = 8.dp, vertical = 12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            HubAccentTile(
                                                gradient = HubCatalog.accentColors(HubCatalog.appAccent(r)),
                                                glyph = null,
                                                initials = MirrorArt.initials(r.name),
                                                sizeDp = 44,
                                            )
                                            Text(
                                                r.name,
                                                color = SubPageInk.Zinc100,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                            )
                                            Text(
                                                "#" + r.n.toString().padStart(3, '0'),
                                                color = SubPageInk.Zinc500,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> HubAppCommunityPanel(
                        app = app,
                        community = community,
                        loading = communityLoading,
                        error = communityError,
                        joining = joining,
                        viewerId = viewerId,
                        onJoin = { joinCommunity() },
                        onOpenChat = { openChat() },
                        onRetry = { communityLoading = true; loadCommunity() },
                    )
                    else -> HubAppConnectorsPanel(
                        app = app,
                        status = status,
                        loading = statusLoading,
                        error = statusError,
                        viewerId = viewerId,
                        busy = busy,
                        onToggle = { toggleConnect() },
                        onRetry = { statusLoading = true; loadStatus() },
                    )
                }
            }
        }
    }
}

/** Unknown app id (web 1023-1046): honest 404, back to the hub. */
@Composable
private fun MirrorHubAppUnknown(appId: Int, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(SubPageInk.Page)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(SubPageInk.Panel)
                .border(1.dp, SubPageInk.PanelBorder)
                .padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x14FFFFFF))
                    .mirrorPressClick(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                MirrorLucideIcon("LChevronLeft", tint = SubPageInk.Zinc300, modifier = Modifier.size(19.dp))
            }
            Text(
                "Unknown app",
                color = SubPageInk.Zinc50,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MirrorLucideIcon("LShieldQuestion", tint = SubPageInk.Zinc400, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "No platform in the matrix answers to #" + appId.toString().padStart(3, '0') + ".",
                color = SubPageInk.Zinc500,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(CircleShape)
                    .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                    .mirrorPressClick(onClick = onBack)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text("Back to the Hub", color = SubPageInk.Zinc300, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Amber count with the CountPulse pop (scale 1.4 -> 1 spring on change). */
@Composable
private fun HubCountText(value: Int) {
    val scale = remember(value) { androidx.compose.animation.core.Animatable(1.4f) }
    LaunchedEffect(value) {
        scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 520f))
    }
    val animated by animateIntAsState(targetValue = value, label = "hubCountValue")
    Text(
        animated.toString(),
        color = SubPageInk.Amber400,
        fontSize = 24.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}

/** The connected-button ping dot (web 352-361: ping + solid amber center). */
@Composable
private fun HubPingDot() {
    val ping = rememberInfiniteTransition(label = "hubPingDot")
    val pingAlpha by ping.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "hubPingAlpha",
    )
    val pingScale by ping.animateFloat(
        initialValue = 1f,
        targetValue = 1.7f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Restart),
        label = "hubPingScale",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer {
                    scaleX = pingScale
                    scaleY = pingScale
                    alpha = pingAlpha
                }
                .clip(CircleShape)
                .background(SubPageInk.Amber500),
        )
        Box(Modifier.size(8.dp).clip(CircleShape).background(SubPageInk.Amber500))
    }
}

/** Community tab (web CommunityPanel 535-755) - real provisioning + roster. */
@Composable
private fun HubAppCommunityPanel(
    app: MatrixApp,
    community: AppCommunityDto?,
    loading: Boolean,
    error: Boolean,
    joining: Boolean,
    viewerId: String,
    onJoin: () -> Unit,
    onOpenChat: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        loading -> HubAppSkeletonCard(lines = 4)
        error || community == null -> HubAppStateCard(
            glyph = "LSearchX",
            title = "Something went wrong",
            body = "Could not load the community.",
            actionLabel = "Retry",
            onAction = onRetry,
        )
        community.conversation == null -> {
            // founder moment (web 566-600)
            val fallbackName = "#" + app.n.toString().padStart(3, '0') + " . " + app.name + " community"
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SubPageInk.Panel)
                    .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(16.dp))
                        .background(Color(0x0DFFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    MirrorLucideIcon("LUsers", tint = SubPageInk.Zinc400, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Be the first to start the community",
                    color = SubPageInk.Zinc50,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "No members yet - the room " + fallbackName + " gets created on first join.",
                    color = SubPageInk.Zinc500,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    lineHeight = 14.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SubPageInk.Amber600)
                        .mirrorPressClick(enabled = !joining, onClick = onJoin)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (joining) {
                        MirrorLucideIcon("LLoaderCircle", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Founding", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else {
                        MirrorLucideIcon("LSparkles", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Found the community", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "You'll be the founding admin.",
                    color = SubPageInk.Amber400,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        else -> {
            val convo = community.conversation ?: return
            val joined = community.joined
            val memberCount = community.memberCount.toInt()
            val fallbackName = "#" + app.n.toString().padStart(3, '0') + " . " + app.name + " community"
            // identity card (web 610-707)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SubPageInk.Panel)
                    .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HubAccentTile(
                        gradient = HubCatalog.accentColors(HubCatalog.appAccent(app)),
                        glyph = null,
                        initials = MirrorArt.initials(app.name),
                        sizeDp = 48,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            convo.name ?: fallbackName,
                            color = SubPageInk.Zinc50,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (joined) "Member" else "Not joined",
                                color = if (joined) SubPageInk.Amber400 else SubPageInk.Zinc400,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .border(
                                        1.dp,
                                        if (joined) Color(0x66F59E0B) else SubPageInk.PanelBorder,
                                        CircleShape,
                                    )
                                    .background(if (joined) Color(0x1AF59E0B) else Color.Transparent)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                            if (convo.broadcastMode == true) {
                                Row(
                                    Modifier
                                        .clip(CircleShape)
                                        .border(1.dp, Color(0x66F59E0B), CircleShape)
                                        .background(Color(0x1AF59E0B))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    MirrorLucideIcon("LMegaphone", tint = SubPageInk.Amber400, modifier = Modifier.size(10.dp))
                                    Text(
                                        "Admins post only",
                                        color = SubPageInk.Amber400,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HubCountText(memberCount)
                        Text(
                            "member" + if (memberCount == 1) "" else "s",
                            color = SubPageInk.Zinc500,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    val stack = convo.members.take(8)
                    val extra = (memberCount - stack.size).coerceAtLeast(0)
                    if (stack.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), verticalAlignment = Alignment.CenterVertically) {
                            stack.forEach { m ->
                                Box(
                                    Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, Color(0xFF18181B), CircleShape),
                                ) {
                                    MirrorAvatar(
                                        name = m.name,
                                        color = m.color,
                                        isGroup = false,
                                        groupId = "",
                                        online = false,
                                        showPresence = false,
                                        sizeDp = 22,
                                        cornerDp = 11,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                            }
                            if (extra > 0) {
                                Box(
                                    Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(SubPageInk.Zinc800)
                                        .border(2.dp, Color(0xFF18181B), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "+" + extra,
                                        color = SubPageInk.Zinc300,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (joined) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SubPageInk.Amber600)
                            .mirrorPressClick(onClick = onOpenChat)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        MirrorLucideIcon("LMessagesSquare", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open chat", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Opens " + (convo.name ?: fallbackName) + " in your Chats.",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SubPageInk.Amber600)
                            .mirrorPressClick(enabled = !joining, onClick = onJoin)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        if (joining) {
                            MirrorLucideIcon("LLoaderCircle", tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Joining", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        } else {
                            MirrorLucideIcon("LUserPlus", tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Join community", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (memberCount == 1) "1 member is already inside." else memberCount.toString() + " members are already inside.",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // members roster (web 709-752)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SubPageInk.Panel)
                    .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x0DFFFFFF))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Members",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        convo.members.size.toString(),
                        color = SubPageInk.Amber400,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                val list = convo.members.take(8)
                val listExtra = (convo.members.size - list.size).coerceAtLeast(0)
                list.forEachIndexed { index, m ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MirrorAvatar(
                            name = m.name,
                            color = m.color,
                            isGroup = false,
                            groupId = "",
                            online = false,
                            showPresence = false,
                            sizeDp = 34,
                            cornerDp = 17,
                        )
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    m.name + if (m.id == viewerId) " (you)" else "",
                                    color = SubPageInk.Zinc50,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                m.username?.let { "@" + it } ?: "Pulse member",
                                color = SubPageInk.Zinc500,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (m.role == "admin") {
                            Row(
                                Modifier
                                    .clip(CircleShape)
                                    .border(1.dp, Color(0x66F59E0B), CircleShape)
                                    .background(Color(0x1AF59E0B))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                MirrorLucideIcon("LCrown", tint = SubPageInk.Amber400, modifier = Modifier.size(10.dp))
                                Text(
                                    "Admin",
                                    color = SubPageInk.Amber400,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
                if (listExtra > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
                    Text(
                        "+" + listExtra + " more member" + if (listExtra == 1) "" else "s",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** Connectors tab (web ConnectorsPanel 779-918) - the live installer roster. */
@Composable
private fun HubAppConnectorsPanel(
    app: MatrixApp,
    status: AppInstallStateDto?,
    loading: Boolean,
    error: Boolean,
    viewerId: String,
    busy: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
) {
    val installed = status?.installed == true
    // viewer connect-state card (web 798-843)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SubPageInk.Panel)
            .border(1.dp, if (installed) Color(0x66F59E0B) else SubPageInk.PanelBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HubAccentTile(
                gradient = HubCatalog.accentColors(HubCatalog.appAccent(app)),
                glyph = null,
                initials = MirrorArt.initials(app.name),
                sizeDp = 40,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Your connection",
                    color = SubPageInk.Zinc50,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        loading -> "Checking"
                        installed && !status?.installedAt.isNullOrBlank() -> "Connected on " + hubAppFormatDay(status?.installedAt)
                        else -> "Not connected yet"
                    },
                    color = SubPageInk.Zinc500,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .widthIn(min = 108.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (installed) Color(0x1AF59E0B) else SubPageInk.Amber600)
                    .border(1.dp, if (installed) Color(0x66F59E0B) else Color.Transparent, RoundedCornerShape(12.dp))
                    .mirrorPressClick(enabled = !busy, onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (busy) {
                    MirrorLucideIcon("LLoaderCircle", tint = if (installed) SubPageInk.Amber400 else Color.White, modifier = Modifier.size(16.dp))
                } else if (installed) {
                    MirrorLucideIcon("LCheck", tint = SubPageInk.Amber400, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Connected",
                        color = SubPageInk.Amber400,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    MirrorLucideIcon("LPlus", tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Connect",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
    // roster (web 845-915)
    when {
        loading -> HubAppSkeletonCard(lines = 3)
        error || status == null -> HubAppStateCard(
            glyph = "LSearchX",
            title = "Something went wrong",
            body = "Could not load connectors.",
            actionLabel = "Retry",
            onAction = onRetry,
        )
        status.installers.isEmpty() -> HubAppStateCard(
            glyph = "LCable",
            title = "No connectors yet",
            body = "Be the first to connect " + app.name + ".",
            actionLabel = null,
            onAction = {},
        )
        else -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SubPageInk.Panel)
                    .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp)),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x0DFFFFFF))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Connected members",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        status.installs.toString(),
                        color = SubPageInk.Amber400,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                status.installers.forEachIndexed { index, u ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
                    }
                    val isViewer = u.id == viewerId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MirrorAvatar(
                            name = u.name,
                            color = u.color,
                            isGroup = false,
                            groupId = "",
                            online = false,
                            showPresence = false,
                            sizeDp = 34,
                            cornerDp = 17,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                u.name + if (isViewer) " (you)" else "",
                                color = SubPageInk.Zinc50,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                u.username?.let { "@" + it } ?: "Pulse member",
                                color = SubPageInk.Zinc500,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "connected",
                                color = SubPageInk.Zinc500,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            // installedAt is per-viewer truth - others get no invented date
                            if (isViewer && installed && !status.installedAt.isNullOrBlank()) {
                                Text(
                                    hubAppFormatRelative(status.installedAt),
                                    color = SubPageInk.Zinc400,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
                val extra = (status.installs - status.installers.size).coerceAtLeast(0)
                if (extra > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
                    Text(
                        "+" + extra + " more connected",
                        color = SubPageInk.Zinc500,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** Pulsing skeleton card (web CommunitySkeleton / ConnectorsSkeleton). */
@Composable
private fun HubAppSkeletonCard(lines: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SubPageInk.Panel)
            .border(1.dp, SubPageInk.PanelBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HubAppSkeletonBox(48.dp, 16.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HubAppSkeletonBox(160.dp, 12.dp)
                HubAppSkeletonBox(96.dp, 9.dp)
            }
        }
        for (i in 0 until lines) {
            HubAppSkeletonBox(if (i == lines - 1) 120.dp else 220.dp, 12.dp)
        }
    }
}

@Composable
private fun HubAppSkeletonBox(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    val alpha by rememberInfiniteTransition(label = "hubAppSk").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "hubAppSkAlpha",
    )
    Box(
        Modifier
            .width(width)
            .height(height)
            .graphicsLayer { this.alpha = alpha * 0.5f }
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x1AFFFFFF)),
    )
}

/** Glass state card with an optional action pill (web LoadErrorCard shapes). */
@Composable
private fun HubAppStateCard(
    glyph: String,
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SubPageInk.Panel)
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MirrorLucideIcon(glyph, tint = SubPageInk.Zinc400, modifier = Modifier.size(20.dp))
        Text(
            title,
            color = SubPageInk.Zinc100,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            body,
            color = SubPageInk.Zinc500,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp,
        )
        if (actionLabel != null) {
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(CircleShape)
                    .border(1.dp, SubPageInk.PanelBorder, CircleShape)
                    .mirrorPressClick(onClick = onAction)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(actionLabel, color = SubPageInk.Zinc300, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** web formatDay (app-detail-sheet.tsx:90): "Nov 3, 2025". */
internal fun hubAppFormatDay(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        val instant = if (iso.endsWith("Z") || iso.contains('+')) {
            Instant.parse(iso)
        } else {
            Instant.parse(iso + "Z")
        }
        DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)
            .format(instant.atZone(ZoneId.systemDefault()))
    }.getOrDefault("")
}

/** web formatRelative (app-detail-sheet.tsx:95): just now / 3m / 5h / 2d ago. */
internal fun hubAppFormatRelative(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val parsed = runCatching {
        val instant = if (iso.endsWith("Z") || iso.contains('+')) {
            Instant.parse(iso)
        } else {
            Instant.parse(iso + "Z")
        }
        instant
    }.getOrNull() ?: return ""
    val ms = System.currentTimeMillis() - parsed.toEpochMilli()
    if (ms < 0) return ""
    val mins = ms / 60_000
    if (mins < 1) return "just now"
    if (mins < 60) return mins.toString() + "m ago"
    val hours = mins / 60
    if (hours < 24) return hours.toString() + "h ago"
    val days = hours / 24
    if (days < 7) return days.toString() + "d ago"
    return hubAppFormatDay(iso)
}
