package dev.ftcsim.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.ui.common.FileChooser
import dev.ftcsim.ui.common.LogOpener
import dev.ftcsim.ui.controls.ControlsScreen
import dev.ftcsim.ui.home.HomeScreen
import dev.ftcsim.ui.nav.AppShell
import dev.ftcsim.ui.nav.Destination
import dev.ftcsim.ui.practice.PerformanceOverlay
import dev.ftcsim.ui.practice.PracticeScreen
import dev.ftcsim.ui.prepare.PrepareScreen
import dev.ftcsim.ui.settings.SettingsScreen
import dev.ftcsim.ui.setup.SetupRules
import dev.ftcsim.ui.setup.SetupScreen
import java.nio.file.Path
import kotlinx.coroutines.launch

/**
 * Everything inside the window: the navigation rail and the destination it shows. Starting a run from Home or Setup
 * opens it on the session and goes to Practice; leaving Practice does not stop it, and leaving Prepare does not stop a
 * preparation. A folder chosen on Home or Prepare is looked at off the UI thread and leads to Prepare, to Setup, or
 * to a message where it was chosen.
 *
 * @param logOpener opens a build log with the system's program for such files
 * @param settingsPath where the settings are saved, for the Settings screen to show
 */
@Composable
fun AppContent(
    services: AppServices,
    ui: AppUi,
    files: FileChooser,
    logOpener: LogOpener,
    settingsPath: String?,
    modifier: Modifier = Modifier,
) {
    val settings by services.settings.state.collectAsState()
    val settingsProblem by services.settings.problem.collectAsState()
    val catalogue by services.session.catalogue.collectAsState()
    val rules = remember(catalogue, services.programRequired) { catalogue?.let { SetupRules.of(it, services.programRequired) } }
    val navigation by ui.navigator.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val navigator = ui.navigator
    val run: (SessionRequest) -> Unit = { request ->
        services.startRun(request)
        navigator.go(Destination.PRACTICE)
    }
    val openFolder: (Path, Destination) -> Unit = { folder, from -> scope.launch { ui.openFolder(folder, from) } }
    val chooseFolder: (String, Destination) -> Unit = { title, from ->
        scope.launch { files.chooseFolder(title, null)?.let { ui.openFolder(it, from) } }
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
    Box(modifier.fillMaxSize()) {
        AppShell(navigator) { destination ->
            val notice = ui.folderNotice?.takeIf { it.screen == destination }?.text
            when (destination) {
                Destination.HOME -> HomeScreen(
                    recents = settings.recents,
                    rules = rules,
                    notice = notice,
                    onOpen = run,
                    onRemove = { request -> services.settings.update { it.withoutRecent(request) } },
                    onNewRun = { navigator.go(Destination.SETUP) },
                    onOpenProject = { chooseFolder("Open a project", Destination.HOME) },
                    onOpenPackage = { chooseFolder("Open a practice package", Destination.HOME) },
                    onDismissNotice = ui::dismissFolderNotice,
                )
                Destination.PREPARE -> PrepareScreen(
                    ui.prepare, files, logOpener, notice,
                    onChooseFolder = { openFolder(it, Destination.PREPARE) },
                    onUseProgram = ui::useProgram,
                    onDismissNotice = ui::dismissFolderNotice,
                )
                Destination.SETUP -> SetupScreen(
                    ui.setup, services.session, files, services.programRequired,
                    onStart = run, onReconnect = services::reconnect,
                )
                Destination.CONTROLS -> ControlsScreen(ui.controls, services.settings, services.router, services.input)
                Destination.PRACTICE -> PracticeScreen(
                    services.session, services.engine, ui.practice,
                    onRetry = services::retrySession,
                    onReconnect = services::reconnect,
                    onSetup = { navigator.go(Destination.SETUP) },
                    corner = { corner ->
                        if (settings.display.performanceOverlay) PerformanceOverlay(services.performance, corner)
                    },
                )
                Destination.SETTINGS -> SettingsScreen(services.settings, services.engine, settingsPath)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
