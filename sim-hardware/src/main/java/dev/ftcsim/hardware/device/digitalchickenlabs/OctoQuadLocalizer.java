package dev.ftcsim.hardware.device.digitalchickenlabs;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.hardware.device.odometry.DeadReckoner;
import dev.ftcsim.hardware.device.odometry.GyroHeading;

/**
 * The OctoQuad MK2's absolute localizer: it dead-reckons the pose of a tracking point from two of the channels'
 * encoders, wired to tracking wheels, and the onboard IMU, which the chip does with the same pose exponential as
 * the Pinpoint. Its settings take effect only when robot code resets the localizer, which also zeroes the pose
 * and recalibrates the IMU; until then the localizer is not ready, afterwards it warms up and calibrates, holding
 * its pose, and then runs. The IMU is a gyro of the true yaw. The durations are estimates.
 *
 * <p>The tracking point is the real tracking centre point, where the two wheels' lines cross, moved by the TCP
 * offset: the vector from where the lines cross to where the robot code wants the pose to be.
 */
final class OctoQuadLocalizer {

    static final int STATUS_NOT_READY = 1;
    static final int STATUS_WARMING_UP = 2;
    static final int STATUS_CALIBRATING = 3;
    static final int STATUS_RUNNING = 4;
    static final int YAW_AXIS_UNDECIDED = 0;
    static final int YAW_AXIS_Z = 5;
    static final double HEADING_SCALE = 5000;
    static final double HEADING_RATE_SCALE = 600;

    static final long WARM_UP_MILLIS = 100;
    static final long CALIBRATION_MILLIS = 300;

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final double NANOS_PER_SECOND = 1e9;
    private static final double TWO_PI = 2.0 * Math.PI;

    /** The localizer's settings, which are staged by robot code and taken up at the next reset. */
    static final class Settings {
        int portX = 0;
        int portY = 1;
        double ticksPerMmX = 1;
        double ticksPerMmY = 1;
        double tcpOffsetXMm;
        double tcpOffsetYMm;
        double imuScalar = 1;
        int velocityIntervalMs = 25;

        Settings copy() {
            Settings copy = new Settings();
            copy.portX = portX;
            copy.portY = portY;
            copy.ticksPerMmX = ticksPerMmX;
            copy.ticksPerMmY = ticksPerMmY;
            copy.tcpOffsetXMm = tcpOffsetXMm;
            copy.tcpOffsetYMm = tcpOffsetYMm;
            copy.imuScalar = imuScalar;
            copy.velocityIntervalMs = velocityIntervalMs;
            return copy;
        }
    }

    private final BodyView body;
    private final OctoQuadChannel[] channels;
    private final double gyroBias;
    private final DeadReckoner reckoner = new DeadReckoner();
    private Settings staged = new Settings();
    private Settings active = new Settings();
    private GyroHeading gyro;

    private boolean everReset;
    private long resetNanos;
    private boolean running;
    private long lastRawX;
    private long lastRawY;
    private long nextVelocityNanos;
    private long lastVelocityNanos;
    private double velocityBaseX;
    private double velocityBaseY;
    private double velocityBaseHeading;
    private int velocityX;
    private int velocityY;
    private int velocityHeading;

    OctoQuadLocalizer(BodyView body, OctoQuadChannel[] channels, double gyroBias, long nowNanos) {
        this.body = body;
        this.channels = channels;
        this.gyroBias = gyroBias;
        this.gyro = new GyroHeading(gyroBias, active.imuScalar, body.sample().yaw(), nowNanos);
    }

    Settings staged() {
        return staged;
    }

    /** Takes up the staged settings, zeroes the pose and starts the IMU's warm-up. */
    void reset(long nowNanos) {
        active = staged.copy();
        gyro = new GyroHeading(gyroBias, active.imuScalar, body.sample().yaw(), nowNanos);
        reckoner.setPose(0, 0, 0);
        everReset = true;
        resetNanos = nowNanos;
        running = false;
        clearVelocity();
    }

    /** Back to the factory settings and not ready, as after the chip's own reset. */
    void restoreDefaults() {
        staged = new Settings();
        active = new Settings();
        reckoner.setPose(0, 0, 0);
        everReset = false;
        running = false;
        clearVelocity();
    }

    int status(long nowNanos) {
        if (!everReset) {
            return STATUS_NOT_READY;
        }
        long elapsed = nowNanos - resetNanos;
        if (elapsed < WARM_UP_MILLIS * NANOS_PER_MILLI) {
            return STATUS_WARMING_UP;
        }
        return elapsed < (WARM_UP_MILLIS + CALIBRATION_MILLIS) * NANOS_PER_MILLI ? STATUS_CALIBRATING : STATUS_RUNNING;
    }

    int yawAxis(long nowNanos) {
        return status(nowNanos) == STATUS_RUNNING ? YAW_AXIS_Z : YAW_AXIS_UNDECIDED;
    }

    int velocityX() {
        return velocityX;
    }

    int velocityY() {
        return velocityY;
    }

    int velocityHeading() {
        return velocityHeading;
    }

    int xMm() {
        return clamp16(Math.round(reckoner.x()));
    }

    int yMm() {
        return clamp16(Math.round(reckoner.y()));
    }

    /** The heading register: the heading, wrapped to (-pi, pi], in units of 1/5000 radian. */
    int headingRaw() {
        double wrapped = StrictMath.IEEEremainder(reckoner.heading(), TWO_PI);
        return clamp16(Math.round(wrapped * HEADING_SCALE));
    }

    void setXMm(int x) {
        reckoner.setX(x);
    }

    void setYMm(int y) {
        reckoner.setY(y);
    }

    void setHeadingRaw(int raw) {
        double heading = raw / HEADING_SCALE;
        reckoner.setHeading(heading);
        gyro.setHeading(heading);
    }

    /** One update, after the physics step that ended at {@code stepNanos}. */
    void afterStep(long stepNanos) {
        BodyPose pose = body.sample();
        gyro.sample(pose.yaw(), stepNanos);
        long rawX = channels[active.portX].rawCounts();
        long rawY = channels[active.portY].rawCounts();
        if (status(stepNanos) != STATUS_RUNNING) {
            clearVelocity();
        } else if (!running) {
            running = true;
            gyro.setHeading(reckoner.heading());
            startVelocityInterval(stepNanos);
        } else {
            integrate(stepNanos, rawX - lastRawX, rawY - lastRawY);
            if (stepNanos >= nextVelocityNanos) {
                measureVelocity(stepNanos);
            }
        }
        lastRawX = rawX;
        lastRawY = rawY;
    }

    private void integrate(long stepNanos, long xTicks, long yTicks) {
        double xTravel = signOf(active.portX) * xTicks / active.ticksPerMmX;
        double yTravel = signOf(active.portY) * yTicks / active.ticksPerMmY;
        // The wheels' lines cross at the real tracking point; the pose belongs to the point offset from it.
        reckoner.advance(
                xTravel, yTravel, gyro.heading() - reckoner.heading(), -active.tcpOffsetYMm, -active.tcpOffsetXMm);
    }

    private void measureVelocity(long stepNanos) {
        double seconds = (stepNanos - lastVelocityNanos) / NANOS_PER_SECOND;
        velocityX = clamp16(Math.round((reckoner.x() - velocityBaseX) / seconds));
        velocityY = clamp16(Math.round((reckoner.y() - velocityBaseY) / seconds));
        velocityHeading = clamp16(
                Math.round((reckoner.heading() - velocityBaseHeading) / seconds * HEADING_RATE_SCALE));
        long interval = active.velocityIntervalMs * NANOS_PER_MILLI;
        lastVelocityNanos = stepNanos;
        nextVelocityNanos += ((stepNanos - nextVelocityNanos) / interval + 1) * interval;
        rememberPose();
    }

    private void startVelocityInterval(long stepNanos) {
        lastVelocityNanos = stepNanos;
        nextVelocityNanos = stepNanos + active.velocityIntervalMs * NANOS_PER_MILLI;
        rememberPose();
    }

    private void rememberPose() {
        velocityBaseX = reckoner.x();
        velocityBaseY = reckoner.y();
        velocityBaseHeading = reckoner.heading();
    }

    private void clearVelocity() {
        velocityX = 0;
        velocityY = 0;
        velocityHeading = 0;
    }

    /** The channel's direction decides which way its steps count; the localizer works on raw steps. */
    private int signOf(int port) {
        return channels[port].reversed() ? -1 : 1;
    }

    private static int clamp16(long value) {
        return (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }
}
