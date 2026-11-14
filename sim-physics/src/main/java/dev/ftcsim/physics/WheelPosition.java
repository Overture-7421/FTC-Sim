package dev.ftcsim.physics;

/** The four wheel corners of a mecanum chassis, seen from above with +X forward and +Y left. */
public enum WheelPosition {
    FRONT_LEFT(1, 1),
    FRONT_RIGHT(1, -1),
    REAR_LEFT(-1, 1),
    REAR_RIGHT(-1, -1);

    private final int forwardSign;
    private final int leftSign;

    WheelPosition(int forwardSign, int leftSign) {
        this.forwardSign = forwardSign;
        this.leftSign = leftSign;
    }

    /** +1 for the front axle, -1 for the rear axle. */
    public int forwardSign() {
        return forwardSign;
    }

    /** +1 for the left side, -1 for the right side. */
    public int leftSign() {
        return leftSign;
    }

    /**
     * Handedness of the rollers in the usual X layout: the rollers of the front-left and rear-right
     * wheels transmit force along the forward-right diagonal and the other pair along forward-left.
     * A positive wheel speed always rolls the robot forward, whichever side the wheel is on.
     */
    int rollerSign() {
        return -forwardSign * leftSign;
    }
}
