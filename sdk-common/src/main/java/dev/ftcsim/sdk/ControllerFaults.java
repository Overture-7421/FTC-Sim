package dev.ftcsim.sdk;

import com.qualcomm.robotcore.util.GlobalWarningSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What went wrong in the robot controller that on a robot would have stopped the app or one of its libraries, and
 * that nothing else shows: hooks that failed, and threads that died of an exception nobody handled. They are shown
 * on the Driver Station like any other warning. The SDK only logs a failing hook, which nobody looks at on a
 * desktop, and its warnings are cleared whenever an OpMode is initialised; a fault does not go away during a
 * session, so this source never lets go of what it was told.
 */
final class ControllerFaults implements GlobalWarningSource {
    private final List<String> faults = new CopyOnWriteArrayList<>();
    private final AtomicInteger suppressions = new AtomicInteger();

    void add(String fault) {
        faults.add(fault);
    }

    @Override
    public String getGlobalWarning() {
        return suppressions.get() > 0 ? "" : String.join("\n", faults);
    }

    @Override
    public boolean shouldTriggerWarningSound() {
        return false;
    }

    @Override
    public void suppressGlobalWarning(boolean suppress) {
        suppressions.addAndGet(suppress ? 1 : -1);
    }

    /** Only faults produce these warnings. */
    @Override
    public void setGlobalWarning(String warning) {}

    @Override
    public void clearGlobalWarning() {
        suppressions.set(0);
    }
}
