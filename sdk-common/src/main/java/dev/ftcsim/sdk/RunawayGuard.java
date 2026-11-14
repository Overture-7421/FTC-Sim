package dev.ftcsim.sdk;

import com.qualcomm.robotcore.util.RobotLog;
import dev.ftcsim.contracts.hooks.Hooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The SDK's stuck-code monitor for the Stop of an OpMode, with the same timeouts measured on the host's clock, which
 * for a simulated host is simulated time: pausing the simulation or running it slower cannot trip it, and neither
 * can running it faster make it late. The monitor on a robot ends with the Robot Controller app restarting, which a
 * simulated session cannot do and must not need.
 *
 * <p>Like the SDK's monitor, it first lets the hubs' bus lock throw at whatever loop touches a hub, for the grace
 * period that starts after the timeout. If the OpMode is still running then, the SDK would restart the app; here the
 * OpMode's thread is asked to end instead, which the host does by unwinding it where it next looks at the clock, a
 * loop or a sleep, and a thread that swallows that is asked again. One that still does not end is abandoned, and the
 * SDK's wait for it is released, so that the session carries on and can run another OpMode.
 */
final class RunawayGuard {
    /** The SDK waits this much longer, with the bus lock throwing, before it restarts the app. */
    static final long GRACE_MILLIS = 100;
    private static final long ASK_AGAIN_MILLIS = 250;
    private static final int TIMES_TO_ASK = 4;

    private static final String FORCE_STOPPED = "User OpMode was stuck in %s, but was able to be force stopped without restarting the app. ";
    private static final String HINT_ITERATIVE = "It appears this was an iterative OpMode; make sure you aren't using your own loops.";
    private static final String HINT_LINEAR = "It appears this was a linear OpMode; make sure you are calling opModeIsActive() in any loops.";

    private final SimOpModeManager manager;
    private final String method;
    private final long timeoutMillis;
    private final List<Runnable> watchdogs = new ArrayList<>();
    private boolean busLockThrowing;
    private boolean gaveUp;
    private boolean finished;

    RunawayGuard(SimOpModeManager manager, int timeoutMillis, String method) {
        this.manager = manager;
        this.timeoutMillis = timeoutMillis;
        this.method = method;
    }

    synchronized void start() {
        after(timeoutMillis, this::throwAtTheBusLock);
        after(timeoutMillis + GRACE_MILLIS, this::giveUpOnTheOpMode);
    }

    /** The OpMode finished: forget the escalation, and say what the SDK would have said if it was forced out. */
    synchronized void stop() {
        finished = true;
        watchdogs.forEach(Runnable::run);
        if (busLockThrowing) {
            manager.throwAtTheBusLock(false);
            if (!gaveUp) {
                RobotLog.addGlobalWarningMessage(String.format(Locale.ROOT, FORCE_STOPPED, method) + hint());
            }
        }
    }

    private String hint() {
        return manager.isLinearOpModeActive() ? HINT_LINEAR : HINT_ITERATIVE;
    }

    private void after(long millis, Runnable action) {
        watchdogs.add(Hooks.watchdog(TimeUnit.MILLISECONDS.toNanos(millis), () -> {
            synchronized (this) {
                if (!finished) {
                    action.run();
                }
            }
        }));
    }

    private void throwAtTheBusLock() {
        busLockThrowing = true;
        manager.throwAtTheBusLock(true);
    }

    private void giveUpOnTheOpMode() {
        gaveUp = true;
        manager.throwAtTheBusLock(false);
        String message = String.format(
                Locale.ROOT,
                "OpMode '%s' ignored Stop for %.1f s (stuck in %s); on a robot the Robot Controller app would restart. "
                        + "The simulator ends the OpMode's thread instead. %s",
                manager.getActiveOpModeName(),
                (timeoutMillis + GRACE_MILLIS) / 1000.0,
                method,
                hint());
        RobotLog.setGlobalErrorMsg(message);
        RobotLog.e(message);
        askToEnd(1);
    }

    private void askToEnd(int attempt) {
        if (attempt > TIMES_TO_ASK) {
            abandon();
            return;
        }
        Hooks.unwindRobotThread();
        after(ASK_AGAIN_MILLIS, () -> askToEnd(attempt + 1));
    }

    private void abandon() {
        boolean abandoned = Hooks.abandonRobotThread();
        RobotLog.e("The OpMode thread did not end when asked to; " + (abandoned ? "it was abandoned" : "nothing could be done about it")
                + " and the SDK's wait for it is over");
        manager.forceOpModeThreadFinished();
    }
}
