package dev.ftcsim.mechanisms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PositionServoPlantTest {
    private static void run(PositionServoPlant plant, double seconds, boolean enabled, int pulse, double step) {
        long steps = Math.round(seconds / step);
        for (long i = 0; i < steps; i++) {
            plant.step(step, enabled, pulse);
        }
    }

    @Test
    void thePulseIsAPositionFromTheSdksUsualRangeAndSaturatesBeyondIt() {
        assertEquals(0.0, PositionServoPlant.position(600));
        assertEquals(0.5, PositionServoPlant.position(1500), 1e-12);
        assertEquals(1.0, PositionServoPlant.position(2400));
        assertEquals(0.0, PositionServoPlant.position(0));
        assertEquals(1.0, PositionServoPlant.position(3000));
    }

    @Test
    void theShaftGoesToThePositionAtNoMoreThanItsTopSpeedAndThenStays() {
        PositionServoPlant plant = new PositionServoPlant(4.0, false);
        assertEquals(0.0, plant.angleRadians(), "before any pulse the servo is at position 0");

        run(plant, 0.25, true, 2400, 0.005);
        assertEquals(1.0, plant.angleRadians(), 1e-9, "a quarter second at 4 rad/s");
        assertEquals(4.0, plant.speedRadPerSec(), 1e-9);

        run(plant, 1.5, true, 2400, 0.005);
        assertEquals(PositionServoPlant.TRAVEL_RADIANS, plant.angleRadians(), 1e-9, "it arrives and stops there");
        assertEquals(0.0, plant.speedRadPerSec(), 1e-9);
    }

    @Test
    void aServoThatIsNotBeingSentPulsesHoldsWhereItIs() {
        PositionServoPlant plant = new PositionServoPlant(4.0, false);
        run(plant, 0.1, true, 2400, 0.004);
        double where = plant.angleRadians();

        run(plant, 1.0, false, 600, 0.004);

        assertEquals(where, plant.angleRadians(), 1e-12);
    }

    @Test
    void aMirroredServoStartsAtTheOtherEndAndTravelsTheOtherWay() {
        PositionServoPlant plant = new PositionServoPlant(100.0, true);
        assertEquals(PositionServoPlant.TRAVEL_RADIANS, plant.angleRadians());

        run(plant, 0.5, true, 2400, 0.004);

        assertEquals(0.0, plant.angleRadians(), 1e-9, "position 1 is the near end when it is mounted the other way");
        assertThrows(IllegalArgumentException.class, () -> new PositionServoPlant(0.0, false));
    }
}
