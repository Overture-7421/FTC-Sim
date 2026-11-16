package dev.ftcsim.game.decode;

import dev.ftcsim.physics.Vector3;

/**
 * Where the CLASSIFIER's ramp runs, for the blue goal: the line that the centres of the balls on the ramp follow, a
 * slope of 15 degrees along the wall from the goal's archway down to the gate. A position on the line is called
 * {@code s}, in metres downhill from the middle of the 45 in rails.
 */
final class RampGeometry {

    static final double SLOPE = Math.toRadians(15);
    static final double SIN = StrictMath.sin(SLOPE);
    static final double COS = StrictMath.cos(SLOPE);
    static final double TAN = SIN / COS;

    /** The ramp holds this many CLASSIFIED balls; more overflow. */
    static final int CAPACITY = 9;

    /** The distance between the centres of two balls that touch. */
    static final double SLOT = 2 * Dimensions.ARTIFACT_RADIUS;

    /**
     * The ball nearest the gate rests with its centre here, 12 mm short of the closed gate arm. A gap that small and no
     * smaller keeps the engine from making a contact between the ball and the arm, which a ball arriving fast would
     * use to knock the arm open.
     */
    static final double FIRST_SLOT = 0.5129;

    /** A ball whose centre passes this leaves the end of the rails. */
    static final double EXIT = 0.5715;

    /** A ball that rolled over the stack is only free once it is past the gate arm. */
    static final double OVERFLOW_EXIT = 0.70;

    /** Where a ball joins the ramp, below the SQUARE. */
    static final double ENTRY = -0.60;

    /** How far above the line of centres a ball rolls while it passes over the stack: on top of a ball of the row. */
    static final double OVERFLOW_LIFT = 2 * Dimensions.ARTIFACT_RADIUS;

    /** The gate lets balls through once its arm has turned this far: 5.1 cm of travel at the handle, as in the manual. */
    static final double GATE_OPEN_ANGLE = 0.5;

    private static final double LINE_X = -0.5273;
    private static final double LINE_Y = -1.69265;
    private static final double LINE_Z = 0.3478;

    private RampGeometry() {}

    /** The centre of a ball at {@code s}, raised by {@code lift} perpendicular to the slope. */
    static Vector3 point(double s, double lift, boolean red) {
        return new Vector3(LINE_X + s * COS + lift * SIN, red ? -LINE_Y : LINE_Y, LINE_Z - s * SIN + lift * COS);
    }

    /** Direction downhill. */
    static Vector3 downhill() {
        return new Vector3(COS, 0, -SIN);
    }

    /** Height of the surface that carries a ball along the ramp, at world x. */
    static double rampTop(double x) {
        return 0.2862 - TAN * (x + 0.5401);
    }
}
