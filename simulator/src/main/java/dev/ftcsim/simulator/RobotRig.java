package dev.ftcsim.simulator;

import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.hardware.MotorPlant;
import dev.ftcsim.contracts.robot.RobotEnvironment;
import dev.ftcsim.contracts.snapshot.BodyKinds;
import dev.ftcsim.contracts.world.BodyPose;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.hardware.device.DeviceHost;
import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.device.bosch.Bno055Model;
import dev.ftcsim.hardware.i2c.I2cPeripheral;
import dev.ftcsim.hardware.lynx.HubConfig;
import dev.ftcsim.hardware.lynx.LynxBus;
import dev.ftcsim.hardware.lynx.LynxHub;
import dev.ftcsim.hardware.wiring.WiringTable;
import dev.ftcsim.mechanisms.PlantView;
import dev.ftcsim.mechanisms.ShaftPlant;
import dev.ftcsim.physics.DcMotor;
import dev.ftcsim.physics.MecanumChassis;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.Vector3;
import dev.ftcsim.physics.WheelPosition;
import dev.ftcsim.simulator.profile.MotorCatalog;
import dev.ftcsim.simulator.profile.ProfileException;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The hardware a robot profile describes, built and connected: hubs on a simulated bus, the chassis standing in the
 * physics world, a plant on every motor port the profile wires, and the sources the profile plugs into the digital
 * and analog pins. What robot code can reach is only what this exposes through {@link #environment()}; everything
 * else here is for the simulator's own use. The Control Hub's own
 * IMU is part of the rig without the profile saying so (see {@code attachEmbeddedImus}); a profile lists it only to
 * say how it is mounted, and lists the IMU of an Expansion Hub, on that hub's bus 0, to have one.
 */
public final class RobotRig {

    /** The body kind a snapshot reports for the chassis. */
    public static final int CHASSIS_KIND = BodyKinds.CHASSIS;

    private static final double SECONDS_PER_TICK = LynxHub.TICK_NANOS / 1e9;

    /** A drivetrain motor: which wheel it turns, and which way round it is mounted. */
    private record WheelRef(WheelPosition position, int polarity) {}

    private final RobotProfile profile;
    private final LynxBus bus;
    private final Map<Integer, LynxHub> hubs;
    private final WiringTable wiring;
    private final MecanumChassis chassis;
    private final BodyView chassisView;
    private final DeviceHost devices;
    private final Map<String, MotorPlant> plants;
    private final Map<String, ShaftPlant> shafts;
    private final Map<String, WheelRef> wheels;

    private RobotRig(
            RobotProfile profile,
            LynxBus bus,
            Map<Integer, LynxHub> hubs,
            WiringTable wiring,
            MecanumChassis chassis,
            BodyView chassisView,
            DeviceHost devices,
            Map<String, MotorPlant> plants,
            Map<String, ShaftPlant> shafts,
            Map<String, WheelRef> wheels) {
        this.profile = profile;
        this.bus = bus;
        this.hubs = hubs;
        this.wiring = wiring;
        this.chassis = chassis;
        this.chassisView = chassisView;
        this.devices = devices;
        this.plants = plants;
        this.shafts = shafts;
        this.wheels = wheels;
    }

    /**
     * Builds the rig of a profile. The profile is validated first, so a robot that cannot exist never reaches the
     * world.
     *
     * @param chassisEntityId the entity id the chassis gets in the world
     * @param x               field position of the robot's centre, metres
     * @param y               field position of the robot's centre, metres
     * @param heading         counter-clockwise from the field's +X, radians
     * @param clock           the simulated time, which the hubs' bus reads
     * @param rays            what the devices' rays meet; only asked once the world is stepping
     * @param models          the device models a profile's devices are made from
     * @throws ProfileException if a device has a type no model is registered for, or settings its model refuses
     */
    public static RobotRig build(
            RobotProfile profile, PhysicsWorld world, int chassisEntityId, double x, double y, double heading, LongSupplier clock,
            RayView rays, DeviceModels models) {
        RobotProfileLoader.validate(profile);
        int parent = RobotProfileLoader.parentAddress(profile);

        WiringTable.Builder wiring = WiringTable.builder();
        for (RobotProfile.Hub hub : profile.hubs()) {
            wiring.hub(hub.address(), hub.name(), hub.parent(), hub.embedded());
        }

        MecanumConfig chassisConfig = profile.chassis() == null ? null : profile.chassis().toConfig();
        MecanumChassis chassis = chassisConfig == null
                ? null
                : world.addMecanumChassis(chassisEntityId, CHASSIS_KIND, chassisConfig, new Vector3(x, y, 0), heading);

        Map<String, MotorPlant> plants = new LinkedHashMap<>();
        Map<String, ShaftPlant> shafts = new LinkedHashMap<>();
        Map<String, WheelRef> wheels = new LinkedHashMap<>();
        Map<String, int[]> ports = new LinkedHashMap<>();
        for (Map.Entry<String, RobotProfile.Motor> entry : profile.motors().entrySet()) {
            RobotProfile.Motor motor = entry.getValue();
            int hub = motor.hub() == null ? parent : motor.hub();
            DcMotor spec;
            MotorPlant plant;
            if (motor.wheel() != null) {
                spec = chassisConfig.motor();
                WheelPosition position = RobotProfileLoader.wheelPosition("motors." + entry.getKey(), motor.wheel().position());
                plant = chassis.wheelPlant(position, motor.wheel().polarity());
                wheels.put(entry.getKey(), new WheelRef(position, motor.wheel().polarity()));
            } else {
                spec = MotorCatalog.motor(motor.motor() == null ? MotorCatalog.GOBILDA_435 : motor.motor());
                ShaftPlant shaft = new ShaftPlant(spec, motor.shaft().loadInertiaKgM2(), motor.shaft().frictionNmsPerRad());
                shafts.put(entry.getKey(), shaft);
                plant = shaft;
            }
            wiring.motor(entry.getKey(), hub, motor.port(), spec.encoderTicksPerRevolution(), rpm(spec));
            plants.put(entry.getKey(), plant);
            ports.put(entry.getKey(), new int[] {hub, motor.port()});
        }
        for (Map.Entry<String, RobotProfile.Servo> entry : profile.servos().entrySet()) {
            RobotProfile.Servo servo = entry.getValue();
            wiring.servo(entry.getKey(), servo.hub() == null ? parent : servo.hub(), servo.port());
        }
        for (Map.Entry<String, RobotProfile.Device> entry : profile.devices().entrySet()) {
            RobotProfile.Device device = entry.getValue();
            wiring.i2c(entry.getKey(), device.hub() == null ? parent : device.hub(), device.bus());
        }
        PinWiring.place(profile, parent, wiring);

        LynxBus bus = new LynxBus(clock);
        Map<Integer, LynxHub> hubs = new LinkedHashMap<>();
        double battery = profile.batteryVolts();
        for (RobotProfile.Hub hub : profile.hubs()) {
            HubConfig config = hub.embedded()
                    ? HubConfig.controlHub()
                    : HubConfig.expansionHub(hub.address(), hub.parent(), hub.name());
            // The SDK's keep-alive timers run on real time; the simulator's hubs answer to simulated time only.
            LynxHub lynx = new LynxHub(
                    config.withKeepAliveSupervision(false).withAttentionSignalling(false), () -> battery, clock.getAsLong());
            bus.attach(lynx);
            hubs.put(hub.address(), lynx);
        }
        for (Map.Entry<String, int[]> port : ports.entrySet()) {
            hubs.get(port.getValue()[0]).motorPort(port.getValue()[1]).connect(plants.get(port.getKey()));
        }

        BodyView view = chassis == null ? () -> BodyPose.atOrigin(clock.getAsLong()) : () -> world.bodyPose(chassisEntityId);
        DeviceHost devices = new DeviceHost(view, rays, clock);
        plants.forEach(devices::provideEncoder);
        PinWiring.connect(profile, parent, hubs, devices::encoder);
        for (Map.Entry<String, RobotProfile.Device> entry : profile.devices().entrySet()) {
            RobotProfile.Device device = entry.getValue();
            int hubAddress = device.hub() == null ? parent : device.hub();
            try {
                I2cPeripheral peripheral = models.create(device.type(), entry.getKey(), device.settings(), devices);
                if (isHubsOwnImu(device, peripheral)) {
                    hubs.get(hubAddress).attachInternal(device.bus(), peripheral);
                    if (isEmbedded(profile, hubAddress)) {
                        wiring.embeddedImu(entry.getKey());
                    }
                } else {
                    hubs.get(hubAddress).attach(device.bus(), peripheral);
                }
            } catch (IllegalArgumentException refused) {
                throw new ProfileException("devices." + entry.getKey() + ": " + refused.getMessage(), refused);
            }
        }
        attachEmbeddedImus(profile, hubs, models, devices);
        return new RobotRig(
                profile, bus, Collections.unmodifiableMap(hubs), wiring.build(), chassis, view, devices,
                Collections.unmodifiableMap(plants), Collections.unmodifiableMap(shafts), Collections.unmodifiableMap(wheels));
    }

    /**
     * Whether a device the profile lists is the IMU of the hub it is on: the chip at its address on the hub's bus 0.
     * It is inside the hub, so no port supplies it, and the interfaces robot code asks for it by are served by the
     * hub's own drivers.
     */
    private static boolean isHubsOwnImu(RobotProfile.Device device, I2cPeripheral peripheral) {
        return device.type().equals(DeviceModels.BNO055_IMU)
                && device.bus() == HardwareDirectory.EMBEDDED_IMU_BUS
                && peripheral.address() == Bno055Model.ADDRESS;
    }

    private static boolean isEmbedded(RobotProfile profile, int hubAddress) {
        return profile.hubs().stream().anyMatch(hub -> hub.address() == hubAddress && hub.embedded());
    }

    /**
     * Every Control Hub has an IMU on its bus 0, whether the profile mentions it or not: it is put there unless the
     * profile has already placed one (to say how it is mounted, say). It is inside the hub, so no port supplies it.
     * An Expansion Hub has one on its bus 0 as well, but robot code names it as its configuration does, and a
     * simulated robot has no configuration to say which name that is, so it is only there when the profile lists it.
     */
    private static void attachEmbeddedImus(RobotProfile profile, Map<Integer, LynxHub> hubs, DeviceModels models, DeviceHost devices) {
        if (!models.typeIds().contains(DeviceModels.BNO055_IMU)) {
            return;
        }
        for (RobotProfile.Hub hub : profile.hubs()) {
            LynxHub lynx = hubs.get(hub.address());
            if (hub.embedded() && !lynx.answersAt(HardwareDirectory.EMBEDDED_IMU_BUS, Bno055Model.ADDRESS)) {
                lynx.attachInternal(
                        HardwareDirectory.EMBEDDED_IMU_BUS,
                        models.create(DeviceModels.BNO055_IMU, hub.name() + " IMU", Map.of(), devices));
            }
        }
    }

    private static double rpm(DcMotor motor) {
        return motor.freeSpeedRadPerSec() * 60.0 / (2.0 * Math.PI);
    }

    public RobotProfile profile() {
        return profile;
    }

    /** What robot code is connected to: the hubs' bus and where each named device is plugged in. */
    public RobotEnvironment environment() {
        return new RobotEnvironment(bus, wiring);
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

    /** The chassis, or null if the robot does not drive. */
    public MecanumChassis chassis() {
        return chassis;
    }

    /** The true state of the chassis for the devices that measure it. */
    public BodyView chassisView() {
        return chassisView;
    }

    /** Lets every device that follows the world look at it after the physics step that ended at {@code stepNanos}. */
    public void stepDevices(long stepNanos) {
        devices.step(stepNanos);
    }

    /** The plant on the motor port the profile names, by the name robot code uses. */
    public MotorPlant plant(String motorName) {
        MotorPlant plant = plants.get(motorName);
        if (plant == null) {
            throw new IllegalArgumentException("the profile has no motor '" + motorName + "'; it has " + plants.keySet());
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
        bus.advanceTo(nanos);
    }

    /** The motors as mechanisms follow them: how fast each shaft turns, and what a launch costs a flywheel. */
    public PlantView plantView() {
        return new PlantView() {
            @Override
            public boolean hasShaft(String motor) {
                return shafts.containsKey(motor) || wheels.containsKey(motor);
            }

            @Override
            public double shaftSpeedRadPerSec(String motor) {
                ShaftPlant shaft = shafts.get(motor);
                if (shaft != null) {
                    return shaft.speedRadPerSec();
                }
                WheelRef wheel = wheels.get(motor);
                if (wheel == null) {
                    throw new IllegalArgumentException("the robot has no motor '" + motor + "'");
                }
                return wheel.polarity() * chassis.wheelSpeedRadPerSec(wheel.position());
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
