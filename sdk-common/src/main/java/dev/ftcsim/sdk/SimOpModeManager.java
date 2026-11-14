package dev.ftcsim.sdk;

import android.app.Activity;
import android.util.Log;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.hardware.Gamepad;
import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.hardware.RobotCoreLynxUsbDevice;
import com.qualcomm.robotcore.robocol.TelemetryMessage;
import com.qualcomm.robotcore.util.RobotLog;
import dev.ftcsim.contracts.hooks.RobotExit;
import dev.ftcsim.contracts.robot.RobotPhase;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The SDK's own OpMode manager, with its state machine untouched. Only the places where the robot controller
 * would hand over to something the simulator does not have are replaced: the event loop (stop requests and
 * user telemetry), the Driver Station (the crash dialog), logcat (match logs) and the stuck-code monitor.
 *
 * <p>The SDK's monitor measures in real time how long user code takes to finish {@code stop()}, and if it takes more
 * than about a second it restarts the app, which needs a root activity, an alarm service and toast and dialog windows
 * that the simulator does not provide. A {@link RunawayGuard} takes its place and measures on the host's clock, so
 * that a paused or slowed simulation cannot trip it and an OpMode that ignores Stop is ended instead of the session.
 */
final class SimOpModeManager extends OpModeManagerImpl {
    private static final Field THREAD_FINISHED = threadFinishedField();

    private final DriverStationDisplay display;
    private final AtomicReference<OpMode> stopRequest = new AtomicReference<>();

    SimOpModeManager(Activity activity, HardwareMap placeholderHardwareMap, DriverStationDisplay display) {
        super(activity, placeholderHardwareMap);
        this.display = display;
    }

    @Override
    protected void detectStuck(int msTimeout, String method, Runnable runnable, boolean resetDebuggerCheck) {
        RunawayGuard guard = new RunawayGuard(this, msTimeout, method);
        guard.start();
        try {
            runnable.run();
        } finally {
            guard.stop();
        }
    }

    /** What the SDK's monitor does at the end of the timeout: every use of a hub's bus lock throws, to unwind a loop that uses it. */
    void throwAtTheBusLock(boolean on) {
        for (RobotCoreLynxUsbDevice device : hardwareMap.getAll(RobotCoreLynxUsbDevice.class)) {
            device.setThrowOnNetworkLockAcquisition(on);
        }
    }

    boolean isLinearOpModeActive() {
        return activeOpMode instanceof LinearOpMode;
    }

    /**
     * Releases the SDK's wait for the OpMode thread to finish, which it will never do if the thread was abandoned.
     * The SDK keeps the flag in a package-private field of its OpMode base class, and nothing else sets it.
     */
    void forceOpModeThreadFinished() {
        try {
            THREAD_FINISHED.setBoolean(activeOpMode, true);
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Field threadFinishedField() {
        try {
            Field field = OpMode.class.getSuperclass().getDeclaredField("opModeThreadFinished");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException missing) {
            throw new IllegalStateException(
                    "This SDK's OpMode base class has no 'opModeThreadFinished' flag, which the simulator sets when it abandons an"
                            + " OpMode thread that will not end",
                    missing);
        }
    }

    /**
     * One pass of the robot controller's event loop around the OpMode manager. It mirrors what the SDK's
     * {@code FtcEventLoop.loop()} does with it: honour a stop request made by the OpMode itself, then run the
     * state machine with the latest gamepad data.
     */
    void runEventLoopPass(Gamepad[] opModeGamepads, Gamepad latestGamepad1, Gamepad latestGamepad2) {
        try {
            OpMode stopRequested = stopRequest.getAndSet(null);
            if (stopRequested != null && getActiveOpMode() == stopRequested) {
                RobotLog.ii(TAG, "auto-stopping OpMode '%s'", getActiveOpModeName());
                stopActiveOpMode();
            }
            runActiveOpMode(opModeGamepads, latestGamepad1, latestGamepad2);
        } catch (NoClassDefFoundError missingClass) {
            recoverFromMissingClass(missingClass);
        }
    }

    /**
     * The SDK counts a class that user code cannot find as fatal to the whole app: the manager rethrows it on
     * every pass for as long as the OpMode that holds it stays active. A desktop session survives it. The display
     * shows which class is missing, and the OpMode is dropped so that the controller can return to idle.
     */
    private void recoverFromMissingClass(NoClassDefFoundError missingClass) {
        RobotLog.ee(TAG, missingClass, "User code needs a class that is not available");
        display.showCrash(Log.getStackTraceString(missingClass));
        callActiveOpModeStop();
        setActiveOpMode(null, DEFAULT_OP_MODE_NAME);
        initOpMode(DEFAULT_OP_MODE_NAME);
    }

    /**
     * The SDK's OpMode thread catches runtime exceptions and missing classes, and lets any other error, such as a
     * failed static initialiser or a missing native method, kill the thread unnoticed. The simulator shows the
     * error and has the controller stop the OpMode, as it would a crash.
     */
    void opModeThreadDied(Throwable error) {
        RobotLog.ee(TAG, error, "The OpMode thread died from an uncaught error");
        display.showCrash(Log.getStackTraceString(error));
        requestOpModeStop(getActiveOpMode());
    }

    /**
     * The robot program called {@code System.exit}, {@code Runtime.exit} or {@code Runtime.halt}. The simulator's process
     * is not the program's to end, so the thread that asked was unwound; the controller shows what was asked and stops
     * the OpMode, and the host reads the request from the kernel to end the session.
     */
    void robotProgramExited(RobotExit exit) {
        RobotLog.setGlobalErrorMsg(exit.getMessage() + "; the simulator does not end its process, so the program was stopped instead");
        requestOpModeStop(getActiveOpMode());
    }

    RobotPhase phase() {
        if (DEFAULT_OP_MODE_NAME.equals(getActiveOpModeName())) {
            return RobotPhase.IDLE;
        }
        return opModeState == OpModeState.LOOPING ? RobotPhase.RUNNING : RobotPhase.INIT;
    }

    /** True while a requested change of OpMode or state has not yet been carried out by an event loop pass. */
    boolean isChangePending() {
        return ((AtomicReference<?>) nextOpModeState).get() != null
                || opModeSwapNeeded
                || callToInitNeeded
                || callToStartNeeded;
    }

    /** Users reach this through {@code OpMode.requestOpModeStop()}, from any thread; the next loop pass acts on it. */
    @Override
    public void requestOpModeStop(OpMode opModeToStopIfActive) {
        stopRequest.set(opModeToStopIfActive);
    }

    /**
     * The SDK would hand the message to the Driver Station. The OpMode always asks for immediate transmission
     * and {@code TelemetryImpl} has already applied its own interval, so no further throttling is needed.
     */
    @Override
    public void refreshUserTelemetry(TelemetryMessage telemetry, double sInterval) {
        if (telemetry.hasData()) {
            eventLoopManager.sendTelemetryData(telemetry);
        }
    }

    /** The Driver Station pops up the stack trace of user code that crashed; the display shows it as the error. */
    @Override
    protected void handleUserCodeException(Exception e) {
        super.handleUserCodeException(e);
        display.showCrash(Log.getStackTraceString(e));
    }

    /** Match logs are cut out of logcat by a shell command that does not exist on the desktop; the session log replaces them. */
    @Override
    protected void doMatchLoggingWork(String opModeName, boolean isSystemOpMode) {}
}
