package dev.ftcsim.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ftcsim.ui.controls.ControlsModel
import dev.ftcsim.ui.nav.Destination
import dev.ftcsim.ui.nav.Navigator
import dev.ftcsim.ui.practice.PracticeUi
import dev.ftcsim.ui.prepare.FolderFound
import dev.ftcsim.ui.prepare.PrepareModel
import dev.ftcsim.ui.prepare.look
import dev.ftcsim.ui.project.OpModeMemory
import dev.ftcsim.ui.project.ProjectRunner
import dev.ftcsim.ui.setup.SetupModel
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ThreadLocalRandom

/**
 * What each screen remembers while another one is shown: where the person is, the Simulator screen's menus and tab, the
 * Setup form, the Controls screen's rebinding and the build, and the [runner] that opens projects and
 * restarts their code. Nothing here is simulation state; it lives as long as the application and is used from the UI
 * thread, apart from what the runner documents.
 */
class AppUi(
    private val services: AppServices,
    newSeed: () -> Long = { ThreadLocalRandom.current().nextLong(MAX_RANDOM_SEED) },
) {
    /** Why a folder that was chosen on [screen] could not be used, shown there until it is dismissed or another is chosen. */
    data class FolderNotice(val screen: Destination, val text: String)

    val navigator = Navigator()
    val practice = PracticeUi()
    val setup = SetupModel(newSeed)
    val controls = ControlsModel(services.settings)
    val prepare = PrepareModel(services.preparer)
    val runner = ProjectRunner(services, prepare, setup, navigator, services.io)
    val opModes = OpModeMemory(services.session, services.settings, runner.open)

    var folderNotice by mutableStateOf<FolderNotice?>(null)
        private set

    /**
     * The person chose [folder] on [from]: finds out what it is, off the UI thread, and goes where it leads. A team's
     * project is opened, which builds it and shows the Project screen meanwhile; a built program is not a project and is
     * said not to be; anything else stays where it was, with the reason.
     */
    suspend fun openFolder(folder: Path, from: Destination) {
        when (val found = services.preparer.look(folder)) {
            is FolderFound.IsProject -> folderNotice = if (runner.open(found.project)) null else FolderNotice(from, BUILDING_NOW)
            is FolderFound.IsProgram -> folderNotice = FolderNotice(from, BUILT_PROGRAM)
            is FolderFound.IsNeither -> folderNotice = FolderNotice(from, found.reason)
        }
    }

    /**
     * Opens the project that was open when the application last closed, the way Open project does: the program built
     * from the same source is reused, and a changed project is built with its progress shown. A project that is no
     * longer there is said so on Projects. Does nothing when there is no last project.
     */
    suspend fun openLast() {
        val last = services.settings.state.value.projects.firstOrNull() ?: return
        val folder = try {
            Path.of(last.folder)
        } catch (invalid: InvalidPathException) {
            null
        }
        val found = folder?.let { services.preparer.look(it) }
        if (found is FolderFound.IsProject) {
            runner.open(found.project)
        } else {
            folderNotice = FolderNotice(Destination.PROJECTS, "${last.name} is no longer at ${last.folder}")
        }
    }

    fun dismissFolderNotice() {
        folderNotice = null
    }

    private companion object {
        const val MAX_RANDOM_SEED = 1_000_000_000L
        const val BUILDING_NOW = "Another project is building"
        const val BUILT_PROGRAM = "This is a built program. Open the project folder, the one with settings.gradle"
    }
}
