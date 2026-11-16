package dev.ftcsim.mechanisms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.physics.DcMotor;
import org.junit.jupiter.api.Test;

class ShaftPlantTest {
    private static final double TWO_PI = 2.0 * Math.PI;

    private static ShaftPlant flywheel() {
        // A 435 rpm motor turning a 4e-4 kg m^2 wheel.
        return new ShaftPlant(DcMotor.goBilda435(), 4.0e-4, 2.0e-4);
    }

    private static void run(ShaftPlant plant, double seconds, double stepSeconds) {
        long steps = Math.round(seconds / stepSeconds);
        for (long i = 0; i < steps; i++) {
            plant.step(stepSeconds);
        }
    }

    @Test
    void aShaftDrivenAtNominalVoltageSettlesBelowTheFreeSpeedByTheFriction() {
        DcMotor motor = DcMotor.goBilda435();
        ShaftPlant plant = flywheel();
        plant.applyVoltage(12.0);

        run(plant, 2.0, 0.001);

        double damping = motor.stallTorqueNm() / motor.freeSpeedRadPerSec();
        double expected = motor.stallTorqueNm() / (damping + 2.0e-4);
        assertEquals(expected, plant.speedRadPerSec(), expected * 1e-6);
        assertTrue(plant.speedRadPerSec() < motor.freeSpeedRadPerSec());
    }

    @Test
    void theResultDoesNotDependOnHowTheTimeIsCutIntoSteps() {
        ShaftPlant once = flywheel();
        ShaftPlant fine = flywheel();
        once.applyVoltage(8.0);
        fine.applyVoltage(8.0);

        once.step(0.2);
        run(fine, 0.2, 0.0001);

        assertEquals(once.speedRadPerSec(), fine.speedRadPerSec(), 1e-9);
        assertEquals(once.angleRadians(), fine.angleRadians(), 1e-9);
    }

    @Test
    void floatingTerminalsCoastOnFrictionAloneAndBrakingStopsTheShaftSooner() {
        ShaftPlant coasting = flywheel();
        ShaftPlant braking = flywheel();
        coasting.applyVoltage(12.0);
        braking.applyVoltage(12.0);
        run(coasting, 1.0, 0.001);
        run(braking, 1.0, 0.001);
        double before = coasting.speedRadPerSec();

        coasting.floatTerminals();
        braking.applyVoltage(0.0);
        run(coasting, 0.1, 0.001);
        run(braking, 0.1, 0.001);

        assertTrue(coasting.speedRadPerSec() > 0.9 * before, "friction alone barely slows the shaft: " + coasting.speedRadPerSec());
        assertTrue(braking.speedRadPerSec() < 0.5 * before, "a shorted winding brakes hard: " + braking.speedRadPerSec());
        assertEquals(0.0, coasting.currentAmps());
    }

    @Test
    void theEncoderCountsTicksOfTheOutputShaftInTheDirectionOfThePositiveVoltage() {
        ShaftPlant forward = flywheel();
        ShaftPlant backward = flywheel();
        forward.applyVoltage(6.0);
        backward.applyVoltage(-6.0);

        run(forward, 0.5, 0.001);
        run(backward, 0.5, 0.001);

        assertEquals((long) Math.floor(forward.angleRadians() / TWO_PI * 384.5), forward.encoderCounts());
        assertTrue(forward.encoderCounts() > 0);
        assertTrue(backward.encoderCounts() < 0);
        assertEquals(forward.speedRadPerSec() / TWO_PI * 384.5, forward.encoderCountsPerSecond(), 1e-9);
    }

    @Test
    void theWindingDrawsTheStallCurrentWhileTheShaftHoldsStillAndLessAsItSpinsUp() {
        DcMotor motor = DcMotor.goBilda435();
        ShaftPlant plant = flywheel();
        plant.applyVoltage(12.0);
        assertEquals(motor.stallCurrentA(), plant.currentAmps(), 1e-12);

        run(plant, 1.0, 0.001);

        assertTrue(plant.currentAmps() < 0.25 * motor.stallCurrentA(), String.valueOf(plant.currentAmps()));
    }

    @Test
    void extractingEnergyFromASpinningShaftSlowsItByTheMatchingAmountAndNeverReversesIt() {
        ShaftPlant plant = flywheel();
        plant.applyVoltage(12.0);
        run(plant, 1.0, 0.001);
        double before = plant.speedRadPerSec();
        double inertia = DcMotor.goBilda435().reflectedRotorInertiaKgM2() + 4.0e-4;
        double energyBefore = 0.5 * inertia * before * before;

        plant.extractEnergy(0.25 * energyBefore);
        assertEquals(0.5 * inertia * plant.speedRadPerSec() * plant.speedRadPerSec(), 0.75 * energyBefore, energyBefore * 1e-9);

        plant.extractEnergy(10 * energyBefore);
        assertEquals(0.0, plant.speedRadPerSec());
        assertThrows(IllegalArgumentException.class, () -> plant.extractEnergy(-1.0));
    }

    @Test
    void aShaftWithNeitherInertiaNorALoadIsRefusedAndNegativeFrictionToo() {
        assertThrows(IllegalArgumentException.class, () -> new ShaftPlant(DcMotor.goBilda435(), -1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ShaftPlant(DcMotor.goBilda435(), 1.0, -0.1));
    }
}
