package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The BIOBUZZ robot as the programs of the BIOBUZZ stub and the alliance TeleOps name it, and a reader for their telemetry. */
final class BiobuzzTestRobot {
    private static final Pattern NUMBER = Pattern.compile("(\\w+)=(-?\\d+(?:\\.\\d+)?)");

    private BiobuzzTestRobot() {}

    /**
     * Four wheels mounted as a team mounts a mecanum robot (its left side reversed in code), a continuous-rotation servo that turns
     * the turret 1.375 turns to its one, the two absolute encoders on the OctoQuad's ports 0 and 1, the Limelight on the turret and the
     * hood, stopper and colibri servos.
     */
    static RobotSpec spec() {
        RobotMapping wheels = RobotMapping.empty("BIOBUZZ stub").withMotors(List.of(
                new MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
                new MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
                new MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
                new MotorMapping("backRight", Slot.REAR_RIGHT, "", false),
                // The team's table: 6000 rpm Yellow Jacket motors, 2:1 to the intake and indexer, a 43:30 belt to the flywheels.
                new MotorMapping("intake", Slot.INTAKE, "goBILDA-bare-6000", false, 2.0),
                new MotorMapping("up", Slot.CONVEYOR, "goBILDA-bare-6000", false, 2.0),
                new MotorMapping("shooterLeft", Slot.SHOOTER, "goBILDA-bare-6000", false, 43.0 / 30.0),
                new MotorMapping("shooterRight", Slot.SHOOTER, "goBILDA-bare-6000", false, 43.0 / 30.0)))
                .withDrive("swyft-spike-6000", 12.7);
        RobotMapping.TurretParts turret = new RobotMapping.TurretParts(
                new RobotMapping.ServoMapping("turretServo", "", false),
                List.of(new RobotMapping.TurretEncoder(0, "", 5.5, 0, false), new RobotMapping.TurretEncoder(1, "", 44.0 / 7.0, 0, false)),
                0.089, 0, true,
                List.of(
                        servo(RobotMapping.ServoRole.HOOD, "hood"), servo(RobotMapping.ServoRole.STOPPER, "stopper"),
                        servo(RobotMapping.ServoRole.COLIBRI_LEFT, "colibriLeft"), servo(RobotMapping.ServoRole.COLIBRI_RIGHT, "colibriRight")));
        return RobotDesign.biobuzz().spec(new RobotMapping(
                wheels.name(), wheels.motors(), wheels.massKg(), wheels.wheelDiameterMm(), wheels.driveMotor(), 1.375,
                wheels.hubLogo(), wheels.hubUsb(), wheels.camera(), turret, wheels.driveRatio()));
    }

    private static RobotMapping.MechanismServo servo(RobotMapping.ServoRole role, String name) {
        return new RobotMapping.MechanismServo(role, new RobotMapping.ServoMapping(name, "", false));
    }

    /** The number after {@code key=} in a telemetry line. */
    static double value(String line, String key) {
        Matcher matcher = NUMBER.matcher(line);
        while (matcher.find()) {
            if (matcher.group(1).equals(key)) {
                return Double.parseDouble(matcher.group(2));
            }
        }
        throw new AssertionError("no " + key + " in " + line);
    }
}
