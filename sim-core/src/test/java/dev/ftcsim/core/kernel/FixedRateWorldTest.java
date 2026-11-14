package dev.ftcsim.core.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FixedRateWorldTest {

    @Test
    void aTaskStepsAtTheExactInstantsOfItsRateWithoutDriftAndInANonIntegerPeriodToo() {
        List<Long> times = new ArrayList<>();
        FixedRateWorld world = new FixedRateWorld().every(240, (index, time) -> times.add(time));

        world.advanceTo(25_000_000L);

        // 1e9 / 240 is 4166666.67 ns; each instant is rounded down on its own, so the error never accumulates.
        assertEquals(List.of(4_166_666L, 8_333_333L, 12_500_000L, 16_666_666L, 20_833_333L, 25_000_000L), times);
        assertEquals(29_166_666L, world.nextStepNanos());
    }

    @Test
    void stepsDueAtTheSameInstantRunInTheOrderTheTasksWereAdded() {
        List<String> order = new ArrayList<>();
        FixedRateWorld world = new FixedRateWorld()
                .every(1000, (index, time) -> order.add("hub " + time))
                .every(250, (index, time) -> order.add("physics " + time));

        world.advanceTo(8_000_000L);

        assertEquals(
                List.of(
                        "hub 1000000", "hub 2000000", "hub 3000000", "hub 4000000", "physics 4000000", "hub 5000000",
                        "hub 6000000", "hub 7000000", "hub 8000000", "physics 8000000"),
                order);
    }

    @Test
    void advancingInPiecesGivesTheSameStepsAsAdvancingAtOnce() {
        List<String> once = new ArrayList<>();
        List<String> pieces = new ArrayList<>();
        FixedRateWorld a = new FixedRateWorld().every(240, (i, t) -> once.add("p" + i + "@" + t)).every(1000, (i, t) -> once.add("h" + i));
        FixedRateWorld b = new FixedRateWorld().every(240, (i, t) -> pieces.add("p" + i + "@" + t)).every(1000, (i, t) -> pieces.add("h" + i));

        a.advanceTo(100_000_000L);
        for (long t = 7_000_000L; t < 100_000_000L; t += 7_000_000L) {
            b.advanceTo(t);
        }
        b.advanceTo(100_000_000L);

        assertEquals(once, pieces);
    }

    @Test
    void aWorldWithNoTasksNeverHasANextStep() {
        assertEquals(Long.MAX_VALUE, new FixedRateWorld().nextStepNanos());
    }

    @Test
    void aRateOfZeroOrMoreThanOneStepPerNanosecondIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new FixedRateWorld().every(0, (i, t) -> {}));
        assertThrows(IllegalArgumentException.class, () -> new FixedRateWorld().every(2_000_000_000L, (i, t) -> {}));
    }
}
