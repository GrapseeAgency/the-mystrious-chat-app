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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import app.pulse.protocol.PulseNavStyle
import app.pulse.feature.calls.CallOverlay
import app.pulse.feature.calls.CallViewModel
import app.pulse.feature.calls.CallsView
import app.pulse.feature.calls.ContactsScreen
import app.pulse.feature.chat.ArchivedScreen
import app.pulse.feature.chat.ChatsScreen
import app.pulse.feature.chat.ChatRoomScreen
import app.pulse.feature.chat.ChannelsScreen
import app.pulse.feature.chat.GroupInfoScreen
import app.pulse.feature.chat.JoinInviteSheet
import app.pulse.feature.chat.MentionsScreen
import app.pulse.feature.chat.NewChatSheet
import app.pulse.feature.chat.PipOverlayViewModel
import app.pulse.feature.chat.PipPaneOverlay
import app.pulse.feature.chat.SavedLibraryScreen
import app.pulse.feature.chat.ThreadScreen
import app.pulse.feature.hub.HubScreen
import app.pulse.feature.calls.AddContactScreen
import app.pulse.feature.calls.UserPageScreen
import app.pulse.feature.settings.AboutSection
import app.pulse.feature.settings.AccessibilitySection
import app.pulse.feature.settings.AccountSection
import app.pulse.feature.settings.AppearanceSection
import app.pulse.feature.settings.BlockedListScreen
import app.pulse.feature.settings.ChatSection
import app.pulse.feature.settings.DataSection
import app.pulse.feature.settings.NotificationsSection
import app.pulse.feature.settings.PrivacySection
import app.pulse.feature.settings.RealtimeSection
import app.pulse.feature.settings.SettingsRootScreen
import app.pulse.feature.settings.ProfileEditScreen
import app.pulse.feature.settings.ProfileScreen
import app.pulse.feature.stories.StoriesViewModel
import app.pulse.feature.stories.StoryComposerScreen
import app.pulse.feature.stories.StoryViewerScreen
import app.pulse.feature.voice.ui.VoiceRoomsOverlay
import app.pulse.feature.voice.vm.VoiceRoomsViewModel
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import app.pulse.ui.PulseTheme
import app.pulse.ui.isPulseDarkTheme
import app.pulse.ui.pulseUiThemePageBackground
import app.pulse.ui.pulseGlass
import androidx.hilt.navigation.compose.hiltViewModel
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
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

    // Wave 6 — a pulse://invite code lands here and the chats surface raises
    // the JoinInviteSheet (web ?join= parity).
    private val _pendingInvite = MutableStateFlow<String?>(null)
    val pendingInvite: StateFlow<String?> = _pendingInvite.asStateFlow()

    fun postInvite(code: String) {
        _pendingInvite.value = code
    }

    fun consumeInvite() {
        _pendingInvite.value = null
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var repository: app.pulse.domain.repository.PulseRepository

    /** Wave 6 — pulse:// deep links (invite/user/room); consumed by the shell. */
    private val deepLinks = MutableStateFlow<app.pulse.core.link.PulseDeepLink?>(null)

    /** Wave 7 — POST_NOTIFICATIONS launcher (must register before STARTED). */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Honest: denial keeps in-app surfaces live, no reminders as push.
        }

    /** Wave 7 reminders due-loop request (30 s foreground, web useReminderDueLoop parity). */
    private var reminderLoopStarted = false

    override fun onStart() {
        super.onStart()
        // Foreground outbox trigger (Wave 0): whatever queued while the app
        // was dead/backgrounded drains the moment the surface is up.
        lifecycleScope.launch { runCatching { repository.flushOutbox() } }
        // Wave 7 — ask for the notifications permission ONCE (API 33+), then
        // run the due-loop: GET ?due=1 → local notification → PATCH firedAt.
        app.pulse.android.notify.ReminderNotifier.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!reminderLoopStarted) {
            reminderLoopStarted = true
            lifecycleScope.launch {
                while (true) {
                    runCatching {
                        val due = repository.reminders(dueOnly = true).getOrNull()?.items.orEmpty()
                        for (item in due) {
                            app.pulse.android.notify.ReminderNotifier.show(
                                this@MainActivity,
                                item.id,
                                item.note.ifBlank { "Reminder" },
                                item.snippet ?: item.conversation.name,
                                // R2-C item 6 — the tap deep-links into the chat.
                                item.conversationId.ifBlank { null },
                            )
                            runCatching { repository.resolveReminder(item.id) }
                        }
                    }
                    delay(30_000)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deepLinks.value = app.pulse.core.link.PulseDeepLink.parse(intent?.dataString)
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
            PulseRoot(deepLink = deepLinks.collectAsStateWithLifecycle().value, onConsumeDeepLink = { deepLinks.value = null })
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinks.value = app.pulse.core.link.PulseDeepLink.parse(intent.dataString)
    }
}

@Composable
fun PulseRoot(
    deepLink: app.pulse.core.link.PulseDeepLink? = null,
    onConsumeDeepLink: () -> Unit = {},
    session: SessionViewModel = hiltViewModel(),
) {
    val viewerId by session.viewerId.collectAsStateWithLifecycle()
    val hydrated by session.hydrated.collectAsStateWithLifecycle()
    val fxRaw by session.fxMode.collectAsStateWithLifecycle()
    val darkRaw by session.darkOverride.collectAsStateWithLifecycle()
    val uiThemeRaw by session.uiTheme.collectAsStateWithLifecycle()
    val reduced by session.reducedMotion.collectAsStateWithLifecycle()
    // R2-C item 3 — the selected design language (web pulse.uiTheme.v2).
    val uiTheme = app.pulse.ui.PulseUiTheme.fromId(uiThemeRaw)

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

    PulseTheme(darkTheme = dark, uiTheme = uiTheme) {
        Box(
            Modifier
                .fillMaxSize()
                // R2-C item 3 — the design-language page backdrop (aurora
                // radials for glass, ink-linear for kinetic, …) sits behind
                // the ambient FX field like the web .ui-root.
                .pulseUiThemePageBackground(uiTheme, dark),
        ) {
            AmbientField(
                mode = FxMode.from(fxRaw),
                dark = dark,
                reducedMotion = reduced,
                modifier = Modifier.fillMaxSize(),
            )

            if (onboarding) {
                OnboardingScreen()
            } else {
                PulseShell(viewerId = viewerId, session = session, deepLink = deepLink, onConsumeDeepLink = onConsumeDeepLink)
            }

            ParticleBurstHost(
                Modifier.fillMaxSize(),
                reducedMotion = reduced,
            )
        }
    }
}

/**
 * The four-tab shell behind the onboarding gate — web MainShell parity:
 * direction-aware tab transitions + the floating glass Capsule dock.
 */
@Composable
private fun PulseShell(
    viewerId: String?,
    session: SessionViewModel,
    deepLink: app.pulse.core.link.PulseDeepLink? = null,
    onConsumeDeepLink: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val shell: ShellViewModel = hiltViewModel()
    // Wave 3 — activity-scoped call surface. The engine is a @Singleton; this
    // VM just exposes it to every screen + the root overlay.
    val callVm: CallViewModel = hiltViewModel()
    // Wave 5 — the voice-rooms overlay rides the exact same pattern: the
    // engine is a @Singleton (rooms outlive surfaces), the VM is the bridge.
    val voiceVm: VoiceRoomsViewModel = hiltViewModel()
    val unread by shell.unread.collectAsStateWithLifecycle()
    val searchTick by shell.searchTick.collectAsStateWithLifecycle()
    val viewerName by session.viewerName.collectAsStateWithLifecycle()
    val viewerColor by session.viewerColor.collectAsStateWithLifecycle()
    val voiceState by voiceVm.voiceState.collectAsStateWithLifecycle()
    // R1-W2I — PiP pane overlay (F-PI-01..03): the renderer VM bridge; the
    // pane state itself lives in the PulsePiPStore @Singleton so panes
    // outlive every surface (web usePipChat module-singleton parity).
    val pipVm: PipOverlayViewModel = hiltViewModel()
    // R4-B item 3 — the navigation architecture (web pulse.navStyle.v2): the
    // shell re-renders the matching dock live when the Appearance pick lands.
    val navStyle by session.navStyle.collectAsStateWithLifecycle()
    val reducedMotion by session.reducedMotion.collectAsStateWithLifecycle()

    // Identity adoption for the voice/stage/space wire payloads (the calls
    // surface receives the same values through callPeer's caller args).
    LaunchedEffect(viewerId, viewerName, viewerColor) {
        voiceVm.setIdentity(viewerId, viewerName, viewerColor)
    }

    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var moreMenuOpen by remember { mutableStateOf(false) }
    // R2-A item 1 — the shell-hosted new-chat composer (web mounts
    // NewChatSheet at the shell; both the dock FAB and the chats header
    // pencil raise it).
    var newChatOpen by remember { mutableStateOf(false) }
    val dark = isPulseDarkTheme()

    fun switchTab(route: String) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Wave 6 — pulse:// deep links: room/user open directly, invites land on
    // the chats tab and surface the JoinInviteSheet there (web ?join= parity).
    LaunchedEffect(deepLink, viewerId) {
        if (viewerId == null || deepLink == null) return@LaunchedEffect
        when (deepLink) {
            is app.pulse.core.link.PulseDeepLink.Room ->
                navController.navigate("room/${deepLink.conversationId}")
            is app.pulse.core.link.PulseDeepLink.User ->
                navController.navigate("user/${deepLink.userId}")
            is app.pulse.core.link.PulseDeepLink.Invite -> {
                shell.postInvite(deepLink.code)
                switchTab("chats")
            }
        }
        onConsumeDeepLink()
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
    // R4-B item 3 — per-style content clearance: hub/contacts/profile/saved
    // + the snackbar host clear the dock through this one channel (web
    // pb-[env(safe-area-inset-bottom)+Npx] per architecture).
    val dockSpace = when (navStyle) {
        PulseNavStyle.CAPSULE -> 108.dp + navBottom
        PulseNavStyle.FLOATING_TOP -> navBottom + 12.dp
        PulseNavStyle.PILL -> 104.dp + navBottom
        PulseNavStyle.BOTTOM_BAR, PulseNavStyle.TAB_BAR -> 64.dp + navBottom
        PulseNavStyle.FLOATING_TAB_BAR -> 104.dp + navBottom
        PulseNavStyle.RAIL -> navBottom + 12.dp
        PulseNavStyle.ISLAND -> 84.dp + navBottom
    }
    // floating-top pins the glass capsule beneath the top edge — each screen's
    // own statusBarsPadding still applies; this routes only the EXTRA nav
    // height through the shell scaffold (rail routes its width via the Row
    // below). Rooms push the nav away, so the inset rides showDock too.
    val navTopInset = if (showDock && navStyle == PulseNavStyle.FLOATING_TOP) 84.dp else 0.dp

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            // rail — the ONE style that lives as a layout sibling (web RailNav is
            // a persistent flex child, not an overlay): content insets by weight.
            if (showDock && navStyle == PulseNavStyle.RAIL) {
                RailDock(
                    active = if (currentRoute == "archived") "chats" else currentRoute ?: "chats",
                    unread = unread,
                    dark = dark,
                    reducedMotion = reducedMotion,
                    actions = DockActions(
                        onSelect = { route -> switchTab(route) },
                        onCompose = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            newChatOpen = true
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
                        onStories = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            switchTab("chats")
                        },
                        onSettings = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            navController.navigate("settings")
                        },
                    ),
                    moreMenuOpen = moreMenuOpen,
                    onMoreMenuChange = { moreMenuOpen = it },
                )
            }
            Box(Modifier.weight(1f).padding(top = navTopInset)) {
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
                    // Wave 4 stories — the rail is the ONLY stories entry point
                    // (web parity): ring tap = viewer seeded at that author,
                    // "+" tap = composer.
                    onOpenStoriesViewer = { start ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (start != null) {
                            navController.navigate("stories/viewer?start=$start")
                        } else {
                            navController.navigate("stories/viewer")
                        }
                    },
                    onOpenStoriesComposer = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        navController.navigate("stories/compose")
                    },
                    onOpenMentions = { navController.navigate("mentions") },
                    onOpenChannels = { navController.navigate("channels") },
                    // R2-A item 2 — the header phone button opens the REAL calls page.
                    onOpenCalls = { navController.navigate("calls") },
                    // R2-A item 1 — the header pencil opens the shell-hosted composer.
                    onOpenNewChat = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        newChatOpen = true
                    },
                    searchRequest = searchTick,
                )

                // Wave 6 — pulse://invite/<code> lands here as the JoinInviteSheet
                // (web ?join= parity: preview → already-member jump or join → open room).
                val pendingInvite by shell.pendingInvite.collectAsStateWithLifecycle()
                pendingInvite?.let { code ->
                    JoinInviteSheet(
                        code = code,
                        onDismiss = { shell.consumeInvite() },
                        onOpenRoom = { id -> navController.navigate("room/$id") },
                    )
                }
            }
            composable("mentions") {
                Box(Modifier.fillMaxSize()) {
                    MentionsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRoom = { id -> navController.navigate("room/$id") },
                    )
                }
            }
            composable("channels") {
                Box(Modifier.fillMaxSize()) {
                    ChannelsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRoom = { id -> navController.navigate("room/$id") },
                    )
                }
            }
            composable("hub") {
                Box(Modifier.fillMaxSize().padding(bottom = dockSpace)) {
                    app.pulse.feature.hub.HubScreen(
                        viewerName = viewerName ?: "",
                        onOpenConversation = { id -> navController.navigate("room/$id") },
                    )
                }
            }
            composable("contacts") {
                Box(Modifier.fillMaxSize().padding(bottom = dockSpace)) {
                    ContactsScreen(
                        onOpenRoom = { id -> navController.navigate("room/$id") },
                        onOpenUser = { id -> navController.navigate("user/$id") },
                        onOpenAdd = { navController.navigate("contacts/add") },
                        onCallUser = { user ->
                            callVm.callPeer(
                                peerId = user.id,
                                name = user.name,
                                color = user.color,
                                avatar = user.avatar,
                                callerName = viewerName,
                                callerColor = viewerColor,
                            )
                        },
                        // Wave R1-W2D — real video calls (wire kind 'video';
                        // camera prompt handled in ContactsScreen).
                        onVideoCallUser = { user ->
                            callVm.callPeer(
                                peerId = user.id,
                                name = user.name,
                                color = user.color,
                                avatar = user.avatar,
                                callerName = viewerName,
                                callerColor = viewerColor,
                                kind = app.pulse.domain.model.CallKind.VIDEO,
                            )
                        },
                        onOpenCalls = { navController.navigate("calls") },
                    )
                }
            }
            composable("calls") {
                Box(Modifier.fillMaxSize().padding(bottom = dockSpace)) {
                    CallsView(
                        onBack = { navController.popBackStack() },
                        onOpenRoom = { id -> navController.navigate("room/$id") },
                        // R3-B item 5 — redial: the SAME engine path as the contacts
                        // call button, driven by the row's denormalized peer + wire
                        // kind (the engine resolves the DM, iOS R2-D parity).
                        onRedial = { row ->
                            row.peer?.let { peer ->
                                callVm.callPeer(
                                    peerId = peer.id,
                                    name = peer.name,
                                    color = peer.color,
                                    avatar = peer.avatar,
                                    callerName = viewerName,
                                    callerColor = viewerColor,
                                    kind = row.kind,
                                )
                            }
                        },
                    )
                }
            }
            // R2-A item 6/7/8/9 — the room-info surface: automations manager,
            // webhooks manager, screen-security toggles and the photo edit.
            composable(
                "room-info/{conversationId}",
                arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
            ) {
                Box(Modifier.fillMaxSize()) {
                    GroupInfoScreen(
                        onBack = { navController.popBackStack() },
                        onLeft = { navController.popBackStack() },
                    )
                }
            }
            composable("profile") {
                Box(Modifier.fillMaxSize().padding(bottom = dockSpace)) {
                    ProfileScreen(
                        onEditProfile = { navController.navigate("profile/edit") },
                        onOpenBlocked = { navController.navigate("settings/blocked") },
                    )
                }
            }
            // Wave 6 — social graph: user page, add contact, profile edit, blocked list.
            composable(
                "user/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                Box(Modifier.fillMaxSize()) {
                    UserPageScreen(
                        userId = entry.arguments?.getString("id").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenRoom = { id -> navController.navigate("room/$id") },
                    )
                }
            }
            composable("contacts/add") {
                Box(Modifier.fillMaxSize()) {
                    AddContactScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRoom = { id -> navController.navigate("room/$id") },
                    )
                }
            }
            composable("profile/edit") {
                Box(Modifier.fillMaxSize()) {
                    ProfileEditScreen(onBack = { navController.popBackStack() })
                }
            }
            composable("settings/blocked") {
                Box(Modifier.fillMaxSize()) {
                    BlockedListScreen(onBack = { navController.popBackStack() })
                }
            }
            // Wave 8 — the full Settings root + nine sections.
            composable("settings") {
                Box(Modifier.fillMaxSize()) {
                    SettingsRootScreen(
                        onBack = { navController.popBackStack() },
                        onOpenSection = { id -> navController.navigate("settings/$id") },
                        onOpenBlocked = { navController.navigate("settings/blocked") },
                    )
                }
            }
            composable(
                "settings/{section}",
                arguments = listOf(navArgument("section") { type = NavType.StringType }),
            ) { entry ->
                when (entry.arguments?.getString("section")) {
                    "account" -> Box(Modifier.fillMaxSize()) {
                        AccountSection(
                            onBack = { navController.popBackStack() },
                            onEditProfile = { navController.navigate("profile/edit") },
                        )
                    }
                    "appearance" -> Box(Modifier.fillMaxSize()) {
                        AppearanceSection(onBack = { navController.popBackStack() })
                    }
                    "chat" -> Box(Modifier.fillMaxSize()) {
                        ChatSection(onBack = { navController.popBackStack() })
                    }
                    "notifications" -> Box(Modifier.fillMaxSize()) {
                        NotificationsSection(onBack = { navController.popBackStack() })
                    }
                    "privacy" -> Box(Modifier.fillMaxSize()) {
                        PrivacySection(
                            onBack = { navController.popBackStack() },
                            onOpenBlocked = { navController.navigate("settings/blocked") },
                        )
                    }
                    "realtime" -> Box(Modifier.fillMaxSize()) {
                        RealtimeSection(onBack = { navController.popBackStack() })
                    }
                    "accessibility" -> Box(Modifier.fillMaxSize()) {
                        AccessibilitySection(onBack = { navController.popBackStack() })
                    }
                    "data" -> Box(Modifier.fillMaxSize()) {
                        DataSection(onBack = { navController.popBackStack() })
                    }
                    "about" -> Box(Modifier.fillMaxSize()) {
                        AboutSection(onBack = { navController.popBackStack() })
                    }
                    else -> Box(Modifier.fillMaxSize()) {
                        SettingsRootScreen(
                            onBack = { navController.popBackStack() },
                            onOpenSection = { id -> navController.navigate("settings/$id") },
                            onOpenBlocked = { navController.navigate("settings/blocked") },
                        )
                    }
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
                    // Wave 5 voice room entry — header mic + "Voice · N live"
                    // pill, opening the overlay the same way calls open.
                    voiceJoined = voiceState.joined && voiceState.conversationId == conversationId,
                    voiceLiveCount = voiceState.roster.size,
                    onOpenVoiceRoom = { voiceVm.openVoice(conversationId) },
                    // R2-A item 6/7/8/9 — the room-menu "Room info" entry.
                    onOpenRoomInfo = { id -> navController.navigate("room-info/$id") },
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
            // Wave 4 — full-screen stories. The viewer/composer are ROOM-class
            // surfaces (no dock). The nav-entry-scoped StoriesViewModel boots on
            // entry: one fresh REST fetch (REST only — zero socket for stories)
            // + 60s poll while open, so D2 expiry and D3 vanishing reconcile.
            composable(
                "stories/viewer?start={start}",
                arguments = listOf(
                    navArgument("start") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val storiesVm: StoriesViewModel = hiltViewModel()
                LaunchedEffect(Unit) { storiesVm.boot() }
                StoryViewerScreen(
                    startUserId = entry.arguments?.getString("start"),
                    onClose = { navController.popBackStack() },
                    storiesVm = storiesVm,
                )
            }
            composable("stories/compose") {
                val storiesVm: StoriesViewModel = hiltViewModel()
                StoryComposerScreen(
                    onPublished = { navController.popBackStack() },
                    onClose = { navController.popBackStack() },
                    storiesVm = storiesVm,
                )
            }
            }
        }
    }

    // ── R4-B item 3 — the dock dispatch: one shared action bundle (tabs,
    // compose → NewChatSheet, More → Settings/Search/Saved/Stories) over the
    // SAME state for every architecture; the rail renders as a layout
    // sibling above, every other style is an overlay here.
    if (showDock && navStyle != PulseNavStyle.RAIL) {
        val dockActions = DockActions(
            onSelect = { route -> switchTab(route) },
            onCompose = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                // R2-A item 1 — the REAL new-chat composer (was a stub toast).
                newChatOpen = true
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
            onStories = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                switchTab("chats")
            },
            onSettings = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                navController.navigate("settings")
            },
        )
        when (navStyle) {
            PulseNavStyle.CAPSULE -> CapsuleDock(
                modifier = Modifier.align(Alignment.BottomCenter),
                active = if (currentRoute == "archived") "chats" else currentRoute ?: "chats",
                unread = unread,
                dark = dark,
                reducedMotion = reducedMotion,
                onSelect = dockActions.onSelect,
                onCompose = dockActions.onCompose,
                onSearch = dockActions.onSearch,
                onSaved = dockActions.onSaved,
                onStories = dockActions.onStories,
                onSettings = dockActions.onSettings,
                onDeferred = { message -> honest(message) },
                moreMenuOpen = moreMenuOpen,
                onMoreMenuChange = { moreMenuOpen = it },
            )
            PulseNavStyle.FLOATING_TOP -> FloatingTopDock(
                modifier = Modifier.align(Alignment.TopCenter),
                active = if (currentRoute == "archived") "chats" else currentRoute ?: "chats",
                unread = unread,
                dark = dark,
                reducedMotion = reducedMotion,
                actions = dockActions,
                moreMenuOpen = moreMenuOpen,
                onMoreMenuChange = { moreMenuOpen = it },
            )
            PulseNavStyle.PILL -> PillDock(
                modifier = Modifier.align(Alignment.BottomCenter),
                active = if (currentRoute == "archived") "chats" else currentRoute ?: "chats",
                unread = unread,
                dark = dark,
                reducedMotion = reducedMotion,
                actions = dockActions,
                moreMenuOpen = moreMenuOpen,
                onMoreMenuChange = { moreMenuOpen = it },
            )
            PulseNavStyle.BOTTOM_BAR -> BottomBarDock(
                modifier = Modifier.align(Alignment.BottomCenter),
                active = if (currentRoute == "archived") "chats" else currentRoute ?: "chats",
                unread = unread,
                dark = dark,
                actions = dockActions,
                moreMenuOpen = moreMenuOpen,
                onMoreMenuChange = { moreMenuOpen = it },
            )
            PulseNavStyle.TAB_BAR -> TabBarDock(
                modifier = Modifier.align(Alignment.BottomCenter),
                active = if (currentRoute == "archived") "chats" else currentRoute ?: "chats",
                unread = unread,
                dark = dark,
                actions = dockActions,
                moreMenuOpen = moreMenuOpen,
                onMoreMenuChange = { moreMenuOpen = it },
            )
            PulseNavStyle.FLOATING_TAB_BAR -> FloatingTabBarDock(
                modifier = Modifier.align(Alignment.BottomCenter),
                active = if (currentRoute == "archived") "chats" else currentRoute ?: "chats",
                unread = unread,
                dark = dark,
                reducedMotion = reducedMotion,
                actions = dockActions,
                moreMenuOpen = moreMenuOpen,
                onMoreMenuChange = { moreMenuOpen = it },
            )
            PulseNavStyle.ISLAND -> IslandDock(
                modifier = Modifier.align(Alignment.BottomCenter),
                active = if (currentRoute == "archived") "chats" else currentRoute ?: "chats",
                unread = unread,
                dark = dark,
                reducedMotion = reducedMotion,
                actions = dockActions,
                moreMenuOpen = moreMenuOpen,
                onMoreMenuChange = { moreMenuOpen = it },
            )
            PulseNavStyle.RAIL -> Unit // handled as the Row sibling above
        }
    }

        // R2-A item 1 — the shell-hosted new-chat composer (web mounts the
        // NewChatSheet at the shell): dock FAB + chats header pencil both
        // raise it; a create navigates into the room and the Room-cache-backed
        // chats list refreshes itself through the existing flows.
        if (newChatOpen) {
            NewChatSheet(
                viewerId = viewerId,
                onDismiss = { newChatOpen = false },
                onConversationOpened = { id ->
                    newChatOpen = false
                    navController.navigate("room/$id")
                },
                onNotice = { honest(it) },
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

        // R1-W2I — the PiP pane overlay (F-PI-01..03): floats above the
        // NavHost + dock and every pushed room (web mounts PipChat at the
        // shell AND inside the room overlay — the two never co-render, so
        // one always-on instance here is the same net effect). Hidden on
        // the Settings sub-pages (web hides the shell instance while
        // settings is open). Sits BELOW the call/voice overlays.
        if (currentRoute?.startsWith("settings") != true) {
            PipPaneOverlay(
                viewModel = pipVm,
                viewerId = viewerId,
                dark = dark,
                reducedMotion = reducedMotion,
                // R4-B item 3 — keep the draggable panes clear of the LEFT
                // rail band when the rail style is active.
                startInset = if (showDock && navStyle == PulseNavStyle.RAIL) 68.dp else 0.dp,
                onOpenRoom = { id ->
                    // F-PI-03 open bridge — a header tap opens the conversation
                    // in the main shell; a tap inside its own room is a no-op
                    // (web dispatch is equally inert with no shell listener).
                    val currentEntry = navController.currentBackStackEntry
                    val alreadyThere = currentEntry?.destination?.route == "room/{conversationId}" &&
                        currentEntry.arguments?.getString("conversationId") == id
                    if (!alreadyThere) navController.navigate("room/$id")
                },
                onNotice = { honest(it) },
            )
        }

        // Wave 3 — the call overlay owns the WHOLE screen whenever the engine
        // is not idle (ringing/connecting/connected/ended). Renders above the
        // dock and every tab — one call, one surface.
        CallOverlay(callVm)

        // Wave 5 — the voice rooms overlay (voice room / stage / space) sits
        // next to the call overlay; one room surface at a time, engine-owned.
        VoiceRoomsOverlay(voiceVm)
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
    // R4-B item 3 — the reduce-motion idiom is shared by every nav style.
    reducedMotion: Boolean = false,
    onSelect: (String) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onSaved: () -> Unit,
    onStories: () -> Unit,
    onSettings: () -> Unit = {},
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
                        reducedMotion = reducedMotion,
                        modifier = Modifier.weight(1f),
                        onSelect = { onSelect("chats") },
                    )
                    Spacer(Modifier.width(gap))
                    DockTabButton(
                        tab = DOCK_TABS[1],
                        active = active == "hub",
                        unread = 0,
                        dark = dark,
                        reducedMotion = reducedMotion,
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
                        reducedMotion = reducedMotion,
                        modifier = Modifier.weight(1f),
                        onSelect = { onSelect("contacts") },
                    )
                    Spacer(Modifier.width(gap))
                    DockTabButton(
                        tab = DOCK_TABS[3],
                        active = active == "profile",
                        unread = 0,
                        dark = dark,
                        reducedMotion = reducedMotion,
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
                        onStories = onStories,
                        onSettings = onSettings,
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
    // R4-B item 3 — reduce-motion kills the wobble (web useReducedMotion).
    reducedMotion: Boolean = false,
    onSelect: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // web wobble — icon rotates [0, -8, 6, 0]° when a tab becomes active
    val rotate = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active && !reducedMotion) {
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
private fun ComposeDockButton(onCompose: () -> Unit, size: Dp = 46.dp) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(PulsePalette.Emerald, DockTeal600)))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onCompose()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = "New chat", tint = Color.White, modifier = Modifier.size(if (size >= 44.dp) 20.dp else 17.dp))
    }
}

@Composable
private fun MoreDockButton(
    dark: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onSaved: () -> Unit,
    onStories: () -> Unit,
    onSettings: () -> Unit = {},
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
                    onSettings()
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
                    onStories()
                },
            )
        }
    }
}

// ── R4-B item 3 — the navigation architectures (web nav-router.tsx port) ─
//
// Ports of the web renderers, phone-feasible subset only:
//   FloatingTopDock  · nav-router.tsx:466  FloatingTopNav
//   PillDock         · nav-router.tsx:563  PillNav
//   BottomBarDock    · nav-router.tsx:617  BottomBar
//   TabBarDock       · nav-router.tsx:664  TabBarNav
//   FloatingTabBarDock · nav-router.tsx:719 FloatingTabBar
//   RailDock         · nav-router.tsx:873  RailNav
//   IslandDock       · nav-router.tsx:925  IslandNav
// (capsule IS the existing CapsuleDock; floating-dock / command-bar / radial
// / gesture / contextual-dock stay web-only — desktop/keyboard/exotic, see
// protocol PulseNavStyle.EXCLUDED_ON_PHONE.)
//
// Every style keeps the SAME 5 slots (Chats/Hub/Compose-FAB/Contacts/More)
// over the SAME state: unread badge (99+ cap), direction-aware tab
// transitions, snackbar channel, PiP rules, NewChatSheet + More menu, the
// PulseMotion springs and the reduce-motion + haptics idioms.

/** Shared dock callbacks — every architecture drives the identical state. */
private data class DockActions(
    val onSelect: (String) -> Unit,
    val onCompose: () -> Unit,
    val onSearch: () -> Unit,
    val onSaved: () -> Unit,
    val onStories: () -> Unit,
    val onSettings: () -> Unit,
)

private val dockActiveTint = DockEmerald600
private fun dockInactiveTint(dark: Boolean) = if (dark) DockInactiveDark else DockInactiveLight

/** Pill fill + top-bar glass colors (web GLASS_PANEL is pulseGlass here). */
private fun dockGlassShape(radius: Dp) = RoundedCornerShape(radius)

/** Simple icon+label tab for the flat bar styles (bottom-bar/tab-bar family). */
@Composable
private fun BarTabItem(
    tab: DockTab,
    active: Boolean,
    unread: Int,
    dark: Boolean,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    onSelect: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val tint = if (active) dockActiveTint else dockInactiveTint(dark)
    Column(
        modifier
            .clip(dockGlassShape(16.dp))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (active) tab.activeIcon else tab.inactiveIcon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
            if (tab.carriesUnread && unread > 0) DockUnreadBadge(unread, dark)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            tab.label,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = tint,
        )
    }
}

// ── 2 · floating-top — glass capsule pinned beneath the top edge ─────

@Composable
private fun FloatingTopDock(
    modifier: Modifier = Modifier,
    active: String,
    unread: Int,
    dark: Boolean,
    reducedMotion: Boolean,
    actions: DockActions,
    moreMenuOpen: Boolean,
    onMoreMenuChange: (Boolean) -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp),
    ) {
        BoxWithConstraints {
            val pad = 6.dp
            val gap = 4.dp
            val composeW = 40.dp
            val slotW = (maxWidth - pad * 2 - composeW - 40.dp - gap * 5) / 4
            val tabIndex = TAB_ROUTES.indexOf(active).coerceAtLeast(0)
            val pillX = pad + (slotW + gap) * tabIndex + (if (tabIndex >= 2) composeW + gap else 0.dp)
            val pillXAnim by animateDpAsState(
                targetValue = pillX,
                animationSpec = if (reducedMotion) snap() else PulseMotion.snappy(),
                label = "topDockPill",
            )
            Box(Modifier.fillMaxWidth().pulseGlass(dark, RoundedCornerShape(26.dp))) {
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
                    Modifier.fillMaxWidth().padding(pad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DockTabButton(
                        tab = DOCK_TABS[0], active = active == "chats", unread = unread,
                        dark = dark, reducedMotion = reducedMotion,
                        modifier = Modifier.weight(1f), onSelect = { actions.onSelect("chats") },
                    )
                    Spacer(Modifier.width(gap))
                    DockTabButton(
                        tab = DOCK_TABS[1], active = active == "hub", unread = 0,
                        dark = dark, reducedMotion = reducedMotion,
                        modifier = Modifier.weight(1f), onSelect = { actions.onSelect("hub") },
                    )
                    Spacer(Modifier.width(gap))
                    ComposeDockButton(actions.onCompose, size = composeW)
                    Spacer(Modifier.width(gap))
                    DockTabButton(
                        tab = DOCK_TABS[2], active = active == "contacts", unread = 0,
                        dark = dark, reducedMotion = reducedMotion,
                        modifier = Modifier.weight(1f), onSelect = { actions.onSelect("contacts") },
                    )
                    Spacer(Modifier.width(gap))
                    DockTabButton(
                        tab = DOCK_TABS[3], active = active == "profile", unread = 0,
                        dark = dark, reducedMotion = reducedMotion,
                        modifier = Modifier.weight(1f), onSelect = { actions.onSelect("profile") },
                    )
                    Spacer(Modifier.width(gap))
                    MoreDockButton(
                        dark = dark,
                        open = moreMenuOpen,
                        onOpenChange = onMoreMenuChange,
                        onSearch = actions.onSearch,
                        onSaved = actions.onSaved,
                        onStories = actions.onStories,
                        onSettings = actions.onSettings,
                        onDeferred = {},
                    )
                }
            }
        }
    }
}

// ── 4 · pill — single segmented pill with sliding emerald fill ───────

@Composable
private fun PillTabItem(
    tab: DockTab,
    active: Boolean,
    unread: Int,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // web PillNav: the active segment sits ON the emerald fill → white ink.
    val tint = if (active) Color.White else dockInactiveTint(dark)
    Column(
        modifier
            .height(44.dp)
            .clip(dockGlassShape(999.dp))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (active) tab.activeIcon else tab.inactiveIcon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size(17.dp),
            )
            if (tab.carriesUnread && unread > 0) DockUnreadBadge(unread, dark)
        }
        Spacer(Modifier.height(1.dp))
        Text(tab.label, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
private fun PillDock(
    modifier: Modifier = Modifier,
    active: String,
    unread: Int,
    dark: Boolean,
    reducedMotion: Boolean,
    actions: DockActions,
    moreMenuOpen: Boolean,
    onMoreMenuChange: (Boolean) -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 12.dp),
    ) {
        BoxWithConstraints {
            val pad = 4.dp
            val composeW = 40.dp
            val slotW = (maxWidth - pad * 2 - composeW - 40.dp) / 4
            val tabIndex = TAB_ROUTES.indexOf(active).coerceAtLeast(0)
            val pillX = pad + slotW * tabIndex + (if (tabIndex >= 2) composeW else 0.dp)
            val fillX by animateDpAsState(
                targetValue = pillX,
                animationSpec = if (reducedMotion) snap() else PulseMotion.snappy(),
                label = "pillFill",
            )
            Box(Modifier.fillMaxWidth().pulseGlass(dark, RoundedCornerShape(999.dp))) {
                // web nav-pill-fill: emerald-600→teal-600 sliding segment
                Box(
                    Modifier
                        .offset(x = fillX, y = pad)
                        .size(width = slotW, height = 44.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Brush.horizontalGradient(listOf(DockEmerald600, DockTeal600))),
                )
                Row(
                    Modifier.fillMaxWidth().padding(pad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillTabItem(DOCK_TABS[0], active == "chats", unread, dark, Modifier.weight(1f)) { actions.onSelect("chats") }
                    PillTabItem(DOCK_TABS[1], active == "hub", 0, dark, Modifier.weight(1f)) { actions.onSelect("hub") }
                    ComposeDockButton(actions.onCompose, size = composeW)
                    PillTabItem(DOCK_TABS[2], active == "contacts", 0, dark, Modifier.weight(1f)) { actions.onSelect("contacts") }
                    PillTabItem(DOCK_TABS[3], active == "profile", 0, dark, Modifier.weight(1f)) { actions.onSelect("profile") }
                    MoreDockButton(
                        dark = dark,
                        open = moreMenuOpen,
                        onOpenChange = onMoreMenuChange,
                        onSearch = actions.onSearch,
                        onSaved = actions.onSaved,
                        onStories = actions.onStories,
                        onSettings = actions.onSettings,
                        onDeferred = {},
                    )
                }
            }
        }
    }
}

// ── 5/6 · bottom-bar + tab-bar — edge-to-edge bars ───────────────────

@Composable
private fun BarDockChrome(
    modifier: Modifier = Modifier,
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        // web border-t (zinc-200/70 · white/10)
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(if (dark) Color(0x1AFFFFFF) else Color(0x66E4E4E7)),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .background(if (dark) Color(0xE618181B) else Color(0xF2FFFFFF)),
        ) {
            content()
        }
    }
}

@Composable
private fun BottomBarDock(
    modifier: Modifier = Modifier,
    active: String,
    unread: Int,
    dark: Boolean,
    actions: DockActions,
    moreMenuOpen: Boolean,
    onMoreMenuChange: (Boolean) -> Unit,
) {
    BarDockChrome(modifier, dark) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val pad = 6.dp
            val fixedW = 40.dp
            val slotW = (maxWidth - pad * 2 - fixedW * 2) / 4
            val tabIndex = TAB_ROUTES.indexOf(active).coerceAtLeast(0)
            // web nav-bottombar-dot: 3×32 emerald bar sliding at the top edge
            val dotX by animateDpAsState(
                targetValue = pad + slotW * tabIndex + (slotW - 32.dp) / 2,
                animationSpec = PulseMotion.snappy(),
                label = "bottomBarDot",
            )
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .offset(x = dotX)
                        .width(32.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(bottomStart = 999.dp, bottomEnd = 999.dp))
                        .background(DockEmerald600),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = pad, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BarTabItem(DOCK_TABS[0], active == "chats", unread, dark, Modifier.weight(1f)) { actions.onSelect("chats") }
                    BarTabItem(DOCK_TABS[1], active == "hub", 0, dark, Modifier.weight(1f)) { actions.onSelect("hub") }
                    BarTabItem(DOCK_TABS[2], active == "contacts", 0, dark, Modifier.weight(1f)) { actions.onSelect("contacts") }
                    BarTabItem(DOCK_TABS[3], active == "profile", 0, dark, Modifier.weight(1f)) { actions.onSelect("profile") }
                    Spacer(Modifier.width(4.dp))
                    ComposeDockButton(actions.onCompose, size = 38.dp)
                    MoreDockButton(
                        dark = dark,
                        open = moreMenuOpen,
                        onOpenChange = onMoreMenuChange,
                        onSearch = actions.onSearch,
                        onSaved = actions.onSaved,
                        onStories = actions.onStories,
                        onSettings = actions.onSettings,
                        onDeferred = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun TabBarDock(
    modifier: Modifier = Modifier,
    active: String,
    unread: Int,
    dark: Boolean,
    actions: DockActions,
    moreMenuOpen: Boolean,
    onMoreMenuChange: (Boolean) -> Unit,
) {
    BarDockChrome(modifier, dark) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DOCK_TABS.forEach { tab ->
                val isActive = active == tab.route
                Box(
                    Modifier
                        .weight(1f)
                        .height(50.dp)
                        .then(
                            if (isActive) {
                                // web nav-tabbar-squircle: emerald tint + ring
                                Modifier
                                    .clip(dockGlassShape(16.dp))
                                    .background(PulsePalette.Emerald.copy(alpha = if (dark) 0.10f else 0.15f))
                                    .border(1.dp, PulsePalette.Emerald.copy(alpha = 0.25f), dockGlassShape(16.dp))
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    BarTabItem(
                        tab = tab,
                        active = isActive,
                        unread = if (tab.carriesUnread) unread else 0,
                        dark = dark,
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { actions.onSelect(tab.route) },
                    )
                }
            }
            ComposeDockButton(actions.onCompose, size = 38.dp)
            MoreDockButton(
                dark = dark,
                open = moreMenuOpen,
                onOpenChange = onMoreMenuChange,
                onSearch = actions.onSearch,
                onSaved = actions.onSaved,
                onStories = actions.onStories,
                onSettings = actions.onSettings,
                onDeferred = {},
            )
        }
    }
}

// ── 7 · floating-tab-bar — detached elevated card, active tab lifted ─

@Composable
private fun FloatingTabBarDock(
    modifier: Modifier = Modifier,
    active: String,
    unread: Int,
    dark: Boolean,
    reducedMotion: Boolean,
    actions: DockActions,
    moreMenuOpen: Boolean,
    onMoreMenuChange: (Boolean) -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
    ) {
        BoxWithConstraints {
            val pad = 8.dp
            val gap = 6.dp
            val slotW = (maxWidth - pad * 2 - 46.dp - 40.dp - gap * 5) / 4
            val tabIndex = TAB_ROUTES.indexOf(active).coerceAtLeast(0)
            val cardX = pad + (slotW + gap) * tabIndex + (if (tabIndex >= 2) 46.dp + gap else 0.dp)
            val cardXAnim by animateDpAsState(
                targetValue = cardX,
                animationSpec = if (reducedMotion) snap() else PulseMotion.snappy(),
                label = "fTabCard",
            )
            Box(Modifier.fillMaxWidth().pulseGlass(dark, RoundedCornerShape(26.dp))) {
                // web nav-ftab-card: elevated white/zinc card behind the active tab
                Box(
                    Modifier
                        .offset(x = cardXAnim, y = pad)
                        .size(width = slotW, height = 54.dp)
                        .shadow(6.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (dark) {
                                Brush.verticalGradient(listOf(Color(0xFF1F1F23), Color(0xFF141416)))
                            } else {
                                Brush.verticalGradient(listOf(Color.White, Color(0xFFFAFAFA)))
                            },
                        )
                        .border(1.dp, PulsePalette.Emerald.copy(alpha = 0.30f), RoundedCornerShape(20.dp)),
                )
                Row(
                    Modifier.fillMaxWidth().padding(pad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DOCK_TABS.forEach { tab ->
                        val isActive = active == tab.route
                        // web animate y: isActive ? -4 : 0 + scale 1.02
                        val lift by animateDpAsState(
                            targetValue = if (isActive) (-4).dp else 0.dp,
                            animationSpec = if (reducedMotion) snap() else PulseMotion.bouncy(),
                            label = "fTabLift",
                        )
                        val scale by animateFloatAsState(
                            targetValue = if (isActive) 1.02f else 1f,
                            animationSpec = if (reducedMotion) snap() else PulseMotion.bouncy(),
                            label = "fTabScale",
                        )
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            BarTabItem(
                                tab = tab,
                                active = isActive,
                                unread = if (tab.carriesUnread) unread else 0,
                                dark = dark,
                                modifier = Modifier
                                    .offset(y = lift)
                                    .graphicsLayer { scaleX = scale; scaleY = scale },
                                iconSize = 21.dp,
                                onSelect = { actions.onSelect(tab.route) },
                            )
                        }
                    }
                    Spacer(Modifier.width(gap))
                    ComposeDockButton(actions.onCompose, size = 46.dp)
                    MoreDockButton(
                        dark = dark,
                        open = moreMenuOpen,
                        onOpenChange = onMoreMenuChange,
                        onSearch = actions.onSearch,
                        onSaved = actions.onSaved,
                        onStories = actions.onStories,
                        onSettings = actions.onSettings,
                        onDeferred = {},
                    )
                }
            }
        }
    }
}

// ── 9 · rail — persistent LEFT side rail, content insets by weight ───

@Composable
private fun RailTabItem(
    tab: DockTab,
    active: Boolean,
    unread: Int,
    dark: Boolean,
    onSelect: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val tint = if (active) dockActiveTint else dockInactiveTint(dark)
    Column(
        Modifier
            .width(56.dp)
            .clip(dockGlassShape(14.dp))
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect()
            }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (active) tab.activeIcon else tab.inactiveIcon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            if (tab.carriesUnread && unread > 0) DockUnreadBadge(unread, dark)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            tab.label,
            fontSize = 9.sp,
            lineHeight = 9.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = tint,
        )
    }
}

@Composable
private fun RailDock(
    active: String,
    unread: Int,
    dark: Boolean,
    reducedMotion: Boolean,
    actions: DockActions,
    moreMenuOpen: Boolean,
    onMoreMenuChange: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(68.dp)
            .background(if (dark) Color(0xCC111113) else Color(0xCCFFFFFF))
            .statusBarsPadding()
            .padding(top = 12.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // web rail "P" logo tile
        Box(
            Modifier
                .size(36.dp)
                .shadow(4.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(PulsePalette.Emerald, DockTeal600))),
            contentAlignment = Alignment.Center,
        ) {
            Text("P", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(12.dp))
        DOCK_TABS.forEach { tab ->
            val isActive = active == tab.route
            Box {
                if (isActive && !reducedMotion) {
                    // web nav-rail-bar: 4×28 emerald bar hugging the rail's edge
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = (-5).dp)
                            .size(width = 4.dp, height = 28.dp)
                            .clip(RoundedCornerShape(topEnd = 999.dp, bottomEnd = 999.dp))
                            .background(DockEmerald600),
                    )
                }
                RailTabItem(tab, isActive, if (tab.carriesUnread) unread else 0, dark) {
                    actions.onSelect(tab.route)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ComposeDockButton(actions.onCompose, size = 44.dp)
        Spacer(Modifier.height(6.dp))
        MoreDockButton(
            dark = dark,
            open = moreMenuOpen,
            onOpenChange = onMoreMenuChange,
            onSearch = actions.onSearch,
            onSaved = actions.onSaved,
            onStories = actions.onStories,
            onSettings = actions.onSettings,
            onDeferred = {},
        )
    }
}

// ── 10 · island — dynamic-island pill that expands on tap ────────────

@Composable
private fun IslandDock(
    modifier: Modifier = Modifier,
    active: String,
    unread: Int,
    dark: Boolean,
    reducedMotion: Boolean,
    actions: DockActions,
    moreMenuOpen: Boolean,
    onMoreMenuChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // web auto-collapse (4200 ms), paused while the More menu is open —
    // the menu lives inside the expanded island and must not vanish.
    LaunchedEffect(expanded, moreMenuOpen) {
        if (expanded && !moreMenuOpen) {
            kotlinx.coroutines.delay(4200)
            expanded = false
        }
    }
    val activeTab = DOCK_TABS.firstOrNull { it.route == active } ?: DOCK_TABS[0]
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints {
            val target = if (expanded) minOf(maxWidth - 24.dp, 380.dp) else 148.dp
            val width by animateDpAsState(
                targetValue = target,
                animationSpec = if (reducedMotion) snap() else PulseMotion.snappy(),
                label = "islandWidth",
            )
            Row(
                Modifier
                    .width(width)
                    .heightIn(min = 54.dp)
                    .pulseGlass(dark, RoundedCornerShape(999.dp))
                    .clip(RoundedCornerShape(999.dp))
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        expanded = !expanded
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics {
                        contentDescription = if (expanded) {
                            "Navigation — collapse"
                        } else {
                            "Navigation — ${activeTab.label}, tap to expand"
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!expanded) {
                    // collapsed: active icon + label + grip (web island-closed)
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (active == activeTab.route) activeTab.activeIcon else activeTab.inactiveIcon,
                            contentDescription = null,
                            tint = dockActiveTint,
                            modifier = Modifier.size(22.dp),
                        )
                        if (activeTab.carriesUnread && unread > 0) DockUnreadBadge(unread, dark)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(activeTab.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (dark) Color(0xFFE4E4E7) else Color(0xFF27272A))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.DragHandle, contentDescription = null, tint = if (dark) DockInactiveDark else DockInactiveLight, modifier = Modifier.size(16.dp))
                } else {
                    // expanded: 5 slots + More (web island-open, compose added
                    // for the shared 5-slot contract)
                    DOCK_TABS.forEach { tab ->
                        val isActive = active == tab.route
                        Box(
                            Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(dockGlassShape(16.dp))
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    actions.onSelect(tab.route)
                                    expanded = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isActive) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(4.dp)
                                        .clip(dockGlassShape(14.dp))
                                        .background(PulsePalette.Emerald.copy(alpha = 0.18f))
                                        .border(1.dp, PulsePalette.Emerald.copy(alpha = 0.30f), dockGlassShape(14.dp)),
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isActive) tab.activeIcon else tab.inactiveIcon,
                                        contentDescription = tab.label,
                                        tint = if (isActive) dockActiveTint else dockInactiveTint(dark),
                                        modifier = Modifier.size(20.dp),
                                    )
                                    if (tab.carriesUnread && unread > 0) DockUnreadBadge(unread, dark)
                                }
                                Text(
                                    tab.label,
                                    fontSize = 9.sp,
                                    lineHeight = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isActive) dockActiveTint else if (dark) Color(0xFFD4D4D8) else Color(0xFF52525B),
                                )
                            }
                        }
                    }
                    ComposeDockButton(actions.onCompose, size = 40.dp)
                    MoreDockButton(
                        dark = dark,
                        open = moreMenuOpen,
                        onOpenChange = onMoreMenuChange,
                        onSearch = actions.onSearch,
                        onSaved = actions.onSaved,
                        onStories = actions.onStories,
                        onSettings = actions.onSettings,
                        onDeferred = {},
                    )
                }
            }
        }
    }
}
