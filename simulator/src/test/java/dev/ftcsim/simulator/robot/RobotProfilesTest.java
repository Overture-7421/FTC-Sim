package dev.ftcsim.simulator.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.Test;

/** Every number of the two profiles, spelled out, so that a change to any of them fails here and is made on purpose. */
class RobotProfilesTest {

    private static final String BARE = "goBILDA-bare-6000";

    @Test
    void hornetsProfileHasTheNumbersItWasFittedWith() {
        RobotMapping hornet = RobotProfiles.hornet();

        assertEquals("Hornet 2026 (estimated)", hornet.name());
        assertEquals(
                List.of(
                        new MotorMapping("frontLeft", Slot.FRONT_LEFT, BARE, true, 1.0),
                        new MotorMapping("backLeft", Slot.REAR_LEFT, BARE, true, 1.0),
                        new MotorMapping("frontRight", Slot.FRONT_RIGHT, BARE, false, 1.0),
                        new MotorMapping("backRight", Slot.REAR_RIGHT, BARE, false, 1.0),
                        new MotorMapping("shooterLeft", Slot.SHOOTER, BARE, false, 1.0),
                        new MotorMapping("shooterRight", Slot.SHOOTER, BARE, false, 1.0),
                        new MotorMapping("intake", Slot.INTAKE, BARE, false, 13.7),
                        new MotorMapping("upMotor", Slot.CONVEYOR, BARE, true, 13.7)),
                hornet.motors());
        assertEquals(11.3, hornet.massKg());
        assertEquals(96.0, hornet.wheelDiameterMm());
        assertEquals(BARE, hornet.driveMotor());
        assertEquals(13.7, hornet.driveRatio());
        assertEquals(1.0, hornet.turretRatio());
        assertEquals("UP", hornet.hubLogo());
        assertEquals("FORWARD", hornet.hubUsb());
        assertEquals(new CameraMount(0.16, 0.0, 0.25, 0.0, 20.0, 0.0), hornet.camera());
        assertEquals(TurretParts.NONE, hornet.turretParts());
        assertEquals(List.of(new ColorSensorMapping("upSensor", -0.12, 0.09, 0.26, -90.0)), hornet.colorSensors());
        assertEquals(List.of(), hornet.problems());
    }

    @Test
    void biobuzzsProfileHasTheNumbersOfTheTeamsTable() {
        RobotMapping biobuzz = RobotProfiles.biobuzz();

        assertEquals("BIOBUZZ", biobuzz.name());
        assertEquals(
                List.of(
                        new MotorMapping("frontLeft", Slot.FRONT_LEFT, "swyft-spike-6000", true, 1.0),
                        new MotorMapping("backLeft", Slot.REAR_LEFT, "swyft-spike-6000", true, 1.0),
                        new MotorMapping("frontRight", Slot.FRONT_RIGHT, "swyft-spike-6000", false, 1.0),
                        new MotorMapping("backRight", Slot.REAR_RIGHT, "swyft-spike-6000", false, 1.0),
                        new MotorMapping("intake", Slot.INTAKE, BARE, false, 2.0),
                        new MotorMapping("up", Slot.CONVEYOR, BARE, false, 2.0),
                        new MotorMapping("shooterLeft", Slot.SHOOTER, BARE, false, 43.0 / 30.0),
                        new MotorMapping("shooterRight", Slot.SHOOTER, BARE, false, 43.0 / 30.0)),
                biobuzz.motors());
        assertEquals(11.3, biobuzz.massKg());
        assertEquals(112.0, biobuzz.wheelDiameterMm());
        assertEquals("swyft-spike-6000", biobuzz.driveMotor());
        assertEquals(12.7, biobuzz.driveRatio());
        assertEquals(1.0185, biobuzz.turretRatio());
        assertEquals("UP", biobuzz.hubLogo());
        assertEquals("FORWARD", biobuzz.hubUsb());
        assertEquals(new CameraMount(0.217, 0.0035, 0.256, 0.0, 45.0, 0.0), biobuzz.camera());
        assertEquals(
                new TurretParts(
                        new ServoMapping("turretServo", "axon-mini-plus", false),
                        List.of(
                                new TurretEncoder(0, "rev-tbe-v2", 5.5, 0.0, false),
                                new TurretEncoder(1, "rev-tbe-v2", 44.0 / 7.0, 0.0, false)),
                        0.089, 0.0, true,
                        List.of(
                                new MechanismServo(ServoRole.HOOD, new ServoMapping("hood", "gobilda-2000-superspeed", false)),
                                new MechanismServo(ServoRole.STOPPER, new ServoMapping("stopper", "gobilda-2000-torque-5turn", false)),
                                new MechanismServo(ServoRole.COLIBRI_LEFT, new ServoMapping("colibriLeft", "gobilda-2000-torque-5turn", false)),
                                new MechanismServo(ServoRole.COLIBRI_RIGHT, new ServoMapping("colibriRight", "gobilda-2000-torque-5turn", false)))),
                biobuzz.turretParts());
        assertEquals(
                List.of(
                        new ColorSensorMapping("upSensor", 0.089, 0.065, 0.060, -90.0),
                        new ColorSensorMapping("intakeSensor", 0.187, 0.065, -0.112, -90.0)),
                biobuzz.colorSensors());
        assertEquals(List.of(), biobuzz.problems());
    }

    @Test
    void theFieldChoosesTheProfile() {
        assertEquals(RobotProfiles.biobuzz(), RobotProfiles.forField("biobuzz"));
        assertEquals(RobotProfiles.hornet(), RobotProfiles.forField("decode"));
        assertEquals(RobotProfiles.hornet(), RobotProfiles.forField("none"));
    }
}
