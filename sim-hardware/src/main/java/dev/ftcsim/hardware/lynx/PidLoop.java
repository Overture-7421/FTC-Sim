package dev.ftcsim.hardware.lynx;

/**
 * One loop of the motor controller: {@code output = F*target + P*e + I*sum(e) + D*de}, with the integral and
 * the difference taken per 10 ms of control time.
 *
 * <p>Scaling, which the SDK leaves to the firmware: the gains arrive as 16.16 fixed point, so the SDK's
 * {@code getPIDFCoefficients} is {@code raw / 65536}. In the velocity loop the error and the target are in encoder
 * counts per second and the output is in 1/32767 of full power, the same unit as the constant-power command; that
 * is what makes REV's recipe {@code F = 32767 / maxVelocity} a feed-forward that is exact at full speed. In the
 * position loop the error is in encoder counts and the output is a velocity goal in counts per second, which is
 * why {@code P = 5} means 5 counts per second per count of error. The I and D gains are per 10 ms, the period of a
 * 100 Hz velocity loop; with that period the hub's power-on gains (P=10, I=3) are well damped for a free motor
 * and REV's recipe ({@code I = 0.1 P}) is a gentle trim, whereas per-second or per-millisecond periods make the
 * defaults sluggish or oscillatory. The legacy algorithm ignores F.
 */
final class PidLoop {

    private static final double TIME_UNIT_SECONDS = 0.01;

    private PidfCoefficients coefficients;
    private double integral;
    private double previousError;
    private boolean hasPreviousError;

    PidLoop(PidfCoefficients coefficients) {
        this.coefficients = coefficients;
    }

    PidfCoefficients coefficients() {
        return coefficients;
    }

    void configure(PidfCoefficients coefficients) {
        this.coefficients = coefficients;
        reset();
    }

    void reset() {
        integral = 0;
        hasPreviousError = false;
    }

    /**
     * Advances the loop by one control step.
     *
     * @param limit the magnitude the integral term may reach, which stops the integrator winding up
     */
    double update(double error, double target, double dtSeconds, double limit) {
        double steps = dtSeconds / TIME_UNIT_SECONDS;
        double integralGain = coefficients.integralGain();
        if (integralGain == 0) {
            integral = 0;
        } else {
            double bound = limit / Math.abs(integralGain);
            integral = Math.max(-bound, Math.min(bound, integral + error * steps));
        }
        double derivative = hasPreviousError ? (error - previousError) / steps : 0;
        previousError = error;
        hasPreviousError = true;
        boolean legacy = coefficients.algorithm() == PidfCoefficients.Algorithm.LEGACY_PID;
        double feedForward = legacy ? 0 : coefficients.feedForwardGain() * target;
        return feedForward
                + coefficients.proportionalGain() * error
                + integralGain * integral
                + coefficients.derivativeGain() * derivative;
    }
}
