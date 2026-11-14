package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.Scenery;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;

/** A box of the field: where it is, how big it is, how it is turned, what colour it is drawn in and what it is made of. */
record Part(String name, Vector3 center, Vector3 halfExtents, Orientation rotation, int rgb, Material material) {

    Part(String name, Vector3 center, Vector3 halfExtents, Orientation rotation, int rgb) {
        this(name, center, halfExtents, rotation, rgb, Material.DEFAULT);
    }

    /** A box with its edges along the field axes, given by its bounds. */
    static Part bounds(
            String name, double minX, double maxX, double minY, double maxY, double minZ, double maxZ, int rgb) {
        return new Part(
                name,
                new Vector3(0.5 * (minX + maxX), 0.5 * (minY + maxY), 0.5 * (minZ + maxZ)),
                new Vector3(0.5 * (maxX - minX), 0.5 * (maxY - minY), 0.5 * (maxZ - minZ)),
                Orientation.IDENTITY,
                rgb);
    }

    /** The mirror image across y = 0, which turns a blue part into the red one. */
    Part mirrored(String mirroredName) {
        return new Part(
                mirroredName, new Vector3(center.x(), -center.y(), center.z()), halfExtents, rotation.mirrored(), rgb, material);
    }

    Part recolored(int newRgb) {
        return new Part(name, center, halfExtents, rotation, newRgb, material);
    }

    Part madeOf(Material newMaterial) {
        return new Part(name, center, halfExtents, rotation, rgb, newMaterial);
    }

    Scenery scenery() {
        FieldPart box = new FieldPart(
                new Shape.Box(halfExtents.x(), halfExtents.y(), halfExtents.z()), Orientation.IDENTITY.poseAt(Vector3.ZERO), rgb);
        return new Scenery(name, box, rotation.poseAt(center));
    }

    void addTo(PhysicsWorld world) {
        world.addStaticBox(center, halfExtents, rotation.axis(), rotation.angleRadians(), material.friction(), material.restitution());
    }
}
