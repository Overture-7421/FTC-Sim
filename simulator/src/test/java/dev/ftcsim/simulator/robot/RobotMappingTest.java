package dev.ftcsim.simulator.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.simulator.robot.RobotMapping.ColorSensorMapping;
import dev.ftcsim.simulator.robot.RobotMapping.MotorMapping;
import dev.ftcsim.simulator.robot.RobotMapping.Slot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RobotMappingTest {

    private static RobotMapping with(MotorMapping... motors) {
        return RobotMapping.empty("Team").withMotors(List.of(motors));
    }

    private static MotorMapping motor(String name, Slot slot) {
        return new MotorMapping(name, slot, MotorCatalog.GOBILDA_BARE, false);
    }

    private static RobotMapping.ServoMapping servo(String name) {
        return new RobotMapping.ServoMapping(name, ServoCatalog.AXON_MINI_PLUS, false);
    }

    @Test
    void anEmptyMappingIsValidAndHasNoDrivetrain() {
        RobotMapping empty = RobotMapping.empty("Team");

        assertEquals(List.of(), empty.problems());
        assertTrue(!empty.drives());
    }

    @Test
    void wheelsComeAllFourOrNoneAndEachHoldsOneMotor() {
        RobotMapping three = with(motor("a", Slot.FRONT_LEFT), motor("b", Slot.FRONT_RIGHT), motor("c", Slot.REAR_LEFT));
        assertEquals(List.of("Four wheels or none"), three.problems());

        RobotMapping twice = with(
                motor("a", Slot.FRONT_LEFT), motor("a2", Slot.FRONT_LEFT), motor("b", Slot.FRONT_RIGHT), motor("c", Slot.REAR_LEFT), motor("d", Slot.REAR_RIGHT));
        assertTrue(twice.problems().contains("More than one motor on front left"), twice.problems().toString());

        RobotMapping all = with(motor("a", Slot.FRONT_LEFT), motor("b", Slot.FRONT_RIGHT), motor("c", Slot.REAR_LEFT), motor("d", Slot.REAR_RIGHT));
        assertEquals(List.of(), all.problems());
        assertTrue(all.drives());
    }

    @Test
    void motorNamesAreUniqueAndAtMostEightMotorsFitOnTwoHubs() {
        assertTrue(with(motor("a", Slot.INTAKE), motor("a", Slot.SHOOTER)).problems().contains("a is listed twice"));
        List<MotorMapping> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(motor("m" + i, Slot.SHOOTER));
        }
        assertTrue(RobotMapping.empty("Team").withMotors(nine).problems().contains("At most 8 motors fit on two hubs"));
    }

    @Test
    void theTurretHasOneDriverAndEveryServoHasItsOwnNameAndRole() {
        RobotMapping.MechanismServo hood = new RobotMapping.MechanismServo(RobotMapping.ServoRole.HOOD, servo("hood"));
        RobotMapping.TurretParts hoodOnly = new RobotMapping.TurretParts(null, List.of(), 0, 0, false, List.of(hood));
        assertEquals(List.of(), RobotMapping.empty("Team").withTurretParts(hoodOnly).problems());
        assertEquals(servo("hood"), hoodOnly.servoFor(RobotMapping.ServoRole.HOOD));
        assertEquals(null, hoodOnly.servoFor(RobotMapping.ServoRole.STOPPER));

        RobotMapping both = with(motor("t", Slot.TURRET))
                .withTurretParts(new RobotMapping.TurretParts(servo("turretServo"), List.of(), 0, 0, false, List.of()));
        assertEquals(List.of("The turret has a motor and a servo: one of them"), both.problems());

        RobotMapping.TurretParts clashing = new RobotMapping.TurretParts(
                servo("gate"), List.of(), 0, 0, false,
                List.of(
                        hood, new RobotMapping.MechanismServo(RobotMapping.ServoRole.HOOD, servo("other")),
                        new RobotMapping.MechanismServo(RobotMapping.ServoRole.STOPPER, servo("gate"))));
        List<String> problems = with(motor("hood", Slot.SHOOTER)).withTurretParts(clashing).problems();
        assertTrue(problems.contains("Two servos are the hood"), problems.toString());
        assertTrue(problems.contains("gate is the servo of two parts"), problems.toString());
        assertTrue(problems.contains("hood is a motor and a servo"), problems.toString());
    }

    @Test
    void atMostTwoColourSensorsHaveABusAndTheirNamesAreUnique() {
        ColorSensorMapping up = new ColorSensorMapping("upSensor", 0, 0, 0, 0);
        ColorSensorMapping down = new ColorSensorMapping("downSensor", 0, 0, 0, 0);

        assertEquals(List.of(), RobotMapping.empty("Team").withColorSensors(List.of(up, down)).problems());
        assertEquals(List.of("upSensor is listed twice"), RobotMapping.empty("Team").withColorSensors(List.of(up, up)).problems());
        assertEquals(
                List.of("At most 2 colour sensors have an I2C bus"),
                RobotMapping.empty("Team").withColorSensors(List.of(up, down, new ColorSensorMapping("third", 0, 0, 0, 0))).problems());
    }
}
