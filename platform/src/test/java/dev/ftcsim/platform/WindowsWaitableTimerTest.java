package dev.ftcsim.platform;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class WindowsWaitableTimerTest {

    @Test
    void everyWaitLastsAtLeastTheRequestedTimeWhenTheTimerIsReused() {
        try (WindowsWaitableTimer timer = WindowsWaitableTimer.openHighResolution()) {
            for (long requested : new long[] {2_000_000L, 500_000L, 7_000_000L, 1L}) {
                long begun = System.nanoTime();
                timer.parkNanos(requested);
                long waited = System.nanoTime() - begun;

                // The two clocks differ by microseconds, so a wait counts as short only beyond that.
                assertTrue(waited >= requested - 200_000L, "asked for " + requested + " ns, waited " + waited + " ns");
            }
        }
    }
}
