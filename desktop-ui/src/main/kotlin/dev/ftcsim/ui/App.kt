package dev.ftcsim.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.host.AppHost
import dev.ftcsim.ui.host.FakeAppHost
import dev.ftcsim.ui.link.FakeSimulatorLink
import dev.ftcsim.ui.nav.Destination
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
 * [AppHost]; [main] is the window's own, which runs on a fake host so that the interface can be developed without a worker.
 */
object App {
    /** Opens the window on a fake link and a fake preparer; `--practice` also opens the default practice run. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = AppOptions.parse(args)
        launch(FakeAppHost(), options) { services, ui ->
            if (options.openPractice) {
                services.startRun(FakeSimulatorLink.defaultRequest())
                ui.navigator.go(Destination.PRACTICE)
            }
        }
    }

    /**
     * Opens the window on [host] and returns when it has closed. Options: `--exit-after=<seconds>`, `--no-focus` and
     * `--settings=<file>`. Callable from Java.
     */
    @JvmStatic
    fun run(host: AppHost, args: Array<String>) {
        val options = AppOptions.parse(args)
        require(!options.openPractice) { "--practice opens the window's own fake run and is not available here" }
        launch(host, options) { _, _ -> }
    }

    private fun launch(host: AppHost, options: AppOptions, started: (AppServices, AppUi) -> Unit) {
        application {
            val settingsFile = remember { options.settingsFile ?: UserDirectories.uiSettingsFile() }
            val services = remember { AppServices(host, SettingsStore.forFile(settingsFile)) }
            val ui = remember { AppUi(services) }
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
            Window(
                onCloseRequest = close,
                state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
                title = "FTC-Sim",
                visible = !closing,
                focusable = options.takesFocus,
            ) {
                LaunchedEffect(window) {
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
                FtcSimTheme { AppContent(services, ui, files, AwtLogOpener(), settingsFile?.toString()) }
            }
        }
    }
}
