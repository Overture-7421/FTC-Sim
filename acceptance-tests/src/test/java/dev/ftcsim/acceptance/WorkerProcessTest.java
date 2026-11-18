package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.contracts.session.MatchState;
import dev.ftcsim.contracts.session.SessionInfo;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.link.RemoteSimulatorLink;
import dev.ftcsim.link.WorkerLauncher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The simulator in a worker process of its own and the UI's side of the port in this one: the real
 * {@code RemoteSimulatorLink} starts the real worker, which runs the real SDK on the fixture robot program, and the
 * robot drives when a gamepad is pushed here. Nothing is assumed about how fast any of it goes.
 */
@Tag("two-process")
@Timeout(600)
class WorkerProcessTest {

    private static final Duration PATIENCE = Duration.ofMinutes(3);

    @TempDir
    Path temp;

    /** A prepared program as the worker reads it from a folder: the fixture's class paths, in a file, not on a command line. */
    private Path programFolder() throws IOException {
        return WorkerFixtures.programFolder(temp);
    }

    private WorkerLauncher launcher() {
        return new WorkerLauncher(RobotWorld.classpath("ftcsim.workerClasspath"), temp.resolve("worker logs"))
                .nativeDirectory(Path.of(System.getProperty("ftcsim.nativeDirectory")))
                .connectTimeout(PATIENCE)
                .shutdownGrace(Duration.ofSeconds(60));
    }

    private static boolean alive(long pid) {
        return WorkerFixtures.alive(pid);
    }

    /** What the UI heard, for the waits of this test. */
    private LinkRecorder ui;

    /** A recorder for the link; a wait that gives up then says what the UI heard and what the worker wrote. */
    private LinkRecorder listen(RemoteSimulatorLink link) {
        ui = new LinkRecorder(link, PATIENCE);
        return ui;
    }

    private void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        ui.waitFor(condition, what);
    }

    private static Optional<BodyState> chassis(WorldSnapshot snapshot) {
        return LinkRecorder.chassis(snapshot);
    }

    @Test
    void theUiDrivesTheFixtureRobotInAWorkerProcessAndClosingTheLinkLeavesNoProcessBehind() throws Exception {
        RemoteSimulatorLink link = launcher().launch();
        long pid = link.workerPid().orElseThrow();
        listen(link);
        try {
            // The worker tells what can be asked for as soon as it has connected, whether or not a listener was there yet.
            waitFor(() -> !ui.catalogues.isEmpty(), "the catalogue");
            Catalogue catalogue = ui.catalogues.get(0);
            assertEquals(List.of("none", "decode", "biobuzz"), catalogue.fields().stream().map(FieldChoice::id).toList());
            assertTrue(catalogue.fields().get(0).startPoses().isEmpty(), "a bare floor offers no start pose");
            for (FieldChoice game : catalogue.fields().subList(1, 3)) {
                assertFalse(game.displayName().isBlank(), game.id());
                assertFalse(game.startPoses().isEmpty(), game.id() + " offers start poses");
            }
            assertFalse(catalogue.fields().get(1).humanActions().isEmpty(), "DECODE offers human actions");
            assertTrue(catalogue.fields().get(2).humanActions().isEmpty(), "BIOBUZZ needs no human-action buttons");
            assertFalse(catalogue.motors().isEmpty(), "the motors a mapping may name");

            // A request that cannot be met is a failure the UI can read, and the worker goes on.
            RobotMapping oneWheel = RobotMapping.empty("One wheel").withMotors(List.of(new MotorMapping("fl", Slot.FRONT_LEFT, "", false)));
            link.open(new SessionRequest(oneWheel, programFolder().toString(), "none", Alliance.RED, 1L, 0, 0, 0, 1.0));
            waitFor(() -> !ui.failures.isEmpty(), "the failure of a mapping with a problem");
            assertTrue(ui.failures.get(0).contains("Map all four wheels or none"), ui.failures.toString());
            assertTrue(alive(pid), "a request that fails does not end the worker");

            link.open(new SessionRequest(HornetMapping.mapping(), programFolder().toString(), "none", Alliance.RED, 1L, 0, 0, 0, 1.0));
            waitFor(() -> !ui.opened.isEmpty(), "the session to open");
            SessionInfo info = ui.opened.get(0);
            assertNull(info.field(), "a bare floor has no field to draw");
            assertTrue(
                    info.opModes().stream().anyMatch(opMode -> opMode.name().equals("Mecanum Drive") && opMode.kind() == OpModeKind.TELEOP),
                    info.opModes().toString());
            assertEquals("Hornet 2026 (estimated)", info.robot().name());
            assertTrue(info.robot().motors().containsAll(List.of("frontLeft", "backLeft", "frontRight", "backRight")));

            link.init("Mecanum Drive");
            waitFor(() -> ui.status != null && ui.status.phase() == RobotPhase.INIT, "Init");
            link.start();
            waitFor(() -> ui.status.phase() == RobotPhase.RUNNING, "Start");
            link.setGamepad(1, GamepadState.sticks(0f, -1f, 0f, 0f));

            waitFor(() -> chassis(ui.snapshot).map(body -> body.x() > 0.3f).orElse(false), "the chassis to drive 0.3 m forward");
            BodyState driving = chassis(ui.snapshot).orElseThrow();
            assertEquals(1, driving.kind(), "the chassis is the body of kind 1");
            long simTime = ui.status.simTimeNanos();
            waitFor(() -> ui.status.simTimeNanos() > simTime, "simulated time to go on");
            assertEquals(List.of(), ui.status.problems());
            assertTrue(ui.status.achievedSpeed() > 0, "achieved speed " + ui.status.achievedSpeed());
            assertTrue(ui.status.hardware().stream().anyMatch(readout -> readout.name().equals("frontLeft")), ui.status.hardware().toString());
            int init = ui.phases.indexOf(RobotPhase.INIT);
            assertTrue(init >= 0 && init < ui.phases.indexOf(RobotPhase.RUNNING), "the phases went from Init to Running: " + ui.phases);

            link.setSpeed(2.0);
            link.setPaused(true);
            waitFor(() -> ui.status.speed() == 2.0 && ui.status.paused(), "the speed and the pause to reach the simulation");
            link.setPaused(false);
            waitFor(() -> !ui.status.paused(), "the simulation to go on");

            link.humanAction("feed");
            waitFor(() -> !ui.refusals.isEmpty(), "the refusal of an action on a bare floor");
            assertTrue(ui.refusals.get(0).contains("no field"), ui.refusals.toString());

            // Starting again from the beginning: the same session, the robot back where it started and idle.
            long stepBefore = ui.snapshot.stepIndex();
            link.reset();
            waitFor(() -> ui.opened.size() == 2, "the session to open again");
            waitFor(() -> ui.snapshot.stepIndex() < stepBefore && ui.status.phase() == RobotPhase.IDLE, "the first snapshot of the new session");
            assertEquals(info, ui.opened.get(1), "the same program and robot");
            assertTrue(Math.abs(chassis(ui.snapshot).orElseThrow().x()) < 0.2f, "back at the start: " + chassis(ui.snapshot));
            assertEquals(2.0, ui.status.speed(), "a reset keeps the speed that was set");

            link.close();

            assertEquals(OptionalInt.of(0), link.workerExitCode(), "the worker ended because it was asked to");
            assertFalse(alive(pid), "no worker process is left");
            assertEquals(1, ui.failures.size(), "only the profile that does not exist failed to open: " + ui.failures);
            assertEquals(1, ui.refusals.size(), "and only the action on a bare floor was refused: " + ui.refusals);
            assertEquals(1, ui.catalogues.size(), "the catalogue is told once, however many sessions come and go");
            assertEquals(List.of("ftcsim-link-dispatcher"), ui.threads.stream().distinct().toList(), "one thread called the listener");
            assertTrue(Files.isRegularFile(link.workerLog().orElseThrow()));
        } finally {
            link.close();
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test
    void aTimedMatchIsRefusedWhenItCannotBeArmedAndOtherwisePlayedThroughItsPeriodsAcrossTheProcessBoundary() throws Exception {
        RemoteSimulatorLink link = launcher().launch();
        long pid = link.workerPid().orElseThrow();
        listen(link);
        try {
            link.open(new SessionRequest(HornetMapping.mapping(), programFolder().toString(), "none", Alliance.RED, 1L, 0, 0, 0, 1.0));
            waitFor(() -> !ui.opened.isEmpty() && ui.status != null, "the session to open");

            link.init("Counting");
            waitFor(() -> ui.status.phase() == RobotPhase.INIT, "Init");
            link.armMatch("", "Counting");
            waitFor(() -> ui.refusals.size() == 1, "the refusal to arm a match while an OpMode is initialised");
            assertTrue(ui.refusals.get(0).contains("initialised") && ui.refusals.get(0).contains("Press Stop"), ui.refusals.get(0));
            link.stop();
            waitFor(() -> ui.status.phase() == RobotPhase.IDLE, "Stop");
            link.startMatch();
            waitFor(() -> ui.refusals.size() == 2, "the refusal to start a match that is not armed");
            assertTrue(ui.refusals.get(1).contains("No match is armed"), ui.refusals.get(1));
            link.start();
            waitFor(() -> ui.refusals.size() == 3, "the refusal to press Start with nothing initialised");
            assertTrue(ui.refusals.get(2).contains("initialised OpMode"), ui.refusals.get(2));
            assertTrue(link.isUsable() && alive(pid), "a refused command is not the end of the link or of the worker");
            assertEquals(MatchState.NONE, ui.status.match().state());

            link.armMatch("Linear Auto", "Counting");
            waitFor(() -> ui.status.match().state() == MatchState.ARMED, "the match to be armed");
            assertEquals(RobotPhase.INIT, ui.status.phase(), "arming initialises the first OpMode");
            link.init("Counting");
            waitFor(() -> ui.refusals.size() == 4, "the refusal of Init while a match is armed");
            assertTrue(ui.refusals.get(3).contains("while a match is armed"), ui.refusals.get(3));
            link.start();
            waitFor(() -> ui.refusals.size() == 5, "the refusal of Start while a match is armed");
            assertTrue(ui.refusals.get(4).contains("while a match is armed"), ui.refusals.get(4));
            assertEquals(MatchState.ARMED, ui.status.match().state(), "the refusals changed nothing");
            link.setSpeed(10.0);
            link.startMatch();
            waitFor(() -> ui.status.match().state() == MatchState.AUTONOMOUS, "the match to start");
            assertEquals(30_000_000_000L, ui.status.match().phaseDurationNanos());
            link.init("Counting");
            waitFor(() -> ui.refusals.size() == 6, "the refusal of Init during a match");
            assertTrue(ui.refusals.get(5).contains("during a match"), ui.refusals.get(5));

            waitFor(() -> ui.status.match().state() == MatchState.ENDED, "the match to end, in simulated time");
            List<MatchState> seen = new ArrayList<>(ui.matchStates);
            assertEquals(MatchState.NONE, seen.get(0));
            assertTrue(seen.containsAll(List.of(MatchState.ARMED, MatchState.AUTONOMOUS, MatchState.TELEOP, MatchState.ENDED)), seen.toString());
            assertTrue(
                    IntStream.range(1, seen.size()).allMatch(i -> seen.get(i).ordinal() > seen.get(i - 1).ordinal()),
                    "the match only goes forward through its periods: " + seen);
            assertEquals(RobotPhase.IDLE, ui.status.phase(), "the driver-controlled OpMode was stopped at the end");
            assertEquals(List.of(), ui.failures, "refusals are not failures");
            assertEquals(6, ui.refusals.size(), ui.refusals.toString());
            assertTrue(ui.status.simTimeNanos() >= 158_000_000_000L, "the match took 158 s of simulated time: " + ui.status.simTimeNanos());
        } finally {
            link.close();
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
        assertFalse(alive(pid));
    }

    @Test
    void aGamepadPushedInTheUiIsWhatTheRobotProgramShowsInTheStatusTelemetry() throws Exception {
        RemoteSimulatorLink link = launcher().launch();
        long pid = link.workerPid().orElseThrow();
        listen(link);
        try {
            link.open(new SessionRequest(HornetMapping.mapping(), programFolder().toString(), "none", Alliance.BLUE, 1L, 0, 0, 0, 1.0));
            waitFor(() -> !ui.opened.isEmpty(), "the session to open");
            link.init("Gamepad Echo");
            waitFor(() -> ui.status != null && ui.status.phase() == RobotPhase.INIT, "Init");
            link.start();
            waitFor(() -> ui.status.phase() == RobotPhase.RUNNING, "Start");

            GamepadState pressed = new GamepadState(
                    0.5f, -0.25f, 0f, 0f, 0f, 0f, false, false, false, false, true, false, false, false, false, false, false, false,
                    false, false, false);
            link.setGamepad(2, pressed);

            waitFor(
                    () -> ui.status.telemetry().lines().stream().anyMatch(line -> line.startsWith("pad2 lx=0.50 ly=-0.25") && line.contains(" a=true ")),
                    "the robot program to report what gamepad 2 does");
            assertTrue(ui.status.telemetry().lines().stream().anyMatch(line -> line.startsWith("pad1 lx=0.00")), ui.status.telemetry().toString());
            assertEquals(List.of(), ui.failures);
        } finally {
            link.close();
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
        assertFalse(alive(pid));
    }

    @Test
    void aRobotProgramThatThrowsShowsItsStackTraceInTheStatusAndNeitherTheWorkerNorTheLinkGoesDown() throws Exception {
        RemoteSimulatorLink link = launcher().launch();
        long pid = link.workerPid().orElseThrow();
        listen(link);
        try {
            link.open(new SessionRequest(HornetMapping.mapping(), programFolder().toString(), "none", Alliance.RED, 1L, 0, 0, 0, 1.0));
            waitFor(() -> !ui.opened.isEmpty(), "the session to open");
            link.init("Crasher");
            waitFor(() -> ui.status != null && ui.status.phase() == RobotPhase.INIT, "Init");
            link.start();

            waitFor(() -> ui.status.telemetry().error().contains("boom from user code"), "the stack trace of the crash");
            assertTrue(alive(pid), "a crashing robot program does not end the worker");

            link.reset();
            waitFor(() -> ui.opened.size() == 2 && ui.status.telemetry().error().isEmpty(), "a fresh session after the crash");
            link.init("Mecanum Drive");
            waitFor(() -> ui.status.phase() == RobotPhase.INIT, "Init in the fresh session");
            assertTrue(alive(pid));
            assertTrue(
                    ui.failures.stream().noneMatch(message -> message.startsWith("The simulator worker")),
                    "the link itself did not fail: " + ui.failures);
        } finally {
            link.close();
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
        assertFalse(alive(pid));
    }

    @Test
    void aWorkerThatIsKilledFromOutsideIsReportedOnceAndTheUiGoesOn() throws Exception {
        RemoteSimulatorLink link = launcher().launch();
        long pid = link.workerPid().orElseThrow();
        listen(link);
        try {
            ProcessHandle.of(pid).orElseThrow().destroyForcibly();

            waitFor(() -> !ui.failures.isEmpty(), "the link to notice that the worker is gone");
            assertTrue(ui.failures.get(0).contains("exited with code"), ui.failures.get(0));
            link.open(new SessionRequest(HornetMapping.mapping(), programFolder().toString(), "none", Alliance.RED, 1L, 0, 0, 0, 1.0));
            link.setGamepad(1, GamepadState.NEUTRAL);
            link.close();
            TimeUnit.MILLISECONDS.sleep(300);
            assertEquals(1, ui.failures.size(), ui.failures.toString());
            assertFalse(alive(pid));
        } finally {
            link.close();
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }
}
