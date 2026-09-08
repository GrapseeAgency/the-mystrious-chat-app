package app.pulse.feature.calls

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Calls tab — WebRTC call log + active-call surface lands in a later wave. */
@Composable
fun CallsScreen() {
    Column(Modifier.fillMaxSize()) {
        Text("Calls", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
        Text("Call history and WebRTC calls arrive with the data layer wave.", modifier = Modifier.padding(16.dp))
    }
}
