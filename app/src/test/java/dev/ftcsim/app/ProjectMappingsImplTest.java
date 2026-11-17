package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.CameraMount;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.ui.host.ProjectMappings;
import dev.ftcsim.ui.host.RunDefaults;
import dev.ftcsim.ui.host.StartPosition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectMappingsImplTest {

    @TempDir Path temp;
    private AppFolders folders;
    private ProjectMappingsImpl mappings;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        folders = new AppFolders(temp.resolve("app"));
        mappings = new ProjectMappingsImpl(folders);
        project = Files.createDirectories(temp.resolve("TeamProject"));
    }

    static RobotMapping robot() {
        return new RobotMapping(
                "Team robot",
                List.of(
                        new MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
                        new MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
                        new MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
                        new MotorMapping("backRight", Slot.REAR_RIGHT, "", false),
                        new MotorMapping("shooter", Slot.SHOOTER, "goBILDA-5203-1150", false),
                        new MotorMapping("belt", Slot.CONVEYOR, "", true),
                        new MotorMapping("aim", Slot.TURRET, "", false)),
                10.5,
                104,
                "goBILDA-5203-312",
                3.5,
                "LEFT",
                "UP",
                new CameraMount(0.1, -0.05, 0.3, 10, 25, -5));
    }

    private JsonNode read(Path file) throws IOException {
        return JsonMapper.builder().build().readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    private Path write(String text) throws IOException {
        return Files.writeString(project.resolve("ftcsim.json"), text);
    }

    @Test
    void aProjectWithoutTheFileHasNoMappingAndSavingWritesItAtTheRootAsSchemaTwo() throws IOException {
        assertTrue(mappings.find(project).isEmpty());

        ProjectMappings.Saved saved = mappings.save(project, robot());

        assertEquals(project.resolve("ftcsim.json"), saved.file());
        assertFalse(saved.keptByApp());
        JsonNode json = read(saved.file());
        assertEquals(2, json.get("schema").asInt());
        assertEquals("Team robot", json.get("name").asText());
        assertEquals("FRONT_LEFT", json.at("/motors/frontLeft/slot").asText());
        assertTrue(json.at("/motors/frontLeft/mirrored").asBoolean());
        assertEquals("goBILDA-5203-1150", json.at("/motors/shooter/model").asText());
        assertEquals(10.5, json.get("massKg").asDouble());
        assertEquals(104, json.get("wheelDiameterMm").asDouble());
        assertEquals("goBILDA-5203-312", json.get("driveMotor").asText());
        assertEquals(3.5, json.get("turretRatio").asDouble());
        assertEquals("LEFT", json.at("/hub/logo").asText());
        assertEquals("UP", json.at("/hub/usb").asText());
        assertEquals(25, json.at("/camera/pitchDegrees").asDouble());
        assertFalse(json.has("run"), "a file that was never given a run has none");
        String text = Files.readString(saved.file(), StandardCharsets.UTF_8);
        assertTrue(text.contains("\n  \"schema\": 2"), text);
        assertTrue(text.endsWith("}\n"));

        ProjectMappings.Found found = mappings.find(project).orElseThrow();
        assertEquals(robot(), found.mapping(), "every motor, in the order it was mapped, and every number");
        assertTrue(found.usable());
        assertNull(found.problem());
        assertEquals(RunDefaults.NONE, found.run());
    }

    @Test
    void everyKeyButTheSchemaIsOptionalAndTakesTheUsualValue() throws IOException {
        write("{\"schema\":2}");

        ProjectMappings.Found found = mappings.find(project).orElseThrow();

        assertEquals(RobotMapping.empty("TeamProject"), found.mapping());
        assertEquals(RobotMapping.empty("x").hubLogo(), found.mapping().hubLogo());
    }

    @Test
    void aKeyThatIsNotPartOfTheFileIsRefusedAtEveryLevel() throws IOException {
        for (String text : List.of(
                "{\"schema\":2,\"chassis\":{}}",
                "{\"schema\":2,\"motors\":{\"a\":{\"slot\":\"INTAKE\",\"port\":0}}}",
                "{\"schema\":2,\"camera\":{\"fov\":60}}",
                "{\"schema\":2,\"hub\":{\"up\":1}}",
                "{\"schema\":2,\"run\":{\"speed\":2}}")) {
            write(text);

            ProjectMappings.Found found = mappings.find(project).orElseThrow();

            assertNull(found.mapping(), text);
            assertTrue(found.problem().contains("unknown key"), found.problem());
        }
    }

    @Test
    void aMotorOfNoSuchSlotOrAWrongTypeIsAProblemAndNotAnExceptionOrASilentDefault() throws IOException {
        for (String text : List.of(
                "{\"schema\":2,\"motors\":{\"a\":{\"slot\":\"ARM\"}}}",
                "{\"schema\":2,\"motors\":{\"a\":{\"model\":\"\"}}}",
                "{\"schema\":2,\"motors\":{\"a\":{\"slot\":\"INTAKE\",\"mirrored\":\"yes\"}}}",
                "{\"schema\":2,\"massKg\":\"heavy\"}",
                "{\"schema\":2,\"motors\":{\"a\":{\"slot\":\"INTAKE\"},\"a\":{\"slot\":\"SHOOTER\"}}}")) {
            write(text);

            ProjectMappings.Found found = mappings.find(project).orElseThrow();

            assertFalse(found.usable(), text);
            assertTrue(found.problem().startsWith("ftcsim.json cannot be read: "), found.problem());
        }
    }

    @Test
    void theTurretsServoEncodersAxisAndCameraRoundTripAndAFileWithoutThemHasNone() throws IOException {
        RobotMapping.TurretParts parts = new RobotMapping.TurretParts(
                new RobotMapping.ServoMapping("turretServo", "axon-mini-plus", true),
                List.of(
                        new RobotMapping.TurretEncoder(0, "rev-tbe-v2", 5.5, 12.5, false),
                        new RobotMapping.TurretEncoder(1, "", 44.0 / 7.0, -3.0, true)),
                0.05, -0.02, true);
        RobotMapping withParts = robot().withTurretParts(parts);

        ProjectMappings.Saved saved = mappings.save(project, withParts);

        JsonNode json = read(saved.file());
        assertEquals("turretServo", json.at("/turret/servo/name").asText());
        assertTrue(json.at("/turret/servo/mirrored").asBoolean());
        assertEquals(1, json.at("/turret/encoders/1/channel").asInt());
        assertEquals(5.5, json.at("/turret/encoders/0/turnsPerTurretTurn").asDouble());
        assertEquals(-0.02, json.at("/turret/axis/y").asDouble());
        assertTrue(json.at("/turret/cameraOnTurret").asBoolean());
        assertEquals(withParts, mappings.find(project).orElseThrow().mapping());

        mappings.save(project, robot());
        assertFalse(read(saved.file()).has("turret"), "a robot with nothing on its turret writes no turret key");
        assertEquals(RobotMapping.TurretParts.NONE, mappings.find(project).orElseThrow().mapping().turretParts());
    }

    @Test
    void aTurretKeyThatIsNotPartOfTheFileOrANonsenseEncoderIsAProblem() throws IOException {
        for (String text : List.of(
                "{\"schema\":2,\"turret\":{\"gears\":3}}",
                "{\"schema\":2,\"turret\":{\"servo\":{\"model\":\"axon-mini-plus\"}}}",
                "{\"schema\":2,\"turret\":{\"encoders\":[{\"channel\":0}]}}",
                "{\"schema\":2,\"turret\":{\"encoders\":[{\"channel\":0.5,\"turnsPerTurretTurn\":5}]}}",
                "{\"schema\":2,\"turret\":{\"cameraOnTurret\":\"yes\"}}")) {
            write(text);

            ProjectMappings.Found found = mappings.find(project).orElseThrow();

            assertFalse(found.usable(), text);
            assertTrue(found.problem().startsWith("ftcsim.json cannot be read: "), found.problem());
        }
    }

    @Test
    void aSchemaOneFileIsFoundAsAnOlderVersionAndSavingKeepsItAsFtcsimOldJson() throws IOException {
        String old = "{\"schema\":1,\"name\":\"Hornet\",\"motors\":{\"frontLeft\":{\"port\":0}},\"run\":{\"field\":\"decode\"}}";
        write(old);

        ProjectMappings.Found found = mappings.find(project).orElseThrow();
        assertNull(found.mapping());
        assertEquals("ftcsim.json is from an older version", found.problem());
        assertEquals(RunDefaults.NONE, found.run());
        assertThrows(IOException.class, () -> mappings.saveRunDefaults(project, new RunDefaults("decode", Alliance.RED, null)));
        assertEquals(old, Files.readString(project.resolve("ftcsim.json")), "nothing is written before the person saves a mapping");

        mappings.save(project, robot());

        assertEquals(old, Files.readString(project.resolve("ftcsim.old.json")));
        assertEquals(robot(), mappings.find(project).orElseThrow().mapping());
    }

    @Test
    void aFileThatDoesNotParseIsNeverOverwrittenWithoutACopy() throws IOException {
        write("{ this is not json");

        ProjectMappings.Found found = mappings.find(project).orElseThrow();
        assertFalse(found.usable());
        assertTrue(found.problem().startsWith("ftcsim.json cannot be read"), found.problem());

        mappings.save(project, robot());

        assertEquals("{ this is not json", Files.readString(project.resolve("ftcsim.old.json")));
        assertTrue(mappings.find(project).orElseThrow().usable());
    }

    @Test
    void aFileFromANewerVersionIsNotReadEither() throws IOException {
        write("{\"schema\":3}");

        assertEquals("ftcsim.json is from a newer version", mappings.find(project).orElseThrow().problem());
    }

    @Test
    void theRunBlockIsReadAndWrittenAndEachSaveKeepsTheOtherPart() throws IOException {
        write("{\"schema\":2,\"run\":{\"field\":\"decode\",\"alliance\":\"blue\",\"startPose\":\"BLUE start\"}}");
        assertEquals(new RunDefaults("decode", Alliance.BLUE, "BLUE start"), mappings.find(project).orElseThrow().run());

        mappings.save(project, robot());
        assertEquals(new RunDefaults("decode", Alliance.BLUE, "BLUE start"), mappings.find(project).orElseThrow().run(), "saving the robot keeps the run");

        mappings.saveRunDefaults(project, new RunDefaults("biobuzz", Alliance.RED, null));
        ProjectMappings.Found found = mappings.find(project).orElseThrow();
        assertEquals(new RunDefaults("biobuzz", Alliance.RED, null), found.run());
        assertEquals(robot(), found.mapping(), "saving the run keeps the robot");
        assertEquals("RED", read(project.resolve("ftcsim.json")).at("/run/alliance").asText());
    }

    @Test
    void theStartPositionsAreReadAndWrittenInTheFileAndEverySaveKeepsThem() throws IOException {
        write("{\"schema\":2,\"startPositions\":[{\"name\":\"Near goal\",\"field\":\"decode\",\"x\":-1.2,\"y\":0.5,\"headingDegrees\":135}]}");
        List<StartPosition> near = List.of(new StartPosition("Near goal", "decode", -1.2, 0.5, 135));
        assertEquals(near, mappings.find(project).orElseThrow().startPositions());

        mappings.save(project, robot());
        mappings.saveRunDefaults(project, new RunDefaults("decode", Alliance.RED, null));
        assertEquals(near, mappings.find(project).orElseThrow().startPositions(), "saving the robot or the run keeps them");

        List<StartPosition> both = List.of(near.get(0), new StartPosition("Far", "decode", 1.0, -3.0, 90));
        mappings.saveStartPositions(project, both);
        ProjectMappings.Found found = mappings.find(project).orElseThrow();
        assertEquals(both, found.startPositions());
        assertEquals(robot(), found.mapping(), "saving the positions keeps the robot");
        assertEquals(new RunDefaults("decode", Alliance.RED, null), found.run());
        assertEquals("Far", read(project.resolve("ftcsim.json")).at("/startPositions/1/name").asText());

        mappings.saveStartPositions(project, List.of());
        assertFalse(read(project.resolve("ftcsim.json")).has("startPositions"), "no positions, no key");
    }

    @Test
    void startPositionsThatCannotBeReadMakeTheFileUnusableWithTheReason() throws IOException {
        write("{\"schema\":2,\"startPositions\":[{\"name\":\"A\",\"field\":\"decode\",\"x\":0,\"y\":0}]}");
        assertEquals("ftcsim.json cannot be read: the start position A has no headingDegrees", mappings.find(project).orElseThrow().problem());

        write("{\"schema\":2,\"startPositions\":[{\"name\":\"A\",\"field\":\"decode\",\"x\":0,\"y\":0,\"headingDegrees\":0},"
                + "{\"name\":\"a\",\"field\":\"decode\",\"x\":1,\"y\":0,\"headingDegrees\":0}]}");
        assertEquals("ftcsim.json cannot be read: the field decode has two start positions called a", mappings.find(project).orElseThrow().problem());
    }

    @Test
    void savingStartPositionsNeedsAFile() {
        assertThrows(IOException.class, () -> mappings.saveStartPositions(project, List.of(new StartPosition("A", "decode", 0, 0, 0))));
        assertFalse(Files.exists(project.resolve("ftcsim.json")));
    }

    @Test
    void savingTheRunDefaultsNeedsAFile() {
        assertThrows(IOException.class, () -> mappings.saveRunDefaults(project, new RunDefaults("decode", Alliance.RED, null)));
        assertFalse(Files.exists(project.resolve("ftcsim.json")));
    }

    @Test
    void aProjectFolderThatCannotBeWrittenToGetsACopyInTheApplicationsFoldersWhichIsFoundAsIfItWereInTheProject() throws IOException {
        Path gone = temp.resolve("not-there");

        ProjectMappings.Saved saved = mappings.save(gone, robot());

        assertTrue(saved.keptByApp());
        assertEquals(folders.keptProfile(gone), saved.file());
        ProjectMappings.Found found = mappings.find(gone).orElseThrow();
        assertTrue(found.keptByApp());
        assertEquals(saved.file(), found.file());
        assertEquals(robot(), found.mapping());

        mappings.saveRunDefaults(gone, new RunDefaults("decode", Alliance.RED, null));
        ProjectMappings.Saved again = mappings.save(gone, robot().withMotors(List.of()));
        assertEquals(saved.file(), again.file(), "the kept copy is updated where it is");
        assertEquals(new RunDefaults("decode", Alliance.RED, null), mappings.find(gone).orElseThrow().run());
    }

    @Test
    void aNumberThatIsNotFiniteIsNotWritten() {
        RobotMapping broken = new RobotMapping(
                "x", List.of(), Double.NaN, 96, "goBILDA-5203-435", 1, "UP", "FORWARD", RobotMapping.empty("x").camera());

        assertThrows(IOException.class, () -> mappings.save(project, broken));
        assertFalse(Files.exists(project.resolve("ftcsim.json")));
    }
}
