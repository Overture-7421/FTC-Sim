package dev.ftcsim.ui.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Says where the link to the simulator stands, for the screens that need it: nothing once it is there, a note while it
 * is being opened, and, when it could not be, the application's own message with a way to try again.
 *
 * @param waitingForMore also shows the note while the link is there but has not yet said what it can do
 */
@Composable
fun LinkNotice(link: LinkState, waitingForMore: Boolean, onReconnect: () -> Unit, modifier: Modifier = Modifier) {
    when {
        link is LinkState.Unavailable -> ElevatedCard(
            modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Simulator did not start", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(link.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Button(onClick = onReconnect) { Text("Try again") }
            }
        }
        link is LinkState.Connecting || waitingForMore -> Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Connecting", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
