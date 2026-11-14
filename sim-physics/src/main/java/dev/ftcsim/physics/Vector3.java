package dev.ftcsim.physics;

/** A point or direction in the world frame (Z up, right-handed, metres unless stated otherwise). */
public record Vector3(double x, double y, double z) {

    public static final Vector3 ZERO = new Vector3(0, 0, 0);
    public static final Vector3 X = new Vector3(1, 0, 0);
    public static final Vector3 Y = new Vector3(0, 1, 0);
    public static final Vector3 Z = new Vector3(0, 0, 1);

    public Vector3 plus(Vector3 o) {
        return new Vector3(x + o.x, y + o.y, z + o.z);
    }

    public Vector3 minus(Vector3 o) {
        return new Vector3(x - o.x, y - o.y, z - o.z);
    }

    public Vector3 times(double s) {
        return new Vector3(x * s, y * s, z * s);
    }

    public double dot(Vector3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vector3 cross(Vector3 o) {
        return new Vector3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double length() {
        return Math.sqrt(dot(this));
    }

    /** Unit vector in the same direction, or {@link #ZERO} when the length is zero. */
    public Vector3 normalized() {
        double length = length();
        return length == 0 ? ZERO : times(1.0 / length);
    }
}
