package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;
import java.util.ArrayList;
import java.util.List;

/**
 * The OBELISK: a triangular prism outside the goal-side perimeter with an AprilTag on each of its three faces. It is
 * turned before the match so that the face of the pattern that was drawn looks at the field, and tags 21, 22 and 23
 * ride on it, so a robot sees the pattern only by reading the face that looks its way.
 */
final class Obelisk {

    static final int ENTITY_ID = 1200;
    static final double TAG_SIZE_METERS = 0.1651;

    private static final Vector3 CENTER = new Vector3(-1.8998, 0, 0.2921);
    private static final Vector3 HALF_EXTENTS = new Vector3(0.10, 0.10, 0.2921);
    // An equilateral prism with 11 in faces: its faces are this far from the axis.
    private static final double FACE_DISTANCE = 0.0807;
    private static final double FACE_HALF_WIDTH = 0.1397;
    private static final double FACE_THICKNESS = 0.005;
    private static final double TAG_HEIGHT_FROM_CENTER = 0.4295 - 0.2921;
    private static final int RGB = 0x3A3A3A;

    private Obelisk() {}

    /** The face of tag {@code 21 + index} looks along this direction, counter-clockwise from the prism's own +X. */
    private static double faceYaw(int index) {
        return index * 2 * Math.PI / 3;
    }

    static void addTo(PhysicsWorld world, Motif motif) {
        world.addFixedBox(ENTITY_ID, Kinds.OBELISK, CENTER, HALF_EXTENTS, -faceYaw(motif.ordinal()));
    }

    static BodyKind bodyKind() {
        List<FieldPart> faces = new ArrayList<>();
        for (int i = 0; i < Motif.values().length; i++) {
            double yaw = faceYaw(i);
            Vector3 middle = new Vector3(StrictMath.cos(yaw), StrictMath.sin(yaw), 0).times(FACE_DISTANCE - 0.5 * FACE_THICKNESS);
            faces.add(new FieldPart(
                    new Shape.Box(0.5 * FACE_THICKNESS, FACE_HALF_WIDTH, HALF_EXTENTS.z()),
                    Orientation.yaw(yaw).poseAt(middle),
                    RGB));
        }
        return new BodyKind(Kinds.OBELISK, "obelisk", faces);
    }

    static List<FieldTag> tags() {
        List<FieldTag> tags = new ArrayList<>();
        for (Motif motif : Motif.values()) {
            double yaw = faceYaw(motif.ordinal());
            Vector3 onTheFace = new Vector3(StrictMath.cos(yaw), StrictMath.sin(yaw), 0)
                    .times(FACE_DISTANCE)
                    .plus(new Vector3(0, 0, TAG_HEIGHT_FROM_CENTER));
            tags.add(new FieldTag(motif.tagId(), TAG_SIZE_METERS, ENTITY_ID, Orientation.facing(yaw).poseAt(onTheFace)));
        }
        return tags;
    }
}
