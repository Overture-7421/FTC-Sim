package dev.ftcsim.ui.viewport

import androidx.compose.runtime.mutableLongStateOf
import dev.ftcsim.ui.glfw.GlfwThread
import dev.ftcsim.ui.input.ViewportInput
import dev.ftcsim.ui.pacing.Pacing
import dev.ftcsim.ui.render.OrbitCamera
import dev.ftcsim.ui.scene.ViewportFeed
import dev.ftcsim.ui.settings.DisplaySettings
import dev.ftcsim.ui.stats.StatSet
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** How the render thread is doing, for the UI to show. */
sealed interface ViewportStatus {
    /** The render thread has not produced anything yet. */
    data object Starting : ViewportStatus

    /** @property renderer the OpenGL renderer and driver; @property pacing how frames are paced */
    data class Running(val renderer: String, val pacing: String) : ViewportStatus

    /** The viewport cannot draw; [message] says why. The rest of the application is unaffected. */
    data class Failed(val message: String) : ViewportStatus
}

/**
 * The 3D viewport of the Practice screen: the render thread, the offscreen target it draws into, and the hand-off of
 * finished frames to Compose, which draws them as a bitmap like any other content so that menus, dialogs and
 * snackbars overlap the viewport correctly. Owns no simulation state; it draws what its [ViewportFeed] holds.
 *
 * It exists for as long as the application does. The render thread only draws while a [ViewportSurface] is in the
 * composition, so another screen costs nothing, and the picture of the last frame is kept for when it comes back.
 */
class ViewportEngine(
    private val feed: ViewportFeed,
    val camera: OrbitCamera,
    val input: ViewportInput,
    private val msaa: Int = DEFAULT_MSAA,
) : AutoCloseable {
    val stats = StatSet()

    private val frames = AtomicReference<ProducedFrame?>()
    private val mutableStatus = MutableStateFlow<ViewportStatus>(ViewportStatus.Starting)
    private val mutablePreset = MutableStateFlow(camera.preset())
    private val mutableRefresh = MutableStateFlow(0.0)
    private var sink: OffscreenSink? = null
    private var loop: RenderLoop? = null
    private var display = DisplaySettings()
    private var onScreen = false
    private var pacingDescription = ""
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0

    /** Counts finished frames; reading it while drawing makes Compose draw again when a new frame arrives. */
    internal val frameCount = mutableLongStateOf(0L)

    /** What is on screen now, kept across the viewport leaving and entering the composition. */
    internal val shown = ShownFrame()

    val status: StateFlow<ViewportStatus> = mutableStatus
    val preset: StateFlow<OrbitCamera.Preset> = mutablePreset

    /** The refresh rate of the display the window is on, in Hz, or 0 until it is known. */
    val displayRefreshHz: StateFlow<Double> = mutableRefresh

    /** Starts rendering at the rate that suits a display refreshing at [refreshHz]. */
    @Synchronized
    fun start(glfw: GlfwThread, refreshHz: Double) {
        check(loop == null) { "already started" }
        mutableRefresh.value = refreshHz
        val chosen = Pacing.create(refreshHz, display.frameCapHz)
        pacingDescription = chosen.description()
        val offscreen = OffscreenSink(
            glfw, feed, camera, msaa, stats,
            started = { renderer -> mutableStatus.value = ViewportStatus.Running(renderer, pacingDescription) },
            publish = { frame ->
                frames.getAndSet(frame)?.data?.close()
                frameCount.longValue = frame.index
            },
        )
        sink = offscreen
        offscreen.setScale(display.renderScalePercent)
        offscreen.resize(surfaceWidth, surfaceHeight)
        val renderLoop = RenderLoop(feed, offscreen, chosen, camera, stats)
        renderLoop.setSuspended(!onScreen)
        renderLoop.onFailure { mutableStatus.value = ViewportStatus.Failed(describe(it)) }
        feed.onChange(renderLoop::wake)
        camera.onChange {
            renderLoop.wake()
            mutablePreset.value = camera.preset()
        }
        loop = renderLoop
        renderLoop.start()
    }

    /** Takes the render scale, the frame cap and the performance recording from [settings], now or once started. */
    @Synchronized
    fun applyDisplay(settings: DisplaySettings) {
        val before = display
        display = settings
        if (settings.performanceOverlay) stats.startRecording() else stats.stopRecording()
        sink?.setScale(settings.renderScalePercent)
        val renderLoop = loop
        if (renderLoop != null && settings.frameCapHz != before.frameCapHz) {
            val next = Pacing.create(mutableRefresh.value, settings.frameCapHz)
            pacingDescription = next.description()
            mutableStatus.update { if (it is ViewportStatus.Running) it.copy(pacing = pacingDescription) else it }
            renderLoop.replacePacer(next)
        }
        renderLoop?.requestRedraw()
    }

    /** The viewport came into the composition or left it; while it is away nothing is drawn and no key is held. */
    @Synchronized
    fun setShown(isShown: Boolean) {
        onScreen = isShown
        input.viewportShown(isShown)
        if (!isShown) {
            input.focusChanged(false)
        }
        loop?.setSuspended(!isShown)
    }

    /** The size of the area the frames are shown on, in device pixels. */
    fun resize(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        sink?.resize(width, height)
        loop?.wake()
    }

    /** Shows [problem] in place of the viewport, for when the render thread could not even be started. */
    fun fail(problem: Throwable) {
        mutableStatus.value = ViewportStatus.Failed(describe(problem))
    }

    /** Frames drawn so far by the render thread. */
    fun framesDrawn(): Long = loop?.framesDrawn() ?: 0L

    /** The newest finished frame not yet taken, or null; the caller owns its data. */
    internal fun takeFrame(): ProducedFrame? = frames.getAndSet(null)

    @Synchronized
    override fun close() {
        loop?.close()
        loop = null
        feed.onChange { }
        camera.onChange { }
        frames.getAndSet(null)?.data?.close()
    }

    private fun describe(failure: Throwable): String = failure.message ?: failure.toString()

    companion object {
        const val DEFAULT_MSAA = 4
    }
}
