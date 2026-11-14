package dev.ftcsim.mechanisms;

import dev.ftcsim.contracts.world.Vec3;

/** A region in the robot's own frame, X forward, Y left, Z up, with its origin at the chassis origin. */
public sealed interface Volume {

    boolean contains(Vec3 bodyPoint);

    /** A box with its edges along the robot's axes. */
    record Box(Vec3 center, Vec3 halfExtents) implements Volume {
        public Box {
            if (!(halfExtents.x() > 0 && halfExtents.y() > 0 && halfExtents.z() > 0)) {
                throw new IllegalArgumentException("a box needs positive half extents: " + halfExtents);
            }
        }

        @Override
        public boolean contains(Vec3 p) {
            return Math.abs(p.x() - center.x()) <= halfExtents.x()
                    && Math.abs(p.y() - center.y()) <= halfExtents.y()
                    && Math.abs(p.z() - center.z()) <= halfExtents.z();
        }
    }
}
