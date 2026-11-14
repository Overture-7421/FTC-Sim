package dev.ftcsim.physics;

/** Unit quaternion; trigonometry goes through {@link StrictMath} so results never depend on the JIT. */
record Quaternion(double x, double y, double z, double w) {

    static final Quaternion IDENTITY = new Quaternion(0, 0, 0, 1);

    static Quaternion axisAngle(Vector3 axis, double radians) {
        Vector3 unit = axis.normalized();
        double half = 0.5 * radians;
        double s = StrictMath.sin(half);
        return new Quaternion(unit.x() * s, unit.y() * s, unit.z() * s, StrictMath.cos(half));
    }

    Vector3 rotate(Vector3 v) {
        Vector3 u = new Vector3(x, y, z);
        Vector3 t = u.cross(v).times(2.0);
        return v.plus(t.times(w)).plus(u.cross(t));
    }

    Quaternion times(Quaternion o) {
        return new Quaternion(
                w * o.x + x * o.w + y * o.z - z * o.y,
                w * o.y - x * o.z + y * o.w + z * o.x,
                w * o.z + x * o.y - y * o.x + z * o.w,
                w * o.w - x * o.x - y * o.y - z * o.z);
    }
}
