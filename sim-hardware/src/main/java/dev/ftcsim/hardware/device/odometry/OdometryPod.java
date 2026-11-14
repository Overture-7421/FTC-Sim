package dev.ftcsim.hardware.device.odometry;

import dev.ftcsim.contracts.hardware.EncoderSource;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.hardware.device.DeviceContext;

/**
 * A wheel on an encoder, bolted to the chassis, that counts how far its contact point travels along its rolling
 * axis. It is turned out of successive true poses of the chassis, so it does what a real pod does: besides the
 * robot's translation it counts the sideways swing of its own mount when the robot rotates about a point away
 * from it, and a pod that is not where the robot code thinks it is makes the pose drift.
 *
 * <p>Between two samples the pod's contact point moves along a chord; the rolling axis turns with the chassis. The
 * travel is the chord's component along the axis at the middle of the move, corrected by the factor that makes it
 * exact when the chassis turns at a constant rate.
 */
public final class OdometryPod implements EncoderSource {

    private static final double NANOS_PER_SECOND = 1e9;
    private static final double SMALL_ANGLE = 1e-6;

    private final double ticksPerMetre;
    private final int direction;
    private final Vec3 point;
    private final Vec3 axis;
    private BodyPose previous;
    private Vec3 previousPoint;
    private Vec3 previousAxis;
    private double travelMetres;
    private double rateMetresPerSecond;

    /** A pod that has not moved yet and whose chassis is at {@code initial}. */
    public OdometryPod(PodMount mount, BodyPose initial) {
        ticksPerMetre = mount.ticksPerMetre();
        direction = mount.direction();
        point = new Vec3(mount.x(), mount.y(), 0.0);
        axis = new Vec3(StrictMath.cos(mount.axisRadians()), StrictMath.sin(mount.axisRadians()), 0.0);
        moveTo(initial);
    }

    /** A pod on the chassis of {@code context} that follows it after every physics step. */
    public static OdometryPod mount(PodMount mount, DeviceContext context) {
        OdometryPod pod = new OdometryPod(mount, context.body().sample());
        context.onStep(stepNanos -> pod.sample(context.body().sample()));
        return pod;
    }

    /** Moves the pod along with the chassis to {@code pose}, which must be later than the previous sample. */
    public void sample(BodyPose pose) {
        long elapsed = pose.timeNanos() - previous.timeNanos();
        if (elapsed <= 0) {
            return;
        }
        Vec3 mean = previousAxis.plus(pose.directionToField(axis));
        double chord = pose.pointToField(point).minus(previousPoint).dot(mean) / mean.length();
        double travel = chord / chordFactor(previous, pose);
        travelMetres += travel;
        rateMetresPerSecond = travel * NANOS_PER_SECOND / elapsed;
        moveTo(pose);
    }

    /** How far the contact point has travelled along the axis since the pod was mounted, in metres. */
    public double travelMetres() {
        return travelMetres;
    }

    @Override
    public long encoderCounts() {
        return direction * Math.round(travelMetres * ticksPerMetre);
    }

    @Override
    public double encoderCountsPerSecond() {
        return direction * rateMetresPerSecond * ticksPerMetre;
    }

    private void moveTo(BodyPose pose) {
        previous = pose;
        previousPoint = pose.pointToField(point);
        previousAxis = pose.directionToField(axis);
    }

    /** sin(h)/h for half the angle the chassis turned between the poses: a chord is that much shorter than its arc. */
    private static double chordFactor(BodyPose from, BodyPose to) {
        Vec3 fromVector = new Vec3(from.qx(), from.qy(), from.qz());
        Vec3 toVector = new Vec3(to.qx(), to.qy(), to.qz());
        // The relative rotation conj(from) * to.
        Vec3 relative = toVector.times(from.qw()).minus(fromVector.times(to.qw())).minus(fromVector.cross(toVector));
        double scalar = from.qw() * to.qw() + fromVector.dot(toVector);
        double half = StrictMath.atan2(relative.length(), Math.abs(scalar));
        return half < SMALL_ANGLE ? 1.0 - half * half / 6.0 : StrictMath.sin(half) / half;
    }
}
