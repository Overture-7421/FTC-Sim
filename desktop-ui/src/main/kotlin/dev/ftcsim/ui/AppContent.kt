package dev.ftcsim.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ftcsim.ui.common.FakeHostBanner
import dev.ftcsim.ui.common.FileChooser
import dev.ftcsim.ui.common.LogOpener
import dev.ftcsim.ui.common.UrlOpener
import dev.ftcsim.ui.common.UpdateBanner
import dev.ftcsim.ui.common.rememberUpdateStatus
import dev.ftcsim.ui.controls.ControlsScreen
import dev.ftcsim.ui.nav.AppShell
import dev.ftcsim.ui.nav.Destination
import dev.ftcsim.ui.practice.Panel
import dev.ftcsim.ui.practice.PerformanceOverlay
import dev.ftcsim.ui.practice.PracticeScreen
import dev.ftcsim.ui.project.ProjectsScreen
import dev.ftcsim.ui.settings.SettingsScreen
import dev.ftcsim.ui.setup.SetupRules
import dev.ftcsim.ui.setup.StartPanel
import dev.ftcsim.ui.setup.StartPositionMenu
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.coroutines.launch

/**
 * Everything inside the window: the navigation rail and the destination it shows, and above them, on a stand-in host, the
 * banner that says nothing real runs. Opening a project builds it and starts the simulator with it; leaving the
 * Simulator does not stop it, and leaving the Project screen does not stop a build. A folder chosen on Home or Project is
 * looked at off the UI thread and leads to the project being opened, or to a message where it was chosen.
 *
 * @param logOpener opens a build log with the system's program for such files
 * @param urlOpener opens the dashboard of the robot program in the system's browser
 * @param settingsPath where the settings are saved, for the Settings screen to show
 */
@Composable
fun AppContent(
    services: AppServices,
    ui: AppUi,
    files: FileChooser,
    logOpener: LogOpener,
    urlOpener: UrlOpener,
    settingsPath: String?,
    modifier: Modifier = Modifier,
) {
    val settings by services.settings.state.collectAsState()
    val settingsProblem by services.settings.problem.collectAsState()
    val catalogue by services.session.catalogue.collectAsState()
    val rules = remember(catalogue) { catalogue?.let(SetupRules::of) }
    val navigation by ui.navigator.state.collectAsState()
   val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val navigator = ui.navigator
    val openFolder: (Path, Destination) -> Unit = { folder, from -> scope.launch { ui.openFolder(folder, from) } }
    val chooseFolder: (String, Destination) -> Unit = { title, from ->
        scope.launch { files.chooseFolder(title, null)?.let { ui.openFolder(it, from) } }
    }

    LaunchedEffect(ui.opModes) { ui.opModes.run() }
    LaunchedEffect(ui.runner) {
        ui.runner.messages.collect { message ->
            val result = snackbar.showSnackbar(message.text, actionLabel = if (message.showProblems) "Show" else null, withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                ui.practice.showPanel(Panel.PROBLEMS)
                navigator.go(Destination.PRACTICE)
            }
        }
    }
    LaunchedEffect(settingsProblem) {
        settingsProblem?.let {
            snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long)
            services.settings.dismissProblem()
        }
    }
    // A message about a folder belongs to the screen where it was chosen and is not kept for the next visit.
    LaunchedEffect(navigation.current) {
        if (ui.folderNotice?.screen != navigation.current) ui.dismissFolderNotice()
    }
    Column(modifier.fillMaxSize()) {
        if (services.isFake) FakeHostBanner()
        val update by rememberUpdateStatus(services.updates)
        UpdateBanner(
            update,
            onInstall = { services.io.execute { services.updates.install() } },
            onDismiss = { services.updates.dismiss() },
        )
        Box(Modifier.weight(1f).fillMaxSize()) {
            AppShell(navigator) { destination ->
                val notice = ui.folderNotice?.takeIf { it.screen == destination }?.text
                when (destination) {
                    Destination.PROJECTS -> ProjectsScreen(
                        projects = settings.projects,
                        rules = rules,
                        notice = notice,
                        prepare = ui.prepare,
                        setup = ui.setup,
                        session = services.session,
                        runner = ui.runner,
                        logOpener = logOpener,
                        onOpen = { project ->
                            try {
                                openFolder(Path.of(project.folder), Destination.PROJECTS)
                            } catch (invalid: InvalidPathException) {
                                services.settings.update { it.withoutProject(project) }
                            }
                        },
                        onRemove = { project -> services.settings.update { it.withoutProject(project) } },
                        onOpenProject = { chooseFolder("Open a project", Destination.PROJECTS) },
                        onDismissNotice = ui::dismissFolderNotice,
                    )
                    Destination.CONTROLS -> ControlsScreen(ui.controls, services.settings, services.router, services.input)
                    Destination.PRACTICE -> PracticeScreen(
                        services.session, services.engine, ui.practice,
                        onRetry = services::retrySession,
                        onReconnect = services::reconnect,
                        onSetup = { navigator.go(Destination.PROJECTS) },
                        restart = ui.runner,
                        logOpener = logOpener,
                        urlOpener = urlOpener,
                        corner = { corner ->
                            if (settings.display.performanceOverlay) PerformanceOverlay(services.performance, corner)
                        },
                        start = { StartPanel(ui.setup, services.session, ui.runner) },
                        startPosition = { StartPositionMenu(ui.setup, services.session, ui.runner) },
                    )
                    Destination.SETTINGS -> SettingsScreen(services.settings, services.engine, settingsPath)
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
