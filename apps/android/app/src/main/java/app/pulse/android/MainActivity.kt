package app.pulse.android

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.fragment.app.FragmentActivity
import app.pulse.android.ui.AmbientField
import app.pulse.android.ui.FxMode
import app.pulse.android.ui.ParticleBurstHost
import app.pulse.domain.repository.PulseRepository
import app.pulse.protocol.PulseNavStyle
import app.pulse.feature.calls.CallOverlay
import app.pulse.feature.calls.CallViewModel
import app.pulse.feature.calls.GroupCallBanner
import app.pulse.feature.calls.GroupCallOverlay
import app.pulse.feature.calls.GroupCallViewModel
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
import app.pulse.ui.EmberPalette
import app.pulse.ui.PulseIcons
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import app.pulse.ui.PulseTheme
import app.pulse.ui.emberBackdrop
import app.pulse.ui.pulseTabBackdrop
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

// EMB-A: the dock signal pair is now the ember gradient (amber -> deep);
// the names keep their historical slot so every nav style inherits the swap.
private val DockEmerald600 = Color(0xFFFFB86B)
private val DockTeal600 = Color(0xFFFF7A3D)
// EMB-A: inactive chrome is white 45% on the warm backdrop.
private val DockInactiveDark = Color.White.copy(alpha = 0.45f)
private val DockInactiveLight = Color(0xFF71717A)

/** Canonical tab order - drives dock layout + direction-aware transitions.
 *  Reference dock: Chats / Call / Updates / Profile. Contacts left the pill
 *  but stays a registered route reachable from the chats header menu. */
private val TAB_ROUTES = listOf("chats", "calls", "hub", "profile")

private data class DockTab(
    val route: String,
    val label: String,
    val activeIcon: ImageVector,
    val inactiveIcon: ImageVector,
    val carriesUnread: Boolean = false,
)

/** Registry parity with web NAV_ITEMS (nav-router.ts) - EMB-A PulseIcons voice.
 *  The dock speaks the reference labels: Chats / Call / Updates / Profile. */
private val DOCK_TABS = listOf(
    DockTab("chats", "Chats", PulseIcons.ChatBubble, PulseIcons.ChatBubble, carriesUnread = true),
    DockTab("calls", "Call", PulseIcons.Phone, PulseIcons.Phone),
    DockTab("hub", "Updates", PulseIcons.Refresh, PulseIcons.Refresh),
    DockTab("profile", "Profile", PulseIcons.Person, PulseIcons.Person),
)

/**
 * R10-a - launcher-shortcut request. [tab] is a validated TAB_ROUTES entry;
 * [action] is one of the SHORTCUT_ACTION_* ids (or null for plain tab jumps).
 */
data class ShortcutRequest(val tab: String? = null, val action: String? = null)

/** R10-a - extras + actions shared with res/xml/shortcuts.xml. */
const val ACTION_SHORTCUT = "app.pulse.android.action.SHORTCUT"
const val EXTRA_SHORTCUT_TAB = "pulse.shortcut.tab"
const val EXTRA_SHORTCUT_ACTION = "pulse.shortcut.action"
const val SHORTCUT_ACTION_NEW_MESSAGE = "new_message"
const val SHORTCUT_ACTION_SEARCH = "search"

/** Dock-scoped state - live unread total for the Chats badge (web useUnread). */
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

    // Wave 6 - a pulse://invite code lands here and the chats surface raises
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
class MainActivity : FragmentActivity() {

    @Inject lateinit var repository: app.pulse.domain.repository.PulseRepository

    /** R10-a - biometric App lock: state holder + prompt presenter. */
    @Inject lateinit var appLock: app.pulse.android.security.PulseAppLock

    /** Wave 6 - pulse:// deep links (invite/user/room); consumed by the shell. */
    private val deepLinks = MutableStateFlow<app.pulse.core.link.PulseDeepLink?>(null)

    /** R10-a - the share-in payload forwarded by ShareInActivity (null = none). */
    private val shareIn = MutableStateFlow<ShareInPayload?>(null)

    /** R10-a - launcher-shortcut tab/action requests (null = none pending). */
    private val shortcutRequest = MutableStateFlow<ShortcutRequest?>(null)

    /** Wave 7 - POST_NOTIFICATIONS launcher (must register before STARTED). */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Honest: denial keeps in-app surfaces live, no reminders as push.
        }

    /** Wave 7 reminders due-loop request (30 s foreground, web useReminderDueLoop parity). */
    private var reminderLoopStarted = false

    override fun onStart() {
        super.onStart()
        // R6 - M4: the incoming-attention gate is foreground-only (single
        // activity → this is app-level truth).
        app.pulse.android.notify.IncomingAttention.foreground = true
        // Foreground outbox trigger (Wave 0): whatever queued while the app
        // was dead/backgrounded drains the moment the surface is up.
        lifecycleScope.launch { runCatching { repository.flushOutbox() } }
        // Wave 7 - ask for the notifications permission ONCE (API 33+), then
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
                                // R2-C item 6 - the tap deep-links into the chat.
                                // R7 item 4 - the anchored message rides along so
                                // the room auto-jumps + flashes on open.
                                item.conversationId.ifBlank { null },
                                item.messageId,
                            )
                            runCatching { repository.resolveReminder(item.id) }
                        }
                    }
                    delay(30_000)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        app.pulse.android.notify.IncomingAttention.foreground = false
    }

    override fun onResume() {
        super.onResume()
        // R10-a - App lock: whenever Pulse reaches the foreground while the
        // toggle is on and this session hasn't unlocked, the BiometricPrompt
        // goes up over everything (the Compose gate overlay backs it up).
        appLock.onHostResume(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // R50-e - the modern launch: branded splash (windowSplashScreenBackground
        // carbon) that hands off to Theme.Pulse via postSplashScreenTheme.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // True edge-to-edge with NO system scrims: the app surface (ambient field)
        // shows behind the status bar AND the navigation bar - the default
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
            PulseRoot(
                deepLink = deepLinks.collectAsStateWithLifecycle().value,
                onConsumeDeepLink = { deepLinks.value = null },
                repository = repository,
                // R10-a - lock gate + share-in + shortcut requests.
                appLock = appLock,
                onRequestUnlock = { appLock.presentPrompt(this) },
                shareInFlow = shareIn,
                onConsumeShareIn = { shareIn.value = null },
                shortcutFlow = shortcutRequest,
                onConsumeShortcut = { shortcutRequest.value = null },
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * R10-a - one landing place for every external intent shape: pulse://
     * deep links (Wave 6), the share-in hand-off from ShareInActivity, and
     * the launcher-shortcut tab/action extras. Unknown shapes stay no-ops.
     */
    private fun handleIntent(intent: android.content.Intent?) {
        deepLinks.value = app.pulse.core.link.PulseDeepLink.parse(intent?.dataString)
        when (intent?.action) {
            ShareInActivity.ACTION_SHARE_IN -> {
                val text = intent.getStringExtra(ShareInActivity.EXTRA_SHARE_TEXT)
                val stream = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(ShareInActivity.EXTRA_SHARE_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(ShareInActivity.EXTRA_SHARE_STREAM) as? android.net.Uri
                }
                shareIn.value = if (text.isNullOrBlank() && stream == null) {
                    // Unreadable payload - say so and close, no half state.
                    android.widget.Toast.makeText(
                        this,
                        "Couldn't read the shared content",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    null
                } else {
                    ShareInPayload(text = text?.takeIf { it.isNotBlank() }, stream = stream)
                }
            }

            ACTION_SHORTCUT -> {
                shortcutRequest.value = ShortcutRequest(
                    tab = intent.getStringExtra(EXTRA_SHORTCUT_TAB)?.takeIf { it in TAB_ROUTES },
                    action = intent.getStringExtra(EXTRA_SHORTCUT_ACTION),
                )
            }
        }
    }
}

@Composable
fun PulseRoot(
    deepLink: app.pulse.core.link.PulseDeepLink? = null,
    onConsumeDeepLink: () -> Unit = {},
    // R8 Task 3-c - the repository rides down to the shell so the FCM
    // registration can sync the moment a viewer identity exists.
    repository: app.pulse.domain.repository.PulseRepository,
    session: SessionViewModel = hiltViewModel(),
    // R10-a - biometric App lock gate (covers onboarding, the shell and the
    // share-in sheet alike - the whole UI sits behind it while locked).
    appLock: app.pulse.android.security.PulseAppLock,
    onRequestUnlock: () -> Unit,
    // R10-a - share-in payload + launcher-shortcut requests from the activity.
    shareInFlow: StateFlow<ShareInPayload?>,
    onConsumeShareIn: () -> Unit,
    shortcutFlow: StateFlow<ShortcutRequest?>,
    onConsumeShortcut: () -> Unit,
) {
    val viewerId by session.viewerId.collectAsStateWithLifecycle()
    val hydrated by session.hydrated.collectAsStateWithLifecycle()
    val fxRaw by session.fxMode.collectAsStateWithLifecycle()
    val darkRaw by session.darkOverride.collectAsStateWithLifecycle()
    val uiThemeRaw by session.uiTheme.collectAsStateWithLifecycle()
    val reduced by session.reducedMotion.collectAsStateWithLifecycle()
    val viewerName by session.viewerName.collectAsStateWithLifecycle()
    val storedBase by session.serverBase.collectAsStateWithLifecycle()
    // Cold-start race guard (R54): PulseApplication applies the stored gateway
    // asynchronously - if composition lands first, re-apply the persisted base
    // here so the native mirror mounts with the gateway on the very first launch.
    LaunchedEffect(viewerName, storedBase) {
        if (!viewerName.isNullOrBlank() && !app.pulse.core.PulseEndpoints.isConfigured && !storedBase.isNullOrBlank()) {
            app.pulse.core.PulseEndpoints.applyBase(storedBase)
        }
    }

    // R76 - native entry bridges (the retired shells owned these): a
    // pulse://room deep link opens the room once the live cache knows it and
    // launcher tab shortcuts switch tabs cold or warm. User deep links open
    // the profile page, invite codes mount the join sheet, shortcut actions
    // open the composer or search, and share-in mounts the share sheet - all
    // over the mirror, all self-consuming.
    var pendingRoomId by remember { mutableStateOf<String?>(null) }
    var pendingJumpMessageId by remember { mutableStateOf<String?>(null) }
    var pendingUserId by remember { mutableStateOf<String?>(null) }
    var pendingInvite by remember { mutableStateOf<String?>(null) }
    var pendingTab by remember { mutableStateOf<app.pulse.android.mirror.MirrorTab?>(null) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        when (link) {
            is app.pulse.core.link.PulseDeepLink.Room -> {
                pendingRoomId = link.conversationId
                pendingJumpMessageId = link.jumpMessageId
            }
            is app.pulse.core.link.PulseDeepLink.User -> pendingUserId = link.userId
            is app.pulse.core.link.PulseDeepLink.Invite -> pendingInvite = link.code
            null -> {}
        }
        onConsumeDeepLink()
    }
    val shortcutRequest = shortcutFlow.collectAsStateWithLifecycle().value
    LaunchedEffect(shortcutRequest) {
        val request = shortcutRequest ?: return@LaunchedEffect
        pendingTab = when (request.tab) {
            "chats" -> app.pulse.android.mirror.MirrorTab.Chats
            "hub" -> app.pulse.android.mirror.MirrorTab.Hub
            "profile" -> app.pulse.android.mirror.MirrorTab.Profile
            else -> null
        }
        pendingAction = request.action
        onConsumeShortcut()
    }
    // R2-C item 3 - the selected design language (web pulse.uiTheme.v2).
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
                // R76 - the mirror is the ONLY shell on every build (the
                // WebView artboard is deleted): mirror surfaces are ALWAYS
                // dark, so the system icons stay light everywhere.
                val lightIcons = false
                isAppearanceLightStatusBars = lightIcons
                isAppearanceLightNavigationBars = lightIcons
            }
        }
    }

    // Boot the live layer (REST refresh + socket join) as soon as identity exists.
    LaunchedEffect(viewerId) { session.bootstrap(viewerId) }

    // R7 item 8 - the session-rotated re-login notice (SessionViewModel sets
    // it when the stored token was rejected) finally reaches the onboarding
    // screen it was always meant for.
    val sessionNotice by session.sessionNotice.collectAsStateWithLifecycle()

    // Web parity: no viewer identity → the onboarding IS the app (name →
    // live @handle picker). Wait for prefs hydration to avoid a flash.
    val onboarding = hydrated && viewerId == null

    PulseTheme(darkTheme = dark, uiTheme = uiTheme) {
        Box(
            Modifier
                .fillMaxSize()
                // R2-C item 3 - the design-language page backdrop (aurora
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
                OnboardingScreen(
                    sessionNotice = sessionNotice,
                    // R50-a - the connect gate adopts the probed gateway through
                    // the session (PulseEndpoints + prefs persistence).
                    onApplyServerBase = { base -> session.setServerBase(base) },
                )
            } else {
                // R76 - the native mirror IS the app on EVERY build: the
                // WebView artboard shell is deleted (user directive - the app
                // is built in Kotlin, never in the web language). The design
                // is lifted 1:1 from the web source, every tap is wired to a
                // real gateway action, and the room header video/phone icons
                // dial real 1:1/group calls through the same call engines.
                val callVm: CallViewModel = hiltViewModel()
                val groupCallVm: GroupCallViewModel = hiltViewModel()
                app.pulse.android.mirror.MirrorRoot(
                    session = session,
                    repository = repository,
                    onStartCall = { convo, video ->
                        val viewer = viewerName.orEmpty().ifBlank { "You" }
                        if (convo.isGroupish) {
                            groupCallVm.setActiveConversation(convo.id, convo.title)
                            groupCallVm.startCall(
                                if (video) app.pulse.domain.model.CallKind.VIDEO else app.pulse.domain.model.CallKind.VOICE,
                                convo.title,
                            )
                        } else {
                            val peer = convo.members.firstOrNull { it.id != viewerId.orEmpty() }
                            callVm.startOutgoing(
                                peerId = peer?.id ?: convo.otherUserId.orEmpty(),
                                name = convo.title,
                                color = convo.accentColor,
                                avatar = convo.avatar,
                                callerName = viewer,
                                kind = if (video) app.pulse.domain.model.CallKind.VIDEO else app.pulse.domain.model.CallKind.VOICE,
                            )
                        }
                    },
                    tabRequest = pendingTab,
                    onConsumeTabRequest = { pendingTab = null },
                    pendingRoomId = pendingRoomId,
                    onConsumePendingRoom = { pendingRoomId = null },
                    pendingJumpMessageId = pendingJumpMessageId,
                    onConsumePendingJumpMessage = { pendingJumpMessageId = null },
                    pendingUserId = pendingUserId,
                    onConsumePendingUser = { pendingUserId = null },
                    shortcutAction = pendingAction,
                    onConsumeShortcutAction = { pendingAction = null },
                )
                CallOverlay(callVm)
                GroupCallOverlay(groupCallVm)
                // R76 - the same native entry sheets the retired shells used,
                // now mounted over the mirror: share-in hand-off + invite join.
                shareInFlow.collectAsStateWithLifecycle().value?.let { payload ->
                    ShareInSheet(
                        payload = payload,
                        onDismiss = onConsumeShareIn,
                        onOpenRoom = { id ->
                            onConsumeShareIn()
                            pendingRoomId = id
                        },
                    )
                }
                pendingInvite?.let { code ->
                    JoinInviteSheet(
                        code = code,
                        onDismiss = { pendingInvite = null },
                        onOpenRoom = { id ->
                            pendingInvite = null
                            pendingRoomId = id
                        },
                    )
                }
            }

            ParticleBurstHost(
                Modifier.fillMaxSize(),
                reducedMotion = reduced,
            )

            // R10-a - the App lock gate draws LAST: a full-screen overlay over
            // onboarding/shell/particles so nothing behind it is reachable.
            if (appLock.locked.collectAsStateWithLifecycle().value) {
                AppLockGate(appLock = appLock, onRequestUnlock = onRequestUnlock)
            }
        }
    }
}

private fun AppLockGate(
    appLock: app.pulse.android.security.PulseAppLock,
    onRequestUnlock: () -> Unit,
) {
    val notice by appLock.failureNotice.collectAsStateWithLifecycle()
    val backoffUntilMs by appLock.backoffUntilMs.collectAsStateWithLifecycle()
    val unlockImpossible by appLock.unlockImpossible.collectAsStateWithLifecycle()
    val dark = isPulseDarkTheme()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    // Live countdown while the post-failure backoff runs.
    LaunchedEffect(backoffUntilMs) {
        while (System.currentTimeMillis() < backoffUntilMs) {
            delay(500)
            nowMs = System.currentTimeMillis()
        }
        nowMs = System.currentTimeMillis()
    }
    val backingOff = nowMs < backoffUntilMs

    Box(
        Modifier
            .fillMaxSize()
            .background(if (dark) Color(0xF509090B) else Color(0xF5FAFAFA))
            .statusBarsPadding()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                PulseIcons.Lock,
                contentDescription = "Pulse is locked",
                tint = PulsePalette.Emerald,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Pulse is locked",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (dark) Color.White else Color(0xFF18181B),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Unlock with your fingerprint, face, or screen lock.",
                fontSize = 13.sp,
                color = if (dark) Color(0xFFA1A1AA) else Color(0xFF71717A),
            )
            notice?.let { message ->
                Spacer(Modifier.height(14.dp))
                Text(
                    message,
                    fontSize = 13.sp,
                    color = if (unlockImpossible || backingOff) Color(0xFFB91C1C) else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(22.dp))
            when {
                // The device has NO credential to verify with - no prompt can
                // ever succeed, so the gate offers the honest way out instead.
                unlockImpossible -> Button(onClick = { appLock.disableWithoutCredentials() }) {
                    Text("Turn off App lock")
                }
                // Max attempts (or a system lockout) - wait out the backoff.
                backingOff -> {
                    val secondsLeft = ((backoffUntilMs - nowMs) / 1000).coerceAtLeast(0)
                    Button(onClick = {}, enabled = false) {
                        Text("Try again in ${secondsLeft + 1}s")
                    }
                }
                // Normal path: re-present the system prompt.
                else -> Button(onClick = onRequestUnlock) { Text("Unlock") }
            }
        }
    }
}
