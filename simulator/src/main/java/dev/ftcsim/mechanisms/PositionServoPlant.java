package dev.ftcsim.mechanisms;

/**
 * A positional servo: the pulse the hub sends is the position it goes to, at no more than its top speed. The SDK sends
 * 600 to 2400 microseconds for positions 0 to 1 unless the code changes the range, and the servo's travel is
 * {@link #TRAVEL_RADIANS} (300 degrees, the goBILDA 2000 series). A servo that is not being sent pulses holds where it is.
 *
 * <p>A mirrored servo is mounted the other way: position 0 is the far end of the travel. Before the first pulse the
 * servo stands at position 0.
 */
public final class PositionServoPlant implements ServoPlant {

    /** The pulse for position 0, in microseconds. */
    public static final int LOW_MICROS = 600;

    /** The pulse for position 1, in microseconds. */
    public static final int HIGH_MICROS = 2400;

    /** The travel of the shaft from position 0 to position 1. */
    public static final double TRAVEL_RADIANS = Math.toRadians(300.0);

    private final double maxRadPerSec;
    private final boolean mirrored;

    private double angleRadians;
    private double speedRadPerSec;

    /**
     * @param maxRadPerSec the servo's top speed with no load; above 0
     * @param mirrored     whether the travel runs the other way round
     */
    public PositionServoPlant(double maxRadPerSec, boolean mirrored) {
        if (!(maxRadPerSec > 0.0)) {
            throw new IllegalArgumentException("a servo needs a speed above 0");
        }
        this.maxRadPerSec = maxRadPerSec;
        this.mirrored = mirrored;
        this.angleRadians = mirrored ? TRAVEL_RADIANS : 0.0;
    }

    /** The position, from 0 to 1, a pulse of this width asks for. */
    public static double position(int pulseMicros) {
        return Math.max(0.0, Math.min(1.0, (pulseMicros - LOW_MICROS) / (double) (HIGH_MICROS - LOW_MICROS)));
    }

    @Override
    public void step(double seconds, boolean enabled, int pulseMicros) {
        if (!enabled) {
            speedRadPerSec = 0.0;
            return;
        }
        double position = position(pulseMicros);
        double target = TRAVEL_RADIANS * (mirrored ? 1.0 - position : position);
        double most = maxRadPerSec * seconds;
        double move = Math.max(-most, Math.min(most, target - angleRadians));
        angleRadians += move;
        speedRadPerSec = seconds > 0.0 ? move / seconds : 0.0;
    }

    @Override
    public double angleRadians() {
        return angleRadians;
    }

    @Override
    public double speedRadPerSec() {
        return speedRadPerSec;
    }
}
