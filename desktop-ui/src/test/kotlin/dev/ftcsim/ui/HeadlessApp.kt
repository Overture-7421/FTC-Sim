package dev.ftcsim.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.ftcsim.ui.common.FakeFileChooser
import dev.ftcsim.ui.common.FakeLogOpener
import dev.ftcsim.ui.common.FakeUrlOpener
import dev.ftcsim.ui.common.FtcSimTheme
import dev.ftcsim.ui.host.AppHost
import dev.ftcsim.ui.host.testHost
import dev.ftcsim.ui.link.FakeSimulatorLink
import dev.ftcsim.ui.nav.Destination
import dev.ftcsim.ui.practice.Connection
import dev.ftcsim.ui.settings.SettingsStore
import dev.ftcsim.ui.setup.SetupRules
import dev.ftcsim.ui.viewport.ViewportStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The whole application without a window: the real services on a fake link, the real screens drawn by an
 * [ImageComposeScene] into memory, and, unless [startViewport] is false, the real render thread on a hidden OpenGL
 * context. Input is delivered to the scene as events, never through the operating system, and settings are kept in
 * memory only. [pump] stands in for the display's frame clock.
 */
@OptIn(ExperimentalComposeUiApi::class)
class HeadlessApp(
    val width: Int = 1280,
    val height: Int = 800,
    host: AppHost = testHost(),
    density: Float = 1f,
    startViewport: Boolean = true,
) : AutoCloseable {
    val settings = SettingsStore(null, Executor { it.run() })
    val services = AppServices(host, settings)
    val ui = AppUi(services)
    val files = FakeFileChooser()
    val logs = FakeLogOpener()
    val urls = FakeUrlOpener()
    private val scene = ImageComposeScene(width, height, Density(density)) {
        FtcSimTheme(darkTheme = true) { AppContent(services, ui, files, logs, urls, null) }
    }
    private var clockNanos = 0L

    init {
        if (startViewport) services.startViewport(REFRESH_HZ)
    }

    /** What the Setup screen offers, once the link has told; null until then. */
    fun rules(): SetupRules? = services.session.catalogue.value?.let(SetupRules::of)

    /** Starts the default fake session the way opening a project would and shows the Simulator screen. */
    fun openPractice() {
        services.openSession(FakeSimulatorLink.defaultRequest())
        ui.navigator.go(Destination.PRACTICE)
    }

    /** Renders frames until [condition] holds or the time runs out; returns whether it held. */
    fun pump(timeoutSeconds: Long = 20, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!condition()) {
            if (System.nanoTime() > deadline) return false
            frame()
            Thread.sleep(PUMP_MILLIS)
        }
        return true
    }

    /** Draws one frame of the Compose scene and returns it. */
    fun frame(): Image {
        clockNanos += FRAME_NANOS
        return scene.render(clockNanos)
    }

    /** Draws enough frames for a change of screen to be composed, laid out and drawn. */
    fun settle(): Image {
        repeat(SETTLE_FRAMES) { frame() }
        return frame()
    }

    /** True once the session is open and the render thread has drawn frames Compose has taken. */
    fun viewportReady(): Boolean =
        services.session.session.value.connection == Connection.OPEN &&
            services.engine.status.value is ViewportStatus.Running &&
            services.engine.framesDrawn() >= READY_FRAMES

    /** The reason the viewport cannot draw on this machine, or null. */
    fun viewportFailure(): String? = (services.engine.status.value as? ViewportStatus.Failed)?.message

    fun click(x: Float, y: Float) {
        scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
    }

    fun key(event: KeyEvent): Boolean = scene.sendKeyEvent(event)

    fun savePng(image: Image, name: String): File {
        val directory = File("build/screens").also { it.mkdirs() }
        val file = File(directory, name)
        Files.write(file.toPath(), image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        return file
    }

    fun pixels(image: Image): Bitmap = Bitmap().also {
        it.allocPixels(image.imageInfo)
        image.readPixels(it)
    }

    override fun close() {
        scene.close()
        services.close()
    }

    companion object {
        const val REFRESH_HZ = 60.0
        private const val FRAME_NANOS = 16_666_667L
        private const val PUMP_MILLIS = 8L
        private const val READY_FRAMES = 2L
        private const val SETTLE_FRAMES = 3
    }
}
