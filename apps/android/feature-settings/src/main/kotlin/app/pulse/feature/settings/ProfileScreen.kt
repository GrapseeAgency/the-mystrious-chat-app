package app.pulse.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Profile tab — mirrors the web settings section registry (9 sub-pages later). */
@Composable
fun ProfileScreen() {
    Column(Modifier.fillMaxSize()) {
        Text("Profile", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
    }
}
