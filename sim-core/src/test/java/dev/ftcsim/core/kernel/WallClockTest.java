package dev.ftcsim.core.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ftcsim.contracts.hooks.Hooks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** What the robot program sees of the wall clock is a function of the kernel's epoch and of simulated time. */
@Timeout(60)
class WallClockTest {
    private static final long MS = 1_000_000L;

    @Test
    void theWallClockIsTheEpochPlusSimulatedTimeAndAReadCostsWhatAnyClockReadCosts() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            List<Long> seen = Collections.synchronizedList(new ArrayList<>());
            fixture.startRobot("robot", () -> {
                seen.add(Hooks.currentTimeNanos());
                Hooks.sleep(5);
                seen.add(Hooks.currentTimeNanos());
                seen.add(Hooks.currentTimeMillis());
            });

            fixture.kernel.runFor(10 * MS);

            long epochNanos = 1_000_000L * MS;
            assertEquals(epochNanos + KernelFixture.POLL_COST, seen.get(0));
            assertEquals(epochNanos + KernelFixture.POLL_COST + 5 * MS + KernelFixture.POLL_COST, seen.get(1));
            assertEquals(1_000_000L + 5, seen.get(2));
        }
    }

    @Test
    void theEpochIsConfigurable() throws Exception {
        try (KernelFixture fixture = new KernelFixture(config -> config.withEpochMillis(1_767_225_600_000L))) {
            List<Long> seen = Collections.synchronizedList(new ArrayList<>());
            fixture.startRobot("robot", () -> seen.add(Hooks.currentTimeMillis()));

            fixture.kernel.runFor(MS);

            assertEquals(List.of(1_767_225_600_000L), seen);
        }
    }
}
