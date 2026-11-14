package dev.ftcsim.sdk;

import android.app.Activity;
import android.app.Application;
import android.util.Log;
import com.qualcomm.robotcore.eventloop.opmode.OpModeManagerImpl;
import com.qualcomm.robotcore.util.RobotLog;
import dev.ftcsim.sdk.web.InProcessWebServer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.firstinspires.ftc.ftccommon.internal.AnnotatedHooksClassFilter;

/**
 * What the Robot Controller app does around the robot's life that is not running OpModes: it tells the libraries the
 * app has been created (their {@code @OnCreate} hooks), gives them the event loop once the robot exists
 * ({@code @OnCreateEventLoop}) and its web server ({@code @WebHandlerRegistrar}), and tells them the app is being
 * destroyed ({@code @OnDestroy}). The SDK's own {@link AnnotatedHooksClassFilter} finds and calls the hooks, in the
 * order the app calls them. The menu hooks run when the user opens the menu, which the simulator has no screen for.
 */
final class ControllerLifecycle {
    private static final String HOOKS_TAG = "AnnotatedHooksClassFilter";
    private static final String FAILURE_MARK = "failure while calling ";
    private static final String CAUSE_MARK = "Caused by: ";

    private final Application application;
    private final Activity activity;
    private final InProcessWebServer webServer = new InProcessWebServer();
    private final ControllerFaults faults = new ControllerFaults();

    ControllerLifecycle(Application application) {
        this.application = application;
        this.activity = new SimActivity(application);
        // AppUtil learns which activity is current only from the lifecycle callbacks, and SDK code such as the
        // OctoQuad driver finds the OpMode manager of "the activity" through it.
        application.bringToForeground(activity);
        // The registration holds the source weakly, so this object keeps it alive for the session.
        RobotLog.registerGlobalWarningSource(faults);
    }

    /** The activity the hooks receive as their context, as they do from the app. */
    Activity activity() {
        return activity;
    }

    /** The end of the app's {@code onCreate}. */
    void created() {
        call("OnCreate", filter -> filter.callOnCreateMethods(activity));
    }

    /** The robot controller service has started the robot's event loop and its web server. */
    void eventLoopCreated(OpModeManagerImpl opModeManager) {
        SimEventLoop eventLoop = new SimEventLoop(new SimActivity(application), opModeManager);
        call("OnCreateEventLoop", filter -> filter.callOnCreateEventLoopMethods(activity, eventLoop));
        webServer.start();
        call("WebHandlerRegistrar", filter -> filter.callWebHandlerRegistrarMethods(activity, webServer.getWebHandlerManager()));
    }

    /** The end of the app's {@code onDestroy}, after the robot has been shut down. */
    void destroyed() {
        call("OnDestroy", filter -> filter.callOnDestroyMethods(activity));
        webServer.stop();
    }

    /**
     * A thread of the robot world died of an exception nobody handled. On a robot that stops the app; here the
     * rest of the controller carries on, so the Driver Station is told which thread was lost and why.
     */
    void threadDied(Thread thread, Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        faults.add("Thread '" + thread.getName() + "' died: " + cause);
    }

    /**
     * The SDK catches whatever its hooks throw and only logs it, which nobody looks at on a desktop, so the log
     * lines of failed hooks are turned into warnings on the Driver Station, where they cannot be missed.
     */
    private void call(String kind, Consumer<AnnotatedHooksClassFilter> call) {
        Log.Sink logged = Log.sink();
        List<String> hookLog = new CopyOnWriteArrayList<>();
        Log.setSink((priority, tag, message) -> {
            logged.log(priority, tag, message);
            if (priority == Log.ERROR && HOOKS_TAG.equals(tag)) {
                hookLog.add(message);
            }
        });
        try {
            call.accept(AnnotatedHooksClassFilter.getInstance());
        } catch (LinkageError | AssertionError | StackOverflowError error) {
            // The SDK's own code failed before it reached the hooks, for example because it could not be linked.
            RobotLog.ee(HOOKS_TAG, error, "The " + kind + " hooks could not be called");
            faults.add("The " + kind + " hooks could not be called: " + error);
        } finally {
            Log.setSink(logged);
        }
        summaries(hookLog).forEach(faults::add);
    }

    /**
     * The SDK logs a failed hook as one line that names it, then the stack trace line by line. The innermost
     * "Caused by" line says why the hook failed; without one, the exception on the first line of the trace does.
     */
    private static List<String> summaries(List<String> lines) {
        List<String> summaries = new ArrayList<>();
        String failure = null;
        String reason = null;
        for (String line : lines) {
            if (line.startsWith(FAILURE_MARK)) {
                if (failure != null) {
                    summaries.add(failure + (reason == null ? "" : ": " + reason));
                }
                failure = line;
                reason = null;
            } else if (line.startsWith(CAUSE_MARK)) {
                reason = line.substring(CAUSE_MARK.length());
            } else if (reason == null && failure != null && !line.startsWith("\tat ")) {
                reason = line;
            }
        }
        if (failure != null) {
            summaries.add(failure + (reason == null ? "" : ": " + reason));
        }
        return summaries;
    }
}
