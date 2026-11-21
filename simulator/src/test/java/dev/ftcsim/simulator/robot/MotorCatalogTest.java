package dev.ftcsim.simulator.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.physics.DcMotor;
import org.junit.jupiter.api.Test;

class MotorCatalogTest {

    @Test
    void aReductionPutsAMotorBehindAGearbox() {
        DcMotor spike = MotorCatalog.motor(MotorCatalog.SWYFT_SPIKE);
        DcMotor behind = MotorCatalog.motor(MotorCatalog.SWYFT_SPIKE, 3.0);
        assertEquals(spike.stallTorqueNm() * 3.0, behind.stallTorqueNm(), 1e-12, "torque goes up with the reduction");
        assertEquals(spike.freeSpeedRadPerSec() / 3.0, behind.freeSpeedRadPerSec(), 1e-12, "and speed comes down");
        assertEquals(spike.encoderTicksPerRevolution() * 3.0, behind.encoderTicksPerRevolution(), 1e-12);
        assertEquals(spike, MotorCatalog.motor(MotorCatalog.SWYFT_SPIKE, 1.0), "one to one is the motor");
    }

    @Test
    void theSwyftSpikeBehindItsDriveReductionHasThePublishedCoreScaledBy12Point7() {
        DcMotor spike = MotorCatalog.motor(MotorCatalog.SWYFT_SPIKE, 12.7);

        assertEquals(1.47 * 0.0980665 * 12.7, spike.stallTorqueNm(), 1e-9, "1.47 kg cm at the rotor");
        assertEquals(6000.0 * 2.0 * Math.PI / 60.0 / 12.7, spike.freeSpeedRadPerSec(), 1e-9, "6000 rpm at the rotor");
        assertEquals(9.2, spike.stallCurrentA(), 1e-9);
        assertEquals(12.7, spike.gearRatio(), 1e-9);
        assertEquals(28.0 * 12.7, spike.encoderTicksPerRevolution(), 1e-9, "28 counts per rotor turn");
    }

    @Test
    void theYellowJacketIsTheWindingOfTheMeasured435RpmOneWithNoGearbox() {
        DcMotor measured = DcMotor.goBilda435();

        DcMotor bare = MotorCatalog.motor(MotorCatalog.GOBILDA_BARE);
        assertEquals(measured.stallTorqueNm() / measured.gearRatio(), bare.stallTorqueNm());
        assertEquals(measured.freeSpeedRadPerSec() * measured.gearRatio(), bare.freeSpeedRadPerSec());
        assertEquals(28.0, bare.encoderTicksPerRevolution());
        assertEquals(1.0, bare.gearRatio());
        DcMotor behind = MotorCatalog.motor(MotorCatalog.GOBILDA_BARE, 13.7);
        assertEquals(measured.stallTorqueNm(), behind.stallTorqueNm(), 1e-9, "at 13.7:1 it is the 435 rpm motor");
        assertEquals(measured.freeSpeedRadPerSec(), behind.freeSpeedRadPerSec(), 1e-9);
        assertEquals(measured.rotorInertiaKgM2(), behind.rotorInertiaKgM2(), 1e-12);
    }

    @Test
    void aMotorThatIsNotInTheCatalogueIsRefusedWithTheNamesThatAre() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> MotorCatalog.motor("REV-HD-Hex"));

        assertTrue(refused.getMessage().contains("REV-HD-Hex"), refused.getMessage());
        assertTrue(refused.getMessage().contains("swyft-spike-6000"), refused.getMessage());
    }
}
