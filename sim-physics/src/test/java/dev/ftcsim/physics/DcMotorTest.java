package dev.ftcsim.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DcMotorTest {

    private static final DcMotor MOTOR = DcMotor.goBilda435();

    @Test
    void stallAndFreeSpeedAreTheDataSheetOperatingPoints() {
        DcMotor.Thevenin full = MOTOR.thevenin(1.0, 12.0);
        assertEquals(1.834, full.torqueNm(0.0), 1e-12);
        assertEquals(0.0, full.torqueNm(435.0 * 2.0 * Math.PI / 60.0), 1e-9);
        assertEquals(9.2, MOTOR.currentAmps(1.0, 12.0, 0.0), 1e-12);
        assertEquals(0.0, MOTOR.currentAmps(1.0, 12.0, MOTOR.freeSpeedRadPerSec()), 1e-9);
    }

    @Test
    void theCommandAndTheBatteryScaleTheVoltageAcrossTheWindings() {
        assertEquals(0.75 * 1.834, MOTOR.thevenin(1.0, 9.0).zeroSpeedTorqueNm(), 1e-12);
        assertEquals(-0.5 * 1.834, MOTOR.thevenin(-0.5, 12.0).zeroSpeedTorqueNm(), 1e-12);
        assertEquals(1.834, MOTOR.thevenin(3.0, 12.0).zeroSpeedTorqueNm(), 1e-12);
    }

    @Test
    void anUnpoweredMotorBrakesWhateverTurnsIt() {
        DcMotor.Thevenin idle = MOTOR.thevenin(0.0, 12.0);
        assertEquals(0.0, idle.zeroSpeedTorqueNm());
        assertTrue(idle.torqueNm(10.0) < 0.0);
        assertTrue(idle.torqueNm(-10.0) > 0.0);
    }

    @Test
    void rejectsParametersThatDescribeNoMotor() {
        assertThrows(IllegalArgumentException.class, () -> new DcMotor(0.0, 45.0, 12.0, 9.2, 5e-6, 13.7, 384.5));
    }
}
