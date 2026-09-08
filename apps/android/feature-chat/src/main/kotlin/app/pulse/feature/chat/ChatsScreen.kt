package app.pulse.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Chats tab — skeleton wave N1. Wire-in point for the domain
 * `observeConversations` flow once the data layer lands in N2.
 */
@Composable
fun ChatsScreen(onOpenCalls: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Chats",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(emptyList<String>(), key = { it }) { item ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(item, Modifier.padding(16.dp))
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOpenCalls) { Text("Open calls") }
                }
            }
        }
    }
}
