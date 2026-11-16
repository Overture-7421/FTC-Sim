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
 */
public record StorageSpec(String name, int capacity, List<Vec3> path, List<Drive> drives) {

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

    public StorageSpec {
        if (name.isBlank() || capacity < 1 || path.size() < 2) {
            throw new IllegalArgumentException("a storage needs a name, room for a piece and a path of two points: " + name);
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
