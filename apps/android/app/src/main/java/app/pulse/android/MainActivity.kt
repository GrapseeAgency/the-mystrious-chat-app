package app.pulse.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.pulse.android.ui.AmbientField
import app.pulse.android.ui.FxMode
import app.pulse.android.ui.ParticleBurstHost
import app.pulse.ui.PulseTheme
import app.pulse.feature.calls.ContactsScreen
import app.pulse.feature.chat.ChatsScreen
import app.pulse.feature.chat.ChatRoomScreen
import app.pulse.feature.hub.HubScreen
import app.pulse.feature.settings.ProfileScreen
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel

private data class PulseTab(val route: String, val label: String, val icon: ImageVector)

/** Four destinations — parity with the web NAV_ITEMS registry (nav-registry.ts). */
private val TABS = listOf(
    PulseTab("chats", "Chats", Icons.AutoMirrored.Filled.Chat),
    PulseTab("hub", "Hub", Icons.Filled.Whatshot),
    PulseTab("contacts", "Contacts", Icons.Filled.Groups),
    PulseTab("profile", "Profile", Icons.Filled.AccountCircle),
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

/** The four-tab shell — unchanged chrome behind the onboarding gate. */
@Composable
private fun PulseShell(viewerId: String?, session: SessionViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBars = currentRoute in TABS.map { it.route }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = {
            if (showBars) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chats",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable("chats") {
                ChatsScreen(
                    viewerId = viewerId,
                    onOpenRoom = { id -> navController.navigate("room/$id") },
                    onNeedIdentity = { navController.navigate("profile") { launchSingleTop = true } },
                )
            }
            composable("hub") { HubScreen(viewerName = session.viewerName.collectAsStateWithLifecycle().value) }
            composable("contacts") { ContactsScreen(onOpenRoom = { id -> navController.navigate("room/$id") }) }
            composable("profile") { ProfileScreen() }
            composable("room/{conversationId}") { entry ->
                val conversationId = entry.arguments?.getString("conversationId").orEmpty()
                ChatRoomScreen(
                    conversationId = conversationId,
                    viewerId = viewerId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
