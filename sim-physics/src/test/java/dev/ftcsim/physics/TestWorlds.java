package dev.ftcsim.physics;

import dev.ftcsim.contracts.snapshot.BodyState;

/** Small helpers shared by the world-level tests. */
final class TestWorlds {

    private TestWorlds() {}

    static void steps(PhysicsWorld world, int count) {
        for (int i = 0; i < count; i++) {
            world.step();
        }
    }

    static BodyState body(PhysicsWorld world, int entityId) {
        return world.snapshot().bodies().stream()
                .filter(b -> b.entityId() == entityId)
                .findFirst()
                .orElseThrow();
    }

    /** A world with a 10 x 10 m floor whose top surface is at z = 0. */
    static PhysicsWorld worldWithFloor() {
        PhysicsWorld world = TestNatives.newWorld();
        world.addStaticBox(new Vector3(0, 0, -0.5), new Vector3(5, 5, 0.5));
        return world;
    }

    /** Heading of a body's +X axis about +Z, in radians. */
    static double yaw(BodyState body) {
        Vector3 heading = forward(body);
        return Math.atan2(heading.y(), heading.x());
    }

    /** Elevation of a body's +X axis above the horizontal, in degrees; positive when the nose is up. */
    static double pitchDegrees(BodyState body) {
        return Math.toDegrees(Math.asin(forward(body).z()));
    }

    private static Vector3 forward(BodyState body) {
        return new Quaternion(body.qx(), body.qy(), body.qz(), body.qw()).rotate(Vector3.X);
    }
}
