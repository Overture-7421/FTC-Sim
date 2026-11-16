package dev.ftcsim.mechanisms;

import dev.ftcsim.contracts.world.Vec3;

/**
 * A region in the robot's own frame, X forward, Y left, Z up, with its origin at the chassis origin: a box with its
 * edges along the robot's axes.
 */
public record Volume(Vec3 center, Vec3 halfExtents) {

    public Volume {
        if (!(halfExtents.x() > 0 && halfExtents.y() > 0 && halfExtents.z() > 0)) {
            throw new IllegalArgumentException("a box needs positive half extents: " + halfExtents);
        }
    }

    public boolean contains(Vec3 bodyPoint) {
        return Math.abs(bodyPoint.x() - center.x()) <= halfExtents.x()
                && Math.abs(bodyPoint.y() - center.y()) <= halfExtents.y()
                && Math.abs(bodyPoint.z() - center.z()) <= halfExtents.z();
    }
}
