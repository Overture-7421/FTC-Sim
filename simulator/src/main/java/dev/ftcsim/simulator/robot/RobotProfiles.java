package dev.ftcsim.simulator.robot;

import dev.ftcsim.hardware.device.rev.ThroughBoreEncoder;
import dev.ftcsim.simulator.robot.RobotMapping.CameraMount;
import dev.ftcsim.simulator.robot.RobotMapping.ColorSensorMapping;
import dev.ftcsim.simulator.robot.RobotMapping.MechanismServo;
import dev.ftcsim.simulator.robot.RobotMapping.MotorMapping;
import dev.ftcsim.simulator.robot.RobotMapping.ServoMapping;
import dev.ftcsim.simulator.robot.RobotMapping.ServoRole;
import dev.ftcsim.simulator.robot.RobotMapping.Slot;
import dev.ftcsim.simulator.robot.RobotMapping.TurretEncoder;
import dev.ftcsim.simulator.robot.RobotMapping.TurretParts;
import java.util.List;

/**
 * The two robots of the simulator as data: Hornet's, which every field but BIOBUZZ's has, and BIOBUZZ's. They are the
 * team's own robots, so refitting one means editing the numbers here.
 *
 * <p>Hornet's are the numbers it was fitted on ({@code docs/robots/hornet-fit.md}). BIOBUZZ's come from the team's actuator
 * table and its CAD; the colour sensors' mounts are estimates from the geometry of the ball path, not measured.
 */
public final class RobotProfiles {

    private static final String YELLOW_JACKET = MotorCatalog.GOBILDA_BARE;

    private RobotProfiles() {}

    /** The profile of the robot a field has: BIOBUZZ's on its field, Hornet's on any other. */
    public static RobotMapping forField(String fieldId) {
        return RobotDesign.forField(fieldId).game() == RobotDesign.Game.BIOBUZZ ? biobuzz() : hornet();
    }

    /** Hornet 2026, the fitted DECODE robot; its 435 rpm Yellow Jackets are the 6000 rpm motor behind 13.7:1. */
    public static RobotMapping hornet() {
        return new RobotMapping(
                "Hornet 2026 (estimated)",
                List.of(
                        new MotorMapping("frontLeft", Slot.FRONT_LEFT, YELLOW_JACKET, true),
                        new MotorMapping("backLeft", Slot.REAR_LEFT, YELLOW_JACKET, true),
                        new MotorMapping("frontRight", Slot.FRONT_RIGHT, YELLOW_JACKET, false),
                        new MotorMapping("backRight", Slot.REAR_RIGHT, YELLOW_JACKET, false),
                        new MotorMapping("shooterLeft", Slot.SHOOTER, YELLOW_JACKET, false),
                        new MotorMapping("shooterRight", Slot.SHOOTER, YELLOW_JACKET, false),
                        new MotorMapping("intake", Slot.INTAKE, YELLOW_JACKET, false, 13.7),
                        new MotorMapping("upMotor", Slot.CONVEYOR, YELLOW_JACKET, true, 13.7)),
                11.3, 96, YELLOW_JACKET, 1.0, "UP", "FORWARD",
                new CameraMount(0.16, 0.0, 0.25, 0.0, 20.0, 0.0),
                TurretParts.NONE, 13.7,
                List.of(new ColorSensorMapping("upSensor", -0.12, 0.09, 0.26, -90.0)));
    }

    /**
     * BIOBUZZ's robot: SWYFT Spike drive motors at 12.7:1 on 112 mm wheels, Yellow Jackets 2:1 to the intake and the indexer and 43:30
     * to the flywheels, an Axon Mini+ turning the turret 1.0185 turns to its one, two through-bore encoders on the turret, a Super
     * Speed hood, and the stopper and colibri arms on 5-turn Torque servos. The sensors sit where the first and fourth ball of the
     * queue wait, on the left wall looking right.
     */
    public static RobotMapping biobuzz() {
        return new RobotMapping(
                "BIOBUZZ",
                List.of(
                        new MotorMapping("frontLeft", Slot.FRONT_LEFT, MotorCatalog.SWYFT_SPIKE, true),
                        new MotorMapping("backLeft", Slot.REAR_LEFT, MotorCatalog.SWYFT_SPIKE, true),
                        new MotorMapping("frontRight", Slot.FRONT_RIGHT, MotorCatalog.SWYFT_SPIKE, false),
                        new MotorMapping("backRight", Slot.REAR_RIGHT, MotorCatalog.SWYFT_SPIKE, false),
                        new MotorMapping("intake", Slot.INTAKE, YELLOW_JACKET, false, 2.0),
                        new MotorMapping("up", Slot.CONVEYOR, YELLOW_JACKET, false, 2.0),
                        new MotorMapping("shooterLeft", Slot.SHOOTER, YELLOW_JACKET, false, 43.0 / 30.0),
                        new MotorMapping("shooterRight", Slot.SHOOTER, YELLOW_JACKET, false, 43.0 / 30.0)),
                11.3, 112, MotorCatalog.SWYFT_SPIKE, 1.0185, "UP", "FORWARD",
                RobotDesign.BIOBUZZ_CAMERA,
                new TurretParts(
                        new ServoMapping("turretServo", ServoCatalog.AXON_MINI_PLUS, false),
                        List.of(
                                new TurretEncoder(0, ThroughBoreEncoder.V2, 5.5, 0, false),
                                new TurretEncoder(1, ThroughBoreEncoder.V2, 44.0 / 7.0, 0, false)),
                        0.089, 0.0, true,
                        List.of(
                                mechanism(ServoRole.HOOD, "hood", ServoCatalog.GOBILDA_SUPER_SPEED),
                                mechanism(ServoRole.STOPPER, "stopper", ServoCatalog.GOBILDA_TORQUE_5_TURN),
                                mechanism(ServoRole.COLIBRI_LEFT, "colibriLeft", ServoCatalog.GOBILDA_TORQUE_5_TURN),
                                mechanism(ServoRole.COLIBRI_RIGHT, "colibriRight", ServoCatalog.GOBILDA_TORQUE_5_TURN))),
                12.7,
                List.of(
                        new ColorSensorMapping("upSensor", 0.089, 0.065, 0.060, -90.0),
                        new ColorSensorMapping("intakeSensor", 0.187, 0.065, -0.112, -90.0)));
    }

    private static MechanismServo mechanism(ServoRole role, String name, String model) {
        return new MechanismServo(role, new ServoMapping(name, model, false));
    }
}
