package dev.ftcsim.physics;

/**
 * Detects flush-to-zero or denormals-are-zero modes that a native library may leave set on the
 * simulation thread. Java arithmetic assumes subnormal numbers survive, so a changed mode would
 * silently alter results compared to another machine.
 */
final class DenormalProbe {

    // Volatile so that neither the compiler nor the interpreter can fold the products away.
    private static volatile float floatMinNormal = Float.MIN_NORMAL;
    private static volatile float floatHalf = 0.5f;
    private static volatile double doubleMinNormal = Double.MIN_NORMAL;
    private static volatile double doubleHalf = 0.5;

    private DenormalProbe() {}

    static void require() {
        float subnormal = floatMinNormal * floatHalf;
        double doubleSubnormal = doubleMinNormal * doubleHalf;
        boolean flushed = subnormal == 0f
                || subnormal * 2f != Float.MIN_NORMAL
                || doubleSubnormal == 0.0
                || doubleSubnormal * 2.0 != Double.MIN_NORMAL;
        if (flushed) {
            throw new IllegalStateException(
                    "FTZ/DAZ is enabled on this thread; subnormal arithmetic is flushed to zero");
        }
    }
}
