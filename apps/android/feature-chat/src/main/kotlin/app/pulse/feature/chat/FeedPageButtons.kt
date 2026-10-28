package app.pulse.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A bounded page may have zero matches and still have older history to scan. */
@Composable
fun FeedPageButtons(previous: Boolean, next: Boolean, loading: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit) {
    if (previous || next) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = previous && !loading, onClick = onPrevious) { Text("Newer results") }
        TextButton(enabled = next && !loading, onClick = onNext) { Text("Search older history") }
    }
}
