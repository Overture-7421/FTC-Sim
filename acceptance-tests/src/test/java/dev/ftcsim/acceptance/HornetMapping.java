package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.util.List;

/**
 * Hornet 2026 as a student maps it: the names its code asks the hardware map for, on the parts of the fixed DECODE robot.
 * With the usual numbers, which is also what the simulator's own test of the fixed robot spells out
 * ({@code RobotDesignTest}, which keeps its own copy of this mapping).
 */
final class HornetMapping {

    /** The name of the robot, as the fitted profile spells it. */
    static final String NAME = "Hornet 2026 (estimated)";

    private HornetMapping() {}

    /** The fixed DECODE robot, which every field but BIOBUZZ's has, built from Hornet's mapping. */
    static RobotSpec spec() {
        return RobotDesign.decode().spec(mapping());
    }

    static RobotMapping mapping() {
        return RobotMapping.empty(NAME).withMotors(List.of(
                new MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
                new MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
                new MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
                new MotorMapping("backRight", Slot.REAR_RIGHT, "", false),
                new MotorMapping("shooterLeft", Slot.SHOOTER, "", false),
                new MotorMapping("shooterRight", Slot.SHOOTER, "", false),
                // Hornet's intake and belt are 435 rpm Yellow Jackets: the 6000 rpm motor behind 13.7:1, as are its wheels (the default).
                new MotorMapping("intake", Slot.INTAKE, "", false, 13.7),
                new MotorMapping("upMotor", Slot.CONVEYOR, "", true, 13.7)));
    }
}
