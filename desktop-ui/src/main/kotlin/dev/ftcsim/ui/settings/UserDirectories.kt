package dev.ftcsim.ui.settings

import java.nio.file.Path
import java.nio.file.Paths

/** Where the application keeps what belongs to one Windows user. */
object UserDirectories {
    private const val APP_FOLDER = "FTC-Sim"

    /** The folder the launcher keeps its caches in as well: `%LOCALAPPDATA%\FTC-Sim`. */
    fun appData(localAppData: String? = System.getenv("LOCALAPPDATA"), userHome: String? = System.getProperty("user.home")): Path? {
        val base = localAppData?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: userHome?.takeIf { it.isNotBlank() }?.let { Paths.get(it, "AppData", "Local") }
        return base?.resolve(APP_FOLDER)
    }

    /** The file the user interface's settings are saved in, or null when the user has no folder to keep it in. */
    fun uiSettingsFile(appData: Path? = appData()): Path? = appData?.resolve("config")?.resolve("ui.properties")
}
