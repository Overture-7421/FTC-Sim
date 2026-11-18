package dev.ftcsim.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ftcsim.ui.host.UpdateService
import dev.ftcsim.ui.host.UpdateService.Kind
import dev.ftcsim.ui.host.UpdateService.Status

/** The sentence the update banner says for [status]; empty when there is nothing to say. */
fun updateText(status: Status): String = when (status.kind) {
    Kind.NONE -> ""
    Kind.AVAILABLE -> "FTC-Sim ${status.version} is available."
    Kind.DOWNLOADING -> "Downloading FTC-Sim ${status.version}: ${status.percent}%"
    Kind.RESTARTING -> "Installing FTC-Sim ${status.version}. The window closes and FTC-Sim starts again."
    Kind.FAILED -> "The update to FTC-Sim ${status.version} did not work: ${status.message}"
}

/** The status of [service] as Compose state: it changes when the service says so, from whatever thread. */
@Composable
fun rememberUpdateStatus(service: UpdateService): State<Status> {
    val state = remember(service) { mutableStateOf(service.status()) }
    DisposableEffect(service) {
        val listener = java.util.function.Consumer<Status> { state.value = it }
        service.addListener(listener)
        state.value = service.status()
        onDispose { service.removeListener(listener) }
    }
    return state
}

/**
 * The bar above the window's screens while there is something to say about updates: a newer version, offered with the
 * choice to install it now or later, the progress of fetching it, or why it failed, with the choice to try again. It
 * never installs anything by itself.
 */
@Composable
fun UpdateBanner(status: Status, onInstall: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (status.kind == Kind.NONE) return
    val text = updateText(status)
    Surface(
        modifier.fillMaxWidth().semantics { contentDescription = text },
        color = if (status.kind == Kind.FAILED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (status.kind == Kind.FAILED) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.kind == Kind.FAILED) Icon(AppIcons.Error, contentDescription = null, modifier = Modifier.size(20.dp).padding(end = 8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            when (status.kind) {
                Kind.AVAILABLE -> {
                    TextButton(onInstall) { Text("Update and restart") }
                    TextButton(onDismiss) { Text("Later") }
                }
                Kind.FAILED -> {
                    TextButton(onInstall) { Text("Try again") }
                    TextButton(onDismiss) { Text("Dismiss") }
                }
                else -> Unit
            }
        }
    }
}
