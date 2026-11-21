package dev.ftcsim.simulator.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.mechanisms.CaptureSpec;
import dev.ftcsim.mechanisms.Condition;
import dev.ftcsim.mechanisms.LaunchSpec;
import dev.ftcsim.mechanisms.StorageSpec;
import dev.ftcsim.mechanisms.Volume;
import dev.ftcsim.physics.DcMotor;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.WheelPosition;
import dev.ftcsim.simulator.robot.RobotMapping.CameraMount;
import dev.ftcsim.simulator.robot.RobotMapping.MotorMapping;
import dev.ftcsim.simulator.robot.RobotMapping.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RobotDesignTest {

    private static final List<Vec3> PATH = List.of(new Vec3(0.27, 0.0, -0.10), new Vec3(0.05, 0.0, 0.0), new Vec3(-0.12, 0.0, 0.26));

    /** The numbers Hornet was fitted with, spelled out, so that a change to any of them fails here and is made on purpose. */
    @Test
    void hornetsRobotHasTheNumbersHornetWasFittedWith() {
        RobotSpec spec = RobotDesign.decode().spec();

        assertEquals("Hornet 2026 (estimated)", spec.name());
        assertEquals(
                List.of(
                        new HardwareDirectory.Hub(173, "Control Hub", true, true),
                        new HardwareDirectory.Hub(2, "Expansion Hub 2", false, false)),
                spec.hubs());
        assertEquals(173, spec.parentAddress());

        MecanumConfig chassis = new MecanumConfig(
                11.3, 0.4572, 0.4572, 0.3048, 0.03, 0.08, 0.048, 0.36, 0.36, MotorCatalog.motor(MotorCatalog.GOBILDA_BARE, 13.7), 2.3e-4,
                0.0034, 9_200, 300, 0.8, 300, 0.5, 2.2, 0.12, 0.053);
        assertEquals(chassis, spec.chassis(), "every number of the chassis");

        assertEquals(
                Map.of(
                        "frontLeft", wheel(0, WheelPosition.FRONT_LEFT, -1),
                        "backLeft", wheel(1, WheelPosition.REAR_LEFT, -1),
                        "frontRight", wheel(2, WheelPosition.FRONT_RIGHT, 1),
                        "backRight", wheel(3, WheelPosition.REAR_RIGHT, 1),
                        "shooterLeft", shaft(0, MotorCatalog.GOBILDA_BARE, 2.5e-4, 8.0e-5),
                        "shooterRight", shaft(1, MotorCatalog.GOBILDA_BARE, 2.5e-4, 8.0e-5),
                        "intake", shaft(2, MotorCatalog.GOBILDA_BARE, 2.0e-4 / (13.7 * 13.7), rollerFriction()),
                        "upMotor", shaft(3, MotorCatalog.GOBILDA_BARE, 1.0e-4 / (13.7 * 13.7), rollerFriction())),
                spec.motors(),
                "ports, models, shaft inertias and friction, wheel directions");
        assertEquals(List.of("frontLeft", "backLeft", "frontRight", "backRight", "shooterLeft", "shooterRight", "intake", "upMotor"),
                List.copyOf(spec.motors().keySet()), "in the order they were mapped");

        List<StorageSpec.Drive> drives = List.of(
                new StorageSpec.Drive("intake", 0.025 / 13.7, 1, 0.0, 0.20),
                new StorageSpec.Drive("upMotor", 0.02 / 13.7, -1, 0.15, new StorageSpec("magazine", 3, PATH, List.of()).lengthMeters()));
        assertEquals(List.of(new StorageSpec("magazine", 3, PATH, drives)), spec.storages(), "path, capacity and the drives that move pieces along it");
        assertEquals(
                List.of(new CaptureSpec(
                        "front", "magazine", new Volume(new Vec3(0.30, 0.0, -0.12), new Vec3(0.06, 0.20, 0.07)),
                        List.of(Condition.atLeast("intake", 10.0 * 13.7)), 0.10, 0.15)),
                spec.captures(),
                "the intake zone, its roller condition and the pieces it takes");
        assertEquals(
                List.of(new LaunchSpec(
                        "shooter", "magazine", List.of(Condition.atMost("upMotor", -20.0 * 13.7)),
                        List.of(new LaunchSpec.Wheel("shooterLeft", 0.05, 1), new LaunchSpec.Wheel("shooterRight", 0.05, 1)),
                        0.49, new Vec3(0.5736, 0.0, 0.8192), 0.3)),
                spec.launches(),
                "efficiency, direction, flywheels and the feed condition");

        assertEquals(List.of(new RobotSpec.ColorSensor("upSensor", 173, 2, -0.12, 0.09, 0.26, -90.0)), spec.colorSensors());
        assertEquals(
                new RobotSpec.Limelight(new CameraMount(0.16, 0.0, 0.25, 0.0, 20.0, 0.0), "field", "1280x960"), spec.limelight());
        assertEquals(new RobotSpec.ImuMount("UP", "FORWARD"), spec.imu());
    }

    /** A roller's friction: 5 percent of its motor's own curve (stall torque over free speed). */
    private static double rollerFriction() {
        DcMotor bare = MotorCatalog.motor(MotorCatalog.GOBILDA_BARE);
        return 0.05 * bare.stallTorqueNm() / bare.freeSpeedRadPerSec();
    }

    private static RobotSpec.Motor wheel(int port, WheelPosition position, int polarity) {
        return new RobotSpec.Motor(173, port, null, new RobotSpec.Wheel(position, polarity), null);
    }

    private static RobotSpec.Motor shaft(int port, String model, double inertia, double friction) {
        return new RobotSpec.Motor(2, port, model, null, new RobotSpec.Shaft(inertia, friction));
    }

    private static final String BARE = MotorCatalog.GOBILDA_BARE;

    private static MotorMapping motor(String name, Slot slot, boolean mirrored) {
        return new MotorMapping(name, slot, BARE, mirrored);
    }

    private static RobotMapping mapping(List<MotorMapping> motors) {
        return RobotMapping.empty("Test").withMotors(motors);
    }

    private static RobotMapping.ServoMapping servo(String name, String model, boolean mirrored) {
        return new RobotMapping.ServoMapping(name, model, mirrored);
    }

    private static RobotMapping.MechanismServo mechanism(RobotMapping.ServoRole role, String name, String model) {
        return new RobotMapping.MechanismServo(role, servo(name, model, false));
    }

    /** The empty robot with the given turret ratio and camera. */
    private static RobotMapping mounted(RobotMapping base, double turretRatio, CameraMount camera) {
        return new RobotMapping(
                base.name(), base.motors(), base.massKg(), base.wheelDiameterMm(), base.driveMotor(), turretRatio, base.hubLogo(),
                base.hubUsb(), camera, base.turretParts(), base.driveRatio(), base.colorSensors());
    }

    @Test
    void biobuzzsRobotIsBuiltFromItsProfileAndEveryOtherFieldGetsHornets() {
        assertEquals(RobotDesign.biobuzz().spec(RobotProfiles.biobuzz()), RobotDesign.forField("biobuzz").spec());
        assertEquals(RobotDesign.decode().spec(RobotProfiles.hornet()), RobotDesign.forField("decode").spec());
        assertEquals("Hornet 2026 (estimated)", RobotDesign.forField("none").spec().name());
        assertEquals("BIOBUZZ", RobotDesign.forField("biobuzz").spec().name());
    }

    @Test
    void colourSensorsGoToTheControlHubsBus2ThenTheExpansionHubsBus0() {
        RobotSpec biobuzz = RobotDesign.biobuzz().spec();

        assertEquals(
                List.of(
                        new RobotSpec.ColorSensor("upSensor", 173, 2, 0.089, 0.065, 0.060, -90.0),
                        new RobotSpec.ColorSensor("intakeSensor", 2, 0, 0.187, 0.065, -0.112, -90.0)),
                biobuzz.colorSensors());
        assertTrue(RobotDesign.decode().spec(RobotMapping.empty("Test")).colorSensors().isEmpty());
        RobotException refused = assertThrows(
                RobotException.class,
                () -> RobotDesign.decode().spec(RobotMapping.empty("Test").withColorSensors(List.of(
                        new RobotMapping.ColorSensorMapping("a", 0, 0, 0, 0), new RobotMapping.ColorSensorMapping("b", 0, 0, 0, 0),
                        new RobotMapping.ColorSensorMapping("c", 0, 0, 0, 0)))));
        assertTrue(refused.getMessage().contains("At most 2 colour sensors"), refused.getMessage());
    }

    @Test
    void aMirroredMotorTurnsItsPartTheOtherWayRoundAndTheConditionsFollow() {
        RobotMapping mapping = mapping(List.of(
                motor("roll", Slot.INTAKE, true), motor("belt", Slot.CONVEYOR, false), motor("fly", Slot.SHOOTER, true)));

        RobotSpec spec = RobotDesign.decode().spec(mapping);

        assertEquals(List.of(Condition.atMost("roll", -10.0)), spec.captures().get(0).active());
        LaunchSpec launch = spec.launches().get(0);
        assertEquals(List.of(Condition.atLeast("belt", 20.0)), launch.active(), "an unmirrored belt feeds forward when it turns forward");
        assertEquals(-1, launch.wheels().get(0).sign());
        assertEquals(List.of(-1, 1), spec.storages().get(0).drives().stream().map(StorageSpec.Drive::sign).toList());
        assertNull(spec.chassis(), "a robot without wheels has no body");
    }

    @Test
    void aPartExistsOnlyWhenTheMappingGivesItAMotor() {
        RobotSpec none = RobotDesign.decode().spec(RobotMapping.empty("Test"));
        RobotSpec shooterOnly = RobotDesign.decode().spec(mapping(List.of(motor("fly", Slot.SHOOTER, false))));

        assertTrue(none.storages().isEmpty() && none.captures().isEmpty() && none.launches().isEmpty());
        assertTrue(shooterOnly.captures().isEmpty(), "no intake motor, no intake");
        assertEquals(1, shooterOnly.launches().size());
        assertTrue(shooterOnly.launches().get(0).active().isEmpty(), "with no conveyor there is no feed to wait for");
    }

    @Test
    void theMotorModelOfAMappingIsUsedAndOneTheCatalogueLacksIsRefusedByTheRig() {
        RobotMapping spike = mapping(List.of(new MotorMapping("fly", Slot.SHOOTER, MotorCatalog.SWYFT_SPIKE, false)));
        assertEquals(MotorCatalog.SWYFT_SPIKE, RobotDesign.decode().spec(spike).motors().get("fly").model());

        RobotMapping unknown = mapping(List.of(new MotorMapping("fly", Slot.SHOOTER, "nonsense", false)));
        assertThrows(IllegalArgumentException.class, () -> RobotDesign.decode().spec(unknown));
    }

    @Test
    void theMassAndTheWheelDiameterAndTheDriveOfTheMappingReachTheChassis() {
        RobotMapping base = RobotProfiles.hornet();
        RobotMapping heavier = new RobotMapping(
                base.name(), base.motors(), 15.0, 104.0, MotorCatalog.SWYFT_SPIKE, base.turretRatio(), base.hubLogo(), base.hubUsb(),
                base.camera(), base.turretParts(), 12.7, base.colorSensors());

        MecanumConfig chassis = RobotDesign.decode().spec(heavier).chassis();

        assertEquals(15.0, chassis.massKg());
        assertEquals(0.052, chassis.wheelRadiusM(), 1e-12);
        assertEquals(MotorCatalog.motor(MotorCatalog.SWYFT_SPIKE, 12.7), chassis.motor());
    }

    @Test
    void motorsAreGivenFreePortsExpansionHubFirstAndTooManyAreRefused() {
        RobotSpec spec = RobotDesign.decode().spec(RobotProfiles.hornet());
        assertEquals(2, spec.motors().get("shooterLeft").hub());
        assertEquals(0, spec.motors().get("shooterLeft").port());
        assertEquals(3, spec.motors().get("upMotor").port());

        List<MotorMapping> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(motor("m" + i, Slot.SHOOTER, false));
        }
        RobotException refused = assertThrows(RobotException.class, () -> RobotDesign.decode().spec(mapping(nine)));
        assertTrue(refused.getMessage().contains("At most 8 motors"), refused.getMessage());
    }

    @Test
    void theTurretBelongsToBiobuzzAndTurnsItsLauncherByItsRatioAndMirroring() {
        RobotMapping base = mapping(List.of(
                motor("fly", Slot.SHOOTER, false), motor("belt", Slot.CONVEYOR, false), motor("turret", Slot.TURRET, true)));
        RobotMapping geared = mounted(base, 3.5, base.camera());

        assertNull(RobotDesign.decode().spec(geared).turret(), "DECODE's robot has no turret");
        RobotSpec spec = RobotDesign.biobuzz().spec(geared);

        assertNotNull(spec.motors().get("turret").shaft());
        assertEquals(new LaunchSpec.Turret("turret", 3.5, -1), spec.launches().get(0).turret(), "the turret motor, mirrored, at the mapped ratio");
        assertNull(RobotDesign.biobuzz().spec(base.withMotors(base.motors().subList(0, 2))).launches().get(0).turret(), "no turret motor, a fixed launcher");
        assertTrue(RobotDesign.forField("biobuzz").turret());
        assertTrue(!RobotDesign.forField("decode").turret() && !RobotDesign.forField("none").turret());
    }

    @Test
    void aMotorsRatioDrivesItsRollerAndItsFlywheelAndTheDriveRatioTheWheels() {
        RobotSpec biobuzz = RobotDesign.biobuzz().spec();
        RobotSpec decode = RobotDesign.decode().spec();

        assertEquals(MotorCatalog.motor(MotorCatalog.SWYFT_SPIKE, 12.7), biobuzz.chassis().motor());
        assertEquals(0.056, biobuzz.chassis().wheelRadiusM(), 1e-9, "112 mm wheels");
        assertEquals(MotorCatalog.motor(MotorCatalog.GOBILDA_BARE, 13.7), decode.chassis().motor());
        assertEquals(0.048, decode.chassis().wheelRadiusM(), 1e-9);
        assertEquals(0.0381 / (43.0 / 30.0), biobuzz.launches().get(0).wheels().get(0).radiusM(), 1e-9,
                "a 3 in wheel behind a 43:30 belt, per radian of the motor");
        assertEquals(new StorageSpec.Gate(4, "stopper", Math.toRadians(60.0)), biobuzz.storages().get(0).gate(),
                "the stopper holds the pieces at the foot of the column");
        assertNull(decode.storages().get(0).gate());
        assertEquals(0.02 / 2.0, biobuzz.storages().get(0).drives().get(1).radiusM(), 1e-12, "the indexer's rollers turn at half the motor's speed (2:1)");
        assertEquals(0.025 / 2.0, biobuzz.storages().get(0).drives().get(0).radiusM(), 1e-12, "and the intake's at half too");
        assertEquals(43.0 / 30.0, biobuzz.joints().flywheelRatio(), 1e-12, "and the picture turns the flywheel by it");
        assertEquals(2.0, biobuzz.joints().conveyorRatio(), 1e-12);
        assertEquals(2.0, biobuzz.joints().intakeRatio(), 1e-12);
        assertEquals(List.of(Condition.atLeast("up", 20.0 * 2.0)), biobuzz.launches().get(0).active(), "the belt feeds when the indexer, not the motor, turns fast enough");
    }

    @Test
    void theHoodsServoStopHoldsItAt89DegreesAndTheJointFollowsIt() {
        RobotMapping base = mapping(List.of(motor("fly", Slot.SHOOTER, false), motor("belt", Slot.CONVEYOR, false)))
                .withTurretParts(new RobotMapping.TurretParts(
                        null, List.of(), 0, 0, false,
                        List.of(
                                mechanism(RobotMapping.ServoRole.HOOD, "hood", ServoCatalog.GOBILDA_SUPER_SPEED),
                                mechanism(RobotMapping.ServoRole.COLIBRI_LEFT, "arm", ServoCatalog.GOBILDA_TORQUE_5_TURN))));
        RobotSpec spec = RobotDesign.biobuzz().spec(base);

        assertEquals(ServoCatalog.GOBILDA_SUPER_SPEED, spec.servos().get("hood").model());
        assertEquals(ServoCatalog.GOBILDA_TORQUE_5_TURN, spec.servos().get("arm").model());
        LaunchSpec.Hood hood = spec.launches().get(0).hood();
        assertEquals(Math.toRadians(45.0), hood.elevationRadians(0.0), 1e-9, "the servo at 0: 45 degrees");
        assertEquals(Math.toRadians(45.0 + 100.0 / RobotDesign.HOOD_RATIO), hood.elevationRadians(Math.toRadians(100.0)), 1e-9,
                "100 degrees of servo are 19.7 degrees of hood: 64.7 degrees");
        assertEquals(Math.toRadians(89.0), hood.elevationRadians(Math.toRadians(44.0 * RobotDesign.HOOD_RATIO)), 1e-9, "the stop is 44 degrees of hood: 89");
        assertEquals(hood.elevationRadians(Math.toRadians(44.0 * RobotDesign.HOOD_RATIO)), hood.elevationRadians(Math.toRadians(300.0)), 1e-12, "no further");
        assertEquals(Math.toRadians(44.0), spec.joints().servos().get(0).limit(), 1e-12, "and the drawn hood stops there too");
    }

    @Test
    void aTurretServoTurnsTheLauncherByItsRatioAndMirroringAndTakesThePortOfAServoNotOfAMotor() {
        RobotMapping base = mapping(List.of(motor("fly", Slot.SHOOTER, false), motor("belt", Slot.CONVEYOR, false)));
        RobotMapping withServo = mounted(base, 2.75, base.camera()).withTurretParts(
                new RobotMapping.TurretParts(servo("turretServo", ServoCatalog.AXON_MINI_PLUS, true), List.of(), 0.05, -0.02, true, List.of()));

        RobotSpec spec = RobotDesign.biobuzz().spec(withServo);

        assertEquals(Map.of("turretServo", new RobotSpec.Servo(173, 0, ServoCatalog.AXON_MINI_PLUS)), spec.servos());
        assertEquals(
                new RobotSpec.Turret("turretServo", 2.75, -1, 0.05, -0.02, true, List.of()), spec.turret(),
                "the servo is the shaft, mirrored, at the mapped ratio, with its axis and the camera on it");
        assertEquals(new LaunchSpec.Turret("turretServo", 2.75, -1), spec.launches().get(0).turret());
        assertEquals(Math.toRadians(-90), spec.turret().yawRadians(Math.PI / 2 * 2.75), 1e-12, "a mirrored servo turns the turret clockwise");
        assertTrue(spec.limelight().onTurret());
        assertNull(RobotDesign.biobuzz().spec(base).turret().shaft(), "nothing turns a turret that has no motor and no servo");
        assertEquals(RobotDesign.BIOBUZZ_CAMERA, RobotDesign.biobuzz().spec().limelight().mount());
        CameraMount moved = new CameraMount(0.1, 0.0, 0.2, 0.0, 10.0, 0.0);
        assertEquals(moved, RobotDesign.biobuzz().spec(mounted(base, 1.0, moved)).limelight().mount());
    }

    @Test
    void theServoCataloguePinsTheSpeedsTheDatasheetsGiveAt5VoltsAndRefusesAnUnknownServo() {
        assertEquals(Math.toRadians(60.0) / (0.110 - 0.020 / 6.0), ServoCatalog.noLoadRadPerSec(ServoCatalog.AXON_MINI_PLUS), 1e-9);
        assertEquals(9.82, ServoCatalog.noLoadRadPerSec(ServoCatalog.AXON_MINI_PLUS), 0.01);
        assertEquals(19.76, ServoCatalog.noLoadRadPerSec(ServoCatalog.GOBILDA_SUPER_SPEED), 0.01);
        assertEquals(4.36, ServoCatalog.noLoadRadPerSec(ServoCatalog.GOBILDA_TORQUE_5_TURN), 0.01);
        assertThrows(IllegalArgumentException.class, () -> ServoCatalog.noLoadRadPerSec("nonsense"));
    }

    @Test
    void theEncodersOfTheTurretReachTheSpecAsTheyAreMapped() {
        RobotMapping.TurretEncoder first = new RobotMapping.TurretEncoder(1, "rev-tbe-v1", 44.0 / 7.0, 0.0, true);
        RobotMapping.TurretEncoder bare = new RobotMapping.TurretEncoder(0, "rev-tbe-v2", 5.5, 12.0, false);
        RobotMapping mapping = RobotMapping.empty("Test").withTurretParts(new RobotMapping.TurretParts(
                servo("turretServo", ServoCatalog.AXON_MINI_PLUS, false), List.of(bare, first), 0, 0, false, List.of()));

        assertEquals(List.of(bare, first), RobotDesign.biobuzz().spec(mapping).turret().encoders());
    }

    @Test
    void theIntakeOfEachGameTakesOnlyItsOwnPieces() {
        RobotMapping mapping = mapping(List.of(motor("roll", Slot.INTAKE, false)));

        CaptureSpec decode = RobotDesign.decode().spec(mapping).captures().get(0);
        CaptureSpec biobuzz = RobotDesign.biobuzz().spec(mapping).captures().get(0);

        assertEquals(0.10, decode.minDiameterM());
        assertEquals(0.15, decode.maxDiameterM());
        assertEquals(0.06, biobuzz.minDiameterM());
        assertEquals(0.085, biobuzz.maxDiameterM());
    }

    @Test
    void theHubMountingAndTheLimelightMountOfTheMappingAreTheOnlySensorSettings() {
        CameraMount camera = new CameraMount(0.1, -0.05, 0.3, 15.0, 10.0, 2.0);
        RobotMapping turned = new RobotMapping(
                "Test", List.of(), 11.3, 96, MotorCatalog.GOBILDA_BARE, 1.0, "LEFT", "UP", camera, RobotMapping.TurretParts.NONE, 13.7, List.of());

        RobotSpec spec = RobotDesign.decode().spec(turned);

        assertEquals(Map.of("mount.logo", "LEFT", "mount.port", "UP"), spec.imu().settings());
        Map<String, Object> limelight = spec.limelight().settings();
        assertEquals(0.1, limelight.get("mount.x"));
        assertEquals(-0.05, limelight.get("mount.y"));
        assertEquals(0.3, limelight.get("mount.z"));
        assertEquals(15.0, limelight.get("mount.yawDegrees"));
        assertEquals(10.0, limelight.get("mount.pitchDegrees"));
        assertEquals(2.0, limelight.get("mount.rollDegrees"));
    }
}

