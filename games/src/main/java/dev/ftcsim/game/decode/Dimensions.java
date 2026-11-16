package dev.ftcsim.game.decode;

import dev.ftcsim.physics.Vector3;

/**
 * Measures of the DECODE field that more than one part of the package needs, in the field frame. Everything is given
 * for the blue goal, in the corner at -X, -Y; the red goal is its mirror image across y = 0.
 */
final class Dimensions {

    /** Where the inside face of the perimeter is: the field runs from -WALL to +WALL on both axes. */
    static final double WALL = 1.7945;

    static final double WALL_HEIGHT = 0.2785;
    static final double WALL_THICKNESS = 0.02;

    static final double ARTIFACT_RADIUS = 0.06275;
    static final double ARTIFACT_MASS_KG = 0.0748;

    static final double PANEL_HEIGHT = 0.9842;
    static final double PANEL_THICKNESS = 0.01;
    static final double BACKBOARD_TOP = 1.3652;

    static final double FRONT_PANEL_LENGTH = 0.6858;
    static final double FRONT_PANEL_X = -1.50155;
    static final double FRONT_PANEL_Y = -1.40585;

    /** Direction of the front panel's outward normal, counter-clockwise from +X (the SDK's pose of tag 20). */
    static final double FRONT_NORMAL_YAW = StrictMath.atan2(0.80952, 0.58706);

    private Dimensions() {}

    static Vector3 frontNormal() {
        return new Vector3(StrictMath.cos(FRONT_NORMAL_YAW), StrictMath.sin(FRONT_NORMAL_YAW), 0);
    }

    /** Along the front panel from the wall towards the archway. */
    static Vector3 frontTangent() {
        return new Vector3(StrictMath.sin(FRONT_NORMAL_YAW), -StrictMath.cos(FRONT_NORMAL_YAW), 0);
    }

    static Vector3 frontPanelCenter() {
        return new Vector3(FRONT_PANEL_X, FRONT_PANEL_Y, 0.5 * PANEL_HEIGHT);
    }
}
