package dev.ftcsim.simulator.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.physics.DcMotor;
import java.util.List;
import org.junit.jupiter.api.Test;

class MotorCatalogTest {

    @Test
    void theSwyftSpikeBehindItsDriveReductionHasThePublishedCoreScaledBy12Point7() {
        DcMotor spike = MotorCatalog.motor(MotorCatalog.SWYFT_SPIKE_12_7);

        assertEquals(1.47 * 0.0980665 * 12.7, spike.stallTorqueNm(), 1e-9, "1.47 kg cm at the rotor");
        assertEquals(6000.0 * 2.0 * Math.PI / 60.0 / 12.7, spike.freeSpeedRadPerSec(), 1e-9, "6000 rpm at the rotor");
        assertEquals(9.2, spike.stallCurrentA(), 1e-9);
        assertEquals(12.7, spike.gearRatio(), 1e-9);
        assertEquals(28.0 * 12.7, spike.encoderTicksPerRevolution(), 1e-9, "28 counts per rotor turn");
    }

    @Test
    void everyChoiceNamesACatalogueMotorTheDefaultComesFirstAndNoneRepeats() {
        List<dev.ftcsim.contracts.session.MotorChoice> choices = MotorCatalog.choices();

        assertEquals(MotorCatalog.GOBILDA_435, choices.get(0).id());
        assertEquals(12, choices.size());
        assertEquals(12, choices.stream().map(dev.ftcsim.contracts.session.MotorChoice::id).distinct().count());
        for (dev.ftcsim.contracts.session.MotorChoice choice : choices) {
            assertTrue(MotorCatalog.motor(choice.id()) != null && !choice.displayName().isBlank(), choice.toString());
        }
    }

    @Test
    void everyYellowJacketTurnsAtItsNominalSpeedWithinSixPercent() {
        for (int rpm : new int[] {1620, 1150, 435, 312, 223, 117, 84, 60, 43, 30}) {
            DcMotor motor = MotorCatalog.motor("goBILDA-5203-" + rpm);
            double actualRpm = motor.freeSpeedRadPerSec() * 60.0 / (2 * Math.PI);
            assertEquals(rpm, actualRpm, rpm * 0.06, "goBILDA-5203-" + rpm);
        }
    }

    @Test
    void aGearboxTradesSpeedForTorqueAtTheSameCurrentAndThePowerOfTheSameMotor() {
        DcMotor fast = MotorCatalog.motor("goBILDA-5203-1150");
        DcMotor slow = MotorCatalog.motor("goBILDA-5203-60");

        assertEquals(
                fast.stallTorqueNm() * fast.freeSpeedRadPerSec(),
                slow.stallTorqueNm() * slow.freeSpeedRadPerSec(),
                1e-9,
                "the winding is the same, so the mechanical power at the corner of its curve is");
        assertEquals(fast.stallCurrentA(), slow.stallCurrentA());
        assertTrue(slow.stallTorqueNm() > 15 * fast.stallTorqueNm(), "19 times the reduction, 19 times the torque");
        assertEquals(28.0 * slow.gearRatio(), slow.encoderTicksPerRevolution(), 1e-9);
    }

    @Test
    void theMotorsThatWereThereBeforeAreUntouched() {
        DcMotor measured = DcMotor.goBilda435();

        assertEquals(measured, MotorCatalog.motor(MotorCatalog.GOBILDA_435));
        DcMotor bare = MotorCatalog.motor(MotorCatalog.GOBILDA_BARE);
        assertEquals(measured.stallTorqueNm() / measured.gearRatio(), bare.stallTorqueNm());
        assertEquals(measured.freeSpeedRadPerSec() * measured.gearRatio(), bare.freeSpeedRadPerSec());
        assertEquals(28.0, bare.encoderTicksPerRevolution());
        assertEquals(1.0, bare.gearRatio());
    }

    @Test
    void aMotorThatIsNotInTheCatalogueIsRefusedWithTheNamesThatAre() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> MotorCatalog.motor("REV-HD-Hex"));

        assertTrue(refused.getMessage().contains("REV-HD-Hex"), refused.getMessage());
        assertTrue(refused.getMessage().contains("goBILDA-5203-312"), refused.getMessage());
    }
}
