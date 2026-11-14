package dev.ftcsim.contracts.snapshot;

/**
 * One rigid body as presentation code sees it. Positions are metres and the rotation is a unit
 * quaternion, both expressed in the world frame owned by the active field package.
 *
 * @param entityId stable identifier, never reused within a session
 * @param kind     identifies the visual and collision category; interpreted by the field package
 */
public record BodyState(
        int entityId, int kind, float x, float y, float z, float qx, float qy, float qz, float qw) {}
