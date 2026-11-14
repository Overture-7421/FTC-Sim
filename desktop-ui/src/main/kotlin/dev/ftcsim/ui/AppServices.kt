package dev.ftcsim.ui

import dev.ftcsim.contracts.session.SessionRequest
import dev.ftcsim.contracts.session.SimulatorLink
import dev.ftcsim.ui.glfw.GamepadPoller
import dev.ftcsim.ui.glfw.GlfwThread
import dev.ftcsim.ui.host.AppHost
import dev.ftcsim.ui.host.ProgramPreparer
import dev.ftcsim.ui.input.GamepadRouter
import dev.ftcsim.ui.input.ViewportInput
import dev.ftcsim.ui.practice.PracticeSession
import dev.ftcsim.ui.render.OrbitCamera
import dev.ftcsim.ui.scene.ViewportFeed
import dev.ftcsim.ui.settings.SettingsStore
import dev.ftcsim.ui.settings.UserSettings
import dev.ftcsim.ui.viewport.PerformanceMeter
import dev.ftcsim.ui.viewport.ViewportEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Everything the application is made of apart from the windows: the session on its link, the viewport and its
 * render thread, controller input, the program preparer and the user's settings. They live as long as the
 * application, whichever screen is shown. The link comes from the [host], which may have to start a process, so it is
 * opened on a thread of its own, one request at a time; until it is there a session that is asked for waits for it,
 * and when it cannot be opened the session says why and [reconnect] tries again. Starting the viewport and GLFW also
 * happens off the UI thread, because loading the native libraries and creating an OpenGL context take long enough
 * to be seen.
 */
class AppServices(private val host: AppHost, val settings: SettingsStore) : AutoCloseable {
    val feed = ViewportFeed()
    val camera = OrbitCamera().also { it.select(OrbitCamera.Preset.DRIVER) }
    val input = ViewportInput(camera)
    val engine = ViewportEngine(feed, camera, input)
    val session = PracticeSession(feed, camera)
    val router = GamepadRouter({ gamepad, state -> session.setGamepad(gamepad, state) }, input.keys())
    val performance = PerformanceMeter(engine, feed)
    val preparer: ProgramPreparer = host.preparer()

    /** Whether a run needs a prepared program, which only the host knows. */
    val programRequired: Boolean = host.programRequired()

    private val links: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "link-opener").also { it.isDaemon = true }
    }
    private val opens = AtomicInteger()

    @Volatile private var glfw: GlfwThread? = null
    @Volatile private var poller: GamepadPoller? = null
    @Volatile private var closed = false
    private var applied: UserSettings? = null

    init {
        input.onActiveChange(router::setActive)
        input.setKeyboardKeys { settings.state.value.gamepads.keyboard().isMapped(it) }
        settings.onChange(::apply)
        reconnect()
    }

    private fun apply(now: UserSettings) {
        val before = applied
        applied = now
        if (before?.gamepads != now.gamepads) router.configure(now.gamepads)
        if (before?.display != now.display) engine.applyDisplay(now.display)
    }

    /**
     * Opens the link through the host, off the UI thread, and hands it to the session; or tells the session why not.
     * Does nothing while a link is already being opened, so that a second press of "Try again" cannot start a second worker.
     */
    fun reconnect() {
        if (closed || opens.get() > 0) return
        session.connecting()
        open(null)
    }

    /** Asks for the session that failed again, on a link that is made anew, since a failed link cannot be asked again. */
    fun retrySession() {
        if (closed) return
        open(session.restart())
    }

    /** Opens a link on the link thread after closing [old], and closes whatever link it replaces. */
    private fun open(old: SimulatorLink?) {
        opens.incrementAndGet()
        links.execute {
            try {
                closeQuietly(old)
                openLink()
            } finally {
                opens.decrementAndGet()
            }
        }
    }

    private fun openLink() {
        if (closed) return
        val link = try {
            host.openLink()
        } catch (failure: Exception) {
            if (!closed) session.unavailable(failure.message?.takeIf { it.isNotBlank() } ?: failure.toString())
            return
        }
        closeQuietly(session.attach(link))
        if (closed) closeQuietly(session.detach())
    }

    private fun closeQuietly(link: SimulatorLink?) {
        try {
            link?.close()
        } catch (failure: RuntimeException) {
            System.err.println("a simulator link did not close cleanly: $failure")
        }
    }

    /** Opens [request] as the practice session, replacing the one that is open, and remembers it as a recent run. */
    fun startRun(request: SessionRequest) {
        settings.update { it.withRecent(request) }
        session.open(request)
    }

    /** Starts controller polling and the render thread, for a display refreshing at [refreshHz]. */
    fun startViewport(refreshHz: Double) {
        thread(name = "viewport-startup", isDaemon = true) {
            try {
                val started = GlfwThread.start()
                glfw = started
                if (closed) {
                    started.close()
                    return@thread
                }
                poller = GamepadPoller(started) { router.padsPolled(it) }
                engine.start(started, refreshHz)
            } catch (failure: Throwable) {
                engine.fail(failure)
            }
        }
    }

    /** Stops everything; waits for the link, which may have a worker process to end, so not for the UI thread. */
    override fun close() {
        closed = true
        links.shutdown()
        engine.close()
        poller?.close()
        glfw?.close()
        closeQuietly(session.detach())
        settings.close()
    }
}
