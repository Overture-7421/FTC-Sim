package dev.ftcsim.ui.robot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ftcsim.ui.prepare.Project

/**
 * The Robot screen: the mapping page of the open project, which is where its robot is mapped, for a project with or
 * without an `ftcsim.json`. Before a project is open there is nothing to map, and while its first build runs the
 * screen says so.
 *
 * @param project the open project, or null
 * @param building whether a project is being built
 * @param onOpenProject leads to the Projects screen, from the empty state
 * @param content the mapping page for the project
 */
@Composable
fun RobotScreen(
    project: Project?,
    building: Boolean,
    onOpenProject: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Project) -> Unit,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            project != null -> content(project)
            building -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No project open", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onOpenProject) { Text("Open a project") }
                }
            }
        }
    }
}
