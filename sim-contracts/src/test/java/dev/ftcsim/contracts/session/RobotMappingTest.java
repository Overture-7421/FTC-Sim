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
}
