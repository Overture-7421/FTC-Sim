package dev.ftcsim.contracts.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RobotMappingTest {

    private static RobotMapping with(MotorMapping... motors) {
        return RobotMapping.empty("Team").withMotors(List.of(motors));
    }

    private static MotorMapping motor(String name, Slot slot) {
        return new MotorMapping(name, slot, "", false);
    }

    @Test
    void anEmptyMappingIsValidAndHasNoDrivetrain() {
        RobotMapping empty = RobotMapping.empty("Team");

        assertEquals(List.of(), empty.problems(false));
        assertTrue(!empty.drives());
    }

    @Test
    void wheelsComeAllFourOrNoneAndEachHoldsOneMotor() {
        RobotMapping three = with(motor("a", Slot.FRONT_LEFT), motor("b", Slot.FRONT_RIGHT), motor("c", Slot.REAR_LEFT));
        assertEquals(List.of("Map all four wheels or none"), three.problems(false));

        RobotMapping twice = with(
                motor("a", Slot.FRONT_LEFT), motor("a2", Slot.FRONT_LEFT), motor("b", Slot.FRONT_RIGHT), motor("c", Slot.REAR_LEFT), motor("d", Slot.REAR_RIGHT));
        assertTrue(twice.problems(false).contains("More than one motor on front left"), twice.problems(false).toString());

        RobotMapping all = with(motor("a", Slot.FRONT_LEFT), motor("b", Slot.FRONT_RIGHT), motor("c", Slot.REAR_LEFT), motor("d", Slot.REAR_RIGHT));
        assertEquals(List.of(), all.problems(false));
        assertTrue(all.drives());
    }

    @Test
    void namesAreUniqueAtMostEightMotorsAndATurretOnlyWhereTheRobotHasOne() {
        assertTrue(with(motor("a", Slot.INTAKE), motor("a", Slot.SHOOTER)).problems(false).contains("a is mapped twice"));
        List<MotorMapping> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(motor("m" + i, Slot.SHOOTER));
        }
        assertTrue(RobotMapping.empty("Team").withMotors(nine).problems(false).contains("At most 8 motors fit on two hubs"));
        RobotMapping turret = with(motor("t", Slot.TURRET));
        assertEquals(List.of("t: this game's robot has no turret"), turret.problems(false));
        assertEquals(List.of(), turret.problems(true));
    }

    @Test
    void theNumbersAndTheHubMountingAreChecked() {
        RobotMapping.CameraMount camera = RobotMapping.empty("x").camera();
        RobotMapping heavy = new RobotMapping("Team", List.of(), 90, 96, "goBILDA-5203-435", 1, "UP", "FORWARD", camera);
        assertEquals(List.of("Mass is 1 to 40 kg"), heavy.problems(false));
        RobotMapping flat = new RobotMapping("Team", List.of(), 11, 96, "goBILDA-5203-435", 1, "UP", "DOWN", camera);
        assertEquals(List.of("Hub logo and USB face at right angles"), flat.problems(false));
        RobotMapping ratio = new RobotMapping("Team", List.of(), 11, 96, "goBILDA-5203-435", 0, "UP", "FORWARD", camera);
        assertEquals(List.of("Turret ratio is above 0"), ratio.problems(true));
    }

    private static RobotMapping.TurretEncoder encoder(int channel) {
        return new RobotMapping.TurretEncoder(channel, "", 5.5, 0, false);
    }

    private static RobotMapping withParts(RobotMapping.ServoMapping servo, List<RobotMapping.TurretEncoder> encoders, double axisX, boolean camera) {
        return RobotMapping.empty("Team").withTurretParts(new RobotMapping.TurretParts(servo, encoders, axisX, 0.0, camera));
    }

    @Test
    void aTurretServoEncodersAndAxisBelongToARobotWithATurretAndAreChecked() {
        RobotMapping.ServoMapping servo = new RobotMapping.ServoMapping("turretServo", "", false);

        assertEquals(List.of(), RobotMapping.empty("Team").problems(false), "nothing mapped on the turret is nothing to check");
        assertEquals(List.of(), withParts(servo, List.of(encoder(0), encoder(1)), 0.05, true).problems(true));
        assertEquals(
                List.of("This game's robot has no turret for a servo, encoders or an axis"),
                withParts(servo, List.of(), 0, false).problems(false));
        assertTrue(withParts(servo, List.of(encoder(0), encoder(0)), 0, false).problems(true).contains("OctoQuad port 0 has two encoders"));
        assertTrue(withParts(null, List.of(encoder(8)), 0, false).problems(true).contains("An encoder is on OctoQuad port 8, which is 0 to 7"));
        assertTrue(withParts(null, List.of(new RobotMapping.TurretEncoder(2, "", 0, 0, false)), 0, false).problems(true)
                .contains("Encoder 2: turns per turret turn is above 0"));
        assertTrue(withParts(null, List.of(), 0.9, false).problems(true).contains("The turret axis is within 0.5 m of the robot's centre"));
    }

    @Test
    void theTurretServoNeedsAFreeNameAndCannotShareTheTurretWithAMotor() {
        RobotMapping clash = with(motor("turretServo", Slot.TURRET))
                .withTurretParts(new RobotMapping.TurretParts(new RobotMapping.ServoMapping("turretServo", "", false), List.of(), 0, 0, false));

        List<String> problems = clash.problems(true);
        assertTrue(problems.contains("turretServo is mapped as a motor and as the turret servo"), problems.toString());
        assertTrue(problems.contains("The turret has a motor and a servo: map one of them"), problems.toString());
    }
}
