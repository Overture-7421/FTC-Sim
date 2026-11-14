package dev.ftcsim.ui

import java.nio.file.Path
import java.nio.file.Paths

/**
 * What can be asked for on the command line. The options exist for scripted launches: `--exit-after` closes the
 * application by itself, `--no-focus` opens the window without taking the keyboard from whoever is working,
 * `--practice` opens the default practice run and shows it, and `--settings` keeps the user settings in another file
 * so that a scripted run does not touch the real ones.
 */
data class AppOptions(
    val exitAfterSeconds: Long? = null,
    val takesFocus: Boolean = true,
    val openPractice: Boolean = false,
    val settingsFile: Path? = null,
) {
    companion object {
        fun parse(args: Array<String>): AppOptions {
            var options = AppOptions()
            for (argument in args) {
                options = when {
                    argument == "--no-focus" -> options.copy(takesFocus = false)
                    argument == "--practice" -> options.copy(openPractice = true)
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
