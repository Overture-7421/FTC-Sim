package dev.ftcsim.hardware.camera;

/**
 * Noise that is a pure function of its key: the same seed, frame, tag, corner and axis always give the same deviate,
 * whatever order or number of times they are asked for, so a camera's pixels never depend on how the run was sliced
 * in time. SplitMix64's finaliser mixes the key and Box-Muller turns two of its outputs into a normal deviate.
 */
final class PixelNoise {

    private static final long FRAME_MIX = 0x9E3779B97F4A7C15L;
    private static final long KEY_MIX = 0xBF58476D1CE4E5B9L;

    private final long seed;

    PixelNoise(long seed) {
        this.seed = seed;
    }

    /** A standard normal deviate. */
    double gaussian(long frame, int tag, int corner, int axis) {
        long key = mix(seed ^ (frame * FRAME_MIX));
        key = mix(key ^ ((((long) tag << 8) | (corner << 2) | axis) * KEY_MIX));
        double first = unit(mix(key + 1));
        double second = unit(mix(key + 2));
        return StrictMath.sqrt(-2.0 * StrictMath.log(first)) * StrictMath.cos(2.0 * StrictMath.PI * second);
    }

    /** A number in (0, 1) with 53 bits of resolution. */
    static double unit(long bits) {
        return ((bits >>> 11) + 0.5) * 0x1.0p-53;
    }

    static long mix(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * KEY_MIX;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
