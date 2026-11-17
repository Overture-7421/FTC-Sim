package dev.ftcsim.mechanisms;

/**
 * A condition on the state of the robot's motors that makes a zone active: the shaft turns, in the direction its
 * encoder counts up, at a speed within a range. Conditions are data, not code: a zone never asks what the robot
 * program is doing, only what the hardware is doing.
 *
 * @param minRadPerSec the lowest speed that satisfies the condition, negative infinity for none
 * @param maxRadPerSec the highest speed that satisfies it, positive infinity for none
 * @param onAngle      whether the range is of the shaft's angle in radians since the session began and not of its speed
 */
public record Condition(String motor, double minRadPerSec, double maxRadPerSec, boolean onAngle) {

    public Condition {
        if (!(minRadPerSec <= maxRadPerSec)) {
            throw new IllegalArgumentException("the range is empty: " + minRadPerSec + " .. " + maxRadPerSec);
        }
    }

    /** A condition on the shaft's speed. */
    public Condition(String motor, double minRadPerSec, double maxRadPerSec) {
        this(motor, minRadPerSec, maxRadPerSec, false);
    }

    /** Turning forward at least this fast. */
    public static Condition atLeast(String motor, double radPerSec) {
        return new Condition(motor, radPerSec, Double.POSITIVE_INFINITY);
    }

    /** Turning backward at least this fast. */
    public static Condition atMost(String motor, double radPerSec) {
        return new Condition(motor, Double.NEGATIVE_INFINITY, radPerSec);
    }

    /** A servo whose shaft has turned at least this far, which is how far a gate must have opened. */
    public static Condition angleAtLeast(String servo, double radians) {
        return new Condition(servo, radians, Double.POSITIVE_INFINITY, true);
    }

    boolean holds(PlantView plants) {
        double value = onAngle ? plants.shaftAngleRad(motor) : plants.shaftSpeedRadPerSec(motor);
        return value >= minRadPerSec && value <= maxRadPerSec;
    }
}
