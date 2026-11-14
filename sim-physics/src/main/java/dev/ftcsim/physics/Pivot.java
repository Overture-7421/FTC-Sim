package dev.ftcsim.physics;

/** Handle to a body that swings about an axis between two stops; its motion is simulated entirely by the engine. */
public final class Pivot {

    private final PhysicsWorld world;
    private final int entityId;

    Pivot(PhysicsWorld world, int entityId) {
        this.world = world;
        this.entityId = entityId;
    }

    public int entityId() {
        return entityId;
    }

    /** Where the body is between its stops, as an angle about its axis in radians. */
    public double angleRadians() {
        return world.pivotAngle(entityId);
    }

    /** How fast the body turns about its axis, in radians per second. */
    public double angularVelocityRadiansPerSecond() {
        return world.pivotAngularVelocity(entityId);
    }
}
