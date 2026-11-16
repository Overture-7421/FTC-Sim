package dev.ftcsim.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.field.StartPose
import dev.ftcsim.contracts.session.FieldChoice
import dev.ftcsim.ui.common.ChoiceRow
import dev.ftcsim.ui.common.DropdownSelect
import dev.ftcsim.ui.common.FormTextField
import dev.ftcsim.ui.common.ProblemText
import dev.ftcsim.ui.practice.LinkState
import dev.ftcsim.ui.practice.PracticeSession
import dev.ftcsim.ui.project.ProjectActivity
import dev.ftcsim.ui.project.ProjectRunner
import dev.ftcsim.ui.project.RobotState

/**
 * The Setup tab of the Simulator screen: the field, the alliance and where the robot starts. **Apply** starts the
 * session again with them, with the same program, without building, on a fresh worker, which takes a few seconds.
 * **Save as default** writes them into the project's `ftcsim.json`, so that teammates start the same way.
 *
 * @param modifier the place the tab takes in the side panel
 */
@Composable
fun StartPanel(model: SetupModel, session: PracticeSession, runner: ProjectRunner, modifier: Modifier = Modifier) {
    val catalogue by session.catalogue.collectAsState()
    val link by session.link.collectAsState()
    val activity by runner.activity.collectAsState()
    val open by runner.open.collectAsState()
    val robot by runner.robot.collectAsState()
    val rules = remember(catalogue) { catalogue?.let(SetupRules::of) }
    val input = model.input
    val problems = model.problems(rules)
    LaunchedEffect(rules) { rules?.let(model::adopt) }

    val building = activity is ProjectActivity.Building
    val ready = model.ready(rules) && link !is LinkState.Unavailable

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Field", style = MaterialTheme.typography.titleSmall)
        if (rules != null) {
            val fields = rules.fields.let { known ->
                if (input.field.isBlank() || known.any { it.id() == input.field }) known else known + FieldChoice(input.field, input.field, emptyList(), emptyList())
            }
            DropdownSelect(rules.displayName(input.field), "Field") { close ->
                fields.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.displayName()) },
                        leadingIcon = { if (choice.id() == input.field) Icon(Icons.Filled.Check, contentDescription = "Chosen") },
                        onClick = { model.chooseField(rules, choice.id()); close() },
                    )
                }
            }
            problems.field?.let { ProblemText(it) }
        }
        Text("Alliance", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(
            Alliance.entries, input.alliance, { if (it == Alliance.RED) "Red" else "Blue" },
            { choice -> if (rules != null) model.chooseAlliance(rules, choice) else model.edit { it.copy(alliance = choice) } },
            "Alliance",
        )
        StartPoseControls(model, rules, input, problems)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = ready && !building && open != null, onClick = { runner.apply() }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Apply")
            }
            OutlinedButton(enabled = model.ready(rules) && robot is RobotState.FromFile && open != null, onClick = { runner.saveDefaults() }) {
                Text("Save as default")
            }
        }
    }
}

/** The start pose of the form: the field's poses for the chosen alliance, or numbers of the person's own. */
@Composable
internal fun StartPoseControls(model: SetupModel, rules: SetupRules?, input: SetupInput, problems: SetupProblems) {
    Text("Start position", style = MaterialTheme.typography.titleSmall)
    val poses = rules?.startPoses(input.field, input.alliance).orEmpty()
    val chosen = if (rules == null) null else SetupValidation.poseOf(input, rules)
    if (rules != null && poses.isNotEmpty()) {
        DropdownSelect(chosen?.name() ?: "Custom position", "Start position") { close ->
            poses.forEach { pose ->
                DropdownMenuItem(
                    text = { Text(pose.name()) },
                    leadingIcon = { if (pose.name() == chosen?.name()) Icon(Icons.Filled.Check, contentDescription = "Chosen") },
                    onClick = { model.choosePose(rules, pose.name()); close() },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Custom position") }, onClick = { model.choosePose(rules, null); close() })
        }
        problems.startPose?.let { ProblemText(it) }
    } else {
        if (rules != null) Text("No presets", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    StartPoseFields(model, chosen, input, problems)
}

/** The X, Y and heading of the start pose of the form, which are [chosen]'s and read-only when a pose of the field is chosen. */
@Composable
internal fun StartPoseFields(model: SetupModel, chosen: StartPose?, input: SetupInput, problems: SetupProblems) {
    val fixed = chosen != null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormTextField(
            chosen?.x()?.let(SetupInput.Companion::formatNumber) ?: input.startX,
            { text -> model.edit { it.copy(startX = text, startTouched = true) } }, "X (m)", problems.startX, null, Modifier.weight(1f), enabled = !fixed,
        )
        FormTextField(
            chosen?.y()?.let(SetupInput.Companion::formatNumber) ?: input.startY,
            { text -> model.edit { it.copy(startY = text, startTouched = true) } }, "Y (m)", problems.startY, null, Modifier.weight(1f), enabled = !fixed,
        )
        FormTextField(
            chosen?.headingRadians()?.let { SetupInput.formatNumber(Math.toDegrees(it)) } ?: input.headingDegrees,
            { text -> model.edit { it.copy(headingDegrees = text, startTouched = true) } }, "Heading (deg)", problems.heading, null, Modifier.weight(1f), enabled = !fixed,
        )
    }
}
