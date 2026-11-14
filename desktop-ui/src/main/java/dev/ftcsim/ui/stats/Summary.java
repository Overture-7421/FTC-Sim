package dev.ftcsim.ui.stats;

import java.util.Arrays;
import java.util.Locale;

/** Distribution of a set of durations, reported in milliseconds with nearest-rank percentiles. */
public record Summary(int count, double p50, double p95, double p99, double max, double mean) {
    private static final Summary EMPTY = new Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    public static Summary ofNanos(long[] nanos) {
        if (nanos.length == 0) {
            return EMPTY;
        }
        long[] sorted = Arrays.copyOf(nanos, nanos.length);
        Arrays.sort(sorted);
        double sum = 0;
        for (long value : sorted) {
            sum += value;
        }
        return new Summary(
                sorted.length,
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99),
                sorted[sorted.length - 1] / 1e6,
                sum / sorted.length / 1e6);
    }

    /** Nearest-rank percentile of an ascending array, in milliseconds. */
    static double percentile(long[] sortedNanos, double percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sortedNanos.length);
        return sortedNanos[Math.min(sortedNanos.length, Math.max(1, rank)) - 1] / 1e6;
    }

    @Override
    public String toString() {
        return String.format(
                Locale.ROOT, "n=%d p50=%.3f p95=%.3f p99=%.3f max=%.3f ms", count, p50, p95, p99, max);
    }
}
