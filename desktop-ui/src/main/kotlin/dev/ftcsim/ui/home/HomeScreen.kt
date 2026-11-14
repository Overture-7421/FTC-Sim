package dev.ftcsim.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ftcsim.contracts.field.Alliance
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.common.AppIcons
import dev.ftcsim.ui.common.NoticeCard
import dev.ftcsim.ui.common.ScreenPage
import dev.ftcsim.ui.common.SectionCard
import dev.ftcsim.ui.common.Tip
import dev.ftcsim.ui.setup.SetupRules
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/**
 * The Home screen: start a new practice run, open a team's project to prepare it or a prepared practice package, and
 * open again, with one click, a run that was started before.
 *
 * @param recents the runs started before, newest first
 * @param rules the fields as the simulator names them, or null before it has said; a run then shows its field's id
 * @param notice why the folder that was chosen here could not be used, or null
 * @param onOpen reopens a run exactly as it was asked for
 * @param onRemove forgets a run
 * @param onNewRun goes to the Setup screen
 * @param onOpenProject asks for a team's project folder and goes on to prepare it
 * @param onOpenPackage asks for a prepared practice package and goes to the Setup screen with it filled in
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    recents: List<SessionRequest>,
    rules: SetupRules?,
    notice: String?,
    onOpen: (SessionRequest) -> Unit,
    onRemove: (SessionRequest) -> Unit,
    onNewRun: () -> Unit,
    onOpenProject: () -> Unit,
    onOpenPackage: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenPage("FTC-Sim", modifier) {
        Text(
            "Practice your robot code and your driving on a simulated field.",
            style = MaterialTheme.typography.bodyLarge,
        )
        if (notice != null) NoticeCard("That folder cannot be used", notice, onDismissNotice)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNewRun) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("New practice run")
            }
            OutlinedButton(onClick = onOpenProject) {
                Icon(AppIcons.Package, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Open project")
            }
            OutlinedButton(onClick = onOpenPackage) {
                Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Open practice package")
            }
        }
        SectionCard("Recent runs") {
            if (recents.isEmpty()) {
                Text(
                    "No runs yet. Set up a practice run and it will be listed here, ready to open again with one click.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recents.forEach { RecentRun(it, rules, onOpen, onRemove) }
                }
            }
        }
    }
}

@Composable
private fun RecentRun(request: SessionRequest, rules: SetupRules?, onOpen: (SessionRequest) -> Unit, onRemove: (SessionRequest) -> Unit) {
    val title = recentTitle(request, rules)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    recentDetails(request),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = { onOpen(request) },
                modifier = Modifier.semantics { contentDescription = "Open $title" },
            ) { Text("Open") }
            Tip("Remove from recent runs") {
                IconButton(onClick = { onRemove(request) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove $title from recent runs")
                }
            }
        }
    }
}

/** The headline of a recent run: the field and the alliance, `DECODE, red alliance`. */
internal fun recentTitle(request: SessionRequest, rules: SetupRules?): String =
    "${rules?.displayName(request.field()) ?: request.field()}, ${if (request.alliance() == Alliance.RED) "red" else "blue"} alliance"

/** The rest of what a recent run was asked for: the robot profile, the seed and the program. */
internal fun recentDetails(request: SessionRequest): String =
    "Profile ${profileName(request.profile())}  |  Seed ${request.seed()}  |  Program ${programName(request.program())}"

private fun profileName(profile: String): String = lastSegment(profile)

private fun programName(program: String): String = if (program.isBlank()) "none" else lastSegment(program)

private fun lastSegment(path: String): String =
    try {
        Paths.get(path).fileName?.toString() ?: path
    } catch (invalid: InvalidPathException) {
        path
    }
