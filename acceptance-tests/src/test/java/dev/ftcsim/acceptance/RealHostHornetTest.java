package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.ftcsim.app.Machine;
import dev.ftcsim.app.RealHost;
import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.importer.ImportProgress;
import dev.ftcsim.importer.ImportStage;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.importer.snapshot.SourceManifest;
import dev.ftcsim.link.RemoteSimulatorLink;
import dev.ftcsim.ui.host.ProgramPreparer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The application's host on a real team project, Hornet, unmodified. Both tests are slow and need what only a
 * developer's computer has, so each runs only when its property is given and says how to enable it otherwise:
 * {@code -Pftcsim.hornetPackage=<folder>} runs a session on an already prepared package in the real worker, and
 * {@code -Pftcsim.hornet=<checkout>} prepares the checkout through {@code RealHost.preparer()} (optionally with
 * {@code -Pftcsim.jdk=<JDK 17 to 21>} and {@code -Pftcsim.androidSdk=<folder>} where discovery would not find them).
 *
 * <p>Nothing here depends on what any OpMode of the team does: only on signals that every robot program gives.
 */
@Tag("two-process")
@Timeout(3600)
class RealHostHornetTest {
    private static final Duration PATIENCE = Duration.ofMinutes(5);

    /**
     * The OpMode the session runs. It is a stand-in: it is the one OpMode of this program that runs today without the
     * Limelight camera model, which is still being modelled. It should become "Teleop - Blue" once the camera model lands.
     */
    private static final String OP_MODE = "Tuning";

    /**
     * What a driver presses on gamepad 1 after Init, in order, for the stand-in OpMode to be startable: it is a menu and
     * refuses to start before something is chosen in it (down, then the right bumper twice choose the first entry of
     * the first folder). The test looks at none of the menu's text. "Teleop - Blue" has no menu, so this becomes empty.
     */
    private static final List<GamepadState> PRESSES_AFTER_INIT = List.of(pad(true, false), pad(false, true), pad(false, true));

    private static final long TENTH_OF_A_SECOND = 100_000_000L;

    private static final String HOW_TO_RUN_A_SESSION = "Skipped: give -Pftcsim.hornetPackage=<folder of Hornet's prepared package> "
            + "(and, the first time, -Pftcsim.hornet=<checkout> so that it is made).";
    private static final String HOW_TO_PREPARE = "Skipped: give -Pftcsim.hornet=<path to the Hornet checkout> to prepare it through the "
            + "application's preparer (optionally -Pftcsim.jdk=<JDK 17 to 21 home> -Pftcsim.androidSdk=<Android SDK folder>).";

    @TempDir
    Path temp;

    @Test
    void aSessionOnTheHornetPackageOpensInitialisesAndRunsInTheRealWorkerAndNothingGoesWrong() throws Exception {
        if (System.getProperty("ftcsim.hornetPackage") == null) {
            System.out.println("REAL-HOST-HORNET> " + HOW_TO_RUN_A_SESSION);
        }
        PreparedProgram prepared = HornetFixture.prepared();
        RealHost host = WorkerFixtures.realHost(temp, Machine.real());
        RemoteSimulatorLink link = (RemoteSimulatorLink) host.openLink();
        long pid = link.workerPid().orElseThrow();
        LinkRecorder ui = new LinkRecorder(link, PATIENCE);
        try {
            ui.waitFor(() -> !ui.catalogues.isEmpty(), "the catalogue");
            FieldChoice field = ui.catalogues.get(0).fields().stream().filter(choice -> choice.id().equals("decode")).findFirst().orElseThrow();
            StartPose start = field.startPoses().get(0);

            link.open(new SessionRequest(prepared.directory().toString(), field.id(), Alliance.BLUE, 1L, start.x(), start.y(), start.headingRadians(), 1.0));
            ui.waitFor(() -> !ui.opened.isEmpty(), "the session to open");
            assertTrue(ui.opened.get(0).opModes().stream().anyMatch(opMode -> opMode.name().equals(OP_MODE)), ui.opened.get(0).opModes().toString());

            link.init(OP_MODE);
            ui.waitFor(() -> ui.status != null && ui.status.phase() == RobotPhase.INIT, "the OpMode to reach Init");
            waitSimulated(ui, 10 * TENTH_OF_A_SECOND, "the OpMode to settle in Init");
            for (GamepadState press : PRESSES_AFTER_INIT) {
                link.setGamepad(1, press);
                waitSimulated(ui, TENTH_OF_A_SECOND, "a button to be held for a tenth of a second");
                link.setGamepad(1, GamepadState.NEUTRAL);
                waitSimulated(ui, TENTH_OF_A_SECOND, "the button to be let go");
            }
            assertTrue(ui.status.telemetry().lines().size() > 0, "an OpMode in Init has something to show: " + ui.status.telemetry());
            link.start();
            ui.waitFor(() -> ui.status.phase() == RobotPhase.RUNNING, "the OpMode to reach Running");
            long simTime = ui.status.simTimeNanos();
            ui.waitFor(() -> ui.status.simTimeNanos() > simTime, "simulated time to advance");
            ui.waitFor(() -> !ui.status.telemetry().lines().isEmpty(), "the OpMode to show a line of telemetry");

            // What the window shows as an error: a failed session, a refused command and the robot program's own error.
            // The simulator's problems are warnings there, which a run on a program this size may well have.
            assertEquals(List.of(), ui.failures);
            assertEquals(List.of(), ui.refusals);
            assertEquals("", ui.status.telemetry().error());
            System.out.println("REAL-HOST-HORNET> simulator problems (warnings): " + ui.status.problems());
            System.out.println("REAL-HOST-HORNET> telemetry: " + ui.status.telemetry().lines());

            link.close();

            assertEquals(OptionalInt.of(0), link.workerExitCode(), "the worker exits cleanly when the link closes");
            assertFalse(WorkerFixtures.alive(pid), "no worker process is left");
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

    /** Gamepad 1 with the d-pad's down button, or the right bumper, pressed and nothing else. */
    private static GamepadState pad(boolean down, boolean rightBumper) {
        return new GamepadState(
                0f, 0f, 0f, 0f, 0f, 0f, false, down, false, false, false, false, false, false, false, rightBumper, false, false, false,
                false, false);
    }

    /** Lets simulated time go on by {@code nanos} from now, however fast the worker runs it. */
    private static void waitSimulated(LinkRecorder ui, long nanos, String what) throws InterruptedException {
        long until = ui.status.simTimeNanos() + nanos;
        ui.waitFor(() -> ui.status.simTimeNanos() >= until, what);
    }

    @Test
    void thePreparerMakesAPackageOfTheHornetCheckoutLeavesTheCheckoutAloneAndDoesNotBuildTheSameSourceTwice() throws Exception {
        String checkout = System.getProperty("ftcsim.hornet");
        if (checkout == null) {
            System.out.println("REAL-HOST-HORNET> " + HOW_TO_PREPARE);
        }
        assumeTrue(checkout != null, HOW_TO_PREPARE);
        Path project = Path.of(checkout);
        RealHost host = WorkerFixtures.realHost(temp, WorkerFixtures.machine());
        try {
            ProgramPreparer preparer = host.preparer();
            ProgramPreparer.Inspection inspection = preparer.inspect(project);
            assertEquals(ProgramPreparer.Kind.PROJECT, inspection.kind(), inspection.summary());
            SourceManifest before = SourceManifest.scan(project, ImportProgress.SILENT);

            PrepareEvents first = new PrepareEvents();
            long started = System.nanoTime();
            preparer.prepare(new ProgramPreparer.Request(project, true), first);
            first.await(Duration.ofMinutes(40));
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);

            ProgramPreparer.Prepared prepared = first.prepared;
            System.out.println("REAL-HOST-HORNET> prepared in " + seconds + " s: " + prepared.opModes().size() + " OpModes " + prepared.opModes()
                    + ", " + prepared.warnings().size() + " warnings, FTC SDK " + prepared.sdkVersion());
            assertEquals(ImportStage.values().length + 1, first.steps.get(0).of());
            assertEquals(1, first.steps.get(0).number());
            assertEquals(ImportStage.VERIFY.label(), first.steps.get(first.steps.size() - 1).label(), "the preparation went through every stage: " + first.steps);
            assertFalse(first.output.isEmpty(), "the project's own build output reaches the window");
            assertTrue(prepared.folder().startsWith(temp), "the program was made where the application keeps programs: " + prepared.folder());
            assertEquals("11.1.0", prepared.sdkVersion());
            // The importer's static scan of the class files: the real SDK host lists one more, which Panels registers in code.
            assertTrue(prepared.opModes().size() == 10 || prepared.opModes().size() == 11, prepared.opModes().toString());
            assertTrue(prepared.warnings().stream().anyMatch(warning -> warning.contains("may register OpModes")), prepared.warnings().toString());
            assertTrue(Files.size(prepared.log()) > 0, "the build's log is kept");
            assertEquals(prepared.opModes().size(), PreparedProgram.open(prepared.folder()).opModes().size(), "the folder is a complete, valid package");
            ProgramPreparer.Inspection program = preparer.inspect(prepared.folder());
            assertEquals(ProgramPreparer.Kind.PREPARED_PROGRAM, program.kind(), program.summary());
            assertTrue(
                    before.compareWith(SourceManifest.scan(project, ImportProgress.SILENT)).isEmpty(),
                    "the checkout is exactly as it was; the importer's own hashes of its source and build files agree");

            PrepareEvents second = new PrepareEvents();
            started = System.nanoTime();
            preparer.prepare(new ProgramPreparer.Request(project, false), second);
            second.await(Duration.ofMinutes(5));
            System.out.println("REAL-HOST-HORNET> the same source again took " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms");
            assertEquals("Already prepared, source unchanged", second.steps.get(second.steps.size() - 1).label());
            assertEquals(prepared.folder(), second.prepared.folder());
            assertEquals(prepared.opModes(), second.prepared.opModes());
        } finally {
            host.close();
        }
    }
}
