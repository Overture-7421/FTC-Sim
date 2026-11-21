package dev.ftcsim.ui.project

import dev.ftcsim.contracts.session.SimulatorLink
import dev.ftcsim.ui.AppServices
import dev.ftcsim.ui.AppUi
import dev.ftcsim.ui.common.FakeFileChooser
import dev.ftcsim.ui.common.FakeLogOpener
import dev.ftcsim.ui.host.FakeAppHost
import dev.ftcsim.ui.host.newWorkFolder
import dev.ftcsim.ui.practice.Fixtures
import dev.ftcsim.ui.practice.LinkState
import dev.ftcsim.ui.practice.StubLink
import dev.ftcsim.ui.prepare.Project
import dev.ftcsim.ui.prepare.ScriptedPreparer
import dev.ftcsim.ui.settings.RecentProject
import dev.ftcsim.ui.settings.SettingsStore
import dev.ftcsim.ui.setup.SetupInput
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The application on a fake host whose builds the test finishes by hand ([preparer]) and whose links are stubs it can
 * speak for ([links]): what the runner does after a build is on threads of its own, so the test waits for state with
 * [await], a bounded wait on a flow, never a sleep.
 */
class ProjectRig(private val linkFailure: IOException? = null) : AutoCloseable {
    val preparer = ScriptedPreparer()
    val work: Path = newWorkFolder()
    val settings = SettingsStore(null, Executor { it.run() })
    val links = CopyOnWriteArrayList<StubLink>()
    val seedsDrawn = AtomicInteger()
    val services = AppServices(
        FakeAppHost(preparer, FakeAppHost.LinkOpener { open() }),
        settings,
    )
    val ui = AppUi(services) {
        seedsDrawn.incrementAndGet()
        SEED
    }
    val runner get() = ui.runner
    val files = FakeFileChooser()
    val logs = FakeLogOpener()

    /** The link the session is on now. */
    val link: StubLink get() = links.last()

    private fun open(): SimulatorLink {
        linkFailure?.let { throw it }
        return StubLink().also { links += it }
    }

    init {
        if (linkFailure == null) {
            await(services.session.link) { it == LinkState.Connected }
            link.listener!!.catalogue(Fixtures.catalogue())
        } else {
            await(services.session.link) { it is LinkState.Unavailable }
        }
    }

    /**
     * A project folder the preparer will call a project. [run] is what it was last run with, which names its game so that it is not asked
     * for; null for a project that never ran here.
     */
    fun project(name: String = "TeamProject", run: SetupInput? = SetupInput(field = "decode")): Project {
        val folder = Files.createDirectories(work.resolve("projects").resolve(name))
        if (run != null) settings.update { it.withProject(RecentProject(folder.toString(), name, run)) }
        return Project(folder, name, "")
    }

    /** What the runner told the person, in order. */
    private val received = MutableStateFlow<List<RunnerMessage>>(emptyList())

    val messages: List<RunnerMessage> get() = received.value

    private val listening = CoroutineScope(Dispatchers.Unconfined).launch { runner.messages.collect { message -> received.update { it + message } } }

    /** Waits until the runner has said [count] things. */
    fun awaitMessages(count: Int) {
        await(received) { it.size >= count }
    }

    /** Chooses the game of a project that waits for it, and waits for the session to start. */
    fun chooseGame(): ProjectActivity {
        assertInstanceOf(ProjectActivity.NeedsGame::class.java, await(runner.activity) { it !is ProjectActivity.Building })
        runner.continueWithGame()
        return await(runner.activity) { it == ProjectActivity.Idle }
    }

    /** Makes the settings form wrong, as typing nonsense into the custom start numbers does. */
    fun breakForm() = ui.setup.edit { it.copy(startPose = null, startX = "far", startTouched = true) }

    /** Makes the form right again after [breakForm]. */
    fun fixForm() = ui.setup.edit { it.copy(startX = "0.5") }

    /** Finishes the build that is under way with a program in a folder of [name]. */
    fun built(name: String = "program") {
        preparer.finish(work.resolve(name))
    }

    /** Waits for [flow] to hold a value that [accept] accepts, and returns it; fails after a bounded wait. */
    fun <T> await(flow: StateFlow<T>, accept: (T) -> Boolean): T = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) { flow.first(accept) }
    }

    /** Waits until the session is on the [count]th link the host made: a session that replaces another is opened on a fresh worker. */
    fun awaitLinks(count: Int) {
        await(services.session.link) { it == LinkState.Connected && links.size >= count }
    }

    /** Waits until the runner has finished what it was doing, whatever came of it. */
    fun settle(): ProjectActivity = await(runner.activity) { it !is ProjectActivity.Building }

    override fun close() {
        listening.cancel()
        services.close()
    }

    companion object {
        const val SEED = 123L
        const val WAIT_SECONDS = 10L
    }
}
