package dev.ftcsim.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.field.StartPose
import dev.ftcsim.ui.common.AppIcons
import dev.ftcsim.ui.common.Tip
import dev.ftcsim.ui.practice.PracticeSession
import dev.ftcsim.ui.project.ProjectActivity
import dev.ftcsim.ui.project.ProjectRunner

/**
 * The start position in the top bar of the Simulator screen: the poses of the field, those of the chosen alliance first.
 * Choosing one starts the session again with it, the way **Apply** on the Setup tab does: the same program, without
 * building, on a fresh worker. A pose of the other alliance takes the alliance with it.
 */
@Composable
fun StartPositionMenu(model: SetupModel, session: PracticeSession, runner: ProjectRunner) {
    val catalogue by session.catalogue.collectAsState()
    val activity by runner.activity.collectAsState()
    val open by runner.open.collectAsState()
    val rules = remember(catalogue) { catalogue?.let(SetupRules::of) } ?: return
    val input = model.input
    val offered = offered(rules, input)
    val chosen = SetupValidation.poseOf(input, rules)
    var expanded by remember { mutableStateOf(false) }
    val label = chosen?.name() ?: "Custom position"
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
        }
    }
}
/** Every pose of the field once, the chosen alliance's first, each with the alliance that choosing it asks for. */
private fun offered(rules: SetupRules, input: SetupInput): List<Pair<Alliance, StartPose>> {
    val own = rules.startPoses(input.field, input.alliance)
    val others = Alliance.entries.filter { it != input.alliance }.flatMap { alliance ->
        rules.startPoses(input.field, alliance).filter { pose -> own.none { it.name() == pose.name() } }.map { pose -> alliance to pose }
    }
    return own.map { input.alliance to it } + others.distinctBy { it.second.name() }
}
