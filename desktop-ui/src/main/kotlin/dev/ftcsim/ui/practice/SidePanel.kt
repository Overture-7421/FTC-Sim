package dev.ftcsim.ui.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.field.Score
import dev.ftcsim.contracts.robot.TelemetrySnapshot
import dev.ftcsim.contracts.session.Readout
import dev.ftcsim.ui.common.AppIcons
import kotlinx.coroutines.flow.StateFlow

private val PANEL_PADDING = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** The tabs beside the viewport: what the OpMode shows on the Driver Station, the hardware, the score and the problems. */
@Composable
fun SidePanel(session: PracticeSession, ui: PracticeUi, modifier: Modifier = Modifier) {
    val problems by session.problems.collectAsState()
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = ui.panel.ordinal, containerColor = Color.Transparent) {
                Panel.entries.forEach { panel ->
                    Tab(
                        selected = ui.panel == panel,
                        onClick = { ui.panel = panel },
                        text = { TabLabel(panel, problems) },
                    )
                }
            }
            when (ui.panel) {
                Panel.TELEMETRY -> TelemetryPanel(session.telemetry)
                Panel.HARDWARE -> HardwarePanel(session.hardware)
                Panel.SCORE -> ScorePanel(session.score)
                Panel.PROBLEMS -> ProblemsPanel(problems)
            }
        }
    }
}

@Composable
private fun TabLabel(panel: Panel, problems: List<Problem>) {
    val style = MaterialTheme.typography.labelMedium
    if (panel != Panel.PROBLEMS || problems.isEmpty()) {
        Text(panel.label, style = style, maxLines = 1)
        return
    }
    val worst = if (problems.any { it.severity == Severity.ERROR }) Severity.ERROR else Severity.WARNING
    BadgedBox(
        badge = {
            Badge(containerColor = severityColors(worst).container, contentColor = severityColors(worst).content) {
                Text(problems.size.toString())
            }
        },
        modifier = Modifier.semantics { contentDescription = "${panel.label}, ${problems.size}, worst ${worst.label}" },
    ) {
        Text(panel.label, style = style, maxLines = 1)
    }
}

@Composable
private fun TelemetryPanel(source: StateFlow<TelemetrySnapshot>) {
    val telemetry by source.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PANEL_PADDING, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (telemetry.error().isNotEmpty()) {
            item { ProblemRow(Problem(Severity.ERROR, "Robot program", telemetry.error())) }
        }
        if (telemetry.warnings().isNotEmpty()) {
            item { ProblemRow(Problem(Severity.WARNING, "Driver Station", telemetry.warnings())) }
        }
        if (telemetry.lines().isEmpty()) {
            item { EmptyHint("The OpMode has not shown any telemetry.") }
        }
        itemsIndexed(telemetry.lines()) { _, line ->
            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun HardwarePanel(source: StateFlow<List<Readout>>) {
    val readouts by source.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PANEL_PADDING) {
        if (readouts.isEmpty()) {
            item { EmptyHint("No hardware has been used yet.") }
        }
        items(readouts) { readout ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(readout.name(), style = MaterialTheme.typography.bodyMedium)
                Text(readout.value(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ScorePanel(source: StateFlow<Score>) {
    val score by source.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PANEL_PADDING) {
        item {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text("Total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(score.total().toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
            }
        }
        if (score.lines().isEmpty()) {
            item { EmptyHint("Nothing has scored yet.") }
        }
        items(score.lines()) { line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${line.label()}  x${line.count()}", style = MaterialTheme.typography.bodyMedium)
                Text("${line.points()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ProblemsPanel(problems: List<Problem>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PANEL_PADDING, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (problems.isEmpty()) {
            item { EmptyHint("Nothing weakens this run.") }
        }
        items(problems.sortedBy { it.severity }) { problem -> ProblemRow(problem) }
    }
}

/** One problem: an icon, the severity and source spelled out, and the text; the colour only repeats the severity. */
@Composable
private fun ProblemRow(problem: Problem) {
    val colors = severityColors(problem.severity)
    Surface(color = colors.container, contentColor = colors.content, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (problem.severity == Severity.ERROR) AppIcons.Error else Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text("${problem.severity.label} - ${problem.source}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                Text(problem.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

private class SeverityColors(val container: Color, val content: Color)

@Composable
private fun severityColors(severity: Severity): SeverityColors = when (severity) {
    Severity.ERROR -> SeverityColors(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    Severity.WARNING -> SeverityColors(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
}
