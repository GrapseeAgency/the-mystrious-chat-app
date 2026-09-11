package app.pulse.android

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.core.view.WindowCompat
import app.pulse.android.ui.AmbientField
import app.pulse.android.ui.FxMode
import app.pulse.android.ui.ParticleBurstHost
import app.pulse.domain.repository.PulseRepository
import app.pulse.feature.calls.ContactsScreen
import app.pulse.feature.chat.ArchivedScreen
import app.pulse.feature.chat.ChatsScreen
import app.pulse.feature.chat.ChatRoomScreen
import app.pulse.feature.chat.SavedLibraryScreen
import app.pulse.feature.chat.ThreadScreen
import app.pulse.feature.hub.HubScreen
import app.pulse.feature.settings.ProfileScreen
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import app.pulse.ui.PulseTheme
import app.pulse.ui.isPulseDarkTheme
import app.pulse.ui.pulseGlass
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val DockEmerald600 = Color(0xFF059669)
private val DockTeal600 = Color(0xFF0D9488)
private val DockInactiveDark = Color(0xFFA1A1AA)
private val DockInactiveLight = Color(0xFF71717A)

/** Canonical tab order — drives dock layout + direction-aware transitions. */
private val TAB_ROUTES = listOf("chats", "hub", "contacts", "profile")

private data class DockTab(
    val route: String,
    val label: String,
    val activeIcon: ImageVector,
    val inactiveIcon: ImageVector,
    val carriesUnread: Boolean = false,
)

/** Registry parity with web NAV_ITEMS (nav-router.ts) — Lucide icon mapping. */
private val DOCK_TABS = listOf(
    DockTab("chats", "Chats", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline, carriesUnread = true),
    DockTab("hub", "Hub", Icons.Filled.Whatshot, Icons.Outlined.Whatshot),
    DockTab("contacts", "Contacts", Icons.Filled.Group, Icons.Outlined.Group),
    DockTab("profile", "Profile", Icons.Filled.AccountCircle, Icons.Outlined.AccountCircle),
)

/** Dock-scoped state — live unread total for the Chats badge (web useUnread). */
@HiltViewModel
class ShellViewModel @Inject constructor(
    repo: PulseRepository,
) : ViewModel() {
    val unread: StateFlow<Int> = repo.observeConversations()
        .map { list -> list.sumOf { it.unreadCount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _searchTick = MutableStateFlow(0)
    val searchTick: StateFlow<Int> = _searchTick.asStateFlow()

    fun requestSearch() {
        _searchTick.value += 1
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var repository: app.pulse.domain.repository.PulseRepository

    override fun onStart() {
        super.onStart()
        // Foreground outbox trigger (Wave 0): whatever queued while the app
        // was dead/backgrounded drains the moment the surface is up.
        lifecycleScope.launch { runCatching { repository.flushOutbox() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // True edge-to-edge with NO system scrims: the app surface (ambient field)
        // shows behind the status bar AND the navigation bar — the default
        // enableEdgeToEdge() nav scrim is what painted a gray band over the dock.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            PulseRoot()
        }
    }
}

@Composable
fun PulseRoot(session: SessionViewModel = hiltViewModel()) {
    val viewerId by session.viewerId.collectAsStateWithLifecycle()
    val hydrated by session.hydrated.collectAsStateWithLifecycle()
    val fxRaw by session.fxMode.collectAsStateWithLifecycle()
    val darkRaw by session.darkOverride.collectAsStateWithLifecycle()
    val reduced by session.reducedMotion.collectAsStateWithLifecycle()

    val dark = when (darkRaw) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    // System bars follow the IN-APP theme (not just the system one), so the
    // clock/battery icons flip together with the in-app light/dark override.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    // Boot the live layer (REST refresh + socket join) as soon as identity exists.
    LaunchedEffect(viewerId) { session.bootstrap(viewerId) }

    // Web parity: no viewer identity → the onboarding IS the app (name →
    // live @handle picker). Wait for prefs hydration to avoid a flash.
    val onboarding = hydrated && viewerId == null

    PulseTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxSize()) {
            Box {
                AmbientField(
                    mode = FxMode.from(fxRaw),
                    dark = dark,
                    reducedMotion = reduced,
                    modifier = Modifier.fillMaxSize(),
                )

                if (onboarding) {
                    OnboardingScreen()
                } else {
                    PulseShell(viewerId = viewerId, session = session)
                }

                ParticleBurstHost(
                    Modifier.fillMaxSize(),
                    reducedMotion = reduced,
                )
            }
        }
    }
}

/**
 * The four-tab shell behind the onboarding gate — web MainShell parity:
 * direction-aware tab transitions + the floating glass Capsule dock.
 */
@Composable
private fun PulseShell(viewerId: String?, session: SessionViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val shell: ShellViewModel = hiltViewModel()
    val unread by shell.unread.collectAsStateWithLifecycle()
    val searchTick by shell.searchTick.collectAsStateWithLifecycle()
    val viewerName by session.viewerName.collectAsStateWithLifecycle()
    val viewerColor by session.viewerColor.collectAsStateWithLifecycle()

    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var moreMenuOpen by remember { mutableStateOf(false) }
    val dark = isPulseDarkTheme()

    fun switchTab(route: String) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun honest(message: String) {
        scope.launch { snackbar.showSnackbar(message, withDismissAction = false) }
    }

    // Dock visibility: tabs + the archived sub-page keep the chrome (web keeps
    // the nav over hash sub-pages); rooms own the whole screen. Wave 2: the
    // saved library keeps it too (it's a shell page, not a room).
    val showDock = currentRoute in TAB_ROUTES || currentRoute == "archived" || currentRoute == "saved"

    // The system nav bar (gesture pill or 3-button strip) draws over the app —
    // every bottom-anchored surface must clear it.
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val dockSpace = 108.dp + navBottom

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "chats",
            modifier = Modifier.fillMaxSize(),
            enterTransition = { tabEnter(initialState.destination.route, targetState.destination.route) },
            exitTransition = { tabExit(initialState.destination.route, targetState.destination.route) },
            popEnterTransition = { tabEnter(initialState.destination.route, targetState.destination.route) },
            popExitTransition = { tabExit(initialState.destination.route, targetState.destination.route) },
        ) {
            composable("chats") {
                ChatsScreen(
                    viewerId = viewerId,
                    viewerName = viewerName,
                    viewerColor = viewerColor,
                    onOpenRoom = { id, jump ->
                        if (jump != null) navController.navigate("room/$id?jump=$jump") else navController.navigate("room/$id")
                    },
                    onNeedIdentity = { navController.navigate("profile") { launchSingleTop = true } },
                    onSwitchTab = { route -> switchTab(route) },
                    onOpenArchived = { navController.navigate("archived") },
                    onCycleTheme = { session.cycleDarkOverride() },
                    searchRequest = searchTick,
                )
            }
            composable("hub") {
                Box(Modifier.fillMaxSize().padding(bottom = dockSpace)) {
                    HubScreen(viewerName = viewerName ?: "")
                }
            }
            composable("contacts") {
                Box(Modifier.fillMaxSize().padding(bottom = dockSpace)) {
                    ContactsScreen(onOpenRoom = { id -> navController.navigate("room/$id") })
                }
            }
            composable("profile") {
                Box(Modifier.fillMaxSize().padding(bottom = dockSpace)) {
                    ProfileScreen()
                }
            }
            composable(
                "room/{conversationId}?jump={jump}",
                arguments = listOf(
                    navArgument("conversationId") { type = NavType.StringType },
                    navArgument("jump") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val conversationId = entry.arguments?.getString("conversationId").orEmpty()
                ChatRoomScreen(
                    conversationId = conversationId,
                    viewerId = viewerId,
                    jumpMessageId = entry.arguments?.getString("jump"),
                    onBack = { navController.popBackStack() },
                    onOpenThread = { id, rootId -> navController.navigate("room/$id/thread/$rootId") },
                )
            }
            composable(
                "room/{conversationId}/thread/{rootId}",
                arguments = listOf(
                    navArgument("conversationId") { type = NavType.StringType },
                    navArgument("rootId") { type = NavType.StringType },
                ),
            ) {
                ThreadScreen(
                    viewerId = viewerId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("archived") {
                ArchivedScreen(
                    viewerId = viewerId,
                    onOpenRoom = { id, jump ->
                        if (jump != null) navController.navigate("room/$id?jump=$jump") else navController.navigate("room/$id")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            // Wave 2 — the dock "Saved" menu item now lands on the real library
            // (fetch → Room cache → search → unsave → jump-to-message rows).
            composable("saved") {
                Box(Modifier.fillMaxSize().padding(bottom = dockSpace)) {
                    SavedLibraryScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRoom = { id, jump ->
                            if (jump != null) navController.navigate("room/$id?jump=$jump") else navController.navigate("room/$id")
                        },
                    )
                }
            }
        }

        if (showDock) {
            CapsuleDock(
                modifier = Modifier.align(Alignment.BottomCenter),
                active = if (currentRoute == "archived") "chats" else currentRoute ?: "chats",
                unread = unread,
                dark = dark,
                onSelect = { route -> switchTab(route) },
                onCompose = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    honest("The new chat composer isn't available in this native build yet.")
                },
                onSearch = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (currentRoute != "chats") switchTab("chats")
                    shell.requestSearch()
                },
                onSaved = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    navController.navigate("saved")
                },
                onDeferred = { message -> honest(message) },
                moreMenuOpen = moreMenuOpen,
                onMoreMenuChange = { moreMenuOpen = it },
            )
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = dockSpace),
        ) { data ->
            Snackbar(
                containerColor = if (dark) Color(0xFF27272A) else Color(0xFF18181B),
                contentColor = Color.White,
                shape = RoundedCornerShape(14.dp),
            ) { Text(data.visuals.message, fontSize = 13.sp) }
        }
    }
}

// ── direction-aware tab transitions (web ±24px slide + 220ms fade) ────

private val pulseEaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

private fun tabEnter(fromRoute: String?, toRoute: String?): EnterTransition {
    val from = TAB_ROUTES.indexOf(fromRoute)
    val to = TAB_ROUTES.indexOf(toRoute)
    if (from < 0 || to < 0 || from == to) return fadeIn(tween(220, easing = pulseEaseOut))
    val dir = if (to > from) 1 else -1
    return slideInHorizontally(tween(220, easing = pulseEaseOut)) { dir * it / 8 } +
        fadeIn(tween(220, easing = pulseEaseOut))
}

private fun tabExit(fromRoute: String?, toRoute: String?): ExitTransition {
    val from = TAB_ROUTES.indexOf(fromRoute)
    val to = TAB_ROUTES.indexOf(toRoute)
    if (from < 0 || to < 0 || from == to) return fadeOut(tween(220, easing = pulseEaseOut))
    val dir = if (to > from) 1 else -1
    return slideOutHorizontally(tween(220, easing = pulseEaseOut)) { -dir * it / 8 } +
        fadeOut(tween(220, easing = pulseEaseOut))
}

// ── the Floating Capsule dock (web default nav, spec §12) ─────────────

@Composable
private fun CapsuleDock(
    modifier: Modifier = Modifier,
    active: String,
    unread: Int,
    dark: Boolean,
    onSelect: (String) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onSaved: () -> Unit,
    onDeferred: (String) -> Unit,
    moreMenuOpen: Boolean,
    onMoreMenuChange: (Boolean) -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp),
    ) {
        BoxWithConstraints {
            val pad = 6.dp
            val gap = 4.dp
            val composeW = 46.dp
            // children: chats · hub · compose · contacts · profile · more
            val slotW = (maxWidth - pad * 2 - composeW - 40.dp - gap * 5) / 4
            val tabIndex = TAB_ROUTES.indexOf(active).coerceAtLeast(0)
            val pillX = pad +
                (slotW + gap) * tabIndex +
                (if (tabIndex >= 2) composeW + gap else 0.dp)
            val pillXAnim by animateDpAsState(
                targetValue = pillX,
                animationSpec = PulseMotion.snappy(),
                label = "dockPill",
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .pulseGlass(dark, RoundedCornerShape(28.dp)),
            ) {
                // active pill — slides between tab slots (web layoutId pill)
                Box(
                    Modifier
                        .offset(x = pillXAnim, y = pad)
                        .size(width = slotW, height = 56.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    PulsePalette.Emerald.copy(alpha = if (dark) 0.16f else 0.20f),
                                    PulsePalette.Emerald.copy(alpha = if (dark) 0.05f else 0.06f),
                                ),
                            ),
                        )
                        .border(
                            1.dp,
                            PulsePalette.Emerald.copy(alpha = if (dark) 0.25f else 0.30f),
                            RoundedCornerShape(22.dp),
                        ),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(pad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DockTabButton(
                        tab = DOCK_TABS[0],
                        active = active == "chats",
                        unread = unread,
                        dark = dark,
                        modifier = Modifier.weight(1f),
                        onSelect = { onSelect("chats") },
                    )
                    Spacer(Modifier.width(gap))
                    DockTabButton(
                        tab = DOCK_TABS[1],
                        active = active == "hub",
                        unread = 0,
                        dark = dark,
                        modifier = Modifier.weight(1f),
                        onSelect = { onSelect("hub") },
                    )
                    Spacer(Modifier.width(gap))
                    ComposeDockButton(onCompose)
                    Spacer(Modifier.width(gap))
                    DockTabButton(
                        tab = DOCK_TABS[2],
                        active = active == "contacts",
                        unread = 0,
                        dark = dark,
                        modifier = Modifier.weight(1f),
                        onSelect = { onSelect("contacts") },
                    )
                    Spacer(Modifier.width(gap))
                    DockTabButton(
                        tab = DOCK_TABS[3],
                        active = active == "profile",
                        unread = 0,
                        dark = dark,
                        modifier = Modifier.weight(1f),
                        onSelect = { onSelect("profile") },
                    )
                    Spacer(Modifier.width(gap))
                    MoreDockButton(
                        dark = dark,
                        open = moreMenuOpen,
                        onOpenChange = onMoreMenuChange,
                        onSearch = onSearch,
                        onSaved = onSaved,
                        onDeferred = onDeferred,
                    )
                }
            }
        }
    }
}

@Composable
private fun DockTabButton(
    tab: DockTab,
    active: Boolean,
    unread: Int,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // web wobble — icon rotates [0, -8, 6, 0]° when a tab becomes active
    val rotate = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            rotate.animateTo(-8f, tween(90))
            rotate.animateTo(6f, tween(90))
            rotate.animateTo(0f, tween(110))
        } else {
            rotate.snapTo(0f)
        }
    }
    val activeTint = DockEmerald600
    val inactiveTint = if (dark) DockInactiveDark else DockInactiveLight
    Box(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (active) tab.activeIcon else tab.inactiveIcon,
                    contentDescription = tab.label,
                    tint = if (active) activeTint else inactiveTint,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            rotationZ = rotate.value
                            scaleX = if (active) 1.08f else 1f
                            scaleY = if (active) 1.08f else 1f
                            translationY = if (active) -1.dp.toPx() else 0f
                        },
                )
                if (tab.carriesUnread && unread > 0) {
                    DockUnreadBadge(unread, dark)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                tab.label,
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                color = if (active) activeTint else inactiveTint,
            )
        }
    }
}

@Composable
private fun DockUnreadBadge(count: Int, dark: Boolean) {
    val label = if (count > 99) "99+" else "$count"
    Text(
        label,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier
            .offset(x = 10.dp, y = (-6).dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(PulsePalette.Emerald, PulsePalette.Teal)))
            .border(2.dp, if (dark) Color(0xFF18181B) else Color.White, CircleShape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun ComposeDockButton(onCompose: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(PulsePalette.Emerald, DockTeal600)))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCompose()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = "New chat", tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MoreDockButton(
    dark: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onSaved: () -> Unit,
    onDeferred: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenChange(!open)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MoreHoriz,
                contentDescription = "More options",
                tint = if (dark) DockInactiveDark else DockInactiveLight,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { onOpenChange(false) },
            shape = RoundedCornerShape(16.dp),
            containerColor = if (dark) Color(0xFF1C1C1F) else Color.White,
        ) {
            DropdownMenuItem(
                text = { Text("Settings", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = DockEmerald600) },
                onClick = {
                    onOpenChange(false)
                    onDeferred("Settings aren't available in this native build yet.")
                },
            )
            DropdownMenuItem(
                text = { Text("Search", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = DockEmerald600) },
                onClick = {
                    onOpenChange(false)
                    onSearch()
                },
            )
            DropdownMenuItem(
                text = { Text("Saved", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null, tint = DockEmerald600) },
                onClick = {
                    onOpenChange(false)
                    onSaved()
                },
            )
            DropdownMenuItem(
                text = { Text("Stories", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.AutoStories, contentDescription = null, tint = DockEmerald600) },
                onClick = {
                    onOpenChange(false)
                    onDeferred("Stories aren't available in this native build yet.")
                },
            )
        }
    }
}
