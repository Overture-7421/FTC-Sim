package dev.ftcsim.simulator.robot;

import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import java.util.List;

/**
 * Hornet 2026 as a student maps it: the names its code asks the hardware map for, on the parts of the fixed DECODE robot,
 * with the usual numbers. The acceptance tests keep a copy of it, because they cannot see this module's tests.
 */
public final class HornetMapping {

    private HornetMapping() {}

    public static RobotMapping mapping() {
        return RobotMapping.empty("Hornet 2026 (estimated)").withMotors(List.of(
                new MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
                new MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
                new MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
                new MotorMapping("backRight", Slot.REAR_RIGHT, "", false),
                new MotorMapping("shooterLeft", Slot.SHOOTER, "", false),
                new MotorMapping("shooterRight", Slot.SHOOTER, "", false),
                new MotorMapping("intake", Slot.INTAKE, "", false),
                new MotorMapping("upMotor", Slot.CONVEYOR, "", true)));
    }
}
