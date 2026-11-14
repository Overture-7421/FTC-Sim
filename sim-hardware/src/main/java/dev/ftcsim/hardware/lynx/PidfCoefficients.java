package dev.ftcsim.hardware.lynx;

/**
 * Control loop coefficients as the firmware stores them: signed 16.16 fixed point, which is how the SDK sends them
 * ({@code coefficient * 65536}).
 *
 * <p>The firmware's units are not documented in the SDK. The emulation follows REV's published tuning recipe: the
 * output of a loop is in units of 1/32767 of full power, so {@code F = 32767 / maxVelocity} is an exact feed-forward,
 * and the error of the velocity loop is in encoder counts per second. See {@link PidLoop}.
 */
record PidfCoefficients(int proportional, int integral, int derivative, int feedForward, Algorithm algorithm) {

    enum Algorithm {
        LEGACY_PID,
        PIDF
    }

    private static final double FIXED_ONE = 65536.0;

    /** The power-on velocity gains of the hub: P=10, I=3, D=0 with the legacy algorithm. */
    static final PidfCoefficients DEFAULT_VELOCITY = ofGains(10, 3, 0, 0, Algorithm.LEGACY_PID);

    /** The power-on position gain of the hub: P=5, the value REV recommends. */
    static final PidfCoefficients DEFAULT_POSITION = ofGains(5, 0, 0, 0, Algorithm.PIDF);

    static PidfCoefficients ofGains(double p, double i, double d, double f, Algorithm algorithm) {
        return new PidfCoefficients(fixed(p), fixed(i), fixed(d), fixed(f), algorithm);
    }

    double proportionalGain() {
        return proportional / FIXED_ONE;
    }

    double integralGain() {
        return integral / FIXED_ONE;
    }

    double derivativeGain() {
        return derivative / FIXED_ONE;
    }

    double feedForwardGain() {
        return feedForward / FIXED_ONE;
    }

    private static int fixed(double gain) {
        return (int) Math.round(gain * FIXED_ONE);
    }
}
