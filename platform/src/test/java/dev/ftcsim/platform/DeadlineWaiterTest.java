package dev.ftcsim.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeadlineWaiterTest {

    /** Virtual time: reading the clock costs 1 us and a park lasts the request plus a fixed overshoot. */
    private static final class FakeTime {
        long now;
        long overshoot;
        long parkedNanos;

        long read() {
            now += 1_000;
            return now;
        }

        void park(long nanos) {
            parkedNanos += nanos;
            now += nanos + overshoot;
        }
    }

    @Test
    void parksThroughMostOfTheWaitAndWakesAtTheDeadline() {
        FakeTime time = new FakeTime();
        DeadlineWaiter waiter = new DeadlineWaiter(time::read, time::park);

        long late = waiter.awaitUntil(time.now + 10_000_000L);

        assertTrue(late >= 0 && late < 5_000, "woke " + late + " ns late");
        assertTrue(time.parkedNanos > 8_000_000L, "it should park for most of the wait, parked " + time.parkedNanos);
    }

    @Test
    void aDeadlineThatHasPassedReturnsAtOnceAndSaysHowLateItIs() {
        FakeTime time = new FakeTime();
        DeadlineWaiter waiter = new DeadlineWaiter(time::read, time::park);
        time.now = 5_000_000L;

        long late = waiter.awaitUntil(3_000_000L);

        assertEquals(0, time.parkedNanos);
        assertTrue(late >= 2_000_000L, "late by " + late);
    }

    @Test
    void aParkerThatOvershootsMakesTheWaiterSpinOverAWiderMarginNextTime() {
        FakeTime time = new FakeTime();
        time.overshoot = 700_000L;
        DeadlineWaiter waiter = new DeadlineWaiter(time::read, time::park);

        for (int i = 0; i < 20; i++) {
            waiter.awaitUntil(time.now + 6_000_000L);
        }

        assertTrue(waiter.spinMarginNanos() >= 700_000L, "margin " + waiter.spinMarginNanos());
    }
}
