package dev.ftcsim.mechanisms;

/**
 * A continuous-rotation servo turning a load: the pulse the hub sends sets its speed, as on a hobby servo in its
 * continuous mode. The speed follows the commanded speed through a first-order lag, which is what a servo's motor,
 * gearbox and controller look like from outside, and the shaft's angle is the integral of it.
 *
 * <p>The command is {@code (pulse - neutral) / span}, clipped to -1 and 1, with nothing at all inside the dead band
 * around the neutral pulse; a servo that is not being sent pulses stops. A step integrates the lag exactly, so the
 * result does not depend on how a span of time is cut into steps.
 */
public final class CrServoPlant implements ServoPlant {

    /** The pulse at which the servo stands still, in microseconds. */
    public static final int NEUTRAL_MICROS = 1500;

    /** How far from neutral the pulse is for full speed, in microseconds. */
    public static final int FULL_SPEED_SPAN_MICROS = 1000;

    /** Pulses this close to neutral, either side, are no command at all. */
    public static final int DEAD_BAND_MICROS = 2;

    private final double noLoadRadPerSec;
    private final double lagSeconds;

    private double angleRadians;
    private double speedRadPerSec;

    /**
     * @param noLoadRadPerSec the speed at full command with no load, at the voltage the servo is powered with
     * @param lagSeconds      the time constant of the speed's response; above 0
     */
    public CrServoPlant(double noLoadRadPerSec, double lagSeconds) {
        if (!(noLoadRadPerSec > 0.0 && lagSeconds > 0.0)) {
            throw new IllegalArgumentException("a servo needs a speed and a lag above 0");
        }
        this.noLoadRadPerSec = noLoadRadPerSec;
        this.lagSeconds = lagSeconds;
    }

    /** The commanded speed, from -1 to 1, of a pulse of this width. */
    public static double command(int pulseMicros) {
        int offset = pulseMicros - NEUTRAL_MICROS;
        if (Math.abs(offset) <= DEAD_BAND_MICROS) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, offset / (double) FULL_SPEED_SPAN_MICROS));
    }

    /**
     * Advances the shaft by {@code seconds} with the pulse the hub is sending.
     *
     * @param enabled     whether the hub's output for the servo is on
     * @param pulseMicros the pulse width it is set to
     */
    @Override
    public void step(double seconds, boolean enabled, int pulseMicros) {
        double target = enabled ? command(pulseMicros) * noLoadRadPerSec : 0.0;
        double decay = StrictMath.exp(-seconds / lagSeconds);
        angleRadians += target * seconds + (speedRadPerSec - target) * lagSeconds * (1.0 - decay);
        speedRadPerSec = target + (speedRadPerSec - target) * decay;
    }

    /** The shaft's angle since the plant was created; positive for pulses above neutral. */
    @Override
    public double angleRadians() {
        return angleRadians;
    }

    @Override
    public double speedRadPerSec() {
        return speedRadPerSec;
    }
}
