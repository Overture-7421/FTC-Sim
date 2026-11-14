package dev.ftcsim.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.FieldChoice
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.common.AppIcons
import dev.ftcsim.ui.common.ChoiceRow
import dev.ftcsim.ui.common.DropdownSelect
import dev.ftcsim.ui.common.FileChooser
import dev.ftcsim.ui.common.ProblemText
import dev.ftcsim.ui.common.ScreenPage
import dev.ftcsim.ui.common.SectionCard
import dev.ftcsim.ui.practice.Connection
import dev.ftcsim.ui.practice.LinkNotice
import dev.ftcsim.ui.practice.LinkState
import dev.ftcsim.ui.practice.PracticeSession
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Setup screen: the choices that make a practice run, with what is wrong with them said next to the field it is
 * about, and a Start button that works only for a form that has been checked and found right. The fields, their start
 * poses and the bundled robot profiles are what the simulator's catalogue lists; until it has said, the screen says it
 * is connecting and the parts that depend on it wait, while the rest can be filled in.
 *
 * @param programRequired whether a run needs a prepared program, which the host decides
 * @param onStart called with the run to open when the person starts it
 * @param onReconnect asks the application to open the link to the simulator again
 */
@Composable
fun SetupScreen(
    model: SetupModel,
    session: PracticeSession,
    files: FileChooser,
    programRequired: Boolean,
    onStart: (SessionRequest) -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalogue by session.catalogue.collectAsState()
    val link by session.link.collectAsState()
    val sessionState by session.session.collectAsState()
    val rules = remember(catalogue, programRequired) { catalogue?.let { SetupRules.of(it, programRequired) } }
    val input = model.input
    val problems = model.problems(rules)
    val scope = rememberCoroutineScope()
    LaunchedEffect(rules) { rules?.let(model::adopt) }
    LaunchedEffect(input, rules) {
        if (rules != null) model.publish(withContext(Dispatchers.IO) { model.check(input, rules) })
    }

    ScreenPage("Set up a practice run", modifier) {
        LinkNotice(link, waitingForMore = rules == null, onReconnect = onReconnect)
        SectionCard("Field and alliance") {
            if (rules == null) {
                Text("The fields the simulator offers appear here once it answers.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val fields = rules.fields.let { known ->
                    if (input.field.isBlank() || known.any { it.id() == input.field }) known else known + FieldChoice(input.field, input.field, emptyList(), emptyList())
                }
                ChoiceRow(
                    fields, fields.firstOrNull { it.id() == input.field }, { it.displayName() },
                    { choice -> model.chooseField(rules, choice.id()) }, "Field",
                )
                problems.field?.let { ProblemText(it) }
            }
            ChoiceRow(
                Alliance.entries, input.alliance, { if (it == Alliance.RED) "Red" else "Blue" },
                { choice -> if (rules != null) model.chooseAlliance(rules, choice) else model.edit { it.copy(alliance = choice) } },
                "Alliance",
            )
        }
        SectionCard("Seed", description = "The seed fixes every random choice of the field, so a run can be repeated exactly.") {
            ChoiceRow(
                listOf(true, false), input.randomSeed, { if (it) "Random" else "Fixed" },
                { random -> model.edit { it.copy(randomSeed = random) } }, "Seed",
            )
            if (input.randomSeed) {
                Text(
                    "A new seed is chosen when the run starts and is kept with the run in Recent runs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextInput(input.seedText, { text -> model.edit { it.copy(seedText = text) } }, "Fixed seed", problems.seed, "A whole number")
            }
        }
        SectionCard("Robot profile") {
            DropdownSelect(profileName(input.profile), "Robot profile") { close ->
                rules?.bundledProfiles.orEmpty().forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        leadingIcon = { if (name == input.profile) Icon(Icons.Filled.Check, contentDescription = "Chosen") },
                        onClick = { model.edit { it.copy(profile = name) }; close() },
                    )
                }
                if (rules != null) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Choose a file...") },
                    onClick = {
                        close()
                        scope.launch {
                            files.chooseFile("Choose a robot profile", listOf("json"), startFolder(input.profile))
                                ?.let { path -> model.edit { it.copy(profile = path.toString()) } }
                        }
                    },
                )
            }
            if (SetupValidation.isPath(input.profile)) {
                Text(input.profile, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            problems.profile?.let { ProblemText(it) }
        }
        SectionCard("Robot program") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                TextInput(
                    input.program, { text -> model.edit { it.copy(program = text) } }, "Prepared program folder", problems.program,
                    if (programRequired) "The folder of a prepared practice package"
                    else "Optional for now: the simulator behind this screen runs no robot program",
                    Modifier.weight(1f),
                )
                BrowseButton("Choose the program folder", Modifier.padding(top = 8.dp)) {
                    scope.launch {
                        files.chooseFolder("Choose the prepared program", startFolder(input.program))
                            ?.let { path -> model.edit { it.copy(program = path.toString()) } }
                    }
                }
            }
        }
        StartPoseCard(model, rules, input, problems)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(enabled = model.ready(rules) && link !is LinkState.Unavailable, onClick = { model.request(rules)?.let(onStart) }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Start practice")
            }
            val note = when {
                link is LinkState.Unavailable -> "The simulator is not available."
                rules == null -> "Waiting for the simulator."
                !problems.none -> "Fix the marked fields to start."
                sessionState.connection == Connection.OPEN || sessionState.connection == Connection.OPENING ->
                    "Starting a run replaces the session that is open now."
                else -> null
            }
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StartPoseCard(model: SetupModel, rules: SetupRules?, input: SetupInput, problems: SetupProblems) {
    SectionCard(
        "Start pose",
        description = "Where the robot starts, in the field's frame: metres from the centre, and the heading in degrees from the field's +X.",
    ) {
        val poses = rules?.startPoses(input.field, input.alliance).orEmpty()
        val chosen = if (rules == null) null else SetupValidation.poseOf(input, rules)
        if (rules != null && poses.isNotEmpty()) {
            DropdownSelect(chosen?.name() ?: "Custom position", "Start pose") { close ->
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
            Text(
                if (rules == null) "The field's start poses appear here once the simulator answers." else "This field offers no start poses: say where the robot starts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val fixed = chosen != null
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(
                chosen?.x()?.let(SetupInput.Companion::formatNumber) ?: input.startX,
                { text -> model.edit { it.copy(startX = text, startTouched = true) } }, "X (m)", problems.startX, null, Modifier.weight(1f), enabled = !fixed,
            )
            TextInput(
                chosen?.y()?.let(SetupInput.Companion::formatNumber) ?: input.startY,
                { text -> model.edit { it.copy(startY = text, startTouched = true) } }, "Y (m)", problems.startY, null, Modifier.weight(1f), enabled = !fixed,
            )
            TextInput(
                chosen?.headingRadians()?.let { SetupInput.formatNumber(Math.toDegrees(it)) } ?: input.headingDegrees,
                { text -> model.edit { it.copy(headingDegrees = text, startTouched = true) } }, "Heading (deg)", problems.heading, null, Modifier.weight(1f), enabled = !fixed,
            )
        }
    }
}

/** What the profile button says: the bundled name, the file's name, or an invitation to choose. */
private fun profileName(profile: String): String = when {
    profile.isBlank() -> "Choose a robot profile"
    SetupValidation.isPath(profile) -> "File: " + (try { Paths.get(profile).fileName?.toString() } catch (invalid: InvalidPathException) { null } ?: profile)
    else -> profile
}

@Composable
private fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    problem: String?,
    hint: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = problem != null,
        supportingText = { Text(problem ?: hint.orEmpty()) },
        trailingIcon = { if (problem != null) Icon(AppIcons.Error, contentDescription = "Problem", tint = MaterialTheme.colorScheme.error) },
        modifier = modifier,
    )
}

@Composable
private fun BrowseButton(description: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.semantics { contentDescription = description }) {
        Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text("Browse")
    }
}

/** The folder a chooser should open in for a path the person typed, or null to let the system decide. */
private fun startFolder(text: String): Path? =
    try {
        if (text.isBlank()) null else Paths.get(text.trim()).parent
    } catch (invalid: InvalidPathException) {
        null
    }
