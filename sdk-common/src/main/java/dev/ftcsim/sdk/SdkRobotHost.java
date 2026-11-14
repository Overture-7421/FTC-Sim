package dev.ftcsim.sdk;

import android.app.Application;
import android.util.Log;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.util.RobotLog;
import dev.ftcsim.contracts.hooks.RobotExit;
import dev.ftcsim.contracts.hooks.RobotUnwind;
import dev.ftcsim.contracts.robot.GamepadState;
import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.RobotEnvironment;
import dev.ftcsim.contracts.robot.RobotHost;
import dev.ftcsim.contracts.robot.RobotPhase;
import dev.ftcsim.contracts.robot.TelemetrySnapshot;
import dev.ftcsim.sdk.hardware.SimHardware;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.firstinspires.ftc.robotcore.internal.system.AppUtil;
import org.firstinspires.ftc.robotcore.internal.ui.GamepadUser;

/**
 * A robot controller whose OpModes the real SDK runs. The SDK's OpMode manager owns the state machine; this class
 * plays the Driver Station's part (choose, Init, Start, Stop) and the event loop's part (one pass per call).
 */
final class SdkRobotHost implements RobotHost {
    /** A change of state completes within a pass or two; more means the SDK's manager is not settling. */
    private static final int MAX_PASSES_TO_SETTLE = 100;

    /** The SDK names the thread that runs user code like this (see {@code OpModeInternal}). */
    private static final String OPMODE_THREAD = "OpModeThread";

    private final DriverStationDisplay display = new DriverStationDisplay();
    private final GamepadFeed gamepads = new GamepadFeed();
    private final SimOpModeManager opModeManager;
    private final ControllerLifecycle lifecycle;
    private final Thread.UncaughtExceptionHandler previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler();

    SdkRobotHost(Path storageRoot, List<Path> program, List<Path> team, Consumer<String> log, RobotEnvironment environment) {
        Log.Sink sink = (priority, tag, message) -> log.accept(logcatLine(priority, tag, message));
        ScanLog scanLog = new ScanLog(sink, teamClasses(program, team));
        SdkBootstrap.boot(storageRoot, program, scanLog);
        Application application = AppUtil.getDefContext();
        OpModeCatalog.register(scanLog);
        lifecycle = new ControllerLifecycle(application);
        // Library threads started by the hooks may die at once, so the handler is in place before any hook runs.
        Thread.setDefaultUncaughtExceptionHandler(this::uncaughtError);
        try {
            lifecycle.created();

            // The manager is born with a placeholder hardware map, as in the SDK's FtcEventLoop; the map the OpModes see
            // can only be created once the manager exists, because it listens to the manager's OpMode notifications.
            opModeManager = new SimOpModeManager(lifecycle.activity(), new HardwareMap(application, null), display);
            opModeManager.init(new SimEventLoopManager(application, display));
            opModeManager.setHardwareMap(
                    SimHardware.createHardwareMap(
                            application, opModeManager, environment.hubs(), environment.directory(), environment.cameras()));
            lifecycle.eventLoopCreated(opModeManager);
            settle();
        } catch (RuntimeException | Error failure) {
            Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler);
            throw failure;
        }
    }

    @Override
    public List<OpModeDescriptor> opModes() {
        return OpModeCatalog.list();
    }

    @Override
    public void init(String opModeName) {
        if (opModes().stream().noneMatch(opMode -> opMode.name().equals(opModeName))) {
            throw new IllegalArgumentException("The robot program declares no OpMode named '" + opModeName + "'");
        }
        opModeManager.initOpMode(opModeName);
        settle();
    }

    @Override
    public void start() {
        if (phase() != RobotPhase.INIT) {
            throw new IllegalStateException("Start needs an initialised OpMode, but the robot is " + phase());
        }
        opModeManager.startActiveOpMode();
        settle();
    }

    @Override
    public void stop() {
        opModeManager.stopActiveOpMode();
        settle();
    }

    @Override
    public void eventLoop(GamepadState gamepad1, GamepadState gamepad2) {
        gamepads.update(gamepad1, gamepad2);
        runLoopPass();
    }

    @Override
    public RobotPhase phase() {
        return opModeManager.phase();
    }

    @Override
    public TelemetrySnapshot telemetry() {
        return display.snapshot();
    }

    /** Stops what runs, as the SDK does when the robot controller shuts down. */
    @Override
    public void close() {
        Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler);
        opModeManager.stopActiveOpMode();
        opModeManager.teardown();
        lifecycle.destroyed();
    }

    /** The JVM's default reporting is replaced by this handler, so an error in any other thread is shown here rather than lost. */
    private void uncaughtError(Thread thread, Throwable error) {
        if (error instanceof RobotExit exit) {
            opModeManager.robotProgramExited(exit);
            return;
        }
        if (error instanceof RobotUnwind) {
            // The host ended this thread on purpose, so it is no crash; RunawayGuard has told the user why.
            return;
        }
        if (OPMODE_THREAD.equals(thread.getName())) {
            opModeManager.opModeThreadDied(error);
            return;
        }
        RobotLog.ee("UncaughtException", error, "Thread '" + thread.getName() + "' died from an uncaught exception");
        lifecycle.threadDied(thread, error);
        if (previousUncaughtHandler != null) {
            previousUncaughtHandler.uncaughtException(thread, error);
        }
    }

    /** Runs event loop passes with the gamepads as they were until the SDK has carried out the change that was asked for. */
    private void settle() {
        for (int pass = 0; opModeManager.isChangePending(); pass++) {
            if (pass == MAX_PASSES_TO_SETTLE) {
                throw new IllegalStateException("The SDK's OpMode manager has not settled after " + pass + " event loop passes");
            }
            runLoopPass();
        }
    }

    private void runLoopPass() {
        opModeManager.runEventLoopPass(gamepads.opModeGamepads(), gamepads.latest(GamepadUser.ONE), gamepads.latest(GamepadUser.TWO));
    }

    /** The classes of the team's own code, or null when the whole program is the team's, which leaves nothing to summarise. */
    private static Set<String> teamClasses(List<Path> program, List<Path> team) {
        if (team.equals(program)) {
            return null;
        }
        try {
            return ApplicationPackage.classNames(team);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the classes of the team's code", e);
        }
    }

    private static String logcatLine(int priority, String tag, String message) {
        char level = switch (priority) {
            case Log.VERBOSE -> 'V';
            case Log.DEBUG -> 'D';
            case Log.INFO -> 'I';
            case Log.WARN -> 'W';
            case Log.ERROR -> 'E';
            default -> 'A';
        };
        return level + "/" + tag + ": " + message;
    }
}
