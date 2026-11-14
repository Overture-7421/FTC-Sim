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
import dev.ftcsim.ui.setup.FileSystemChecks
import dev.ftcsim.ui.setup.PathChecks
import dev.ftcsim.ui.setup.SetupInput
import dev.ftcsim.ui.setup.SetupModel
import java.nio.file.Path
import java.util.concurrent.ThreadLocalRandom

/**
 * What each screen remembers while another one is shown: where the person is, the Practice screen's menus and tab,
 * the Setup form, the Controls screen's rebinding and the Prepare screen's preparation. Nothing here is simulation
 * state; it lives as long as the application and is used from the UI thread only.
 */
class AppUi(
    private val services: AppServices,
    checks: PathChecks = FileSystemChecks,
    newSeed: () -> Long = { ThreadLocalRandom.current().nextLong(MAX_RANDOM_SEED) },
) {
    /** Why a folder that was chosen on [screen] could not be used, shown there until it is dismissed or another is chosen. */
    data class FolderNotice(val screen: Destination, val text: String)

    val navigator = Navigator()
    val practice = PracticeUi()
    val setup = SetupModel(checks, newSeed, services.settings.state.value.recents.firstOrNull()?.let { SetupInput.from(it) } ?: SetupInput())
    val controls = ControlsModel(services.settings)
    val prepare = PrepareModel(services.preparer, services.settings)

    var folderNotice by mutableStateOf<FolderNotice?>(null)
        private set

    /**
     * The person chose [folder] on [from]: finds out what it is, off the UI thread, and goes where it leads. A team's
     * project goes to the Prepare screen, a prepared program to the Setup screen with its folder filled in, and
     * anything else stays where it was, with the reason.
     */
    suspend fun openFolder(folder: Path, from: Destination) {
        when (val found = services.preparer.look(folder)) {
            is FolderFound.IsProject -> {
                folderNotice = if (prepare.choose(found.project)) null else FolderNotice(Destination.PREPARE, PREPARING_NOW)
                navigator.go(Destination.PREPARE)
            }
            is FolderFound.IsProgram -> {
                folderNotice = null
                useProgram(found.folder)
            }
            is FolderFound.IsNeither -> folderNotice = FolderNotice(from, found.reason)
        }
    }

    /** Fills the Setup form's program in with [folder] and goes there. */
    fun useProgram(folder: Path) {
        setup.edit { it.copy(program = folder.toString()) }
        navigator.go(Destination.SETUP)
    }

    fun dismissFolderNotice() {
        folderNotice = null
    }

    private companion object {
        const val MAX_RANDOM_SEED = 1_000_000_000L
        const val PREPARING_NOW = "A project is being prepared. Cancel that preparation or wait for it to finish before opening another."
    }
}
