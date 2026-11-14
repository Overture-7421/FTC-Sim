package dev.ftcsim.simulator.profile;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.ftcsim.hardware.camera.CameraModels;
import dev.ftcsim.hardware.lynx.AnalogInput;
import dev.ftcsim.hardware.pin.PinSources;
import dev.ftcsim.physics.WheelPosition;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads robot profiles strictly: an unknown property, a missing required one, a duplicate key or a number where a
 * word belongs is an error that names the place in the file, never something quietly ignored, because a typo in a
 * profile would otherwise show up as a robot that behaves differently from its code for no visible reason.
 */
public final class RobotProfileLoader {

    private static final int MOTOR_PORTS = 4;
    private static final int SERVO_PORTS = 6;
    private static final int I2C_BUSES = 4;
    private static final int DIGITAL_CHANNELS = 8;
    private static final int ANALOG_CHANNELS = 4;
    private static final int EMBEDDED_ADDRESS = 173;
    private static final String BUNDLED_DIRECTORY = "/dev/ftcsim/simulator/profiles";

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private RobotProfileLoader() {}

    public static RobotProfile load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }

    /** A profile shipped inside the simulator, by resource name below {@code dev/ftcsim/simulator/profiles/}. */
    public static RobotProfile loadBundled(String name) throws IOException {
        String resource = BUNDLED_DIRECTORY + "/" + name;
        try (InputStream stream = RobotProfileLoader.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new ProfileException("there is no bundled robot profile '" + name + "'");
            }
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8), "bundled " + name);
        }
    }

    /** The names of the profiles shipped inside the simulator, sorted, in the form {@link #loadBundled} takes. */
    public static List<String> bundledNames() {
        URL directory = RobotProfileLoader.class.getResource(BUNDLED_DIRECTORY);
        return directory == null ? List.of() : jsonFilesAt(directory);
    }

    /** The {@code .json} files directly inside a directory on disk or inside a jar, by file name, sorted. */
    static List<String> jsonFilesAt(URL directory) {
        try {
            URI location = directory.toURI();
            if (!"jar".equals(location.getScheme())) {
                return jsonFilesIn(Path.of(location));
            }
            String entry = location.getRawSchemeSpecificPart();
            entry = entry.substring(entry.indexOf("!/") + 1);
            try (FileSystem jar = FileSystems.newFileSystem(location, Map.of())) {
                return jsonFilesIn(jar.getPath(entry));
            } catch (FileSystemAlreadyExistsException open) {
                return jsonFilesIn(FileSystems.getFileSystem(location).getPath(entry));
            }
        } catch (IOException | URISyntaxException failure) {
            throw new ProfileException("cannot list the bundled robot profiles: " + failure.getMessage(), failure);
        }
    }

    private static List<String> jsonFilesIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    /** @param source where the text came from, for the error messages */
    public static RobotProfile parse(String json, String source) {
        RobotProfile profile;
        try {
            profile = MAPPER.readValue(json, RobotProfile.class);
        } catch (JsonMappingException failure) {
            throw new ProfileException(source + ": " + failure.getOriginalMessage() + " at " + failure.getPathReference(), failure);
        } catch (JsonProcessingException failure) {
            throw new ProfileException(source + ": " + failure.getOriginalMessage(), failure);
        }
        try {
            validate(profile);
        } catch (ProfileException invalid) {
            throw new ProfileException(source + ": " + invalid.getMessage(), invalid);
        }
        return profile;
    }

    /** Checks everything that must hold for the profile to describe a robot that can exist. */
    public static void validate(RobotProfile profile) {
        if (profile.schema() != RobotProfile.SCHEMA) {
            throw new ProfileException("schema is " + profile.schema() + " but this simulator reads schema " + RobotProfile.SCHEMA);
        }
        if (profile.name().isBlank()) {
            throw new ProfileException("name is empty");
        }
        if (!(profile.batteryVolts() > 0)) {
            throw new ProfileException("batteryVolts must be positive: " + profile.batteryVolts());
        }
        Set<Integer> addresses = validateHubs(profile);
        int parent = parentAddress(profile);
        validateMotors(profile, addresses, parent);
        validateServos(profile, addresses, parent);
        validateDevices(profile, addresses, parent);
        validatePins("digitals", DIGITAL_CHANNELS, profile.digitals(), addresses, parent);
        validatePins("analogs", ANALOG_CHANNELS, profile.analogs(), addresses, parent);
        validateDigitalSources(profile);
        validateAnalogSources(profile);
        validateCameras(profile);
    }

    private static Set<Integer> validateHubs(RobotProfile profile) {
        Set<Integer> addresses = new HashSet<>();
        Set<String> names = new HashSet<>();
        int parents = 0;
        for (RobotProfile.Hub hub : profile.hubs()) {
            if (hub.address() < 1 || hub.address() > 254) {
                throw new ProfileException("hubs: address " + hub.address() + " is not 1..254");
            }
            if (!addresses.add(hub.address())) {
                throw new ProfileException("hubs: two hubs have address " + hub.address());
            }
            if (!names.add(hub.name())) {
                throw new ProfileException("hubs: two hubs are called '" + hub.name() + "'");
            }
            if (hub.embedded() && (!hub.parent() || hub.address() != EMBEDDED_ADDRESS)) {
                throw new ProfileException("hubs: the module embedded in a Control Hub is the parent at address " + EMBEDDED_ADDRESS);
            }
            parents += hub.parent() ? 1 : 0;
        }
        if (parents != 1) {
            throw new ProfileException("hubs: exactly one hub is the parent, found " + parents);
        }
        return addresses;
    }

    /** The address devices without an explicit hub are wired to. */
    public static int parentAddress(RobotProfile profile) {
        return profile.hubs().stream().filter(RobotProfile.Hub::parent).findFirst().orElseThrow().address();
    }

    private static void validateMotors(RobotProfile profile, Set<Integer> addresses, int parent) {
        Set<String> ports = new HashSet<>();
        Set<WheelPosition> wheels = new HashSet<>();
        for (Map.Entry<String, RobotProfile.Motor> entry : profile.motors().entrySet()) {
            String where = "motors." + entry.getKey();
            RobotProfile.Motor motor = entry.getValue();
            int hub = motor.hub() == null ? parent : motor.hub();
            requireHub(where, hub, addresses);
            if (motor.port() < 0 || motor.port() >= MOTOR_PORTS) {
                throw new ProfileException(where + ": port " + motor.port() + " does not exist; a hub has motor ports 0.." + (MOTOR_PORTS - 1));
            }
            if (!ports.add(hub + "/" + motor.port())) {
                throw new ProfileException(where + ": motor port " + motor.port() + " of hub " + hub + " is already used");
            }
            if ((motor.wheel() == null) == (motor.shaft() == null)) {
                throw new ProfileException(where + ": says what the motor turns with exactly one of 'wheel' and 'shaft'");
            }
            checkMotorName(where, motor.motor() == null ? MotorCatalog.GOBILDA_435 : motor.motor());
            if (motor.wheel() != null && motor.motor() != null) {
                throw new ProfileException(where + ": a wheel is turned by the chassis's own motor, so it names none; set 'motor' in 'chassis'");
            }
            if (motor.wheel() != null) {
                WheelPosition position = wheelPosition(where, motor.wheel().position());
                if (!wheels.add(position)) {
                    throw new ProfileException(where + ": two motors drive the " + position + " wheel");
                }
                if (Math.abs(motor.wheel().polarity()) != 1) {
                    throw new ProfileException(where + ".wheel: polarity is 1 or -1, not " + motor.wheel().polarity());
                }
            } else if (motor.shaft().loadInertiaKgM2() < 0 || motor.shaft().frictionNmsPerRad() < 0) {
                throw new ProfileException(where + ".shaft: inertia and friction cannot be negative");
            }
        }
        if (!wheels.isEmpty() && wheels.size() != WheelPosition.values().length) {
            throw new ProfileException("motors: a drivetrain has all four wheels, but only " + wheels + " are driven");
        }
        if (!wheels.isEmpty() && profile.chassis() == null) {
            throw new ProfileException("motors: wheels are driven but the profile has no 'chassis' to turn them");
        }
        if (wheels.isEmpty() && profile.chassis() != null) {
            throw new ProfileException("chassis: the profile has a chassis but no motor drives its wheels");
        }
        if (profile.chassis() != null) {
            try {
                profile.chassis().toConfig();
            } catch (IllegalArgumentException invalid) {
                throw new ProfileException("chassis: " + invalid.getMessage(), invalid);
            }
        }
    }

    private static void validateServos(RobotProfile profile, Set<Integer> addresses, int parent) {
        Set<String> ports = new HashSet<>();
        for (Map.Entry<String, RobotProfile.Servo> entry : profile.servos().entrySet()) {
            String where = "servos." + entry.getKey();
            int hub = entry.getValue().hub() == null ? parent : entry.getValue().hub();
            requireHub(where, hub, addresses);
            if (entry.getValue().port() < 0 || entry.getValue().port() >= SERVO_PORTS) {
                throw new ProfileException(where + ": port " + entry.getValue().port() + " does not exist; a hub has servo ports 0.." + (SERVO_PORTS - 1));
            }
            if (!ports.add(hub + "/" + entry.getValue().port())) {
                throw new ProfileException(where + ": servo port " + entry.getValue().port() + " of hub " + hub + " is already used");
            }
        }
    }

    private static void validateDevices(RobotProfile profile, Set<Integer> addresses, int parent) {
        Set<String> buses = new HashSet<>();
        for (Map.Entry<String, RobotProfile.Device> entry : profile.devices().entrySet()) {
            String where = "devices." + entry.getKey();
            RobotProfile.Device device = entry.getValue();
            int hub = device.hub() == null ? parent : device.hub();
            requireHub(where, hub, addresses);
            if (device.bus() < 0 || device.bus() >= I2C_BUSES) {
                throw new ProfileException(where + ": I2C bus " + device.bus() + " does not exist; a hub has buses 0.." + (I2C_BUSES - 1));
            }
            if (device.type().isBlank()) {
                throw new ProfileException(where + ": type is empty");
            }
            buses.add(hub + "/" + device.bus());
        }
    }

    private static void validatePins(
            String section, int channels, Map<String, ? extends RobotProfile.Pin> pins, Set<Integer> addresses, int parent) {
        Set<String> used = new HashSet<>();
        for (Map.Entry<String, ? extends RobotProfile.Pin> entry : pins.entrySet()) {
            String where = section + "." + entry.getKey();
            RobotProfile.Pin pin = entry.getValue();
            int hub = pin.hub() == null ? parent : pin.hub();
            requireHub(where, hub, addresses);
            if (pin.channel() < 0 || pin.channel() >= channels) {
                throw new ProfileException(where + ": channel " + pin.channel() + " does not exist; a hub has channels 0.." + (channels - 1));
            }
            if (!used.add(hub + "/" + pin.channel())) {
                throw new ProfileException(where + ": channel " + pin.channel() + " of hub " + hub + " is already used");
            }
        }
    }

    private static void validateDigitalSources(RobotProfile profile) {
        for (Map.Entry<String, RobotProfile.Digital> entry : profile.digitals().entrySet()) {
            RobotProfile.DigitalSource source = entry.getValue().source();
            if (source == null) {
                continue;
            }
            String where = "digitals." + entry.getKey() + ".source";
            Map<String, Object> given = new LinkedHashMap<>();
            given.put("level", source.level());
            given.put("motor", source.motor());
            given.put("counts", source.counts());
            given.put("closedWhen", source.closedWhen());
            switch (source.type()) {
                case RobotProfile.CONSTANT_SOURCE -> {
                    requireExactly(where, source.type(), given, "level");
                    isHigh(where + ".level", source.level());
                }
                case RobotProfile.ENCODER_SOURCE -> {
                    requireExactly(where, source.type(), given, "motor", "counts", "closedWhen");
                    requireMotor(where + ".motor", source.motor(), profile);
                    requireFinite(where + ".counts", source.counts());
                    closedWhen(where + ".closedWhen", source.closedWhen());
                }
                default -> throw unknownSourceType(where, source.type());
            }
        }
    }

    private static void validateAnalogSources(RobotProfile profile) {
        for (Map.Entry<String, RobotProfile.Analog> entry : profile.analogs().entrySet()) {
            RobotProfile.AnalogSource source = entry.getValue().source();
            if (source == null) {
                continue;
            }
            String where = "analogs." + entry.getKey() + ".source";
            Map<String, Object> given = new LinkedHashMap<>();
            given.put("volts", source.volts());
            given.put("motor", source.motor());
            given.put("voltsAtZero", source.voltsAtZero());
            given.put("voltsPerCount", source.voltsPerCount());
            switch (source.type()) {
                case RobotProfile.CONSTANT_SOURCE -> {
                    requireExactly(where, source.type(), given, "volts");
                    requireWithinRange(where + ".volts", source.volts());
                }
                case RobotProfile.ENCODER_SOURCE -> {
                    requireExactly(where, source.type(), given, "motor", "voltsAtZero", "voltsPerCount");
                    requireMotor(where + ".motor", source.motor(), profile);
                    requireWithinRange(where + ".voltsAtZero", source.voltsAtZero());
                    requireFinite(where + ".voltsPerCount", source.voltsPerCount());
                }
                default -> throw unknownSourceType(where, source.type());
            }
        }
    }

    /** A source of a type takes the properties that type needs and none of the others, which belong to another type. */
    private static void requireExactly(String where, String type, Map<String, Object> given, String... takes) {
        Set<String> accepted = Set.of(takes);
        for (Map.Entry<String, Object> property : given.entrySet()) {
            boolean present = property.getValue() != null;
            if (present && !accepted.contains(property.getKey())) {
                throw new ProfileException(where + ": a '" + type + "' source takes only " + String.join(", ", takes)
                        + ", not '" + property.getKey() + "'");
            }
            if (!present && accepted.contains(property.getKey())) {
                throw new ProfileException(where + ": a '" + type + "' source needs '" + property.getKey() + "'");
            }
        }
    }

    private static ProfileException unknownSourceType(String where, String type) {
        return new ProfileException(where + ".type: '" + type + "' is not a kind of source; use " + RobotProfile.CONSTANT_SOURCE
                + " or " + RobotProfile.ENCODER_SOURCE);
    }

    private static void requireMotor(String where, String motor, RobotProfile profile) {
        if (!profile.motors().containsKey(motor)) {
            throw new ProfileException(where + ": '" + motor + "' is not a motor of the profile; it has " + profile.motors().keySet());
        }
    }

    private static void requireFinite(String where, double value) {
        if (!Double.isFinite(value)) {
            throw new ProfileException(where + ": " + value + " is not a finite number");
        }
    }

    private static void requireWithinRange(String where, double volts) {
        if (!(volts >= 0.0 && volts <= AnalogInput.FULL_SCALE_VOLTS)) {
            throw new ProfileException(where + ": " + volts + " V is outside the 0 to " + AnalogInput.FULL_SCALE_VOLTS + " V the pin measures");
        }
    }

    private static void validateCameras(RobotProfile profile) {
        CameraModels models = CameraModels.standard();
        for (Map.Entry<String, RobotProfile.Camera> entry : profile.cameras().entrySet()) {
            String where = "cameras." + entry.getKey();
            RobotProfile.Camera camera = entry.getValue();
            if (entry.getKey().isBlank()) {
                throw new ProfileException("cameras: a camera needs the name robot code asks the hardware map for");
            }
            try {
                models.check(camera.type(), entry.getKey(), camera.settings());
            } catch (ProfileException invalid) {
                throw new ProfileException(where + "." + invalid.getMessage(), invalid);
            } catch (IllegalArgumentException invalid) {
                throw new ProfileException(where + ": " + invalid.getMessage(), invalid);
            }
        }
    }

    private static void requireHub(String where, int hub, Set<Integer> addresses) {
        if (!addresses.contains(hub)) {
            throw new ProfileException(where + ": hub " + hub + " is not one of the profile's hubs " + addresses);
        }
    }

    private static void checkMotorName(String where, String name) {
        try {
            MotorCatalog.motor(name);
        } catch (IllegalArgumentException unknown) {
            throw new ProfileException(where + ": " + unknown.getMessage(), unknown);
        }
    }

    /** The wheel a profile names; the message of the exception lists the four. */
    public static WheelPosition wheelPosition(String where, String name) {
        try {
            return WheelPosition.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new ProfileException(where + ": '" + name + "' is not a wheel position; use one of "
                    + java.util.Arrays.toString(WheelPosition.values()));
        }
    }

    /** Whether a level a profile names is HIGH; the only other is LOW, and the message of the exception says so. */
    public static boolean isHigh(String where, String level) {
        return switch (level) {
            case "HIGH" -> true;
            case "LOW" -> false;
            default -> throw new ProfileException(where + ": '" + level + "' is not a level; use HIGH or LOW");
        };
    }

    /** The side of its threshold a switch of a profile closes on; the message of the exception lists the two. */
    public static PinSources.Side closedWhen(String where, String side) {
        try {
            return PinSources.Side.valueOf(side);
        } catch (IllegalArgumentException unknown) {
            throw new ProfileException(where + ": '" + side + "' is not a side; use one of " + Arrays.toString(PinSources.Side.values()));
        }
    }
}
