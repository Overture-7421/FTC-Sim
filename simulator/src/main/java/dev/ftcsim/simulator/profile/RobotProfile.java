package dev.ftcsim.simulator.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.ftcsim.physics.MecanumConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the robot is made of, outside its code: which hubs it has, which motors, servos and sensors are plugged
 * into which ports, and what each motor turns. Robot code only knows names; this is where a name becomes a
 * physical thing, so it lives next to the robot program and never inside it.
 *
 * <p>Optional properties take the documented default when absent; required ones are named in
 * {@link RobotProfileLoader}'s error messages. Anything the profile does not say stays unsaid: a device that robot
 * code asks for and the profile does not mention is given the next free port and reported as unbound.
 *
 * @param schema       the version of this format, {@value #SCHEMA}
 * @param batteryVolts the battery voltage; 12.6 when absent
 * @param hubs         the REV hubs; the embedded Control Hub when absent
 * @param chassis      the drivetrain body, if the robot drives
 * @param motors       by the name robot code uses
 * @param servos       by the name robot code uses
 * @param devices      sensors and other I2C devices by the name robot code uses
 * @param mechanisms   what the robot does with game pieces, if it handles any
 * @param cameras      smart cameras, such as a Limelight, by the name robot code uses
 * @param digitals     digital pins, which robot code reads as a {@code DigitalChannel} or a {@code TouchSensor}, by the name it uses
 * @param analogs      analog pins, which robot code reads as an {@code AnalogInput}, by the name it uses
 */
public record RobotProfile(
        @JsonProperty(required = true) int schema,
        @JsonProperty(required = true) String name,
        Double batteryVolts,
        List<Hub> hubs,
        Chassis chassis,
        Map<String, Motor> motors,
        Map<String, Servo> servos,
        Map<String, Device> devices,
        Mechanisms mechanisms,
        Map<String, Camera> cameras,
        Map<String, Digital> digitals,
        Map<String, Analog> analogs) {

    public static final int SCHEMA = 1;
    public static final double DEFAULT_BATTERY_VOLTS = 12.6;

    /** The kind of source that holds a pin at a fixed level. */
    public static final String CONSTANT_SOURCE = "constant";

    /** The kind of source that follows the encoder of one of the robot's motors. */
    public static final String ENCODER_SOURCE = "encoder";

    public RobotProfile {
        batteryVolts = batteryVolts == null ? DEFAULT_BATTERY_VOLTS : batteryVolts;
        hubs = hubs == null || hubs.isEmpty() ? List.of(new Hub(173, "Control Hub", true, true)) : List.copyOf(hubs);
        motors = motors == null ? Map.of() : copy(motors);
        servos = servos == null ? Map.of() : copy(servos);
        devices = devices == null ? Map.of() : copy(devices);
        mechanisms = mechanisms == null ? new Mechanisms(null, null, null) : mechanisms;
        cameras = cameras == null ? Map.of() : copy(cameras);
        digitals = digitals == null ? Map.of() : copy(digitals);
        analogs = analogs == null ? Map.of() : copy(analogs);
    }

    /** A profile of a robot whose hubs have nothing on their digital and analog pins. */
    public RobotProfile(
            int schema,
            String name,
            Double batteryVolts,
            List<Hub> hubs,
            Chassis chassis,
            Map<String, Motor> motors,
            Map<String, Servo> servos,
            Map<String, Device> devices,
            Mechanisms mechanisms,
            Map<String, Camera> cameras) {
        this(schema, name, batteryVolts, hubs, chassis, motors, servos, devices, mechanisms, cameras, null, null);
    }

    private static <T> Map<String, T> copy(Map<String, T> source) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * One REV hub.
     *
     * @param address  the module address, 1..254
     * @param name     the name robot code finds it under; "Control Hub" or "Expansion Hub 2" and so on
     * @param parent   whether it is the one the robot controller is connected to; exactly one hub is
     * @param embedded whether it is the module inside a Control Hub
     */
    public record Hub(
            @JsonProperty(required = true) int address,
            @JsonProperty(required = true) String name,
            Boolean parent,
            Boolean embedded) {

        public Hub {
            parent = parent != null && parent;
            embedded = embedded != null && embedded;
        }
    }

    /**
     * The drivetrain body. Every number is optional and defaults to {@link MecanumConfig#generic18Inch()}, a
     * plausible 18 inch robot that has not been fitted to a real one. The numbers are those of {@link MecanumConfig},
     * which says what each of them means.
     *
     * @param motor the drivetrain motor, by {@link MotorCatalog} name; the same on all four wheels
     */
    public record Chassis(
            String motor,
            Double massKg,
            Double lengthM,
            Double widthM,
            Double heightM,
            Double groundClearanceM,
            Double comHeightM,
            Double wheelRadiusM,
            Double wheelbaseM,
            Double trackM,
            Double wheelInertiaKgM2,
            Double wheelDampingNmsPerRad,
            Double tireStiffnessNPerM,
            Double tireDampingNsPerM,
            Double tractionFriction,
            Double slipStiffnessNsPerM,
            Double rollerFriction,
            Double rollerViscousNsPerM,
            Double wheelFrictionNm,
            Double rollerRollingResistance) {

        /** The physics description with every missing value taken from the generic robot. */
        public MecanumConfig toConfig() {
            MecanumConfig generic = MecanumConfig.generic18Inch();
            return new MecanumConfig(
                    or(massKg, generic.massKg()),
                    or(lengthM, generic.lengthM()),
                    or(widthM, generic.widthM()),
                    or(heightM, generic.heightM()),
                    or(groundClearanceM, generic.groundClearanceM()),
                    or(comHeightM, generic.comHeightM()),
                    or(wheelRadiusM, generic.wheelRadiusM()),
                    or(wheelbaseM, generic.wheelbaseM()),
                    or(trackM, generic.trackM()),
                    motor == null ? generic.motor() : MotorCatalog.motor(motor),
                    or(wheelInertiaKgM2, generic.wheelInertiaKgM2()),
                    or(wheelDampingNmsPerRad, generic.wheelDampingNmsPerRad()),
                    or(tireStiffnessNPerM, generic.tireStiffnessNPerM()),
                    or(tireDampingNsPerM, generic.tireDampingNsPerM()),
                    or(tractionFriction, generic.tractionFriction()),
                    or(slipStiffnessNsPerM, generic.slipStiffnessNsPerM()),
                    or(rollerFriction, generic.rollerFriction()),
                    or(rollerViscousNsPerM, generic.rollerViscousNsPerM()),
                    or(wheelFrictionNm, generic.wheelFrictionNm()),
                    or(rollerRollingResistance, generic.rollerRollingResistance()));
        }

        private static double or(Double value, double fallback) {
            return value == null ? fallback : value;
        }
    }

    /**
     * A motor port and what its motor turns: exactly one of {@code wheel} and {@code shaft}.
     *
     * @param hub   the hub address; the parent hub when absent
     * @param port  0..3
     * @param motor the motor, by {@link MotorCatalog} name; for a wheel it is the chassis's drivetrain motor
     */
    public record Motor(Integer hub, @JsonProperty(required = true) int port, String motor, Wheel wheel, Shaft shaft) {}

    /**
     * A drivetrain wheel.
     *
     * @param position one of FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT
     * @param polarity +1 when positive volts roll the robot forward, -1 when the motor is mounted the other way
     *                 round, as the motors on one side of a symmetric drivetrain are
     */
    public record Wheel(@JsonProperty(required = true) String position, @JsonProperty(required = true) int polarity) {}

    /**
     * A shaft that is not a wheel: a flywheel, an intake roller, a conveyor.
     *
     * @param loadInertiaKgM2   inertia of everything the shaft turns, besides the rotor
     * @param frictionNmsPerRad viscous friction; 0 when absent
     */
    public record Shaft(@JsonProperty(required = true) double loadInertiaKgM2, Double frictionNmsPerRad) {

        public Shaft {
            frictionNmsPerRad = frictionNmsPerRad == null ? 0.0 : frictionNmsPerRad;
        }
    }

    /** A servo port. */
    public record Servo(Integer hub, @JsonProperty(required = true) int port) {}

    /** A pin of a hub that robot code reads under a name: where it is, whichever kind of pin it is. */
    public sealed interface Pin permits Digital, Analog {
        /** The hub address; the parent hub when absent. */
        Integer hub();

        int channel();
    }

    /**
     * A digital pin, which robot code reads as a {@code DigitalChannel} or as a {@code TouchSensor}.
     *
     * @param hub     the hub address; the parent hub when absent
     * @param channel 0..7
     * @param source  what drives the pin; a pin without one is connected but idle, and reads high through the hub's pull-up
     */
    public record Digital(Integer hub, @JsonProperty(required = true) int channel, DigitalSource source) implements Pin {}

    /**
     * What drives a digital pin. A {@value RobotProfile#CONSTANT_SOURCE} source holds it at {@code level}, HIGH or LOW.
     * An {@value RobotProfile#ENCODER_SOURCE} source is a switch to ground, as a REV touch sensor or a magnetic limit
     * switch is, on the mechanism a motor turns: the pin reads LOW while the encoder of {@code motor} is above or
     * below {@code counts} (as {@code closedWhen}, ABOVE or BELOW, says) and HIGH otherwise. Which of the other
     * properties a source takes depends on its type, and {@link RobotProfileLoader} names any that is missing or
     * does not belong.
     */
    public record DigitalSource(
            @JsonProperty(required = true) String type, String level, String motor, Double counts, String closedWhen) {}

    /**
     * An analog pin, which robot code reads as an {@code AnalogInput}.
     *
     * @param hub     the hub address; the parent hub when absent
     * @param channel 0..3
     * @param source  what drives the pin; a pin without one is connected but idle, and reads 0 V
     */
    public record Analog(Integer hub, @JsonProperty(required = true) int channel, AnalogSource source) implements Pin {}

    /**
     * What drives an analog pin. A {@value RobotProfile#CONSTANT_SOURCE} source holds it at {@code volts}. An
     * {@value RobotProfile#ENCODER_SOURCE} source is a potentiometer on the mechanism a motor turns: {@code voltsAtZero}
     * when the encoder of {@code motor} reads zero and {@code voltsPerCount} more for every count, within the 0 to 3.3 V
     * the pin measures.
     */
    public record AnalogSource(
            @JsonProperty(required = true) String type, Double volts, String motor, Double voltsAtZero, Double voltsPerCount) {}

    /**
     * What the robot does with game pieces, described by what its motors do: where pieces are taken in, where they
     * are carried, where they leave. Everything refers to motors by the name robot code uses and to places in the
     * robot's own frame (X forward, Y left, Z up, origin at the chassis centre, metres).
     */
    public record Mechanisms(
            Map<String, Storage> storages,
            Map<String, Intake> intakes,
            Map<String, Launcher> launchers) {

        public Mechanisms {
            storages = storages == null ? Map.of() : copy(storages);
            intakes = intakes == null ? Map.of() : copy(intakes);
            launchers = launchers == null ? Map.of() : copy(launchers);
        }
    }

    /**
     * A path pieces are carried along.
     *
     * @param path   at least two points, each {@code [x, y, z]}; a piece enters at the first and is held back at the last
     * @param drives the rollers and belts that move pieces along it
     */
    public record Storage(@JsonProperty(required = true) int capacity, @JsonProperty(required = true) List<List<Double>> path, List<Drive> drives) {

        public Storage {
            drives = drives == null ? List.of() : List.copyOf(drives);
        }
    }

    /**
     * One roller or belt.
     *
     * @param sign  +1 when the motor turning forward carries pieces towards the end of the path, -1 when it carries them back
     * @param from  where along the path it reaches, in metres from the start; 0 when absent
     * @param to    where its reach ends; the end of the path when absent
     */
    public record Drive(
            @JsonProperty(required = true) String motor,
            @JsonProperty(required = true) double radiusM,
            @JsonProperty(required = true) int sign,
            Double from,
            Double to) {}

    /** What a motor must be doing: its shaft speed, in the direction its encoder counts up, within a range. */
    public record When(@JsonProperty(required = true) String motor, Double minRadPerSec, Double maxRadPerSec) {}

    /**
     * An intake: while every condition holds, a piece whose centre is inside the box and whose diameter the intake
     * takes is taken into the storage.
     *
     * @param center            the box centre, {@code [x, y, z]}
     * @param half              the box half extents, {@code [x, y, z]}
     * @param minPieceDiameterM the smallest piece it grips, in metres; any size when absent
     * @param maxPieceDiameterM the largest piece that fits, in metres; any size when absent
     */
    public record Intake(
            @JsonProperty(required = true) String storage,
            @JsonProperty(required = true) List<Double> center,
            @JsonProperty(required = true) List<Double> half,
            List<When> when,
            Double minPieceDiameterM,
            Double maxPieceDiameterM) {

        public Intake {
            when = when == null ? List.of() : List.copyOf(when);
        }
    }

    /**
     * A launcher at the end of a storage's path.
     *
     * @param direction    the way pieces leave, {@code [x, y, z]}
     * @param efficiency   the share of the wheels' rim speed the piece leaves with, in (0, 1]
     * @param dribbleMps   the least speed a released piece has; 0.3 m/s when absent
     * @param when         what the motors must be doing for it to release the piece waiting at the end
     */
    public record Launcher(
            @JsonProperty(required = true) String storage,
            @JsonProperty(required = true) List<Double> direction,
            @JsonProperty(required = true) double efficiency,
            Double dribbleMps,
            @JsonProperty(required = true) List<Flywheel> wheels,
            List<When> when) {

        public Launcher {
            dribbleMps = dribbleMps == null ? 0.3 : dribbleMps;
            when = when == null ? List.of() : List.copyOf(when);
        }
    }

    /** A flywheel of a launcher: the motor, the radius of its rim, and which way forward turns the rim. */
    public record Flywheel(
            @JsonProperty(required = true) String motor,
            @JsonProperty(required = true) double radiusM,
            @JsonProperty(required = true) int sign) {}

    /**
     * An I2C device on a hub's bus.
     *
     * @param type   the device model's id, such as {@code gobilda-pinpoint}
     * @param hub    the hub address; the parent hub when absent
     * @param bus    0..3; bus 0 of a hub belongs to its own IMU: a {@code bno055-imu} at its default address there says how
     *               that IMU is mounted (the Control Hub's is there without a line), or gives an Expansion Hub one
     * @param config what the device model needs, by key; groups may be nested; each model rejects keys it does not know
     */
    public record Device(
            @JsonProperty(required = true) String type,
            Integer hub,
            @JsonProperty(required = true) int bus,
            Map<String, Object> config) {

        public Device {
            config = config == null ? Map.of() : copy(config);
        }

        /** The settings as the device models read them: a nested group {@code xPod: {x: 1}} becomes {@code xPod.x}. */
        public Map<String, Object> settings() {
            Map<String, Object> flat = new LinkedHashMap<>();
            flatten("", config, flat);
            return flat;
        }

        private static void flatten(String prefix, Map<?, ?> group, Map<String, Object> into) {
            group.forEach((key, value) -> {
                String name = prefix + key;
                if (value instanceof Map<?, ?> inner) {
                    flatten(name + ".", inner, into);
                } else {
                    into.put(name, value);
                }
            });
        }
    }

    /**
     * A smart camera on a USB port of the robot controller, such as a Limelight 3A. Robot code asks the hardware map
     * for it by name; whatever it is plugged into is not part of the profile.
     *
     * @param type      the camera model's id, such as {@code limelight-3a}
     * @param mount     where it is on the robot and which way it looks
     * @param pipelines the pipelines the camera has been set up with, by slot number {@code "0"}..{@code "9"}; a slot
     *                  that is not listed runs the camera's default pipeline, which finds nothing
     */
    public record Camera(
            @JsonProperty(required = true) String type,
            @JsonProperty(required = true) Mount mount,
            Map<String, Pipeline> pipelines) {

        public Camera {
            pipelines = pipelines == null ? Map.of() : copy(pipelines);
        }

        /**
         * The settings as the camera models read them: {@code mount.x}, {@code pipeline0.fieldMap} and so on.
         *
         * @throws ProfileException if a pipeline's slot is not a number
         */
        public Map<String, Object> settings() {
            Map<String, Object> flat = new LinkedHashMap<>();
            flat.put("mount.x", mount.x());
            flat.put("mount.y", mount.y());
            flat.put("mount.z", mount.z());
            putIfPresent(flat, "mount.yawDegrees", mount.yawDegrees());
            putIfPresent(flat, "mount.pitchDegrees", mount.pitchDegrees());
            putIfPresent(flat, "mount.rollDegrees", mount.rollDegrees());
            pipelines.forEach((slot, pipeline) -> {
                int index;
                try {
                    index = Integer.parseInt(slot);
                } catch (NumberFormatException notANumber) {
                    throw new ProfileException("pipelines." + slot + ": a pipeline slot is a number, 0..9");
                }
                String prefix = "pipeline" + index + ".";
                flat.put(prefix + "type", pipeline.type());
                flat.put(prefix + "fieldMap", pipeline.fieldMap());
                if (!pipeline.ids().isEmpty()) {
                    flat.put(prefix + "ids", pipeline.ids().stream().map(String::valueOf).collect(Collectors.joining(",")));
                }
                putIfPresent(flat, prefix + "resolution", pipeline.resolution());
                if (pipeline.sendCorners() != null) {
                    flat.put(prefix + "sendCorners", pipeline.sendCorners() ? 1 : 0);
                }
            });
            return flat;
        }

        private static void putIfPresent(Map<String, Object> into, String key, Object value) {
            if (value != null) {
                into.put(key, value);
            }
        }
    }

    /**
     * Where a camera is on the robot, in the chassis frame like every other point a profile gives: X forward, Y left,
     * Z up, metres from the centre of the chassis's box, which is half its height plus its ground clearance above the
     * floor when the robot stands level.
     *
     * @param yawDegrees   turns the camera to the left; 0 when absent
     * @param pitchDegrees tilts it up; 0 when absent
     * @param rollDegrees  turns it clockwise as seen from behind it; 0 when absent
     */
    public record Mount(
            @JsonProperty(required = true) double x,
            @JsonProperty(required = true) double y,
            @JsonProperty(required = true) double z,
            Double yawDegrees,
            Double pitchDegrees,
            Double rollDegrees) {}

    /**
     * One pipeline slot of a camera.
     *
     * @param type       {@code apriltag}; the other kinds of pipeline are not simulated
     * @param fieldMap   the camera's belief about where tags are: a game's id ({@code decode}, {@code biobuzz}) for that
     *                   game's official map of fixed tags, {@code field} for the fixed tags of the field the robot
     *                   stands on, or {@code none}
     * @param ids        the tag ids the pipeline looks for; every tag when absent
     * @param resolution {@code 640x480} (50 frames per second) or {@code 1280x960} (20 frames per second); the first
     *                   when absent
     * @param sendCorners whether the results carry the corners of each tag; false when absent
     */
    public record Pipeline(
            @JsonProperty(required = true) String type,
            @JsonProperty(required = true) String fieldMap,
            List<Integer> ids,
            String resolution,
            Boolean sendCorners) {

        public Pipeline {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }
    }
}
