package dev.ftcsim.ui.common

import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens an address in the person's browser. Behind an interface so that tests open nothing; the real one is
 * [AwtUrlOpener].
 */
interface UrlOpener {
    /** Opens [url]; returns null when it was opened, or why it was not, in words. */
    suspend fun open(url: String): String?
}

/** Hands the address to the operating system through `java.awt.Desktop`, off the UI thread. */
class AwtUrlOpener : UrlOpener {
    override suspend fun open(url: String): String? = withContext(Dispatchers.IO) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                "This computer cannot open a browser from here. Open $url yourself."
            } else {
                Desktop.getDesktop().browse(URI(url))
                null
            }
        } catch (failure: Exception) {
            failure.message?.takeIf { it.isNotBlank() } ?: failure.toString()
        }
    }
}
