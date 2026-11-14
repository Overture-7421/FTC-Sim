package dev.ftcsim.acceptance;

import dev.ftcsim.contracts.field.FieldTag;
import dev.ftcsim.contracts.hardware.HardwareDirectory;
import dev.ftcsim.contracts.robot.RobotEnvironment;
import dev.ftcsim.contracts.world.BodyView;
import dev.ftcsim.contracts.world.RayView;
import dev.ftcsim.core.kernel.FixedRateWorld;
import dev.ftcsim.hardware.camera.CameraModels;
import dev.ftcsim.hardware.camera.CameraRack;
import dev.ftcsim.hardware.device.DeviceHost;
import dev.ftcsim.hardware.device.DeviceModels;
import dev.ftcsim.hardware.device.scripted.ScriptedBody;
import dev.ftcsim.hardware.lynx.HubConfig;
import dev.ftcsim.hardware.lynx.LynxBus;
import dev.ftcsim.hardware.lynx.LynxHub;
import dev.ftcsim.hardware.wiring.WiringTable;
import dev.ftcsim.mechanisms.ShaftPlant;
import dev.ftcsim.physics.DcMotor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * A Control Hub on a simulated bus, wired the way a test needs, with the clock the test chooses. The plants a
 * test connects are stepped together with the hub every millisecond, plants first, as the simulator does. A test
 * that mounts I2C devices or cameras also gives the robot a chassis that follows a script and a world to look at;
 * both are stepped at the physics rate of 240 Hz, and the devices follow them.
 */
final class TestRobot {
    private static final double BATTERY_VOLTS = 12.6;
    private static final long TICK_NANOS = 1_000_000L;
    private static final long PHYSICS_STEPS_PER_SECOND = 240;
    private static final long CAMERA_SEED = 1;

    final LynxBus bus;
    final LynxHub controlHub;
    final WiringTable wiring;
    /** The robot's cameras; empty unless the builder was given some, and then every camera robot code asks for is unplugged. */
    final CameraRack cameras;
    private final List<ShaftPlant> plants;
    private final FixedRateWorld.Task physicsStep;

    private TestRobot(
            LynxBus bus,
            LynxHub controlHub,
            WiringTable wiring,
            List<ShaftPlant> plants,
            FixedRateWorld.Task physicsStep,
            CameraRack cameras) {
        this.bus = bus;
        this.controlHub = controlHub;
        this.wiring = wiring;
        this.plants = plants;
        this.physicsStep = physicsStep;
        this.cameras = cameras;
    }

    /** The embedded hub only, no device placed: every device robot code asks for gets the next free port. */
    static TestRobot controlHubOnly(LongSupplier clockNanos) {
        return builder().build(clockNanos);
    }

    static Builder builder() {
        return new Builder();
    }

    RobotEnvironment environment() {
        return new RobotEnvironment(bus, wiring, cameras);
    }

    /** The plant connected by the n-th call to {@link Builder#motor}. */
    ShaftPlant plant(int index) {
        return plants.get(index);
    }

    /** The world to give the kernel: every millisecond the plants move and then the hub runs. */
    FixedRateWorld world() {
        FixedRateWorld world = new FixedRateWorld().every(1000, (index, nanos) -> {
            for (ShaftPlant plant : plants) {
                plant.step(TICK_NANOS / 1e9);
            }
            bus.advanceTo(nanos);
        });
        if (physicsStep != null) {
            world.every(PHYSICS_STEPS_PER_SECOND, physicsStep);
        }
        return world;
    }

    private record DeviceSpec(String name, int bus, String typeId, Map<String, ?> settings) {}

    private record CameraSpec(String name, String typeId, Map<String, ?> settings) {}

    static final class Builder {
        private final WiringTable.Builder wiring =
                WiringTable.builder().hub(HubConfig.EMBEDDED_ADDRESS, "Control Hub", true, true);
        private final List<ShaftPlant> plants = new ArrayList<>();
        private final List<Integer> ports = new ArrayList<>();
        private final List<DeviceSpec> devices = new ArrayList<>();
        private final List<CameraSpec> cameraSpecs = new ArrayList<>();
        private final Map<String, Integer> sharedEncoders = new LinkedHashMap<>();
        private final Map<String, List<FieldTag>> gameMaps = new LinkedHashMap<>();
        private List<FieldTag> fieldTags = List.of();
        private IntFunction<BodyView> entities;
        private BodyView chassis;
        private LongConsumer moveChassis = nanos -> {};
        private boolean embeddedImu;
        private Map<String, ?> embeddedImuSettings = Map.of();
        private RayView rays = (origin, direction, maxMeters) -> null;

        /** A motor on a port of the Control Hub, turning a load of the given inertia, under the name robot code uses. */
        Builder motor(String name, int port, DcMotor motor, double loadInertiaKgM2, double frictionNmsPerRad) {
            wiring.motor(name, HubConfig.EMBEDDED_ADDRESS, port, motor.encoderTicksPerRevolution(), rpm(motor));
            plants.add(new ShaftPlant(motor, loadInertiaKgM2, frictionNmsPerRad));
            ports.add(port);
            return this;
        }

        /** The chassis the devices are mounted on, which follows its script as simulated time passes. */
        Builder chassis(ScriptedBody chassis) {
            return chassis(chassis, chassis::advanceTo);
        }

        /**
         * A chassis of any kind, such as one that tilts, which a script cannot describe: {@code moveChassis} is told the
         * time of every physics step before the devices look at {@code chassis}.
         */
        Builder chassis(BodyView chassis, LongConsumer moveChassis) {
            this.chassis = chassis;
            this.moveChassis = moveChassis;
            return this;
        }

        /**
         * The IMU every Control Hub has on its bus 0, which this robot has only if a test asks for it, with the given
         * settings (its mounting, say). The chip answers like the one of a robot's rig and, being inside the hub,
         * is not counted in the current the I2C ports supply.
         */
        Builder embeddedImu(Map<String, ?> settings) {
            this.embeddedImu = true;
            this.embeddedImuSettings = settings;
            return this;
        }

        /** What rays of the devices meet. */
        Builder rays(RayView rays) {
            this.rays = rays;
            return this;
        }

        /** A device model of {@code typeId} on an I2C bus of the Control Hub, under the name robot code uses. */
        Builder device(String name, int bus, String typeId, Map<String, ?> settings) {
            wiring.i2c(name, HubConfig.EMBEDDED_ADDRESS, bus);
            devices.add(new DeviceSpec(name, bus, typeId, settings));
            return this;
        }

        /** Lets devices count the encoder of the n-th motor, the way an OctoQuad channel can, under {@code name}. */
        Builder shareEncoder(String name, int motorIndex) {
            sharedEncoders.put(name, motorIndex);
            return this;
        }

        /** A camera model of {@code typeId} on a USB port, under the name robot code asks the hardware map for. */
        Builder camera(String name, String typeId, Map<String, ?> settings) {
            cameraSpecs.add(new CameraSpec(name, typeId, settings));
            return this;
        }

        /** The AprilTags of the field the robot stands on, which cameras see whether or not they know them. */
        Builder fieldTags(List<FieldTag> tags) {
            this.fieldTags = List.copyOf(tags);
            return this;
        }

        /** The bodies of the world that tags are stuck on, by entity id. */
        Builder entities(IntFunction<BodyView> entities) {
            this.entities = entities;
            return this;
        }

        /** The official AprilTag map of a game, which a camera's pipeline can name; only the fixed tags count. */
        Builder gameMap(String gameId, List<FieldTag> tags) {
            gameMaps.put(gameId, List.copyOf(tags));
            return this;
        }

        TestRobot build(LongSupplier clockNanos) {
            LynxBus bus = new LynxBus(clockNanos);
            LynxHub hub = new LynxHub(
                    HubConfig.controlHub().withKeepAliveSupervision(false).withAttentionSignalling(false),
                    () -> BATTERY_VOLTS,
                    clockNanos.getAsLong());
            bus.attach(hub);
            for (int i = 0; i < plants.size(); i++) {
                hub.motorPort(ports.get(i)).connect(plants.get(i));
            }
            CameraRack cameras = new CameraRack();
            return new TestRobot(bus, hub, wiring.build(), List.copyOf(plants), mountDevices(hub, clockNanos, cameras), cameras);
        }

        /** Creates the devices on the hub and the cameras and returns the work they need after each physics step, if any. */
        private FixedRateWorld.Task mountDevices(LynxHub hub, LongSupplier clockNanos, CameraRack cameras) {
            if (chassis == null && devices.isEmpty() && cameraSpecs.isEmpty() && !embeddedImu) {
                return null;
            }
            BodyView body = chassis != null ? chassis : ScriptedBody.startingAt(0, 0, 0).build();
            LongConsumer move = chassis != null ? moveChassis : nanos -> {};
            DeviceHost host = new DeviceHost(body, rays, clockNanos);
            host.provideFieldTags(fieldTags);
            if (entities != null) {
                host.provideEntities(entities);
            }
            gameMaps.forEach(host::provideGameMap);
            sharedEncoders.forEach((name, motorIndex) -> host.provideEncoder(name, plants.get(motorIndex)));
            DeviceModels models = DeviceModels.standard();
            if (embeddedImu) {
                hub.attachInternal(
                        HardwareDirectory.EMBEDDED_IMU_BUS,
                        models.create(DeviceModels.BNO055_IMU, "Control Hub IMU", embeddedImuSettings, host));
            }
            for (DeviceSpec device : devices) {
                hub.attach(device.bus(), models.create(device.typeId(), device.name(), device.settings(), host));
            }
            CameraModels cameraModels = CameraModels.standard();
            for (CameraSpec camera : cameraSpecs) {
                cameras.add(camera.name(), cameraModels.create(camera.typeId(), camera.name(), camera.settings(), host, CAMERA_SEED));
            }
            return (index, nanos) -> {
                move.accept(nanos);
                host.step(nanos);
            };
        }

        private static double rpm(DcMotor motor) {
            return motor.freeSpeedRadPerSec() * 60.0 / (2.0 * Math.PI);
        }
    }
}
