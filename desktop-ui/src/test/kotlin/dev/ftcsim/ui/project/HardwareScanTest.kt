package dev.ftcsim.ui.project

import dev.ftcsim.contracts.hardware.UnmappedDevice
import dev.ftcsim.contracts.robot.RobotPhase
import dev.ftcsim.ui.practice.Connection
import dev.ftcsim.ui.practice.Fixtures
import dev.ftcsim.ui.practice.Hold
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding the hardware a program asks for: the question after a build, the OpMode run once, and what is remembered. The
 * simulator is a stub link that the test speaks for, so the phases and the names it reports are the test's.
 */
class HardwareScanTest {
    private val rig = ProjectRig()
    private val scan = rig.ui.scan
    private val holds get() = rig.services.session.holds
    private val scope = CoroutineScope(Dispatchers.Default)
    private val follower: Job = scope.launch { scan.run() }

    @AfterEach
    fun close() {
        scope.cancel()
        rig.close()
    }

    private fun opened(name: String = "TeamProject"): Path {
        val project = rig.project(name)
        assertTrue(rig.runner.open(project))
        rig.built("program")
        rig.settle()
        rig.link.listener!!.opened(Fixtures.info())
        return project.folder
    }

    private fun status(phase: RobotPhase, simSeconds: Long = 0L, unmapped: List<UnmappedDevice> = emptyList()) =
        rig.link.listener!!.status(Fixtures.status(phase, simTimeNanos = simSeconds * SECOND, unmapped = unmapped))

    private fun awaitCall(call: String) = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(ProjectRig.WAIT_SECONDS)) {
            while (call !in rig.link.calls) yield()
        }
    }

    /** Lets simulated time pass in Init, with [unmapped] asked for, until the scan sends Stop, as the simulator does while a program waits. */
    private fun initialisedUntilStop(unmapped: List<UnmappedDevice> = emptyList(), from: Long = 1) = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(ProjectRig.WAIT_SECONDS)) {
            var seconds = from
            while ("stop" !in rig.link.calls) {
                status(RobotPhase.INIT, seconds++, unmapped)
                yield()
            }
        }
    }

    @Test
    fun aSessionThatOpensForAProjectRunsNothingUntilAnOpModeIsPicked() {
        opened()

        assertEquals(ScanState.Quiet, scan.state.value)
        assertTrue(rig.link.calls.none { it.startsWith("init") })
        assertTrue(follower.isActive)

        scan.scan("Teleop A")
        awaitCall("init Teleop A")
    }

    private inline fun <reified T : ScanState> awaitState(): T =
        rig.await(scan.state) { it is T } as T


    @Test
    fun theOpModeIsInitialisedAndStoppedAndTheNamesItAskedForAreWhatIsFoundAndTheChoiceIsKeptWithTheProject() {
        val folder = opened()

        scan.scan("Auto B")
        awaitCall("init Auto B")
        assertEquals(ScanState.Scanning("Auto B", automatic = false), scan.state.value)
        initialisedUntilStop(listOf(UnmappedDevice(UnmappedDevice.Kind.MOTOR, "intake")))
        status(RobotPhase.IDLE, simSeconds = 2, unmapped = listOf(UnmappedDevice(UnmappedDevice.Kind.MOTOR, "intake")))

        assertEquals(ScanState.Done("Auto B", 1, null), awaitState<ScanState.Done>())
        assertEquals("Auto B", rig.settings.state.value.project(folder)?.scanOpMode)
        assertEquals("Teleop A", rig.services.session.session.value.selectedOpMode, "the OpMode picked in the top bar is left as it was")
    }

    @Test
    fun aSessionThatOpensLaterRunsTheRememberedOpModeByItselfAndSaysNothingWhenEverythingIsPlaced() {
        opened()
        scan.scan("Teleop A")
        awaitCall("init Teleop A")
        initialisedUntilStop()
        status(RobotPhase.IDLE, simSeconds = 500)
        awaitState<ScanState.Done>()
        rig.link.calls.clear()

        rig.link.listener!!.opened(Fixtures.info())
        awaitCall("init Teleop A")

        assertEquals(ScanState.Scanning("Teleop A", automatic = true), scan.state.value)
        initialisedUntilStop()
        status(RobotPhase.IDLE, simSeconds = 500)
        assertEquals(ScanState.Quiet, rig.await(scan.state) { it == ScanState.Quiet })
    }


    @Test
    fun aSessionThatOpensLaterIsHeldForTheScanFromTheMomentItOpensUntilTheScanEnds() {
        opened()
        scan.scan("Teleop A")
        awaitCall("init Teleop A")
        assertEquals(setOf(Hold.SCANNING), holds.value, "a scan the person asked for holds the run controls too")
        initialisedUntilStop()
        status(RobotPhase.IDLE, simSeconds = 500)
        awaitState<ScanState.Done>()
        assertEquals(emptySet<Hold>(), holds.value)
        rig.link.calls.clear()

        rig.link.listener!!.opened(Fixtures.info())
        assertEquals(setOf(Hold.SCANNING), holds.value, "held at once, not when the scan gets going")
        assertEquals(Connection.OPEN, rig.services.session.session.value.connection)
        awaitCall("init Teleop A")
        initialisedUntilStop()
        status(RobotPhase.IDLE, simSeconds = 500)

        rig.await(holds) { it.isEmpty() }
        assertEquals("Teleop A", rig.services.session.session.value.selectedOpMode)
    }

    @Test
    fun aSessionWithNothingRememberedToScanIsNotHeld() {
        opened()
        assertEquals(emptySet<Hold>(), holds.value)

        rig.link.listener!!.opened(Fixtures.info())

        assertEquals(emptySet<Hold>(), holds.value)
    }

    @Test
    fun anOpModeThatNeverInitialisesIsGivenUpOnAfterTenSimulatedSecondsWithTheProgramsError() {
        opened()

        scan.scan("Teleop A")
        awaitCall("init Teleop A")
        status(RobotPhase.IDLE, simSeconds = 11)

        val done = awaitState<ScanState.Done>()
        assertInstanceOf(String::class.java, done.failure)
        assertTrue(done.failure!!.startsWith("Teleop A did not initialise"))
        assertNull(rig.settings.state.value.projects.single().scanOpMode, "a choice that did not work is not remembered")
    }

    @Test
    fun theOpModeIsNotStoppedWhileItIsStillAskingForDevicesAndIsAfterTwoQuietSimulatedSeconds() {
        opened()
        val motor = UnmappedDevice(UnmappedDevice.Kind.MOTOR, "intake")
        val sensor = UnmappedDevice(UnmappedDevice.Kind.I2C, "odopods")

        scan.scan("Teleop A")
        awaitCall("init Teleop A")
        status(RobotPhase.INIT, simSeconds = 1, unmapped = listOf(sensor))
        status(RobotPhase.INIT, simSeconds = 2, unmapped = listOf(sensor, motor))
        status(RobotPhase.INIT, simSeconds = 3, unmapped = listOf(sensor, motor))
        assertTrue("stop" !in rig.link.calls, "the code asked for a device a second ago, so it may still be building the robot")

        initialisedUntilStop(listOf(sensor, motor), from = 4)
        status(RobotPhase.IDLE, simSeconds = 500, unmapped = listOf(sensor, motor))

        assertEquals(ScanState.Done("Teleop A", 2, null), awaitState<ScanState.Done>())
    }

    @Test
    fun anOpModeThatDiesWhileInitialisingIsGivenUpOnWithWhatTheProgramThrew() {
        opened()

        scan.scan("Teleop A")
        awaitCall("init Teleop A")
        rig.link.listener!!.log(
            listOf(
                "E/OpModeManager: User code threw an uncaught exception",
                "E/OpModeManager: java.lang.IllegalStateException: \"intake\" needs a free motor port but all 4 of them are taken",
                "E/OpModeManager: \tat dev.ftcsim.hardware.wiring.WiringTable.nextFree(WiringTable.java:163)",
            ),
        )
        status(RobotPhase.IDLE, simSeconds = 11)

        assertEquals(
            "Teleop A did not initialise: \"intake\" needs a free motor port but all 4 of them are taken",
            awaitState<ScanState.Done>().failure,
        )
    }

    @Test
    fun aScanCanBeCancelledAndLeavesNothingToSay() {
        opened()
        scan.scan("Teleop A")
        awaitCall("init Teleop A")

        scan.cancel()

        rig.await(scan.state) { it == ScanState.Quiet }
        assertFalse(rig.settings.state.value.projects.single().scanOpMode != null)
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
