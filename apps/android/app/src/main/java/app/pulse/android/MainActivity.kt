package app.pulse.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Flame
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.pulse.android.ui.PulseTheme
import app.pulse.feature.chat.ChatsScreen
import app.pulse.feature.calls.CallsScreen
import app.pulse.feature.hub.HubScreen
import app.pulse.feature.settings.ProfileScreen
import dagger.hilt.android.AndroidEntryPoint

private data class PulseTab(val route: String, val label: String, val icon: ImageVector)

/** Four destinations — parity with the web NAV_ITEMS registry (nav-registry.ts). */
private val TABS = listOf(
    PulseTab("chats", "Chats", Icons.AutoMirrored.Filled.Chat),
    PulseTab("hub", "Hub", Icons.Filled.Flame),
    PulseTab("contacts", "Contacts", Icons.Filled.Groups),
    PulseTab("profile", "Profile", Icons.Filled.AccountCircle),
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PulseTheme {
                Surface { PulseRoot(deepLink = intent?.data?.toString()) }
            }
        }
    }
}

@Composable
fun PulseRoot(deepLink: String? = null) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
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
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chats",
            modifier = Modifier.padding(padding),
        ) {
            composable("chats") { ChatsScreen(onOpenCalls = { navController.navigate("calls") }) }
            composable("hub") { HubScreen() }
            composable("contacts") { CallsScreen() }
            composable("profile") { ProfileScreen() }
            composable("calls") { CallsScreen() }
        }
    }
}
