package dev.ftcsim.contracts.field;

/** A position and an orientation, the orientation as a unit quaternion (w, x, y, z). */
public record Pose3(double x, double y, double z, double qw, double qx, double qy, double qz) {

    public static final Pose3 IDENTITY = new Pose3(0, 0, 0, 1, 0, 0, 0);

    public static Pose3 at(double x, double y, double z) {
        return new Pose3(x, y, z, 1, 0, 0, 0);
    }

    /** At the given place, turned {@code angleRadians} about the unit axis {@code (ax, ay, az)}. */
    public static Pose3 turned(double x, double y, double z, double ax, double ay, double az, double angleRadians) {
        double length = Math.sqrt(ax * ax + ay * ay + az * az);
        double half = StrictMath.sin(angleRadians / 2) / length;
        return new Pose3(x, y, z, StrictMath.cos(angleRadians / 2), ax * half, ay * half, az * half);
    }
}
