package dev.ftcsim.ui.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SummaryTest {
    @Test
    void percentilesUseNearestRankOnMilliseconds() {
        long[] nanos = new long[100];
        for (int i = 0; i < nanos.length; i++) {
            nanos[i] = (i + 1) * 1_000_000L;
        }
        Summary summary = Summary.ofNanos(nanos);

        assertEquals(100, summary.count());
        assertEquals(50.0, summary.p50(), 1e-9);
        assertEquals(95.0, summary.p95(), 1e-9);
        assertEquals(99.0, summary.p99(), 1e-9);
        assertEquals(100.0, summary.max(), 1e-9);
        assertEquals(50.5, summary.mean(), 1e-9);
    }

    @Test
    void anEmptySampleHasNoNumbers() {
        Summary summary = Summary.ofNanos(new long[0]);
        assertEquals(0, summary.count());
        assertTrue(Double.isNaN(summary.p50()));
    }
}
