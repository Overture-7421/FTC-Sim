package dev.ftcsim.ui.render;

import dev.ftcsim.ui.scene.SceneModel;
import java.util.Arrays;

/**
 * Orbit camera around a point of the Z-up field. Input threads change it while the render thread reads it every
 * frame, so all access is synchronized; the critical sections are a handful of arithmetic operations.
 *
 * <p>Three presets place the camera for the usual ways of watching a run; dragging the mouse leaves a preset and
 * continues freely from where the camera stands. {@link Preset#CHASE} follows the robot and needs {@link #track} to
 * be told where the robot is.
 */
public final class OrbitCamera {
    /** Where the camera stands. */
    public enum Preset {
        /** Straight above the field, the driver's forward direction pointing up the screen. */
        TOP,
        /** Behind the driver's end of the field, looking across it. */
        DRIVER,
        /** Behind the robot, turning with it. */
        CHASE,
        /** Wherever the user has put it. */
        FREE
    }

    public static final double MIN_PITCH_DEGREES = 8.0;
    public static final double MAX_PITCH_DEGREES = 85.0;
    public static final double MIN_DISTANCE = 1.5;
    public static final double MAX_DISTANCE = 12.0;
    public static final double DEGREES_PER_PIXEL = 0.4;

    /**
     * How far the camera may be turned and zoomed, and the height of the point it looks at. The field's are the defaults;
     * a camera that looks at a robot instead of a field (the robot editor's) gets closer limits.
     */
    public record Limits(double minDistance, double maxDistance, double minPitchDegrees, double maxPitchDegrees, double targetHeight) {
        /** What the camera watching a run has. */
        public static final Limits FIELD = new Limits(MIN_DISTANCE, MAX_DISTANCE, MIN_PITCH_DEGREES, MAX_PITCH_DEGREES, 0.12);
    }

    private static final float FIELD_OF_VIEW_DEGREES = 45f;
    private static final double ZOOM_FACTOR_PER_STEP = 0.9;
    private static final double TOP_DISTANCE = 6.2;
    private static final double DRIVER_PITCH_DEGREES = 30.0;
    private static final double DRIVER_DISTANCE = 5.6;
    private static final double CHASE_PITCH_DEGREES = 24.0;
    private static final double CHASE_DISTANCE = 1.9;
    private static final double CHASE_SMOOTHING_SECONDS = 0.18;
    private static final double CHASE_EPSILON = 1e-4;
    // A start within this many degrees of a wall's direction is square to the walls; any other is a corner start.
    private static final double SQUARE_START_TOLERANCE_DEGREES = 10.0;
    // What a chase camera cannot see through: scenery reaching higher than this above the floor, such as a goal or a
    // hive. The perimeter walls are lower, and the camera looks over them.
    private static final float BLOCKER_MIN_TOP = 0.5f;
    // The chase camera is never nearer than this to the robot. Where something tall stands closer behind the robot
    // than that, it goes round the robot to the nearest side that is clear, trying these angles in this order.
    private static final double CHASE_MIN_DISTANCE = 1.1;
    private static final double CHASE_RETURN_MARGIN = 0.4;
    private static final double[] CHASE_SIDESTEPS_DEGREES = {35, -35, 70, -70, 105, -105, 140, -140, 180};
    private static final double CHASE_CLEARANCE = 0.12;
    private static final double CHASE_RELEASE_SECONDS = 0.35;
    // Once the camera has backed off to within this many metres of where it is going, it is there.
    private static final double CHASE_SETTLED = 0.01;
    private static final int BLOCKER_STRIDE = 10;

    private final Limits limits;
    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final double[] scratch = new double[6];
    private double targetZ;
    private float[] blockers = new float[0];
    private int blockerCount;
    private double clearDistance = Double.MAX_VALUE;
    private double chaseOffsetDegrees;
    private Preset preset = Preset.FREE;
    private double driverHeadingDegrees;
    private double targetX;
    private double targetY;
    private double yawDegrees = 40.0;
    private double pitchDegrees = 38.0;
    private double distance = 5.2;
    private long version;
    private volatile Runnable changed = () -> {};

    /** A camera with the field's limits. */
    public OrbitCamera() {
        this(Limits.FIELD);
    }

    public OrbitCamera(Limits limits) {
        this.limits = limits;
        this.targetZ = limits.targetHeight();
    }
    /**
     * Which way the driver looks across the field for a robot that starts facing {@code startHeadingRadians}. A robot
     * that starts square to the walls is looked at along its own heading, from behind it. A robot in a corner, facing
     * across the diagonal, is not: behind it is the goal in the corner, so the view falls back to the audience's, which
     * looks along -X in the field frame of every game (the audience is at +X).
     */
    public static double driverHeadingFor(double startHeadingRadians) {
        double quarterTurn = Math.PI / 2;
        double nearestWall = Math.round(startHeadingRadians / quarterTurn) * quarterTurn;
        double off = Math.abs(Math.toDegrees(startHeadingRadians - nearestWall));
        return off <= SQUARE_START_TOLERANCE_DEGREES ? nearestWall : Math.PI;
    }

    /** Called after a change made by {@code orbit}, {@code zoom}, {@code select} or {@code set}; not after {@link #track}. */
    public void onChange(Runnable listener) {
        changed = listener;
    }

    public synchronized void orbit(double deltaXPixels, double deltaYPixels) {
        preset = Preset.FREE;
        yawDegrees = wrap(yawDegrees - deltaXPixels * DEGREES_PER_PIXEL);
        pitchDegrees = clamp(pitchDegrees + deltaYPixels * DEGREES_PER_PIXEL, limits.minPitchDegrees(), limits.maxPitchDegrees());
        changedAndNotify();
    }

    /** Positive steps move closer. */
    public synchronized void zoom(double steps) {
        distance = clamp(distance * Math.pow(ZOOM_FACTOR_PER_STEP, steps), limits.minDistance(), limits.maxDistance());
        changedAndNotify();
    }

    public synchronized void set(double yawDegrees, double pitchDegrees, double distance) {
        preset = Preset.FREE;
        this.yawDegrees = wrap(yawDegrees);
        this.pitchDegrees = clamp(pitchDegrees, limits.minPitchDegrees(), limits.maxPitchDegrees());
        this.distance = clamp(distance, limits.minDistance(), limits.maxDistance());
        changedAndNotify();
    }

    /** Which way the driver looks across the field, counter-clockwise from +X; the presets are laid out around it. */
    public synchronized void setDriverHeading(double headingRadians) {
        driverHeadingDegrees = Math.toDegrees(headingRadians);
        if (preset == Preset.TOP || preset == Preset.DRIVER) {
            select(preset);
        }
    }

    public synchronized void select(Preset newPreset) {
        preset = newPreset;
        clearDistance = Double.MAX_VALUE;
        chaseOffsetDegrees = 0.0;
        switch (newPreset) {
            case TOP -> place(0.0, 0.0, driverHeadingDegrees + 180.0, limits.maxPitchDegrees(), TOP_DISTANCE);
            case DRIVER -> place(0.0, 0.0, driverHeadingDegrees + 180.0, DRIVER_PITCH_DEGREES, DRIVER_DISTANCE);
            case CHASE -> {
                pitchDegrees = CHASE_PITCH_DEGREES;
                distance = CHASE_DISTANCE;
                version++;
            }
            case FREE -> version++;
        }
        changed.run();
    }

    public synchronized Preset preset() {
        return preset;
    }

    /**
     * Tells the camera what stands on the field. The chase camera does not look through anything tall in it, such as a
     * goal: it stops in front of what would hide the robot, and where that leaves it less than 1.1 m from the robot
     * (a robot standing against a goal) it goes round to the nearest side that is clear.
     */
    public synchronized void setObstacles(SceneModel scene) {
        float[] boxes = new float[scene.scenery().size() * BLOCKER_STRIDE];
        int count = 0;
        for (SceneModel.Part part : scene.scenery()) {
            if (part.floor() || part.top() < BLOCKER_MIN_TOP) {
                continue;
            }
            int at = count++ * BLOCKER_STRIDE;
            boxes[at] = part.x();
            boxes[at + 1] = part.y();
            boxes[at + 2] = part.z();
            boxes[at + 3] = part.qx();
            boxes[at + 4] = part.qy();
            boxes[at + 5] = part.qz();
            boxes[at + 6] = part.qw();
            boxes[at + 7] = part.halfX();
            boxes[at + 8] = part.halfY();
            boxes[at + 9] = part.halfZ();
        }
        blockers = Arrays.copyOf(boxes, count * BLOCKER_STRIDE);
        blockerCount = count;
        clearDistance = Double.MAX_VALUE;
        chaseOffsetDegrees = 0.0;
    }

    /**
     * Tells the camera where the robot is, once per frame. Only {@link Preset#CHASE} cares: it keeps the robot in
     * the middle of the picture and swings round behind it, easing the turn so that a spinning robot does not
     * whip the view.
     */
    public synchronized void track(double robotX, double robotY, double robotHeadingRadians, double dtSeconds) {
        if (preset != Preset.CHASE) {
            return;
        }
        double behind = Math.toDegrees(robotHeadingRadians) + 180.0;
        chaseOffsetDegrees = blockerCount == 0 ? 0.0 : chooseOffset(robotX, robotY, behind);
        double desired = behind + chaseOffsetDegrees;
        double ease = 1.0 - Math.exp(-Math.max(0.0, dtSeconds) / CHASE_SMOOTHING_SECONDS);
        double newYaw = wrap(yawDegrees + wrap(desired - yawDegrees) * ease);
        double newClear = clearanceBehind(robotX, robotY, newYaw, dtSeconds);
        if (Math.abs(newYaw - yawDegrees) > CHASE_EPSILON || Math.abs(robotX - targetX) > CHASE_EPSILON
                || Math.abs(robotY - targetY) > CHASE_EPSILON || Math.abs(newClear - clearDistance) > CHASE_EPSILON) {
            yawDegrees = newYaw;
            targetX = robotX;
            targetY = robotY;
            clearDistance = newClear;
            version++;
        }
    }

    /** Points the camera at the point {@code (x, y, z)}, without turning or moving it away. */
    public synchronized void setTarget(double x, double y, double z) {
        targetX = x;
        targetY = y;
        targetZ = z;
        changedAndNotify();
    }

    /**
     * Slides the camera sideways and up or down in the plane of the picture, so that what is under the pointer follows the
     * pointer: dragging right moves the scene right.
     *
     * @param viewportHeightPixels the height of the picture, which tells how far a pixel is
     */
    public synchronized void pan(double deltaXPixels, double deltaYPixels, double viewportHeightPixels) {
        preset = Preset.FREE;
        double metresPerPixel = 2.0 * distance * Math.tan(Math.toRadians(FIELD_OF_VIEW_DEGREES) / 2.0) / Math.max(1.0, viewportHeightPixels);
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double rightX = -Math.sin(yaw);
        double rightY = Math.cos(yaw);
        double upX = -Math.sin(pitch) * Math.cos(yaw);
        double upY = -Math.sin(pitch) * Math.sin(yaw);
        double upZ = Math.cos(pitch);
        targetX += (-rightX * deltaXPixels + upX * deltaYPixels) * metresPerPixel;
        targetY += (-rightY * deltaXPixels + upY * deltaYPixels) * metresPerPixel;
        targetZ += upZ * deltaYPixels * metresPerPixel;
        changedAndNotify();
    }

    /** Where the camera is, and the unit vectors of the picture: forward, right and up, in that order after the eye. */
    private double[] basis() {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double reach = preset == Preset.CHASE ? Math.min(distance, Math.max(CHASE_MIN_DISTANCE, clearDistance)) : distance;
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        return new double[] {
            targetX + reach * cp * cy, targetY + reach * cp * sy, targetZ + reach * sp,
            -cp * cy, -cp * sy, -sp,
            -sy, cy, 0.0,
            -sp * cy, -sp * sy, cp,
        };
    }

    /**
     * Where the point {@code (x, y, z)} of the world appears in the picture, as normalised device coordinates (the picture
     * runs from -1 to 1 across and up), written to {@code ndc}.
     *
     * @param aspect the picture's width over its height
     * @return false when the point is behind the camera, where it does not appear
     */
    public synchronized boolean project(double x, double y, double z, double aspect, double[] ndc) {
        double[] b = basis();
        double vx = x - b[0];
        double vy = y - b[1];
        double vz = z - b[2];
        double depth = vx * b[3] + vy * b[4] + vz * b[5];
        if (depth <= 0.0) {
            return false;
        }
        double scale = Math.tan(Math.toRadians(FIELD_OF_VIEW_DEGREES) / 2.0);
        ndc[0] = (vx * b[6] + vy * b[7] + vz * b[8]) / (depth * scale * aspect);
        ndc[1] = (vx * b[9] + vy * b[10] + vz * b[11]) / (depth * scale);
        return true;
    }

    /**
     * The line of sight through the point of the picture at {@code (ndcX, ndcY)}: its start, which is the camera, and its
     * direction as a unit vector.
     *
     * @param origin three numbers, set to the camera's position
     * @param direction three numbers, set to the direction
     */
    public synchronized void ray(double ndcX, double ndcY, double aspect, double[] origin, double[] direction) {
        double[] b = basis();
        double scale = Math.tan(Math.toRadians(FIELD_OF_VIEW_DEGREES) / 2.0);
        double dx = b[3] + ndcX * scale * aspect * b[6] + ndcY * scale * b[9];
        double dy = b[4] + ndcX * scale * aspect * b[7] + ndcY * scale * b[10];
        double dz = b[5] + ndcX * scale * aspect * b[8] + ndcY * scale * b[11];
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        origin[0] = b[0];
        origin[1] = b[1];
        origin[2] = b[2];
        direction[0] = dx / length;
        direction[1] = dy / length;
        direction[2] = dz / length;
    }

    public synchronized double yawDegrees() {
        return yawDegrees;
    }

    public synchronized double pitchDegrees() {
        return pitchDegrees;
    }

    public synchronized double distance() {
        return distance;
    }

    /** Counts every change made to the view; a frame drawn with an older version is out of date. */
    public synchronized long version() {
        return version;
    }

    /** Writes projection * view into {@code out} (16 floats, column-major) and the eye position into {@code eye}. */
    synchronized void viewProjection(float aspect, float[] out, float[] eye) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double reach = preset == Preset.CHASE ? Math.min(distance, Math.max(CHASE_MIN_DISTANCE, clearDistance)) : distance;
        float eyeX = (float) (targetX + reach * Math.cos(pitch) * Math.cos(yaw));
        float eyeY = (float) (targetY + reach * Math.cos(pitch) * Math.sin(yaw));
        float eyeZ = (float) (targetZ + reach * Math.sin(pitch));
        eye[0] = eyeX;
        eye[1] = eyeY;
        eye[2] = eyeZ;
        Mat4.perspective((float) Math.toRadians(FIELD_OF_VIEW_DEGREES), aspect, 0.1f, 50f, projection);
        Mat4.lookAt(eyeX, eyeY, eyeZ, (float) targetX, (float) targetY, (float) targetZ, view);
        Mat4.multiply(projection, view, out);
    }

    /** How far behind the robot the camera can stand before something tall is between it and the robot. */
    private double clearanceBehind(double robotX, double robotY, double yawDeg, double dtSeconds) {
        if (blockerCount == 0) {
            return Double.MAX_VALUE;
        }
        double hit = distanceToBlocker(robotX, robotY, yawDeg);
        double free = hit < distance ? hit - CHASE_CLEARANCE : distance;
        // Closing in is immediate, opening out again is eased, so that passing a blocker does not snap the view back.
        double current = Math.min(clearDistance, distance);
        if (free <= current || free - current < CHASE_SETTLED) {
            return free;
        }
        double release = 1.0 - Math.exp(-Math.max(0.0, dtSeconds) / CHASE_RELEASE_SECONDS);
        return current + (free - current) * release;
    }

    /**
     * How far round from straight behind the robot the camera should stand: none while behind leaves room for the
     * camera, the side it has gone to while that stays clear, and otherwise the nearest side that is.
     */
    private double chooseOffset(double robotX, double robotY, double behind) {
        double needed = CHASE_MIN_DISTANCE + CHASE_CLEARANCE;
        boolean aside = chaseOffsetDegrees != 0.0;
        double behindReach = distanceToBlocker(robotX, robotY, behind);
        // Once it has gone round, the way behind has to be clearly better before the camera goes back there.
        if (behindReach >= (aside ? Math.min(distance, needed + CHASE_RETURN_MARGIN) : needed)) {
            return 0.0;
        }
        if (aside && distanceToBlocker(robotX, robotY, behind + chaseOffsetDegrees) >= needed) {
            return chaseOffsetDegrees;
        }
        double best = 0.0;
        double bestReach = behindReach;
        for (double step : CHASE_SIDESTEPS_DEGREES) {
            double reach = distanceToBlocker(robotX, robotY, behind + step);
            if (reach >= needed) {
                return step;
            }
            if (reach > bestReach) {
                best = step;
                bestReach = reach;
            }
        }
        return best;
    }

    /** How far a camera standing at {@code yawDeg} round the robot could look back before a blocker is in its way. */
    private double distanceToBlocker(double robotX, double robotY, double yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDegrees);
        return firstBlocker(
                robotX, robotY, targetZ, Math.cos(pitch) * Math.cos(yaw), Math.cos(pitch) * Math.sin(yaw), Math.sin(pitch),
                distance);
    }

    /** The distance along a ray to the first blocker it enters, or {@code limit} when it enters none that close. */
    private double firstBlocker(double ox, double oy, double oz, double dx, double dy, double dz, double limit) {
        double nearest = limit;
        for (int i = 0; i < blockerCount; i++) {
            int box = i * BLOCKER_STRIDE;
            intoBoxFrame(box, ox - blockers[box], oy - blockers[box + 1], oz - blockers[box + 2], 0);
            intoBoxFrame(box, dx, dy, dz, 3);
            double enter = 0.0;
            double leave = nearest;
            boolean hit = true;
            for (int axis = 0; axis < 3 && hit; axis++) {
                double origin = scratch[axis];
                double direction = scratch[3 + axis];
                double half = blockers[box + 7 + axis];
                if (Math.abs(direction) < 1e-9) {
                    hit = Math.abs(origin) <= half;
                } else {
                    double near = (-half - origin) / direction;
                    double far = (half - origin) / direction;
                    enter = Math.max(enter, Math.min(near, far));
                    leave = Math.min(leave, Math.max(near, far));
                    hit = enter <= leave;
                }
            }
            // A ray that starts inside a box (enter stays 0) is not blocked by it: the robot may be standing in one.
            if (hit && enter > 0.0 && enter < nearest) {
                nearest = enter;
            }
        }
        return nearest;
    }

    /** Turns a vector into the frame of a blocker, by the opposite of its rotation. */
    private void intoBoxFrame(int box, double vx, double vy, double vz, int out) {
        double ax = -blockers[box + 3];
        double ay = -blockers[box + 4];
        double az = -blockers[box + 5];
        double aw = blockers[box + 6];
        double tx = 2.0 * (ay * vz - az * vy);
        double ty = 2.0 * (az * vx - ax * vz);
        double tz = 2.0 * (ax * vy - ay * vx);
        scratch[out] = vx + aw * tx + (ay * tz - az * ty);
        scratch[out + 1] = vy + aw * ty + (az * tx - ax * tz);
        scratch[out + 2] = vz + aw * tz + (ax * ty - ay * tx);
    }

    private void place(double x, double y, double yaw, double pitch, double newDistance) {
        targetX = x;
        targetY = y;
        yawDegrees = wrap(yaw);
        pitchDegrees = pitch;
        distance = newDistance;
        version++;
    }

    private void changedAndNotify() {
        version++;
        changed.run();
    }

    private static double wrap(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped > 180.0) {
            return wrapped - 360.0;
        }
        return wrapped <= -180.0 ? wrapped + 360.0 : wrapped;
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
