package app.pulse.feature.stories

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Stories composer/viewer surface — later wave. */
@Composable
fun StoriesScreen() {
    Column(Modifier.fillMaxSize()) {
        Text("Stories", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
    }
}
