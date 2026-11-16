package dev.ftcsim.ui.prepare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ftcsim.ui.common.LogOpener
import dev.ftcsim.ui.common.ProblemText
import kotlinx.coroutines.launch

/** The build, as the Projects screen shows it while it runs: the project's name, one progress line, Cancel and the log behind a toggle. */
@Composable
fun BuildView(project: Project, model: PrepareModel, onCancel: () -> Unit) {
    val state by model.state.collectAsState()
    val running = state as? PrepareState.Running
    var logShown by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(project.name, style = MaterialTheme.typography.headlineSmall)
        val doing = running?.steps?.lastOrNull()?.let { "Step ${it.number()} of ${it.of()}: ${it.label()}" } ?: "Starting"
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                contentDescription = "Building: $doing"
                liveRegion = LiveRegionMode.Polite
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(doing, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onCancel, enabled = running?.cancelling != true) {
                Text(if (running?.cancelling == true) "Cancelling" else "Cancel")
            }
        }
        LogToggle(logShown, { logShown = it }, model.log)
    }
}

/** A build that did not finish: what went wrong in one line, the details and the log behind a toggle, and the way on. */
@Composable
fun BuildFailedView(
    project: Project,
    message: String,
    model: PrepareModel,
    logOpener: LogOpener,
    onBuildAgain: () -> Unit,
    onClose: () -> Unit,
) {
    val state by model.state.collectAsState()
    val failure = (state as? PrepareState.Failed)?.failure
    val details = failure?.details().orEmpty()
    val log = failure?.log()
    var shown by remember { mutableStateOf(false) }
    var logProblem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(project.name, style = MaterialTheme.typography.headlineSmall)
        ProblemText(message)
        if (details.isNotEmpty()) {
            TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide details" else "Show details") }
            if (shown) {
                SelectionContainer {
                    Column {
                        details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBuildAgain) { Text("Build again") }
            if (log != null) OutlinedButton(onClick = { scope.launch { logProblem = logOpener.open(log) } }) { Text("Open build log") }
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        logProblem?.let { ProblemText(it) }
    }
}

@Composable
private fun LogToggle(shown: Boolean, onShown: (Boolean) -> Unit, log: OutputLog) {
    TextButton(onClick = { onShown(!shown) }) { Text(if (shown) "Hide log" else "Show log") }
    if (shown) OutputPanel(log)
}

@Composable
private fun OutputPanel(log: OutputLog) {
    val shown by log.version.collectAsState()
    val view = remember(shown) { log.view() }
    if (view.first > 0) {
        Text(
            "Newest ${view.lines.size} lines; ${view.first} older not shown",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (view.lines.isEmpty()) {
        Text("No output yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutputView(log, Modifier.fillMaxWidth().height(260.dp))
}
