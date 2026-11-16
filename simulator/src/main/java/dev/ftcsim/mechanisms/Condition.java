package dev.ftcsim.mechanisms;

/**
 * A condition on the state of the robot's motors that makes a zone active. Conditions are data, not code: a zone
 * never asks what the robot program is doing, only what the hardware is doing.
 */
public sealed interface Condition {

    boolean holds(PlantView plants);

    /**
     * The shaft turns, in the direction its encoder counts up, at a speed within a range.
     *
     * @param minRadPerSec the lowest speed that satisfies the condition, negative infinity for none
     * @param maxRadPerSec the highest speed that satisfies it, positive infinity for none
     */
    record ShaftSpeed(String motor, double minRadPerSec, double maxRadPerSec) implements Condition {

        public ShaftSpeed {
            if (!(minRadPerSec <= maxRadPerSec)) {
                throw new IllegalArgumentException("the speed range is empty: " + minRadPerSec + " .. " + maxRadPerSec);
            }
        }

        /** Turning forward at least this fast. */
        public static ShaftSpeed atLeast(String motor, double radPerSec) {
            return new ShaftSpeed(motor, radPerSec, Double.POSITIVE_INFINITY);
        }

        /** Turning backward at least this fast. */
        public static ShaftSpeed atMost(String motor, double radPerSec) {
            return new ShaftSpeed(motor, Double.NEGATIVE_INFINITY, radPerSec);
        }

        @Override
        public boolean holds(PlantView plants) {
            double speed = plants.shaftSpeedRadPerSec(motor);
            return speed >= minRadPerSec && speed <= maxRadPerSec;
        }
    }
}
