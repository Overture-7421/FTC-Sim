package dev.ftcsim.physics;

/** Handle to a gravity-closed gate flap; its motion is simulated entirely by the engine. */
public final class Gate {

    private final PhysicsWorld world;
    private final int entityId;

    Gate(PhysicsWorld world, int entityId) {
        this.world = world;
        this.entityId = entityId;
    }

    public int entityId() {
        return entityId;
    }

    /** Opening angle, zero when closed and positive when swung towards +X. */
    public double angleRadians() {
        return world.gateAngle(entityId);
    }
}
