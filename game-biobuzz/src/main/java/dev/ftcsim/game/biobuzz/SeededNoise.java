package dev.ftcsim.game.biobuzz;

/**
 * A small deterministic noise source (SplitMix64), so that the run does not depend on how the JDK implements its
 * random classes: equal seeds give equal values on every machine.
 */
final class SeededNoise {

    private long state;

    SeededNoise(long seed) {
        this.state = seed;
    }

    private long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A value between {@code -amount} and {@code amount}. */
    double signed(double amount) {
        return amount * (2.0 * ((nextLong() >>> 11) * 0x1.0p-53) - 1.0);
    }
}
