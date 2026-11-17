package dev.ftcsim.mechanisms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrServoPlantTest {
    private static final double TICK = 0.001;

    private static void run(CrServoPlant plant, double seconds, boolean enabled, int pulse, double step) {
        for (double t = 0; t < seconds - 1e-12; t += step) {
            plant.step(step, enabled, pulse);
        }
    }

    @Test
    void theCommandIsLinearInThePulseAroundNeutralWithADeadBandAndIsClipped() {
        assertEquals(0.0, CrServoPlant.command(1500));
        assertEquals(0.0, CrServoPlant.command(1502), "inside the dead band");
        assertEquals(0.0, CrServoPlant.command(1498));
        assertEquals(0.9, CrServoPlant.command(2400), 1e-12, "the SDK's setPower(1) is 2400 microseconds");
        assertEquals(-0.9, CrServoPlant.command(600), 1e-12);
        assertEquals(1.0, CrServoPlant.command(2900), "beyond full speed it saturates");
        assertEquals(-1.0, CrServoPlant.command(0));
    }

    @Test
    void theSpeedSettlesOnTheCommandedSpeedAndTheAngleIsItsIntegral() {
        CrServoPlant plant = new CrServoPlant(10.0, 0.03);

        run(plant, 1.0, true, 2000, TICK);

        assertEquals(5.0, plant.speedRadPerSec(), 1e-6, "half the full command, half the no-load speed");
        // After the lag has died away the angle grows at the settled rate; the lag costs speed * tau at the start.
        assertEquals(5.0 * 1.0 - 5.0 * 0.03, plant.angleRadians(), 1e-6);
    }

    @Test
    void aServoThatIsNotBeingSentPulsesCoastsToAStopAndTheSignFollowsThePulse() {
        CrServoPlant plant = new CrServoPlant(10.0, 0.03);
        run(plant, 0.5, true, 1000, TICK);
        assertTrue(plant.speedRadPerSec() < -4.9 && plant.angleRadians() < -2.0, "below neutral turns it backwards");

        run(plant, 0.5, false, 1000, TICK);

        assertEquals(0.0, plant.speedRadPerSec(), 1e-6);
    }

    @Test
    void howATimeIsCutIntoStepsDoesNotChangeWhereTheShaftIs() {
        CrServoPlant fine = new CrServoPlant(9.8, 0.03);
        CrServoPlant coarse = new CrServoPlant(9.8, 0.03);

        run(fine, 0.4, true, 1800, 0.0005);
        run(coarse, 0.4, true, 1800, 0.1);

        assertEquals(fine.angleRadians(), coarse.angleRadians(), 1e-9);
        assertEquals(fine.speedRadPerSec(), coarse.speedRadPerSec(), 1e-9);
    }
}
