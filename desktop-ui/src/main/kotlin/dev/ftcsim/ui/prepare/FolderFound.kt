package dev.ftcsim.ui.prepare

import dev.ftcsim.ui.host.ProgramPreparer
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a folder the person pointed at is, as the preparer sees it. */
sealed interface FolderFound {
    /** A team's project, which has to be prepared before it can run. */
    data class IsProject(val project: Project) : FolderFound

    /** A program that was prepared before and can be used as it is. */
    data class IsProgram(val folder: Path, val name: String, val summary: String) : FolderFound

    /** Neither; [reason] says why, in words. */
    data class IsNeither(val folder: Path, val reason: String) : FolderFound
}

/**
 * Asks the preparer what [folder] is. It touches the disk, so it runs on the IO dispatcher and never holds up the UI
 * thread; a preparer that fails is an answer too, since the person only needs to know that the folder cannot be used.
 */
suspend fun ProgramPreparer.look(folder: Path): FolderFound = withContext(Dispatchers.IO) {
    try {
        val found = inspect(folder)
        when (found.kind()) {
            ProgramPreparer.Kind.PROJECT -> FolderFound.IsProject(Project(folder, found.name(), found.summary()))
            ProgramPreparer.Kind.PREPARED_PROGRAM -> FolderFound.IsProgram(folder, found.name(), found.summary())
            ProgramPreparer.Kind.NOT_RECOGNISED -> FolderFound.IsNeither(folder, found.summary())
        }
    } catch (failure: Exception) {
        FolderFound.IsNeither(folder, "The folder could not be looked at: ${failure.message ?: failure}")
    }
}
