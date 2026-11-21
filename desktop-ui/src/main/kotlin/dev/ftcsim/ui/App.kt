package dev.ftcsim.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.ftcsim.ui.common.AwtFileChooser
import dev.ftcsim.ui.common.AwtLogOpener
import dev.ftcsim.ui.common.AwtUrlOpener
import dev.ftcsim.ui.common.FAKE_HOST_TITLE
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.common.rememberUpdateStatus
import dev.ftcsim.ui.host.AppHost
import dev.ftcsim.ui.host.UpdateService
import dev.ftcsim.ui.settings.SettingsStore
import dev.ftcsim.ui.settings.UserDirectories
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The simulator's desktop application. [run] is the entry point of the application around the window, which gives it a
 * [AppHost]. The window has no host of its own: the test fixtures start it on a fake one, for interface work.
 */
object App {
    /**
     * Opens the window on [host] and returns when it has closed, with the project that was open last time open again,
     * unless `--no-last-project` is given. Options: `--exit-after=<seconds>`, `--no-focus` and `--settings=<file>`.
     * Callable from Java.
     */
    @JvmStatic
    fun run(host: AppHost, args: Array<String>) {
        val options = AppOptions.parse(args)
        run(host, options) { _, ui -> if (options.opensLastProject) ui.openLast() }
    }

    /** What the window's title says: the project that is open, and on a stand-in host that nothing real runs. */
    internal fun title(project: String?, fake: Boolean): String =
        listOfNotNull("FTC-Sim", project, FAKE_HOST_TITLE.takeIf { fake }).joinToString(" - ")

    /** Opens the window on [host] with [options], calls [started] once the window's services exist, and returns when it has closed. */
    fun run(host: AppHost, options: AppOptions, started: suspend (AppServices, AppUi) -> Unit) {
        // exitProcessOnExit = false: the caller's code after this returns (closing the host, starting a fetched installer) must run.
        application(exitProcessOnExit = false) {
            val settingsFile = remember { options.settingsFile ?: UserDirectories.uiSettingsFile() }
            val services = remember { AppServices(host, SettingsStore.forFile(settingsFile)) }
            val ui = remember { AppUi(services) }
            val open by ui.runner.open.collectAsState()
            val scope = rememberCoroutineScope()
            var closing by remember { mutableStateOf(false) }
            // The window disappears at once; the link may take a moment to end, which the UI thread must not wait for.
            val close: () -> Unit = {
                if (!closing) {
                    closing = true
                    scope.launch {
                        withContext(Dispatchers.IO) { services.close() }
                        exitApplication()
                    }
                }
            }
            LaunchedEffect(options) {
                started(services, ui)
                options.exitAfterSeconds?.let {
                    delay(it * 1_000)
                    close()
                }
            }
            // Looking for a newer version waits on the network, so it is not done on the UI thread, and not at all for a scripted run
            // that asked for none. An offer is made in the window; nothing is installed unasked.
            // On a daemon thread of its own: the application ends only when its coroutines have, and a request must not hold it.
            LaunchedEffect(services.updates) {
                if (options.checksForUpdates) services.io.execute { services.updates.checkNow() }
            }
            // Once the installer of the version the person chose is ready, the window goes: the installer replaces this installation.
            val update by rememberUpdateStatus(services.updates)
            LaunchedEffect(update.kind) {
                if (update.kind == UpdateService.Kind.RESTARTING) close()
            }
            Window(
                onCloseRequest = close,
                state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
                title = title(open?.project?.name, services.isFake),
                visible = !closing,
                focusable = options.takesFocus,
            ) {
                LaunchedEffect(window) {
                    App::class.java.getResourceAsStream("icon.png")?.use { window.iconImage = javax.imageio.ImageIO.read(it) }
                    services.input.windowFocusChanged(window.isFocused)
                    services.startViewport(window.graphicsConfiguration.device.displayMode.refreshRate.toDouble())
                }
                DisposableEffect(window) {
                    val listener = object : WindowAdapter() {
                        override fun windowGainedFocus(event: WindowEvent) = services.input.windowFocusChanged(true)

                        override fun windowLostFocus(event: WindowEvent) = services.input.windowFocusChanged(false)
                    }
                    window.addWindowFocusListener(listener)
                    onDispose { window.removeWindowFocusListener(listener) }
                }
                val files = remember(window) { AwtFileChooser { window } }
                FtcSimTheme { AppContent(services, ui, files, AwtLogOpener(), AwtUrlOpener(), settingsFile?.toString()) }
            }
        }
    }
}
