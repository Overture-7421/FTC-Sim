package dev.ftcsim.ui.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ftcsim.ui.prepare.PrepareState
import dev.ftcsim.ui.project.ProjectActivity

/** A thin strip under the top bar while Restart code builds: the step it is at, and Cancel. The simulation goes on under it. */
@Composable
fun RestartStrip(restart: CodeRestart, modifier: Modifier = Modifier) {
    val activity by restart.activity.collectAsState()
    val build by restart.buildState.collectAsState()
    val building = (activity as? ProjectActivity.Building)?.takeIf { it.restarting } ?: return
    val running = build as? PrepareState.Running
    val text = running?.steps?.lastOrNull()?.let { "Step ${it.number()} of ${it.of()}: ${it.label()}" } ?: "Starting"
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.height(40.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = restart::cancel, enabled = running?.cancelling != true) {
                Text(if (running?.cancelling == true) "Cancelling" else "Cancel")
            }
        }
    }
}
