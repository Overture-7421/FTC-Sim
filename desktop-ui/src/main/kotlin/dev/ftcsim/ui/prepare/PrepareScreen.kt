package dev.ftcsim.ui.prepare

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ftcsim.ui.common.AppIcons
import dev.ftcsim.ui.common.FileChooser
import dev.ftcsim.ui.common.LogOpener
import dev.ftcsim.ui.common.NoticeCard
import dev.ftcsim.ui.common.ProblemText
import dev.ftcsim.ui.common.ScreenPage
import dev.ftcsim.ui.common.SectionCard
import dev.ftcsim.ui.host.ProgramPreparer.Failure
import dev.ftcsim.ui.host.ProgramPreparer.Prepared
import java.nio.file.Path
import kotlinx.coroutines.launch

/**
 * The Prepare screen: turns a team's project into a program the simulator can run. It shows the project that was chosen,
 * asks, once for each project folder, whether its own build may run on this computer, and while it runs shows the steps,
 * the output and a Cancel button. How it ended is shown in words: what went wrong with the lines that show why and the
 * build log to open, or the program that is ready with its OpModes and warnings and a way to use it. The state is the
 * [model]'s, not the screen's, so leaving the screen does not stop a preparation, and nothing here waits for it.
 *
 * @param notice why the last folder chosen here was not usable, or null
 * @param onChooseFolder called with a folder the person chose; the application finds out what it is and goes on
 * @param onUseProgram called with the prepared program's folder when the person wants to run it
 */
@Composable
fun PrepareScreen(
    model: PrepareModel,
    files: FileChooser,
    logOpener: LogOpener,
    notice: String?,
    onChooseFolder: (Path) -> Unit,
    onUseProgram: (Path) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val consent by model.consent.collectAsState()
    // A question that was left unanswered when the screen was left would otherwise be waiting, unseen, on return.
    DisposableEffect(model) { onDispose { model.answerConsent(false) } }

    ScreenPage("Prepare a project", modifier) {
        Text(
            "Turn your team's project into a program the simulator can run. Your project is only read, never changed.",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (notice != null) NoticeCard("That folder cannot be used", notice, onDismissNotice)
        val choose: @Composable (String) -> Unit = { label -> ChooseButton(label, files, onChooseFolder) }
        when (val now = state) {
            PrepareState.Idle -> SectionCard("Project") {
                Text(
                    "No project has been chosen. Choose the folder of your team's project, the one that has settings.gradle in it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                choose("Choose project folder")
            }
            is PrepareState.Ready -> SectionCard("Project") {
                ProjectDetails(now.project)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = model::prepare) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("Prepare")
                    }
                    choose("Choose another folder")
                }
            }
            is PrepareState.Running -> RunningCard(now, model::cancel)
            is PrepareState.Failed -> FailedCard(now, logOpener, model::prepare, choose)
            is PrepareState.Finished -> FinishedCard(now, logOpener, onUseProgram, model::prepare, choose)
        }
        if (state !is PrepareState.Idle && state !is PrepareState.Ready) {
            SectionCard("Output") { OutputPanel(model.log) }
        }
    }
    consent?.let { ConsentDialog(it, model::answerConsent) }
}

@Composable
private fun ChooseButton(label: String, files: FileChooser, onChosen: (Path) -> Unit) {
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = { scope.launch { files.chooseFolder("Choose your team's project folder", null)?.let(onChosen) } }) {
        Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label)
    }
}

@Composable
private fun ProjectDetails(project: Project) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(project.name, style = MaterialTheme.typography.titleMedium)
        SelectionContainer {
            Text(project.folder.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(project.summary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RunningCard(running: PrepareState.Running, onCancel: () -> Unit) {
    SectionCard("Preparing ${running.project.name}") {
        val rows = running.rows()
        if (rows.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Starting...", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { rows.forEach { StepLine(it) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onCancel, enabled = !running.cancelling) { Text("Cancel") }
            if (running.cancelling) {
                Text("Cancelling: waiting for the preparation to stop.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StepLine(row: StepRow) {
    val status = when (row.status) {
        StepStatus.DONE -> "done"
        StepStatus.ACTIVE -> "in progress"
        StepStatus.WAITING -> "waiting"
    }
    val text = row.label ?: "Step ${row.number} of ${row.of}"
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = "Step ${row.number} of ${row.of}, $status: $text" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (row.status) {
            StepStatus.DONE -> Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            StepStatus.ACTIVE -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            StepStatus.WAITING -> Box(Modifier.size(18.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (row.status == StepStatus.ACTIVE) FontWeight.Medium else FontWeight.Normal,
            color = if (row.status == StepStatus.WAITING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FailedCard(
    failed: PrepareState.Failed,
    logOpener: LogOpener,
    onPrepareAgain: () -> Unit,
    choose: @Composable (String) -> Unit,
) {
    val failure: Failure = failed.failure
    SectionCard(if (failed.cancelled) "Preparation cancelled" else "Preparing ${failed.project.name} did not finish") {
        if (failed.cancelled) Text(failure.message(), style = MaterialTheme.typography.bodyLarge) else ProblemText(failure.message())
        if (failure.details().isNotEmpty()) {
            Box(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small).padding(10.dp)) {
                SelectionContainer {
                    Column {
                        failure.details().forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }
        Actions(failure.log(), logOpener, onPrepareAgain, choose, primary = null)
    }
}

@Composable
private fun FinishedCard(
    finished: PrepareState.Finished,
    logOpener: LogOpener,
    onUseProgram: (Path) -> Unit,
    onPrepareAgain: () -> Unit,
    choose: @Composable (String) -> Unit,
) {
    val program: Prepared = finished.program
    SectionCard("${program.name()} is ready") {
        Text(
            if (program.sdkVersion().isBlank()) "FTC SDK version unknown" else "FTC SDK ${program.sdkVersion()}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Expandable("OpModes", program.opModes().size) {
            program.opModes().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        if (program.warnings().isEmpty()) {
            Text("No warnings.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Expandable("Warnings", program.warnings().size) {
                Text(
                    "These point to code that may not work in the simulator. They are not claims that it fails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                program.warnings().forEach { warning ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                        Text(warning, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Column {
            Text("Program folder", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(program.folder().toString(), style = MaterialTheme.typography.bodyMedium) }
        }
        Actions(program.log(), logOpener, onPrepareAgain, choose) {
            Button(onClick = { onUseProgram(program.folder()) }) { Text("Use this program") }
        }
    }
}

/** The buttons under a result: the main action if there is one, the build log if there is one, and the ways to go on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
    log: Path?,
    logOpener: LogOpener,
    onPrepareAgain: () -> Unit,
    choose: @Composable (String) -> Unit,
    primary: (@Composable () -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var logProblem by remember { mutableStateOf<String?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        primary?.invoke()
        if (log != null) {
            OutlinedButton(onClick = { scope.launch { logProblem = logOpener.open(log) } }) { Text("Open build log") }
        }
        OutlinedButton(onClick = onPrepareAgain) { Text("Prepare again") }
        choose("Choose another folder")
    }
    logProblem?.let { ProblemText("The build log could not be opened: $it") }
}

/** A heading with a count that opens and closes the list under it; the whole row is the button. */
@Composable
private fun Expandable(title: String, count: Int, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (open) "Collapse $title" else "Expand $title") { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$title ($count)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        if (open) {
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

@Composable
private fun OutputPanel(log: OutputLog) {
    val shown by log.version.collectAsState()
    val view = remember(shown) { log.view() }
    if (view.first > 0) {
        Text(
            "Showing the newest ${view.lines.size} lines of output; the ${view.first} before them are not shown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (view.lines.isEmpty()) {
        Text("Nothing has been printed yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutputView(log, Modifier.fillMaxWidth().height(280.dp))
}

@Composable
private fun ConsentDialog(project: Project, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text("Prepare this project?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Preparing runs this project's own Gradle build and its code on this computer. Only continue with projects you trust.")
                Column {
                    Text(project.name, style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(project.folder.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Prepare") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Cancel") } },
    )
}
