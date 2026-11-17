package dev.ftcsim.hardware.device;

import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.Vec3;

/**
 * The frame of a turret as a body: the chassis's frame turned by the turret's yaw about its axis. A device that rides on
 * the turret is mounted in the robot's frame with the turret facing forward, and is put in the world by composing that
 * mount with this body, so that it turns about the axis and not about the chassis's centre.
 */
public final class TurretPlatform {

    private TurretPlatform() {}

    /** The turret's frame, sampled afresh at every use from the chassis and the turret. */
    public static BodyView of(BodyView chassis, TurretView turret) {
        return () -> on(chassis.sample(), turret);
    }

    /** The turret's frame when the chassis is at {@code pose}. */
    static BodyPose on(BodyPose pose, TurretView turret) {
        double yaw = turret.yawRadians();
        double cos = StrictMath.cos(yaw);
        double sin = StrictMath.sin(yaw);
        double ax = turret.axisX();
        double ay = turret.axisY();
        // The frame's origin is where the chassis's origin goes when it is turned about the axis, in the chassis's frame.
        Vec3 offset = new Vec3(ax - (ax * cos - ay * sin), ay - (ax * sin + ay * cos), 0.0);
        Vec3 origin = pose.pointToField(offset);
        Vec3 velocity = pose.velocityOfPoint(offset);
        double half = yaw / 2.0;
        double tw = StrictMath.cos(half);
        double tz = StrictMath.sin(half);
        // The chassis's rotation followed by the turret's, which is about the body's own Z axis.
        double qw = pose.qw() * tw - pose.qz() * tz;
        double qx = pose.qx() * tw + pose.qy() * tz;
        double qy = pose.qy() * tw - pose.qx() * tz;
        double qz = pose.qz() * tw + pose.qw() * tz;
        Vec3 up = pose.directionToField(new Vec3(0.0, 0.0, 1.0)).times(turret.yawRateRadPerSec());
        return new BodyPose(
                pose.timeNanos(), origin.x(), origin.y(), origin.z(), qw, qx, qy, qz, velocity.x(), velocity.y(), velocity.z(),
                pose.wx() + up.x(), pose.wy() + up.y(), pose.wz() + up.z());
    }
}
