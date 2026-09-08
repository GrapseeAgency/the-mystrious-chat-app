package app.pulse.feature.hub

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Hub tab — wallet, market, tasks, tournaments, leaderboard land later. */
@Composable
fun HubScreen() {
    Column(Modifier.fillMaxSize()) {
        Text("Hub", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
    }
}
