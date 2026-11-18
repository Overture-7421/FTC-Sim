package dev.ftcsim.ui

import java.nio.file.Path
import java.nio.file.Paths

/**
 * What can be asked for on the command line. The options exist for scripted launches: `--exit-after` closes the
 * application by itself, `--no-focus` opens the window without taking the keyboard from whoever is working,
 * `--no-last-project` leaves the project that was open last time
 * closed, `--no-update-check` does not look for a newer version of the application, and `--settings` keeps the user settings in another file so that a scripted run does not touch the real ones.
 */
data class AppOptions(
    val exitAfterSeconds: Long? = null,
    val takesFocus: Boolean = true,
    val settingsFile: Path? = null,
    val opensLastProject: Boolean = true,
    val checksForUpdates: Boolean = true,
) {
    companion object {
        fun parse(args: Array<String>): AppOptions {
            var options = AppOptions()
            for (argument in args) {
                options = when {
                    argument == "--no-focus" -> options.copy(takesFocus = false)
                    argument == "--no-last-project" -> options.copy(opensLastProject = false)
                    argument == "--no-update-check" -> options.copy(checksForUpdates = false)
                    argument.startsWith("--settings=") ->
                        options.copy(settingsFile = argument.substringAfter('=').takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                            ?: throw IllegalArgumentException("--settings needs a file path: $argument"))
                    argument.startsWith("--exit-after=") ->
                        options.copy(exitAfterSeconds = argument.substringAfter('=').toLongOrNull()?.takeIf { it > 0 }
                            ?: throw IllegalArgumentException("--exit-after needs a positive number of seconds: $argument"))
                    else -> throw IllegalArgumentException("unknown option: $argument")
                }
            }
            return options
        }
    }
}
