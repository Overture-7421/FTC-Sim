package dev.ftcsim.ui

import dev.ftcsim.contracts.session.SimulatorLink
import dev.ftcsim.ui.host.AppHost
import dev.ftcsim.ui.host.FakeAppHost
import dev.ftcsim.ui.host.ProgramPreparer
import dev.ftcsim.ui.host.testHost
import dev.ftcsim.ui.practice.Connection
import dev.ftcsim.ui.practice.Fixtures
import dev.ftcsim.ui.practice.LinkState
import dev.ftcsim.ui.practice.StubLink
import dev.ftcsim.ui.settings.SettingsStore
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How the application gets its link from the host: off the UI thread, reported when it cannot be had, tried again on
 * request and closed with the window. The link opens on a thread of its own, so the tests wait for what they expect
 * to happen with a bounded wait on state, never a sleep.
 */
class AppServicesTest {
    /** Opens links the test controls: it can hold them back, make them fail, and sees every one it made. */
    private class ScriptedOpener : FakeAppHost.LinkOpener {
        val links = CopyOnWriteArrayList<LatchLink>()
        val attempts = AtomicInteger()
        val openedOn = AtomicReference<String>()
        val entered = CountDownLatch(1)
        val arrived = CountDownLatch(1)
        @Volatile var failure: IOException? = null
        @Volatile var gate: CountDownLatch? = null

        override fun open(): SimulatorLink {
            attempts.incrementAndGet()
            openedOn.set(Thread.currentThread().name)
            entered.countDown()
            gate?.await(WAIT_SECONDS, TimeUnit.SECONDS)
            failure?.let { throw it }
            return LatchLink().also {
                links += it
                arrived.countDown()
            }
        }
    }

    /** A link that records what it is asked, and lets a test wait for it to be closed. */
    private class LatchLink(val stub: StubLink = StubLink()) : SimulatorLink by stub {
        val closed = CountDownLatch(1)

        override fun close() {
            stub.close()
            closed.countDown()
        }
    }

    private val settings = SettingsStore(null, Executor { it.run() })
    private val opener = ScriptedOpener()
    private val made = ArrayList<AppServices>()

    private fun services(host: AppHost = testHost(links = opener)): AppServices = AppServices(host, settings).also { made += it }

    @AfterEach
    fun closeEverything() = made.forEach { it.close() }

    private fun AppServices.awaitLink(accept: (LinkState) -> Boolean) = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) { session.link.first(accept) }
    }

    @Test
    fun theLinkIsOpenedOnAThreadOfItsOwnAndHandedToTheSessionWhenItIsThere() {
        opener.gate = CountDownLatch(1)

        val services = services()

        assertEquals(LinkState.Connecting, services.session.link.value, "the window does not wait for the link")
        opener.gate!!.countDown()
        services.awaitLink { it == LinkState.Connected }
        assertEquals("link-opener", opener.openedOn.get())
        assertTrue(opener.links.single().stub.calls.contains("addListener"))
    }

    @Test
    fun aLinkThatCannotBeOpenedIsReportedWithTheMessageOfTheFailure() {
        opener.failure = IOException("The worker could not be started: no Java found.")

        val services = services()

        assertEquals(
            LinkState.Unavailable("The worker could not be started: no Java found."),
            services.awaitLink { it is LinkState.Unavailable },
        )
    }

    @Test
    fun aFailureWithoutAMessageIsStillReportedWithSomethingToRead() {
        opener.failure = IOException()

        val unavailable = services().awaitLink { it is LinkState.Unavailable } as LinkState.Unavailable

        assertTrue(unavailable.message.contains("IOException"))
    }

    @Test
    fun aSessionAskedForWhileNoLinkIsThereOpensWhenOneIsAndTryingAgainIsHowToGetOne() {
        opener.failure = IOException("no worker")
        val services = services()
        services.awaitLink { it is LinkState.Unavailable }
        services.startRun(Fixtures.request(seed = 8L))
        assertEquals(Connection.OPENING, services.session.session.value.connection)

        opener.failure = null
        services.reconnect()
        services.awaitLink { it == LinkState.Connected }

        assertEquals(8L, opener.links.single().stub.requests.single().seed())
        assertEquals(2, opener.attempts.get())
    }

    @Test
    fun aSessionAskedForWhileTheLinkIsStillOpeningIsOpenedOnceItArrives() {
        opener.gate = CountDownLatch(1)
        val services = services()

        services.startRun(Fixtures.request(seed = 3L))
        assertEquals(LinkState.Connecting, services.session.link.value)
        assertEquals(Connection.OPENING, services.session.session.value.connection)
        opener.gate!!.countDown()
        services.awaitLink { it == LinkState.Connected }

        assertEquals(listOf("addListener", "open"), opener.links.single().stub.calls)
    }

    @Test
    fun aSessionThatFailedIsAskedForAgainOnAFreshLinkAndTheOldLinkIsClosed() {
        val services = services()
        services.awaitLink { it == LinkState.Connected }
        services.startRun(Fixtures.request(seed = 4L))
        val first = opener.links.single()
        first.stub.listener!!.failed("the worker died")
        assertEquals(Connection.FAILED, services.session.session.value.connection)

        services.retrySession()
        services.awaitLink { it == LinkState.Connected }

        assertTrue(first.closed.await(WAIT_SECONDS, TimeUnit.SECONDS), "a link that failed is closed, not reused")
        assertEquals(2, opener.links.size)
        assertEquals(4L, opener.links[1].stub.requests.single().seed())
        assertEquals(Connection.OPENING, services.session.session.value.connection)
    }

    @Test
    fun closingTheApplicationClosesTheLink() {
        val services = services()
        services.awaitLink { it == LinkState.Connected }

        services.close()

        assertTrue(opener.links.single().closed.await(WAIT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun aSecondTryWhileALinkIsStillBeingOpenedStartsNoSecondWorker() {
        opener.gate = CountDownLatch(1)
        val services = services()
        assertTrue(opener.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))

        services.reconnect()
        services.reconnect()
        opener.gate!!.countDown()
        services.awaitLink { it == LinkState.Connected }

        assertEquals(1, opener.attempts.get(), "only the open that was already under way")
        assertEquals(1, opener.links.size)
    }

    @Test
    fun aLinkThatIsReplacedWhileInUseIsClosedNotLeftRunning() {
        val services = services()
        services.awaitLink { it == LinkState.Connected }
        val first = opener.links.single()

        services.retrySession()
        services.awaitLink { it == LinkState.Connected && opener.links.size == 2 }

        assertTrue(first.closed.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertFalse(opener.links[1].closed.await(0, TimeUnit.SECONDS), "the new link stays open")
    }

    @Test
    fun aLinkThatArrivesAfterTheApplicationWasClosedIsClosedAtOnceNotKept() {
        opener.gate = CountDownLatch(1)
        val services = services()
        assertTrue(opener.entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the opener is waiting for the gate")

        services.close()
        opener.gate!!.countDown()
        assertTrue(opener.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS), "the opener finished")
        assertTrue(opener.links.single().closed.await(WAIT_SECONDS, TimeUnit.SECONDS))
        assertNotEquals(LinkState.Connected, services.session.link.value)
    }

    @Test
    fun theHostDecidesWhetherAProgramIsRequiredAndWhichPreparerIsUsed() {
        val preparer = testHost().preparer()
        val host = object : AppHost {
            override fun openLink(): SimulatorLink = StubLink()

            override fun preparer(): ProgramPreparer = preparer

            override fun programRequired() = true
        }

        val services = services(host)

        assertTrue(services.programRequired)
        assertEquals(preparer, services.preparer)
        assertFalse(services().programRequired, "the fake host needs none")
    }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
