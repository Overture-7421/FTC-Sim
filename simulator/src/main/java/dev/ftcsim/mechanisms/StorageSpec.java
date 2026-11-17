package dev.ftcsim.mechanisms;

import dev.ftcsim.contracts.world.Vec3;
import java.util.List;

/**
 * A place where the robot carries pieces: a path in the robot's frame that pieces ride from its first point to its
 * last, at the speed of whatever drives them.
 *
 * @param name     what zones call it
 * @param capacity the most pieces it holds
 * @param path     at least two points in the robot frame; a piece entering the storage appears at the first and
 *                 is held back at the last
 * @param drives   what moves the pieces along the path
 * @param turned   the stretch of the path that turns with the robot's turret, or null when none does
 * @param gate     a servo-operated stopper across the path, or null when pieces are never held on the way
 */
public record StorageSpec(String name, int capacity, List<Vec3> path, List<Drive> drives, Turned turned, Gate gate) {

    /**
     * A stopper across the path at path point {@code point}: while the servo's angle is under {@code openRadians}, a
     * piece that has not yet passed it waits there, however hard the rollers drive it.
     */
    public record Gate(int point, String servo, double openRadians) {
        public Gate {
            if (point < 1) {
                throw new IllegalArgumentException("a gate is across the path after its first point");
            }
        }
    }

    /**
     * The part of a path that is inside a turret: from path point {@code fromPoint} on, a piece turns about the vertical
     * line through ({@code axisX}, {@code axisY}) by the turret's yaw. The point itself lies on that line, so a piece
     * crosses into the turret without a jump.
     */
    public record Turned(int fromPoint, double axisX, double axisY) {
        public Turned {
            if (fromPoint < 1) {
                throw new IllegalArgumentException("the turned stretch of a path starts after its first point");
            }
        }
    }

    /**
     * A roller or belt that moves the pieces touching it. A piece within the stretch from {@code fromMeters} to
     * {@code toMeters} along the path moves at the roller's surface speed.
     *
     * @param sign +1 when the motor turning forward (its encoder counting up) carries pieces towards the end of the
     *             path, -1 when it carries them back
     */
    public record Drive(String motor, double radiusM, int sign, double fromMeters, double toMeters) {
        public Drive {
            if (!(radiusM > 0) || Math.abs(sign) != 1 || !(fromMeters <= toMeters)) {
                throw new IllegalArgumentException(
                        "a drive needs a positive radius, a sign of 1 or -1 and a non-empty stretch: " + motor);
            }
        }
    }

    /** A storage whose path does not turn and has no gate. */
    public StorageSpec(String name, int capacity, List<Vec3> path, List<Drive> drives) {
        this(name, capacity, path, drives, null, null);
    }

    /** A storage with a turned stretch and no gate. */
    public StorageSpec(String name, int capacity, List<Vec3> path, List<Drive> drives, Turned turned) {
        this(name, capacity, path, drives, turned, null);
    }

    public StorageSpec {
        if (name.isBlank() || capacity < 1 || path.size() < 2) {
            throw new IllegalArgumentException("a storage needs a name, room for a piece and a path of two points: " + name);
        }
        if (turned != null && turned.fromPoint() >= path.size()) {
            throw new IllegalArgumentException("the turned stretch of storage '" + name + "' starts after the end of its path");
        }
        if (gate != null && gate.point() >= path.size()) {
            throw new IllegalArgumentException("the gate of storage '" + name + "' is after the end of its path");
        }
        path = List.copyOf(path);
        drives = List.copyOf(drives);
    }

    /** The length of the path in metres. */
    public double lengthMeters() {
        double length = 0;
        for (int i = 1; i < path.size(); i++) {
            length += path.get(i).minus(path.get(i - 1)).length();
        }
        return length;
    }
}
