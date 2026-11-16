package dev.ftcsim.ui.robot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

internal val DIALOG_WIDTH = 620.dp
internal val DIALOG_HEIGHT = 520.dp
internal val FIELD_WIDTH = 270.dp

/** A window for the settings of one part of the robot: a title, what it is, a scrolling body and a Done button. */
@Composable
internal fun SettingsWindow(title: String, subtitle: String?, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.width(DIALOG_WIDTH),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        text = { Column(Modifier.heightIn(max = DIALOG_HEIGHT).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), content = content) },
        confirmButton = { Button(onClick = onClose) { Text("Done") } },
    )
}

/** A heading with a rule under it and [content] below, as one block of a settings window. */
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()
        content()
    }
}
