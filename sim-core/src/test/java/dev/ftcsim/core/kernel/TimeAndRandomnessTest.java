package dev.ftcsim.core.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.ftcsim.contracts.hooks.Hooks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** What the robot program sees of the wall clock and of randomness is a function of the kernel's epoch and seed and of simulated time. */
@Timeout(60)
class TimeAndRandomnessTest {
    private static final long MS = 1_000_000L;

    /** The seeds the robot thread was given, with a library's thread asking for some of its own in between if told to. */
    private static List<Long> robotSeeds(long seed, boolean aLibraryAsksToo) throws Exception {
        try (KernelFixture fixture = new KernelFixture(config -> config.withSeed(seed))) {
            List<Long> seeds = Collections.synchronizedList(new ArrayList<>());
            fixture.startRobot("robot", () -> {
                for (int i = 0; i < 3; i++) {
                    seeds.add(Hooks.randomSeed());
                    Hooks.sleep(1);
                }
            });
            if (aLibraryAsksToo) {
                Thread library = new Thread(() -> {
                    for (int i = 0; i < 5; i++) {
                        Hooks.randomSeed();
                    }
                }, "library");
                library.start();
                library.join();
            }
            fixture.kernel.runFor(10 * MS);
            return List.copyOf(seeds);
        }
    }

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

    @Test
    void theSameSeedGivesTheRobotTheSameSeedsAndAnotherSeedOtherOnes() throws Exception {
        List<Long> first = robotSeeds(7, false);

        assertEquals(3, first.size());
        assertEquals(3, first.stream().distinct().count(), "each request gets a seed of its own");
        assertEquals(first, robotSeeds(7, false));
        assertNotEquals(first, robotSeeds(8, false));
    }

    @Test
    void whatALibraryThreadAsksForNeverChangesWhatTheRobotThreadGets() throws Exception {
        assertEquals(robotSeeds(7, false), robotSeeds(7, true));
    }

    @Test
    void mathRandomOfTheRobotFollowsFromTheSeed() throws Exception {
        List<List<Double>> runs = new ArrayList<>();
        for (long seed : new long[] {3, 3, 4}) {
            try (KernelFixture fixture = new KernelFixture(config -> config.withSeed(seed))) {
                List<Double> numbers = Collections.synchronizedList(new ArrayList<>());
                fixture.startRobot("robot", () -> {
                    numbers.add(Hooks.mathRandom());
                    numbers.add(Hooks.mathRandom());
                });
                fixture.kernel.runFor(MS);
                runs.add(List.copyOf(numbers));
            }
        }

        assertEquals(runs.get(0), runs.get(1));
        assertNotEquals(runs.get(0), runs.get(2));
    }

    @Test
    void somethingThatCannotBeMadeReproducibleIsAProblemOnceHoweverOftenItHappens() throws Exception {
        try (KernelFixture fixture = new KernelFixture()) {
            fixture.kernel.hooks().nondeterministic("a thing");
            fixture.kernel.hooks().nondeterministic("a thing");
            fixture.kernel.hooks().nondeterministic("another thing");

            assertEquals(List.of("a thing", "another thing"), fixture.kernel.diagnostics().problems());
        }
    }
}
