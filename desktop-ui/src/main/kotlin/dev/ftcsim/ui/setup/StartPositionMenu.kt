package dev.ftcsim.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.field.StartPose
import dev.ftcsim.ui.common.AppIcons
import dev.ftcsim.ui.common.FormTextField
import dev.ftcsim.ui.common.Tip
import dev.ftcsim.ui.host.StartPosition
import dev.ftcsim.ui.practice.PracticeSession
import dev.ftcsim.ui.project.ProjectActivity
import dev.ftcsim.ui.project.ProjectRunner
import dev.ftcsim.ui.project.RobotState

private val POSITIONS_WIDTH = 760.dp
private val POSITIONS_HEIGHT = 420.dp
private const val SAME_NUMBER = 0.0005

/**
 * The start position in the top bar of the Simulator screen: the poses of the field, those of the chosen alliance first,
 * then the positions the team named for the field, which live in the project's `ftcsim.json` and so are shared with the
 * team, and **Edit positions...**, which adds, changes and removes them. Choosing one starts the session again with it, the
 * way **Apply** on the Setup tab does: the same program, without building, on a fresh worker. A pose of the other
 * alliance takes the alliance with it.
 */
@Composable
fun StartPositionMenu(model: SetupModel, session: PracticeSession, runner: ProjectRunner) {
    val catalogue by session.catalogue.collectAsState()
    val activity by runner.activity.collectAsState()
    val open by runner.open.collectAsState()
    val robot by runner.robot.collectAsState()
    val positions by runner.startPositions.collectAsState()
    val rules = remember(catalogue) { catalogue?.let(SetupRules::of) } ?: return
    val input = model.input
    val offered = offered(rules, input)
    val named = positions.filter { it.field == input.field }
    val chosen = SetupValidation.poseOf(input, rules)
    val active = if (chosen != null) null else named.firstOrNull { sameSpot(it, input) }
    var expanded by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<PositionEditor?>(null) }
    val label = chosen?.name() ?: active?.name ?: "Custom position"
    Box {
        Tip("Start position") {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = open != null && activity !is ProjectActivity.Building && model.ready(rules),
                modifier = Modifier.semantics { contentDescription = "Start position: $label" },
            ) {
                Text(label)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            offered.forEach { (alliance, pose) ->
                DropdownMenuItem(
                    text = { Text(pose.name()) },
                    leadingIcon = { if (pose.name() == chosen?.name()) Icon(Icons.Filled.Check, contentDescription = "Chosen") else Icon(AppIcons.Flag, contentDescription = null) },
                    onClick = {
                        expanded = false
                        if (alliance != model.input.alliance) model.chooseAlliance(rules, alliance)
                        model.choosePose(rules, pose.name())
                        runner.apply()
                    },
                )
            }
            if (named.isNotEmpty()) {
                if (offered.isNotEmpty()) HorizontalDivider()
                named.forEach { position ->
                    DropdownMenuItem(
                        text = { Text(position.name) },
                        leadingIcon = { if (position == active) Icon(Icons.Filled.Check, contentDescription = "Chosen") else Icon(AppIcons.Flag, contentDescription = null) },
                        onClick = {
                            expanded = false
                            model.useNumbers(position.x, position.y, position.headingDegrees)
                            runner.apply()
                        },
                    )
                }
            }
            if (offered.isNotEmpty() || named.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Edit positions...") },
                enabled = robot is RobotState.FromFile,
                onClick = {
                    expanded = false
                    editor = PositionEditor(input.field, runner.startPositions.value)
                },
            )
        }
    }
    editor?.let { current ->
        StartPositionsDialog(
            current, whereNow(model, rules),
            onSave = { all -> runner.saveStartPositions(all); editor = null },
            onStart = { all, position ->
                runner.saveStartPositions(all)
                model.useNumbers(position.x, position.y, position.headingDegrees)
                runner.apply()
                editor = null
            },
            onCancel = { editor = null },
        )
    }
}

/** Where the form starts the robot now: the chosen pose's numbers or the typed ones, zero where they are not numbers. */
private fun whereNow(model: SetupModel, rules: SetupRules): Triple<Double, Double, Double> {
    val input = model.input
    val pose = SetupValidation.poseOf(input, rules)
    return Triple(
        pose?.x() ?: SetupValidation.number(input.startX) ?: 0.0,
        pose?.y() ?: SetupValidation.number(input.startY) ?: 0.0,
        pose?.let { Math.toDegrees(it.headingRadians()) } ?: SetupValidation.number(input.headingDegrees) ?: 0.0,
    )
}

private fun sameSpot(position: StartPosition, input: SetupInput): Boolean {
    val x = SetupValidation.number(input.startX) ?: return false
    val y = SetupValidation.number(input.startY) ?: return false
    val heading = SetupValidation.number(input.headingDegrees) ?: return false
    return Math.abs(position.x - x) < SAME_NUMBER && Math.abs(position.y - y) < SAME_NUMBER && Math.abs(position.headingDegrees - heading) < SAME_NUMBER
}

/**
 * The positions of one field, one row each: a name, X, Y and the heading, a **Start** that saves and starts from that row,
 * and a way to remove it; **Add position** begins at where the robot starts now. **Save** writes them into the project.
 */
@Composable
private fun StartPositionsDialog(
    editor: PositionEditor,
    here: Triple<Double, Double, Double>,
    onSave: (List<StartPosition>) -> Unit,
    onStart: (List<StartPosition>, StartPosition) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.width(POSITIONS_WIDTH),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Start positions") },
        text = {
            Column(Modifier.heightIn(max = POSITIONS_HEIGHT).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editor.rows.isEmpty()) Text("None yet")
                editor.rows.forEachIndexed { index, row ->
                    val problems = editor.problems(index)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        FormTextField(row.name, { editor.change(index, row.copy(name = it)) }, "Name", problems.name, null, Modifier.weight(1.6f))
                        FormTextField(row.x, { editor.change(index, row.copy(x = it)) }, "X (m)", problems.x, null, Modifier.weight(1f))
                        FormTextField(row.y, { editor.change(index, row.copy(y = it)) }, "Y (m)", problems.y, null, Modifier.weight(1f))
                        FormTextField(row.heading, { editor.change(index, row.copy(heading = it)) }, "Heading (deg)", problems.heading, null, Modifier.weight(1.2f))
                        TextButton(
                            enabled = editor.valid,
                            onClick = { onStart(editor.result()!!, editor.position(index)!!) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Start") }
                        IconButton(onClick = { editor.remove(index) }, modifier = Modifier.padding(top = 4.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove ${row.name}")
                        }
                    }
                }
                TextButton(onClick = { editor.add(here.first, here.second, here.third) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Add position")
                }
            }
        },
        confirmButton = { Button(enabled = editor.valid, onClick = { onSave(editor.result()!!) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Every pose of the field once, the chosen alliance's first, each with the alliance that choosing it asks for. */
private fun offered(rules: SetupRules, input: SetupInput): List<Pair<Alliance, StartPose>> {
    val own = rules.startPoses(input.field, input.alliance)
    val others = Alliance.entries.filter { it != input.alliance }.flatMap { alliance ->
        rules.startPoses(input.field, alliance).filter { pose -> own.none { it.name() == pose.name() } }.map { pose -> alliance to pose }
    }
    return own.map { input.alliance to it } + others.distinctBy { it.second.name() }
}
