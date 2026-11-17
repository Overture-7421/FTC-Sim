package dev.ftcsim.app;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.CameraMount;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.ServoMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.contracts.session.RobotMapping.TurretEncoder;
import dev.ftcsim.contracts.session.RobotMapping.TurretParts;
import dev.ftcsim.ui.host.ProjectMappings;
import dev.ftcsim.ui.host.RunDefaults;
import dev.ftcsim.ui.host.StartPosition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The robot mapping of a team's project: {@value #FILE} at the project's root, the only file FTC-Sim ever adds to a
 * project. The file is FTC-Sim's own, schema {@value #SCHEMA}, and is read and written whole; nothing in it is a
 * person's text to keep. A file this version does not read (an older schema, or anything that does not parse) is never
 * overwritten silently: it is found with its problem, and saving copies it to {@code ftcsim.old.json} first.
 *
 * <p>A project whose folder cannot be written to gets a copy in FTC-Sim's own folders ({@link AppFolders#keptProfile}),
 * which is found afterwards as if it were in the project.
 */
public final class ProjectMappingsImpl implements ProjectMappings {

    public static final String FILE = "ftcsim.json";

    /** The version of the file's layout. */
    public static final int SCHEMA = 2;

    static final String OLDER_VERSION = FILE + " is from an older version";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter(
            Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER));

    private static final Set<String> TOP_KEYS = Set.of(
            "schema", "name", "motors", "massKg", "wheelDiameterMm", "driveMotor", "turretRatio", "hub", "camera", "turret", "run",
            "startPositions");
    private static final Set<String> MOTOR_KEYS = Set.of("slot", "model", "mirrored");
    private static final Set<String> HUB_KEYS = Set.of("logo", "usb");
    private static final Set<String> CAMERA_KEYS = Set.of("x", "y", "z", "yawDegrees", "pitchDegrees", "rollDegrees");
    private static final Set<String> TURRET_KEYS = Set.of("servo", "encoders", "axis", "cameraOnTurret");
    private static final Set<String> SERVO_KEYS = Set.of("name", "model", "mirrored");
    private static final Set<String> ENCODER_KEYS = Set.of("channel", "model", "turnsPerTurretTurn", "zeroOffsetDegrees", "mirrored");
    private static final Set<String> AXIS_KEYS = Set.of("x", "y");
    private static final Set<String> RUN_KEYS = Set.of("field", "alliance", "startPose");
    private static final Set<String> POSITION_KEYS = Set.of("name", "field", "x", "y", "headingDegrees");

    private final AppFolders folders;

    public ProjectMappingsImpl(AppFolders folders) {
        this.folders = folders;
    }

    @Override
    public Optional<Found> find(Path project) throws IOException {
        Path inProject = project.resolve(FILE);
        if (Files.isRegularFile(inProject)) {
            return Optional.of(read(inProject, false, project));
        }
        Path kept = folders.keptProfile(project);
        if (Files.isRegularFile(kept)) {
            return Optional.of(read(kept, true, project));
        }
        return Optional.empty();
    }

    @Override
    public Saved save(Path project, RobotMapping mapping) throws IOException {
        Found existing = find(project).orElse(null);
        if (existing != null) {
            if (!existing.usable()) {
                Files.copy(existing.file(), oldCopy(existing.file()), StandardCopyOption.REPLACE_EXISTING);
            }
            RunDefaults run = existing.run() == null ? RunDefaults.NONE : existing.run();
            replaceFile(existing.file(), textOf(mapping, run, existing.startPositions()));
            return new Saved(existing.file(), existing.keptByApp());
        }
        String text = textOf(mapping, RunDefaults.NONE, List.of());
        Path inProject = project.resolve(FILE);
        try {
            replaceFile(inProject, text);
            return new Saved(inProject, false);
        } catch (IOException unwritable) {
            Path kept = folders.keptProfile(project);
            Files.createDirectories(kept.getParent());
            replaceFile(kept, text);
            return new Saved(kept, true);
        }
    }

    @Override
    public void saveRunDefaults(Path project, RunDefaults run) throws IOException {
        Found found = find(project).orElseThrow(() -> new IOException("This project has no " + FILE + " to save the settings in."));
        if (!found.usable()) {
            throw new IOException(found.problem() + ", so the settings cannot be saved in it.");
        }
        replaceFile(found.file(), textOf(found.mapping(), run, found.startPositions()));
    }

    @Override
    public void saveStartPositions(Path project, List<StartPosition> positions) throws IOException {
        Found found = find(project).orElseThrow(() -> new IOException("This project has no " + FILE + " to save the start positions in."));
        if (!found.usable()) {
            throw new IOException(found.problem() + ", so the start positions cannot be saved in it.");
        }
        replaceFile(found.file(), textOf(found.mapping(), found.run(), positions));
    }

    private static Path oldCopy(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return file.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".old.json");
    }

    private static void replaceFile(Path file, String text) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    // Reading ---------------------------------------------------------------------------------------------------

    private static Found read(Path file, boolean kept, Path project) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (IOException notJson) {
            return unusable(file, kept, FILE + " cannot be read: " + firstLine(notJson.getMessage()));
        }
        if (root == null || !root.isObject()) {
            return unusable(file, kept, FILE + " cannot be read: it is not a JSON object");
        }
        JsonNode schema = root.get("schema");
        if (schema == null || !schema.isInt()) {
            return unusable(file, kept, FILE + " cannot be read: it has no schema number");
        }
        if (schema.asInt() < SCHEMA) {
            return unusable(file, kept, OLDER_VERSION);
        }
        if (schema.asInt() > SCHEMA) {
            return unusable(file, kept, FILE + " is from a newer version");
        }
        try {
            return new Found(file, mappingOf(root, project), runOf(root.get("run")), kept, null, positionsOf(root.get("startPositions")));
        } catch (IllegalArgumentException wrong) {
            return unusable(file, kept, FILE + " cannot be read: " + wrong.getMessage());
        }
    }

    private static Found unusable(Path file, boolean kept, String problem) {
        return new Found(file, null, RunDefaults.NONE, kept, problem);
    }

    private static String firstLine(String message) {
        return message == null ? "it is not JSON" : message.lines().findFirst().orElse("it is not JSON");
    }

    private static RobotMapping mappingOf(JsonNode root, Path project) {
        noOtherKeys(root, TOP_KEYS, FILE);
        Path folder = project.getFileName();
        RobotMapping defaults = RobotMapping.empty(text(root, "name", folder == null ? "Robot" : folder.toString()));
        List<MotorMapping> motors = new ArrayList<>();
        JsonNode motorsNode = root.get("motors");
        if (motorsNode != null) {
            if (!motorsNode.isObject()) {
                throw new IllegalArgumentException("motors is not an object");
            }
            for (Map.Entry<String, JsonNode> motor : motorsNode.properties()) {
                motors.add(motorOf(motor.getKey(), motor.getValue()));
            }
        }
        JsonNode hub = object(root, "hub", HUB_KEYS);
        JsonNode camera = object(root, "camera", CAMERA_KEYS);
        CameraMount at = defaults.camera();
        return new RobotMapping(
                defaults.name(),
                motors,
                number(root, "massKg", defaults.massKg()),
                number(root, "wheelDiameterMm", defaults.wheelDiameterMm()),
                text(root, "driveMotor", defaults.driveMotor()),
                number(root, "turretRatio", defaults.turretRatio()),
                hub == null ? defaults.hubLogo() : text(hub, "logo", defaults.hubLogo()),
                hub == null ? defaults.hubUsb() : text(hub, "usb", defaults.hubUsb()),
                camera == null
                        ? at
                        : new CameraMount(
                                number(camera, "x", at.x()), number(camera, "y", at.y()), number(camera, "z", at.z()),
                                number(camera, "yawDegrees", at.yawDegrees()), number(camera, "pitchDegrees", at.pitchDegrees()),
                                number(camera, "rollDegrees", at.rollDegrees())),
                turretPartsOf(root.get("turret")));
    }

    private static TurretParts turretPartsOf(JsonNode turret) {
        if (turret == null) {
            return TurretParts.NONE;
        }
        if (!turret.isObject()) {
            throw new IllegalArgumentException("turret is not an object");
        }
        noOtherKeys(turret, TURRET_KEYS, "turret");
        ServoMapping servo = null;
        JsonNode servoNode = object(turret, "servo", SERVO_KEYS);
        if (servoNode != null) {
            String name = text(servoNode, "name", null);
            if (name == null) {
                throw new IllegalArgumentException("the turret servo has no name");
            }
            servo = new ServoMapping(name, text(servoNode, "model", ""), flag(servoNode, "mirrored", "the turret servo"));
        }
        List<TurretEncoder> encoders = new ArrayList<>();
        JsonNode encodersNode = turret.get("encoders");
        if (encodersNode != null) {
            if (!encodersNode.isArray()) {
                throw new IllegalArgumentException("turret encoders is not a list");
            }
            for (JsonNode node : encodersNode) {
                if (!node.isObject()) {
                    throw new IllegalArgumentException("a turret encoder is not an object");
                }
                noOtherKeys(node, ENCODER_KEYS, "a turret encoder");
                JsonNode channel = node.get("channel");
                if (channel == null || !channel.isInt()) {
                    throw new IllegalArgumentException("a turret encoder needs a whole-number channel");
                }
                JsonNode turns = node.get("turnsPerTurretTurn");
                if (turns == null) {
                    throw new IllegalArgumentException("turret encoder " + channel.asInt() + " has no turnsPerTurretTurn");
                }
                encoders.add(new TurretEncoder(
                        channel.asInt(), text(node, "model", ""), number(node, "turnsPerTurretTurn", 0.0),
                        number(node, "zeroOffsetDegrees", 0.0), flag(node, "mirrored", "turret encoder " + channel.asInt())));
            }
        }
        JsonNode axis = object(turret, "axis", AXIS_KEYS);
        return new TurretParts(
                servo, encoders, axis == null ? 0.0 : number(axis, "x", 0.0), axis == null ? 0.0 : number(axis, "y", 0.0),
                flag(turret, "cameraOnTurret", "turret"));
    }

    private static boolean flag(JsonNode node, String key, String where) {
        JsonNode value = node.get(key);
        if (value == null) {
            return false;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(where + ": " + key + " is not true or false");
        }
        return value.asBoolean();
    }

    private static MotorMapping motorOf(String name, JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("motor " + name + " is not an object");
        }
        noOtherKeys(node, MOTOR_KEYS, "motor " + name);
        String slot = text(node, "slot", null);
        if (slot == null) {
            throw new IllegalArgumentException("motor " + name + " has no slot");
        }
        JsonNode mirrored = node.get("mirrored");
        if (mirrored != null && !mirrored.isBoolean()) {
            throw new IllegalArgumentException("motor " + name + ": mirrored is not true or false");
        }
        try {
            return new MotorMapping(name, Slot.valueOf(slot), text(node, "model", ""), mirrored != null && mirrored.asBoolean());
        } catch (IllegalArgumentException unknownSlot) {
            throw new IllegalArgumentException("motor " + name + " has the unknown slot " + slot);
        }
    }

    private static JsonNode object(JsonNode parent, String key, Set<String> keys) {
        JsonNode node = parent.get(key);
        if (node == null) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(key + " is not an object");
        }
        noOtherKeys(node, keys, key);
        return node;
    }

    private static void noOtherKeys(JsonNode node, Set<String> allowed, String where) {
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!allowed.contains(entry.getKey())) {
                throw new IllegalArgumentException(where + " has the unknown key " + entry.getKey());
            }
        }
    }

    private static String text(JsonNode node, String key, String otherwise) {
        JsonNode value = node.get(key);
        if (value == null) {
            return otherwise;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(key + " is not text");
        }
        return value.asText();
    }

    private static double number(JsonNode node, String key, double otherwise) {
        JsonNode value = node.get(key);
        if (value == null) {
            return otherwise;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException(key + " is not a number");
        }
        return value.asDouble();
    }

    /** What the file says about how a run starts. */
    private static RunDefaults runOf(JsonNode run) {
        if (run == null) {
            return RunDefaults.NONE;
        }
        if (!run.isObject()) {
            throw new IllegalArgumentException("run is not an object");
        }
        noOtherKeys(run, RUN_KEYS, "run");
        return new RunDefaults(blankToNull(text(run, "field", null)), alliance(blankToNull(text(run, "alliance", null))), blankToNull(text(run, "startPose", null)));
    }

    /** The start positions the team named; each needs a name, a field and three finite numbers, and a field's names are unique. */
    private static List<StartPosition> positionsOf(JsonNode positions) {
        if (positions == null) {
            return List.of();
        }
        if (!positions.isArray()) {
            throw new IllegalArgumentException("startPositions is not a list");
        }
        List<StartPosition> read = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode node : positions) {
            if (!node.isObject()) {
                throw new IllegalArgumentException("a start position is not an object");
            }
            noOtherKeys(node, POSITION_KEYS, "a start position");
            String name = blankToNull(text(node, "name", null));
            String field = blankToNull(text(node, "field", null));
            if (name == null || field == null) {
                throw new IllegalArgumentException("a start position needs a name and a field");
            }
            if (!seen.add(field + "\n" + name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("the field " + field + " has two start positions called " + name);
            }
            read.add(new StartPosition(
                    name, field, required(node, "x", name), required(node, "y", name), required(node, "headingDegrees", name)));
        }
        return read;
    }

    private static double required(JsonNode node, String key, String position) {
        if (!node.has(key)) {
            throw new IllegalArgumentException("the start position " + position + " has no " + key);
        }
        return number(node, key, 0.0);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static Alliance alliance(String name) {
        if (name == null) {
            return null;
        }
        for (Alliance alliance : Alliance.values()) {
            if (alliance.name().equals(name.toUpperCase(Locale.ROOT))) {
                return alliance;
            }
        }
        throw new IllegalArgumentException("run has the unknown alliance " + name);
    }

    // Writing ---------------------------------------------------------------------------------------------------

    private static String textOf(RobotMapping mapping, RunDefaults run, List<StartPosition> positions) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("name", mapping.name());
        Map<String, Object> motors = new LinkedHashMap<>();
        for (MotorMapping motor : mapping.motors()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slot", motor.slot().name());
            entry.put("model", motor.model());
            entry.put("mirrored", motor.mirrored());
            motors.put(motor.name(), entry);
        }
        root.put("motors", motors);
        root.put("massKg", finite(mapping.massKg()));
        root.put("wheelDiameterMm", finite(mapping.wheelDiameterMm()));
        root.put("driveMotor", mapping.driveMotor());
        root.put("turretRatio", finite(mapping.turretRatio()));
        root.put("hub", Map.of("logo", mapping.hubLogo(), "usb", mapping.hubUsb()));
        CameraMount camera = mapping.camera();
        Map<String, Object> mount = new LinkedHashMap<>();
        mount.put("x", finite(camera.x()));
        mount.put("y", finite(camera.y()));
        mount.put("z", finite(camera.z()));
        mount.put("yawDegrees", finite(camera.yawDegrees()));
        mount.put("pitchDegrees", finite(camera.pitchDegrees()));
        mount.put("rollDegrees", finite(camera.rollDegrees()));
        root.put("camera", mount);
        TurretParts parts = mapping.turretParts();
        if (parts.mapped()) {
            Map<String, Object> turret = new LinkedHashMap<>();
            if (parts.servo() != null) {
                Map<String, Object> servo = new LinkedHashMap<>();
                servo.put("name", parts.servo().name());
                servo.put("model", parts.servo().model());
                servo.put("mirrored", parts.servo().mirrored());
                turret.put("servo", servo);
            }
            if (!parts.encoders().isEmpty()) {
                List<Map<String, Object>> encoders = new ArrayList<>();
                for (TurretEncoder encoder : parts.encoders()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("channel", encoder.channel());
                    entry.put("model", encoder.model());
                    entry.put("turnsPerTurretTurn", finite(encoder.turnsPerTurretTurn()));
                    entry.put("zeroOffsetDegrees", finite(encoder.zeroOffsetDegrees()));
                    entry.put("mirrored", encoder.mirrored());
                    encoders.add(entry);
                }
                turret.put("encoders", encoders);
            }
            Map<String, Object> axis = new LinkedHashMap<>();
            axis.put("x", finite(parts.axisX()));
            axis.put("y", finite(parts.axisY()));
            turret.put("axis", axis);
            turret.put("cameraOnTurret", parts.cameraOnTurret());
            root.put("turret", turret);
        }
        Map<String, Object> runEntry = new LinkedHashMap<>();
        if (run.field() != null) {
            runEntry.put("field", run.field());
        }
        if (run.alliance() != null) {
            runEntry.put("alliance", run.alliance().name());
        }
        if (run.startPose() != null) {
            runEntry.put("startPose", run.startPose());
        }
        if (!runEntry.isEmpty()) {
            root.put("run", runEntry);
        }
        if (!positions.isEmpty()) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (StartPosition position : positions) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", position.name());
                entry.put("field", position.field());
                entry.put("x", finite(position.x()));
                entry.put("y", finite(position.y()));
                entry.put("headingDegrees", finite(position.headingDegrees()));
                entries.add(entry);
            }
            root.put("startPositions", entries);
        }
        return MAPPER.writer(PRINTER).writeValueAsString(root) + "\n";
    }

    private static double finite(double value) throws IOException {
        if (!Double.isFinite(value)) {
            throw new IOException("A number of the robot is not finite, so " + FILE + " is not written.");
        }
        return value;
    }
}
