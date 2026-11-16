package dev.ftcsim.game.decode;

import dev.ftcsim.contracts.field.BodyKind;
import dev.ftcsim.contracts.field.FieldPart;
import dev.ftcsim.contracts.field.Shape;
import dev.ftcsim.physics.PivotSpec;
import dev.ftcsim.physics.ShapePart;
import dev.ftcsim.physics.Vector3;
import java.util.List;

/**
 * The GATE of a CLASSIFIER: a flat lever that hangs across the lower end of the ramp and is closed by gravity. A robot
 * that pushes the handle at its lower end swings the other end out of the way of the balls. The numbers come from the
 * official CAD, except for the mass and the damping, which are estimates.
 */
final class GateArm {

    static final int BLUE_ENTITY_ID = 1100;
    static final int RED_ENTITY_ID = 1101;

    /** Furthest the arm can swing, a little beyond the angle at which it lets the balls by. */
    static final double OPEN_LIMIT = 1.25;

    private static final double MASS_KG = 0.05;
    private static final double LENGTH = 0.3146;
    private static final double WIDTH = 0.057;
    private static final Vector3 HALF_EXTENTS = new Vector3(0.5 * LENGTH, 0.5 * WIDTH, 0.0064);
    // The arm is a plate across the ramp; its bar runs from the handle up towards the wall at this angle to the wall.
    private static final double BAR_ANGLE = Math.toRadians(40.7);
    private static final Vector3 PIVOT = new Vector3(0.033, -1.601, 0.1398);
    private static final Vector3 BOX_CENTER_FROM_PIVOT = new Vector3(0.0089, -0.0493, 0.0341);
    // Chosen so that gravity turns the arm back over its whole travel: the centre of mass hangs towards the wall.
    private static final Vector3 CENTER_OF_MASS_FROM_PIVOT = new Vector3(-0.0044, -0.047, -0.0164);
    private static final double BEARING_FRICTION_NM = 0.002;
    private static final double DAMPER_NMS_PER_RAD = 0.015;
    private static final int RGB = 0xD8D8D8;

    private GateArm() {}

    static int entityId(boolean red) {
        return red ? RED_ENTITY_ID : BLUE_ENTITY_ID;
    }

    static int kind(boolean red) {
        return red ? Kinds.RED_GATE : Kinds.BLUE_GATE;
    }

    static PivotSpec spec(boolean red) {
        Orientation box = boxOrientation(red);
        double inertia = MASS_KG * (LENGTH * LENGTH + WIDTH * WIDTH) / 12;
        return new PivotSpec(
                mirror(PIVOT, red),
                hingeAxis(red),
                List.of(new ShapePart.Box(mirror(BOX_CENTER_FROM_PIVOT, red), HALF_EXTENTS, box.axis(), box.angleRadians())),
                MASS_KG,
                mirror(CENTER_OF_MASS_FROM_PIVOT, red),
                new Vector3(inertia, inertia, inertia),
                0.0,
                0.0,
                OPEN_LIMIT,
                BEARING_FRICTION_NM,
                OPEN_LIMIT,
                DAMPER_NMS_PER_RAD,
                0.3f,
                0.1f);
    }

    /** What the arm looks like, in the frame of its body, which has its origin at the pivot. */
    static BodyKind bodyKind(boolean red) {
        Orientation box = boxOrientation(red);
        FieldPart arm = new FieldPart(
                new Shape.Box(HALF_EXTENTS.x(), HALF_EXTENTS.y(), HALF_EXTENTS.z()),
                box.poseAt(mirror(BOX_CENTER_FROM_PIVOT, red)),
                RGB);
        return new BodyKind(kind(red), red ? "red gate arm" : "blue gate arm", List.of(arm));
    }

    // Opening turns the handle towards the wall, which is a turn against the direction of the ramp for blue.
    private static Vector3 hingeAxis(boolean red) {
        Vector3 downhill = RampGeometry.downhill();
        return red ? downhill : downhill.times(-1);
    }

    private static Orientation boxOrientation(boolean red) {
        Vector3 up = new Vector3(RampGeometry.SIN, 0, RampGeometry.COS);
        double sin = StrictMath.sin(BAR_ANGLE);
        double cos = StrictMath.cos(BAR_ANGLE);
        Vector3 bar = Vector3.Y.times(-cos).plus(up.times(sin));
        Vector3 across = Vector3.Y.times(sin).plus(up.times(cos));
        Orientation blue = Orientation.fromAxes(bar, across);
        return red ? blue.mirrored() : blue;
    }

    private static Vector3 mirror(Vector3 blue, boolean red) {
        return red ? new Vector3(blue.x(), -blue.y(), blue.z()) : blue;
    }
}
