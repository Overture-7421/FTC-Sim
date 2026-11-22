package dev.ftcsim.game.biobuzz;

import static dev.ftcsim.game.biobuzz.Solids.in;

import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.WorldSnapshot;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;
import java.util.HashSet;
import java.util.Set;

/** Models the stack feeding across the retrieval lip once the bottom POLLEN has come out. */
final class FlowerOutlet {
    // Centre height below which a POLLEN can pass under the middle ring.
    private static final double EXIT_HEIGHT = in(FlowerModel.MIDDLE_RING_BOTTOM) - PieceModel.POLLEN_RADIUS_M;
    private final FlowerModel.Site site;
    private final Set<Integer> draining = new HashSet<>();
    private int bottom = -1;

    FlowerOutlet(FlowerModel.Site site) {
        this.site = site;
    }

    void afterStep(PhysicsWorld world, WorldSnapshot snapshot) {
        boolean opened = false;
        if (bottom >= 0) {
            var previous = world.hasPiece(bottom) ? world.bodyPose(bottom) : null;
            opened = previous == null || !insideColumn(previous.x(), previous.y());
        }
        bottom = -1;
        double lowest = in(FlowerModel.MIDDLE_RING_BOTTOM);
        draining.removeIf(id -> !world.hasPiece(id));
        for (BodyState body : snapshot.bodies()) {
            if (body.kind() != BiobuzzIds.KIND_POLLEN) {
                continue;
            }
            int id = body.entityId();
            if (!insideColumn(body.x(), body.y()) || body.z() > FlowerModel.SCORING_TOP_M) {
                draining.remove(id);
                continue;
            }
            if (opened && draining.add(id)) {
                // Removing a supporting ball can leave the bodies above it asleep in Jolt.
                world.pushPiece(id, new Vector3(0, 0, -0.001));
            }
            if (draining.contains(id) && body.z() >= EXIT_HEIGHT) {
                centerOnSeat(world, body);
            }
            if (body.z() < lowest) {
                lowest = body.z();
                bottom = id;
            }
        }
        if (lowest < EXIT_HEIGHT && draining.contains(bottom)) {
            var pose = world.bodyPose(bottom);
            double outwardSpeed = -pose.vx() * site.wallX() - pose.vy() * site.wallY();
            if (outwardSpeed < 0.1 && Math.abs(pose.vz()) < 0.1) {
                // Supply the plate's outward push only once the falling ball reaches the lip.
                double mass = PieceModel.POLLEN.massKg();
                world.pushPiece(bottom, new Vector3(-0.75 * site.wallX() * mass,
                        -0.75 * site.wallY() * mass, 0.48 * mass));
            }
        }
    }

    private void centerOnSeat(PhysicsWorld world, BodyState body) {
        // A ball shifted sideways by the lift can perch on the middle ring seat.
        double dx = in(site.axisX()) - body.x();
        double dy = in(site.axisY()) - body.y();
        if (Math.hypot(dx, dy) > in(0.25)) {
            var pose = world.bodyPose(body.entityId());
            if (Math.abs(pose.vz()) < 0.08 && Math.hypot(pose.vx(), pose.vy()) < 0.05) {
                world.pushPiece(body.entityId(), new Vector3(dx, dy, 0).times(0.22));
            }
        }
    }

    private boolean insideColumn(double x, double y) {
        double dx = x - in(site.axisX());
        double dy = y - in(site.axisY());
        return dx * dx + dy * dy < FlowerModel.COLUMN_RADIUS_M * FlowerModel.COLUMN_RADIUS_M;
    }
}
