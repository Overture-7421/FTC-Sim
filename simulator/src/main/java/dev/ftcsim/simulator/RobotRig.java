package dev.ftcsim.simulator;

import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.hardware.MotorPlant;
import dev.ftcsim.contracts.robot.RobotEnvironment;
import dev.ftcsim.contracts.snapshot.BodyKinds;
import dev.ftcsim.contracts.snapshot.BodyState;
import dev.ftcsim.contracts.snapshot.RobotJoint;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.contracts.world.Vec3;
import dev.ftcsim.field.EntityIds;
import dev.ftcsim.hardware.camera.CameraRack;
import dev.ftcsim.hardware.camera.CameraModels;
import dev.ftcsim.hardware.device.DeviceHost;
import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.device.TurretView;
import dev.ftcsim.hardware.lynx.HubConfig;
import dev.ftcsim.hardware.lynx.LynxBus;
import dev.ftcsim.hardware.lynx.LynxHub;
import dev.ftcsim.hardware.wiring.WiringTable;
import dev.ftcsim.mechanisms.ServoPlant;
import dev.ftcsim.mechanisms.PlantView;
import dev.ftcsim.mechanisms.ShaftPlant;
import dev.ftcsim.physics.DcMotor;
import dev.ftcsim.physics.MecanumChassis;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;
import dev.ftcsim.physics.WheelPosition;
import dev.ftcsim.simulator.robot.MotorCatalog;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotException;
import dev.ftcsim.simulator.robot.RobotMapping;
import dev.ftcsim.simulator.robot.RobotSpec;
import dev.ftcsim.simulator.robot.ServoCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * The hardware of a {@link RobotSpec}, built and connected: hubs on a simulated bus, the chassis standing in the physics
 * world, a plant on every motor port the robot wires, the sensors every robot has (the Control Hub's IMU, the Pinpoint
 * and the colour sensor on the hubs' I2C buses, and the Limelight), found by the driver class robot code asks for. What
 * robot code can reach is only what this exposes through {@link #environment()}; everything else here is for the
 * simulator's own use.
 */
public final class RobotRig {

    /** The body kind a snapshot reports for the chassis. */
    public static final int CHASSIS_KIND = BodyKinds.CHASSIS;

    private static final double SECONDS_PER_TICK = LynxHub.TICK_NANOS / 1e9;
    private static final double BATTERY_VOLTS = 12.6;

    /** What the robot's camera can look at beyond the chassis: the tags of the field and the bodies some are stuck on. */
    public record Vision(List<FieldTag> tags, IntFunction<BodyView> entities, long noiseSeed) {

        /** A robot on a bare floor: nothing to see. */
        public static final Vision NONE = new Vision(List.of(), id -> {
            throw new IllegalArgumentException("there is no body " + id);
        }, 0);
    }

    /** A drivetrain motor: which wheel it turns, and which way round it is mounted. */
    private record WheelRef(WheelPosition position, int polarity) {}

    private final RobotSpec spec;
    private final LynxBus bus;
    private final Map<Integer, LynxHub> hubs;
    private final WiringTable wiring;
    private final CameraRack cameras;
    private volatile MecanumChassis chassis;
    private final BodyView chassisView;
    private final AtomicReference<BodyView> bodySource;
    private final MecanumConfig chassisConfig;
    private final int chassisEntityId;
    private final DeviceHost devices;
    private final Map<String, MotorPlant> plants;
    private final Map<String, WheelPlant> wheelPlants;
    private final Map<String, ShaftPlant> shafts;
    private final Map<String, ServoPlant> servoPlants;
    private final List<RobotSpec.ServoJoint> colibriServos;
    private DoubleConsumer colibriPlate;
    private final Map<String, WheelRef> wheels;

    private RobotRig(
            RobotSpec spec,
            LynxBus bus,
            Map<Integer, LynxHub> hubs,
            WiringTable wiring,
            CameraRack cameras,
            MecanumChassis chassis,
            BodyView chassisView,
            AtomicReference<BodyView> bodySource,
            MecanumConfig chassisConfig,
            int chassisEntityId,
            DeviceHost devices,
            Map<String, MotorPlant> plants,
            Map<String, WheelPlant> wheelPlants,
            Map<String, ShaftPlant> shafts,
            Map<String, ServoPlant> servoPlants,
            Map<String, WheelRef> wheels) {
        this.spec = spec;
        colibriServos = spec.joints().servos().stream()
                .filter(s -> s.joint() == RobotJoint.COLIBRI_LEFT || s.joint() == RobotJoint.COLIBRI_RIGHT).toList();
        this.bus = bus;
        this.hubs = hubs;
        this.wiring = wiring;
        this.cameras = cameras;
        this.chassis = chassis;
        this.chassisView = chassisView;
        this.bodySource = bodySource;
        this.chassisConfig = chassisConfig;
        this.chassisEntityId = chassisEntityId;
        this.devices = devices;
        this.plants = plants;
        this.wheelPlants = wheelPlants;
        this.shafts = shafts;
        this.servoPlants = servoPlants;
        this.wheels = wheels;
    }

    /**
     * Builds the rig of a robot.
     *
     * @param chassisEntityId the entity id the chassis gets in the world
     * @param x               field position of the robot's centre, metres
     * @param y               field position of the robot's centre, metres
     * @param heading         counter-clockwise from the field's +X, radians
     * @param clock           the simulated time, which the hubs' bus reads
     * @param rays            what the devices' rays meet; only asked once the world is stepping
     * @param vision          what the camera may look at
     * @throws RobotException if a device refuses the settings the design gives it
     */
    public static RobotRig build(
            RobotSpec spec, PhysicsWorld world, int chassisEntityId, double x, double y, double heading, LongSupplier clock,
            RayView rays, Vision vision) {
        int parent = spec.parentAddress();
        WiringTable.Builder wiring = WiringTable.builder();
        for (HardwareDirectory.Hub hub : spec.hubs()) {
            wiring.hub(hub.address(), hub.name(), hub.parent(), hub.embedded());
        }
        for (RobotSpec.ColorSensor sensor : spec.colorSensors()) {
            wiring.colorSensor(sensor.name(), sensor.hubAddress(), sensor.bus());
        }
        if (spec.turret() != null && !spec.turret().encoders().isEmpty()) {
            wiring.sensor(HardwareDirectory.Sensor.OCTOQUAD);
        }

        MecanumConfig chassisConfig = spec.chassis();
        MecanumChassis chassis = chassisConfig == null
                ? null
                : world.addMecanumChassis(chassisEntityId, CHASSIS_KIND, chassisConfig, new Vector3(x, y, 0), heading);

        Map<String, MotorPlant> plants = new LinkedHashMap<>();
        Map<String, WheelPlant> wheelPlants = new LinkedHashMap<>();
        Map<String, ShaftPlant> shafts = new LinkedHashMap<>();
        Map<String, WheelRef> wheels = new LinkedHashMap<>();
        for (Map.Entry<String, RobotSpec.Motor> entry : spec.motors().entrySet()) {
            RobotSpec.Motor motor = entry.getValue();
            DcMotor model;
            MotorPlant plant;
            if (motor.wheel() != null) {
                model = chassisConfig.motor();
                WheelPlant wheel = new WheelPlant(motor.wheel().position(), motor.wheel().polarity(), chassis);
                wheelPlants.put(entry.getKey(), wheel);
                plant = wheel;
                wheels.put(entry.getKey(), new WheelRef(motor.wheel().position(), motor.wheel().polarity()));
            } else {
                model = MotorCatalog.motor(motor.model());
                ShaftPlant shaft = new ShaftPlant(model, motor.shaft().loadInertiaKgM2(), motor.shaft().frictionNmsPerRad());
                shafts.put(entry.getKey(), shaft);
                plant = shaft;
            }
            wiring.motor(entry.getKey(), motor.hub(), motor.port(), model.encoderTicksPerRevolution(), rpm(model));
            plants.put(entry.getKey(), plant);
        }

        Map<String, ServoPlant> servoPlants = new LinkedHashMap<>();
        for (Map.Entry<String, RobotSpec.Servo> entry : spec.servos().entrySet()) {
            RobotSpec.Servo servo = entry.getValue();
            servoPlants.put(entry.getKey(), servo.positional() ? ServoCatalog.positionPlant(servo.model(), servo.mirrored()) : ServoCatalog.plant(servo.model()));
            wiring.servo(entry.getKey(), servo.hub(), servo.port());
        }

        LynxBus bus = new LynxBus(clock);
        Map<Integer, LynxHub> hubs = new LinkedHashMap<>();
        for (HardwareDirectory.Hub hub : spec.hubs()) {
            HubConfig config = hub.embedded()
                    ? HubConfig.controlHub()
                    : HubConfig.expansionHub(hub.address(), hub.parent(), hub.name());
            // The SDK's keep-alive timers run on real time; the simulator's hubs answer to simulated time only.
            LynxHub lynx = new LynxHub(
                    config.withKeepAliveSupervision(false).withAttentionSignalling(false), () -> BATTERY_VOLTS, clock.getAsLong());
            bus.attach(lynx);
            hubs.put(hub.address(), lynx);
        }
        spec.motors().forEach((name, motor) -> hubs.get(motor.hub()).motorPort(motor.port()).connect(plants.get(name)));

        AtomicReference<BodyView> bodySource = new AtomicReference<>(
                chassis == null ? () -> BodyPose.atOrigin(clock.getAsLong()) : () -> world.bodyPose(chassisEntityId));
        BodyView view = () -> bodySource.get().sample();
        DeviceHost devices = new DeviceHost(view, rays, clock);
        devices.provideFieldTags(vision.tags());
        devices.provideEntities(vision.entities());
        devices.provideTurret(turretView(spec, shafts, servoPlants));
        CameraRack cameras = sensors(spec, parent, hubs, devices, vision.noiseSeed());
        return new RobotRig(
                spec, bus, Collections.unmodifiableMap(hubs), wiring.build(), cameras, chassis, view, bodySource, chassisConfig,
                chassisEntityId, devices, Collections.unmodifiableMap(plants), Collections.unmodifiableMap(wheelPlants),
                Collections.unmodifiableMap(shafts), Collections.unmodifiableMap(servoPlants), Collections.unmodifiableMap(wheels));
    }

    /** The turret as devices see it: how far the shaft that turns it has turned, and the axis it turns about. */
    private static TurretView turretView(RobotSpec spec, Map<String, ShaftPlant> shafts, Map<String, ServoPlant> servoPlants) {
        RobotSpec.Turret turret = spec.turret();
        if (turret == null) {
            return TurretView.NONE;
        }
        return new TurretView() {
            @Override
            public double yawRadians() {
                ShaftPlant shaft = turret.shaft() == null ? null : shafts.get(turret.shaft());
                if (shaft != null) {
                    return turret.yawRadians(shaft.angleRadians());
                }
                ServoPlant servo = turret.shaft() == null ? null : servoPlants.get(turret.shaft());
                return servo == null ? 0.0 : turret.yawRadians(servo.angleRadians());
            }

            @Override
            public double yawRateRadPerSec() {
                ShaftPlant shaft = turret.shaft() == null ? null : shafts.get(turret.shaft());
                if (shaft != null) {
                    return turret.yawRadians(shaft.speedRadPerSec());
                }
                ServoPlant servo = turret.shaft() == null ? null : servoPlants.get(turret.shaft());
                return servo == null ? 0.0 : turret.yawRadians(servo.speedRadPerSec());
            }

            @Override
            public double axisX() {
                return turret.axisX();
            }

            @Override
            public double axisY() {
                return turret.axisY();
            }
        };
    }

    /**
     * Attaches the robot's fixed sensors: the IMU inside every Control Hub on its bus 0, the Pinpoint on its bus of
     * the parent hub, every colour sensor on the hub and bus it is declared on, the OctoQuad with the turret's encoders if the design has
     * them, and plugs in the Limelight.
     */
    private static CameraRack sensors(RobotSpec spec, int parent, Map<Integer, LynxHub> hubs, DeviceHost devices, long noiseSeed) {
        DeviceModels models = DeviceModels.standard();
        try {
            for (HardwareDirectory.Hub hub : spec.hubs()) {
                if (hub.embedded()) {
                    hubs.get(hub.address()).attachInternal(
                            HardwareDirectory.EMBEDDED_IMU_BUS,
                            models.create(DeviceModels.BNO055_IMU, hub.name() + " IMU", spec.imu().settings(), devices));
                }
            }
            LynxHub parentHub = hubs.get(parent);
            parentHub.attach(
                    HardwareDirectory.Sensor.PINPOINT.bus(), models.create(DeviceModels.PINPOINT, "Pinpoint", Map.of(), devices));
            for (RobotSpec.ColorSensor sensor : spec.colorSensors()) {
                LynxHub hub = hubs.get(sensor.hubAddress());
                if (hub == null) {
                    throw new IllegalArgumentException("colour sensor " + sensor.name() + " is on hub " + sensor.hubAddress() + ", which the robot does not declare");
                }
                hub.attach(sensor.bus(), models.create(DeviceModels.COLOR_SENSOR, sensor.name(), sensor.settings(), devices));
            }
            if (spec.turret() != null && !spec.turret().encoders().isEmpty()) {
                parentHub.attach(
                        HardwareDirectory.Sensor.OCTOQUAD.bus(),
                        models.create(DeviceModels.OCTOQUAD, "OctoQuad", octoQuadSettings(spec.turret()), devices));
            }
            return new CameraRack().plug(
                    CameraModels.standard().create("limelight-3a", "Limelight", spec.limelight().settings(), devices, noiseSeed));
        } catch (IllegalArgumentException refused) {
            throw new RobotException("a sensor of the robot refused its settings: " + refused.getMessage(), refused);
        }
    }

    /** The OctoQuad's settings: the encoders of the turret on the ports the mapping gave them. */
    private static Map<String, Object> octoQuadSettings(RobotSpec.Turret turret) {
        Map<String, Object> settings = new LinkedHashMap<>();
        for (RobotMapping.TurretEncoder encoder : turret.encoders()) {
            String prefix = "encoder" + encoder.channel() + ".";
            settings.put(prefix + "model", encoder.model());
            settings.put(prefix + "turns", encoder.turnsPerTurretTurn());
            settings.put(prefix + "zeroOffsetDegrees", encoder.zeroOffsetDegrees());
            settings.put(prefix + "mirrored", encoder.mirrored() ? 1 : 0);
        }
        return settings;
    }

    /** A drivetrain motor as the hub's port sees it, which stays the same when the chassis is put into another world. */
    private static final class WheelPlant implements MotorPlant {
        private final WheelPosition position;
        private final int polarity;
        private volatile MotorPlant current;

        WheelPlant(WheelPosition position, int polarity, MecanumChassis chassis) {
            this.position = position;
            this.polarity = polarity;
            this.current = chassis.wheelPlant(position, polarity);
        }

        void rebind(MecanumChassis chassis) {
            current = chassis.wheelPlant(position, polarity);
        }

        @Override
        public void applyVoltage(double volts) {
            current.applyVoltage(volts);
        }

        @Override
        public void floatTerminals() {
            current.floatTerminals();
        }

        @Override
        public long encoderCounts() {
            return current.encoderCounts();
        }

        @Override
        public double encoderCountsPerSecond() {
            return current.encoderCountsPerSecond();
        }

        @Override
        public double currentAmps() {
            return current.currentAmps();
        }
    }

    private static double rpm(DcMotor motor) {
        return motor.freeSpeedRadPerSec() * 60.0 / (2.0 * Math.PI);
    }

    public RobotSpec spec() {
        return spec;
    }

    /** What robot code is connected to: the hubs' bus, where each named device is plugged in, and the camera. */
    public RobotEnvironment environment() {
        return new RobotEnvironment(bus, wiring, cameras);
    }

    public LynxBus bus() {
        return bus;
    }

    public LynxHub hub(int address) {
        LynxHub hub = hubs.get(address);
        if (hub == null) {
            throw new IllegalArgumentException("there is no hub " + address + "; the hubs are " + hubs.keySet());
        }
        return hub;
    }

    public WiringTable wiring() {
        return wiring;
    }

    /** The robot's camera and what robot code asked of it that it could not answer. */
    public CameraRack cameras() {
        return cameras;
    }

    /** The chassis, or null if the robot does not drive. */
    public MecanumChassis chassis() {
        return chassis;
    }

    /**
     * Puts the chassis into a new physics world, standing at its start, and has everything that follows the world
     * follow that one: the wheels' motors, which stay connected to the same ports, and the devices that measure the
     * chassis. The hubs, the devices and the motors that turn shafts are kept as they are, so the robot program,
     * which holds them, goes on holding the same robot. Without a chassis there is nothing to move.
     */
    public void rebindWorld(PhysicsWorld world, double x, double y, double heading) {
        if (chassisConfig == null) {
            return;
        }
        MecanumChassis fresh = world.addMecanumChassis(chassisEntityId, CHASSIS_KIND, chassisConfig, new Vector3(x, y, 0), heading);
        wheelPlants.values().forEach(wheel -> wheel.rebind(fresh));
        bodySource.set(() -> world.bodyPose(chassisEntityId));
        chassis = fresh;
        colibriPlate = null;
    }

    /** The true state of the chassis for the devices that measure it. */
    public BodyView chassisView() {
        return chassisView;
    }

    /** Lets every device that follows the world look at it after the physics step that ended at {@code stepNanos}. */
    public void stepDevices(long stepNanos) {
        devices.step(stepNanos);
    }

    /** The plant on the motor port the mapping puts the motor on, by the name robot code uses. */
    public MotorPlant plant(String motorName) {
        MotorPlant plant = plants.get(motorName);
        if (plant == null) {
            throw new IllegalArgumentException("the robot has no motor '" + motorName + "'; it has " + plants.keySet());
        }
        return plant;
    }

    /**
     * One firmware tick: every shaft integrates its motion over the tick with the volts its port left it with, and
     * then the hubs run up to {@code nanos} and decide the volts for the next tick. Wheels are not stepped here;
     * they belong to the physics step.
     */
    public void tick(long nanos) {
        for (ShaftPlant shaft : shafts.values()) {
            shaft.step(SECONDS_PER_TICK);
        }
        for (Map.Entry<String, ServoPlant> entry : servoPlants.entrySet()) {
            RobotSpec.Servo servo = spec.servos().get(entry.getKey());
            LynxHub hub = hubs.get(servo.hub());
            entry.getValue().step(SECONDS_PER_TICK, hub.servoEnabled(servo.port()), hub.servoPulseWidthMicros(servo.port()));
        }
        bus.advanceTo(nanos);
    }

    /**
     * How far the turret has turned since the session began, counter-clockwise from facing forward, in radians; 0 for a
     * robot without a turret, or whose turret nothing turns.
     */
    public double turretYawRadians() {
        RobotSpec.Turret turret = spec.turret();
        if (turret == null || turret.shaft() == null) {
            return 0.0;
        }
        return turret.yawRadians(plantView().shaftAngleRad(turret.shaft()));
    }

    /**
     * The turret as a viewer draws it, or null for a robot without one: a body at the turret's axis, turned by the
     * turret's yaw on top of the chassis's pose.
     */
    public BodyState turretBody(BodyPose chassis) {
        RobotSpec.Turret turret = spec.turret();
        if (turret == null) {
            return null;
        }
        Vec3 axis = chassis.pointToField(new Vec3(turret.axisX(), turret.axisY(), 0.0));
        double half = turretYawRadians() / 2.0;
        double tw = StrictMath.cos(half);
        double tz = StrictMath.sin(half);
        return new BodyState(
                EntityIds.TURRET, BodyKinds.TURRET, (float) axis.x(), (float) axis.y(), (float) axis.z(),
                (float) (chassis.qx() * tw + chassis.qy() * tz), (float) (chassis.qy() * tw - chassis.qx() * tz),
                (float) (chassis.qz() * tw + chassis.qw() * tz), (float) (chassis.qw() * tw - chassis.qz() * tz));
    }

    /** Both servos drive the same rigid linkage. Equally strong servos share the travel if their commands disagree. */
    private double colibriAngle() {
        double sum = 0;
        for (RobotSpec.ServoJoint servo : colibriServos) {
            sum += servoJointAngle(servo);
        }
        return sum / colibriServos.size();
    }

    void moveColibriPlate(PhysicsWorld world) {
        if (chassis == null || colibriServos.isEmpty()) {
            return;
        }
        double angle = colibriAngle();
        if (colibriPlate == null) {
            // The print spans the two CAD noses. Its depth and thickness are estimates, in metres.
            colibriPlate = world.addHingedPlate(chassisEntityId, new Vector3(0.015, 0.212, 0.004),
                    new Vector3(0.0225, 0, -0.1034), new Vector3(0.3311, 0, -0.0700), angle);
        } else {
            colibriPlate.accept(angle);
        }
    }

    private double servoJointAngle(RobotSpec.ServoJoint joint) {
        ServoPlant servo = servoPlants.get(joint.servo());
        return joint.rest() + (servo == null ? 0.0 : Math.min(joint.limit(), servo.angleRadians() / joint.ratio()));
    }

    /** The bodies a window draws the robot's picture with: its turret, and the joints of its mechanisms. */
    public List<BodyState> viewBodies(BodyPose chassisPose) {
        List<BodyState> bodies = new ArrayList<>();
        BodyState turret = turretBody(chassisPose);
        if (turret != null) {
            bodies.add(turret);
        }
        bodies.addAll(jointBodies());
        return bodies;
    }

    /**
     * The values of the picture's joints as bodies for the snapshot, one per {@link RobotJoint} with the value in radians
     * as its X, or none for a robot that has no turret and so no picture. Angles that turn without end are kept within
     * 64 turns so that a long session does not lose the float's precision.
     */
    public List<BodyState> jointBodies() {
        RobotSpec.Turret turretSpec = spec.turret();
        if (turretSpec == null) {
            return List.of();
        }
        double[] values = new double[RobotJoint.values().length];
        MecanumChassis current = chassis;
        if (current != null) {
            values[RobotJoint.WHEEL_FRONT_LEFT.ordinal()] = current.wheelAngleRadians(WheelPosition.FRONT_LEFT);
            values[RobotJoint.WHEEL_FRONT_RIGHT.ordinal()] = current.wheelAngleRadians(WheelPosition.FRONT_RIGHT);
            values[RobotJoint.WHEEL_REAR_LEFT.ordinal()] = current.wheelAngleRadians(WheelPosition.REAR_LEFT);
            values[RobotJoint.WHEEL_REAR_RIGHT.ordinal()] = current.wheelAngleRadians(WheelPosition.REAR_RIGHT);
        }
        RobotSpec.Joints joints = spec.joints();
        values[RobotJoint.INTAKE.ordinal()] = shaftTurns(joints.intake(), joints.intakeSign(), joints.intakeRatio());
        values[RobotJoint.CONVEYOR.ordinal()] = shaftTurns(joints.conveyor(), joints.conveyorSign(), joints.conveyorRatio());
        values[RobotJoint.FLYWHEEL.ordinal()] = shaftTurns(joints.flywheel(), joints.flywheelSign(), joints.flywheelRatio());
        values[RobotJoint.HOOD.ordinal()] = RobotDesign.HOOD_REST_RADIANS;
        for (RobotSpec.ServoJoint servoJoint : joints.servos()) {
            values[servoJoint.joint().ordinal()] = servoJointAngle(servoJoint);
        }
        if (!colibriServos.isEmpty()) {
            double angle = colibriAngle();
            values[RobotJoint.COLIBRI_LEFT.ordinal()] = angle;
            values[RobotJoint.COLIBRI_RIGHT.ordinal()] = angle;
        }
        double yaw = turretYawRadians();
        List<RobotMapping.TurretEncoder> encoders = turretSpec.encoders();
        if (!encoders.isEmpty()) {
            values[RobotJoint.ENCODER_A.ordinal()] = -yaw * encoders.getFirst().turnsPerTurretTurn();
        }
        if (encoders.size() > 1) {
            values[RobotJoint.ENCODER_B.ordinal()] = -yaw * encoders.get(1).turnsPerTurretTurn();
        }
        double wrap = 2.0 * Math.PI * 64.0;
        List<BodyState> bodies = new ArrayList<>();
        for (RobotJoint joint : RobotJoint.values()) {
            bodies.add(new BodyState(joint.kind(), joint.kind(), (float) (values[joint.ordinal()] % wrap), 0f, 0f, 0f, 0f, 0f, 1f));
        }
        return bodies;
    }

    private double shaftTurns(String motor, int sign, double ratio) {
        ShaftPlant shaft = motor == null ? null : shafts.get(motor);
        return shaft == null ? 0.0 : sign * shaft.angleRadians() / ratio;
    }

    /** The motors and servos as mechanisms follow them: how fast and how far each shaft turns, and what a launch costs a flywheel. */
    public PlantView plantView() {
        return new PlantView() {
            @Override
            public boolean hasShaft(String motor) {
                return shafts.containsKey(motor) || wheels.containsKey(motor) || servoPlants.containsKey(motor);
            }

            @Override
            public double shaftSpeedRadPerSec(String motor) {
                ShaftPlant shaft = shafts.get(motor);
                if (shaft != null) {
                    return shaft.speedRadPerSec();
                }
                ServoPlant servo = servoPlants.get(motor);
                if (servo != null) {
                    return servo.speedRadPerSec();
                }
                WheelRef wheel = wheels.get(motor);
                if (wheel == null) {
                    throw new IllegalArgumentException("the robot has no motor '" + motor + "'");
                }
                return wheel.polarity() * chassis.wheelSpeedRadPerSec(wheel.position());
            }

            @Override
            public double shaftAngleRad(String motor) {
                ShaftPlant shaft = shafts.get(motor);
                if (shaft != null) {
                    return shaft.angleRadians();
                }
                ServoPlant servo = servoPlants.get(motor);
                if (servo == null) {
                    throw new IllegalArgumentException("'" + motor + "' is not a shaft whose angle mechanisms can follow");
                }
                return servo.angleRadians();
            }

            @Override
            public void extractEnergy(String motor, double joules) {
                ShaftPlant shaft = shafts.get(motor);
                if (shaft == null) {
                    throw new IllegalArgumentException("'" + motor + "' is not a shaft that can pay for a launch");
                }
                shaft.extractEnergy(joules);
            }
        };
    }
}
