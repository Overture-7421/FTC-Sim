package dev.ftcsim.sdk.hardware;

import com.qualcomm.hardware.lynx.LynxModule;
import com.qualcomm.hardware.lynx.LynxUsbDevice;
import dev.ftcsim.contracts.hooks.Hooks;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A REV hub module whose message numbers do not depend on what other threads send. The SDK numbers all of a hub's
 * messages from one counter and, whenever a number is zero or still in use, turns one more time round a loop; each
 * turn of a loop costs the robot a little simulated time. A thread the simulation does not schedule, such as a
 * library's timer, would move that counter under the robot, so the robot's clock would depend on how often the
 * timer happened to run. Each kind of thread therefore numbers its messages in a range of its own.
 */
final class SimLynxModule extends LynxModule {
    private static final int RANGE = 127;
    private static final int FIRST_SCHEDULED = 1;
    private static final int FIRST_UNSCHEDULED = FIRST_SCHEDULED + RANGE;

    private final AtomicInteger unscheduledNumbers = new AtomicInteger();

    SimLynxModule(LynxUsbDevice device, int moduleAddress, boolean isParent, boolean isUserModule) {
        super(device, moduleAddress, isParent, isUserModule);
    }

    @Override
    protected byte getNewMessageNumber() {
        boolean scheduled = Hooks.isScheduledThread();
        AtomicInteger counter = scheduled ? nextMessageNumber : unscheduledNumbers;
        int first = scheduled ? FIRST_SCHEDULED : FIRST_UNSCHEDULED;
        for (;;) {
            int number = first + Math.floorMod(counter.getAndIncrement(), RANGE);
            if (!unfinishedCommands.containsKey(number)) {
                return (byte) number;
            }
        }
    }
}
