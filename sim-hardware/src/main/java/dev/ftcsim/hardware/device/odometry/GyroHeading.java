package dev.ftcsim.hardware.device.odometry;

/**
 * The yaw an inertial chip reports: the true yaw of the chassis, integrated the way a gyro does, scaled by the chip's
 * yaw scalar and drifting at a constant rate if the chip has a bias. The heading is continuous, it keeps counting
 * past a half turn, and writing it moves the chip's idea of where it faces without touching the gyro.
 */
public final class GyroHeading {

    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double NANOS_PER_SECOND = 1e9;

    private final double biasRadiansPerSecond;
    private double scalar;
    private double lastYaw;
    private long lastNanos;
    private double integrated;
    private double reference;
    private double referenceIntegrated;

    /** A gyro that reads heading 0 now, when the chassis has {@code yaw}. */
    public GyroHeading(double biasRadiansPerSecond, double scalar, double yaw, long nanos) {
        this.biasRadiansPerSecond = biasRadiansPerSecond;
        this.scalar = scalar;
        this.lastYaw = yaw;
        this.lastNanos = nanos;
    }

    /** Integrates the change from the previous sample; the yaw may wrap between -pi and pi but not by more. */
    public void sample(double yaw, long nanos) {
        double change = StrictMath.IEEEremainder(yaw - lastYaw, TWO_PI);
        integrated += change + biasRadiansPerSecond * (nanos - lastNanos) / NANOS_PER_SECOND;
        lastYaw = yaw;
        lastNanos = nanos;
    }

    public double heading() {
        return reference + scalar * (integrated - referenceIntegrated);
    }

    /** From now on the chip believes it faces {@code heading}. */
    public void setHeading(double heading) {
        reference = heading;
        referenceIntegrated = integrated;
    }

    public void setScalar(double scalar) {
        this.scalar = scalar;
    }
}
