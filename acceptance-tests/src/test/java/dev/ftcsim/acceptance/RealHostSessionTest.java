package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.app.Machine;
import dev.ftcsim.app.RealHost;
import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.link.RemoteSimulatorLink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The application's own host, as the window uses it: {@code RealHost.openLink()} starts the real worker from the class
 * path file, on the Java that runs this test, and a session on the fixture robot program runs in it and drives. The
 * link, the session and the worker are the real ones; what the window would add is the window.
 */
@Tag("two-process")
@Timeout(600)
class RealHostSessionTest {
    private static final Duration PATIENCE = Duration.ofMinutes(3);

    @TempDir
    Path temp;

    @Test
    void theHostStartsTheRealWorkerAndASessionOnTheFixtureRobotRunsAndDrivesInItAndClosingLeavesNothingBehind() throws Exception {
        RealHost host = WorkerFixtures.realHost(temp, Machine.real());
        RemoteSimulatorLink link = (RemoteSimulatorLink) host.openLink();
        long pid = link.workerPid().orElseThrow();
        LinkRecorder ui = new LinkRecorder(link, PATIENCE);
        try {
            ui.waitFor(() -> !ui.catalogues.isEmpty(), "the catalogue");

            link.open(new SessionRequest(WorkerFixtures.programFolder(temp).toString(), "none", Alliance.RED, 1L, 0, 0, 0, 1.0));
            ui.waitFor(() -> !ui.opened.isEmpty(), "the session to open");
            assertTrue(
                    ui.opened.get(0).opModes().stream().anyMatch(opMode -> opMode.name().equals("Mecanum Drive")),
                    ui.opened.get(0).opModes().toString());

            link.init("Mecanum Drive");
            ui.waitFor(() -> ui.status != null && ui.status.phase() == RobotPhase.INIT, "Init");
            link.start();
            ui.waitFor(() -> ui.status.phase() == RobotPhase.RUNNING, "Start");
            link.setGamepad(1, GamepadState.sticks(0f, -1f, 0f, 0f));
            ui.waitFor(() -> LinkRecorder.chassis(ui.snapshot).map(body -> body.x() > 0.3f).orElse(false), "the chassis to drive 0.3 m forward");
            long simTime = ui.status.simTimeNanos();
            ui.waitFor(() -> ui.status.simTimeNanos() > simTime, "simulated time to go on");
            assertEquals(List.of(), ui.status.problems());

            link.close();

            assertEquals(OptionalInt.of(0), link.workerExitCode(), "the worker ended because it was asked to");
            assertFalse(WorkerFixtures.alive(pid), "no worker process is left");
            assertEquals(List.of(), ui.failures);
            assertEquals(List.of(), ui.refusals);
            List<String> log = Files.readAllLines(link.workerLog().orElseThrow());
            assertEquals(
                    List.of(),
                    log.stream().filter(line -> line.contains("[stderr] WARNING")).toList(),
                    "the worker's error output holds no warning of the Java runtime");
        } finally {
            link.close();
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            host.close();
        }
    }
}
