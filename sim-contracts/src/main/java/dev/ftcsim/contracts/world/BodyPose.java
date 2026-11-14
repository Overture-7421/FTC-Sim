package dev.ftcsim.contracts.world;

/**
 * Where a rigid body really is at one simulated instant, in the field frame: metres, radians, seconds, Z up. The
 * body frame is the robot convention, X forward, Y left, Z up, and the rotation is the unit quaternion that turns
 * body axes into field axes. Heading is the yaw of the body, counter-clockwise from the field's +X.
 *
 * <p>Sensors that measure the chassis, such as odometry computers and gyros, read this and nothing else about the
 * world; what they report is derived from it, with whatever error the device itself has.
 *
 * @param timeNanos simulated time of this sample
 * @param vx        velocity of the body origin, field frame
 * @param wx        angular velocity, field frame
 */
public record BodyPose(
        long timeNanos,
        double x,
        double y,
        double z,
        double qw,
        double qx,
        double qy,
        double qz,
        double vx,
        double vy,
        double vz,
        double wx,
        double wy,
        double wz) {

    /** At rest at the origin of the field, facing +X. */
    public static BodyPose atOrigin(long timeNanos) {
        return new BodyPose(timeNanos, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** A body on the floor at {@code (x, y)} facing {@code heading}, with the given planar velocity and yaw rate. */
    public static BodyPose planar(long timeNanos, double x, double y, double heading, double vx, double vy, double yawRate) {
        return new BodyPose(
                timeNanos, x, y, 0, StrictMath.cos(heading / 2), 0, 0, StrictMath.sin(heading / 2), vx, vy, 0, 0, 0, yawRate);
    }

    public Vec3 position() {
        return new Vec3(x, y, z);
    }

    public Vec3 velocity() {
        return new Vec3(vx, vy, vz);
    }

    public Vec3 angularVelocity() {
        return new Vec3(wx, wy, wz);
    }

    /** Heading, in (-pi, pi]. */
    public double yaw() {
        return StrictMath.atan2(2.0 * (qw * qz + qx * qy), 1.0 - 2.0 * (qy * qy + qz * qz));
    }

    /** Rotation about the body's Y axis, positive when the nose points down, in [-pi/2, pi/2]. */
    public double pitch() {
        double sine = 2.0 * (qw * qy - qz * qx);
        return StrictMath.asin(Math.max(-1.0, Math.min(1.0, sine)));
    }

    /** Rotation about the body's X axis, positive when the left side goes up, in (-pi, pi]. */
    public double roll() {
        return StrictMath.atan2(2.0 * (qw * qx + qy * qz), 1.0 - 2.0 * (qx * qx + qy * qy));
    }

    /** A direction given in the body frame, as the field frame sees it. */
    public Vec3 directionToField(Vec3 body) {
        // v' = v + 2 w (q x v) + 2 q x (q x v), with q the vector part of the quaternion.
        Vec3 q = new Vec3(qx, qy, qz);
        Vec3 t = q.cross(body).times(2.0);
        return body.plus(t.times(qw)).plus(q.cross(t));
    }

    /** A direction given in the field frame, as the body frame sees it. */
    public Vec3 directionToBody(Vec3 field) {
        Vec3 q = new Vec3(-qx, -qy, -qz);
        Vec3 t = q.cross(field).times(2.0);
        return field.plus(t.times(qw)).plus(q.cross(t));
    }

    /** A point fixed to the body, given in the body frame, as a point of the field frame. */
    public Vec3 pointToField(Vec3 body) {
        return position().plus(directionToField(body));
    }

    /** The velocity, in the field frame, of a point fixed to the body. */
    public Vec3 velocityOfPoint(Vec3 body) {
        return velocity().plus(angularVelocity().cross(directionToField(body)));
    }
}
