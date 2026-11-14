package dev.ftcsim.ui.common

import java.awt.Desktop
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens a log file with whatever the person has for such files. Behind an interface so that tests open nothing; the
 * real one is [AwtLogOpener].
 */
interface LogOpener {
    /** Opens [file]; returns null when it was opened, or why it was not, in words. */
    suspend fun open(file: Path): String?
}

/** Hands the file to the operating system through `java.awt.Desktop`, off the UI thread. */
class AwtLogOpener : LogOpener {
    override suspend fun open(file: Path): String? = withContext(Dispatchers.IO) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                "This computer cannot open files from here."
            } else {
                Desktop.getDesktop().open(file.toFile())
                null
            }
        } catch (failure: Exception) {
            failure.message?.takeIf { it.isNotBlank() } ?: failure.toString()
        }
    }
}
