package dev.ftcsim.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.ui.common.AppIcons
import dev.ftcsim.ui.common.ChoiceRow
import dev.ftcsim.ui.common.LogOpener
import dev.ftcsim.ui.common.NoticeCard
import dev.ftcsim.ui.common.ScreenPage
import dev.ftcsim.ui.common.Tip
import dev.ftcsim.ui.practice.PracticeSession
import dev.ftcsim.ui.prepare.BuildFailedView
import dev.ftcsim.ui.prepare.BuildView
import dev.ftcsim.ui.prepare.PrepareModel
import dev.ftcsim.ui.settings.RecentProject
import dev.ftcsim.ui.setup.SetupModel
import dev.ftcsim.ui.setup.SetupRules

/**
 * The Projects screen: open a team's project, or open again one that was opened before. While a project builds it is
 * the build, and when the build waits for the game to be chosen, that question.
 *
 * @param projects the projects opened before, the most recent first
 * @param rules the fields as the simulator names them, or null before it has said; a project then shows its field's id
 * @param notice why the folder that was chosen here could not be used, or null
 * @param onOpen opens a project that was opened before
 * @param onRemove forgets a project
 * @param onOpenProject asks for a team's project folder and opens it
 */
@Composable
fun ProjectsScreen(
    projects: List<RecentProject>,
    rules: SetupRules?,
    notice: String?,
    prepare: PrepareModel,
    setup: SetupModel,
    session: PracticeSession,
    runner: ProjectRunner,
    logOpener: LogOpener,
    onOpen: (RecentProject) -> Unit,
    onRemove: (RecentProject) -> Unit,
    onOpenProject: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity by runner.activity.collectAsState()
    val now = activity
    when {
        now is ProjectActivity.Building && !now.restarting ->
            ScreenPage("Projects", modifier) { BuildView(now.project, prepare, runner::cancel) }
        now is ProjectActivity.Failed ->
            ScreenPage("Projects", modifier) {
                BuildFailedView(now.project, now.message, prepare, logOpener, onBuildAgain = { runner.open(now.project) }, onClose = runner::dismissFailure)
            }
        now is ProjectActivity.NeedsGame ->
            ScreenPage("Projects", modifier) { GameChoice(now.project.name, setup, session, runner) }
        else -> ProjectList(projects, rules, notice, onOpen, onRemove, onOpenProject, onDismissNotice, modifier)
    }
}

@Composable
private fun GameChoice(name: String, setup: SetupModel, session: PracticeSession, runner: ProjectRunner) {
    val catalogue by session.catalogue.collectAsState()
    val rules = remember(catalogue) { catalogue?.let(SetupRules::of) }
    val games = rules?.fields.orEmpty().filter { it.startPoses().isNotEmpty() }.asReversed()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(name, style = MaterialTheme.typography.headlineSmall)
        Text("Game", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(games, games.firstOrNull { it.id() == setup.input.field }, { it.displayName().substringBefore(" presented").substringBefore(" (") }, { rules?.let { r -> setup.chooseField(r, it.id()) } }, "Game")
        Button(onClick = runner::continueWithGame, enabled = setup.ready(rules)) { Text("Continue") }
    }
}

@Composable
internal fun ProjectList(
    projects: List<RecentProject>,
    rules: SetupRules?,
    notice: String?,
    onOpen: (RecentProject) -> Unit,
    onRemove: (RecentProject) -> Unit,
    onOpenProject: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier,
) {
    if (projects.isEmpty()) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (notice != null) NoticeCard("Cannot open that folder", notice, onDismissNotice, Modifier.padding(horizontal = 24.dp))
                    OpenProjectButton(onOpenProject)
                }
            }
        }
        return
    }
    ScreenPage("Projects", modifier) {
        if (notice != null) NoticeCard("Cannot open that folder", notice, onDismissNotice)
        OpenProjectButton(onOpenProject)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            projects.forEach { RecentProjectRow(it, rules, onOpen, onRemove) }
        }
    }
}

@Composable
private fun OpenProjectButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text("Open project")
    }
}

@Composable
private fun RecentProjectRow(project: RecentProject, rules: SetupRules?, onOpen: (RecentProject) -> Unit, onRemove: (RecentProject) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    recentDetails(project, rules),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = { onOpen(project) },
                modifier = Modifier.semantics { contentDescription = "Open ${project.name}" },
            ) { Text("Open") }
            Tip("Remove from the list") {
                IconButton(onClick = { onRemove(project) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove ${project.name} from the list")
                }
            }
        }
    }
}

/** The folder of a recent project and how it was last run, `C:\robot\code  |  DECODE, red alliance`. */
internal fun recentDetails(project: RecentProject, rules: SetupRules?): String {
    val run = project.run ?: return project.folder
    val field = rules?.displayName(run.field) ?: run.field
    return "${project.folder}  |  $field, ${if (run.alliance == Alliance.RED) "red" else "blue"}"
}
