package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.app.AppFolders;
import dev.ftcsim.app.ProjectMappingsImpl;
import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.session.MotorChoice;
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.simulator.robot.MotorCatalog;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import dev.ftcsim.ui.host.ProjectMappings;
import dev.ftcsim.ui.host.RunDefaults;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mapping that the application writes for a team and the simulator, held against each other: what the window saves is
 * read back whole, built into a robot by the fixed design of the game and opened in a session. The application cannot see
 * the simulator's design (module rules), so this is the one place the two are held against each other.
 */
@Timeout(600)
class ProjectMappingsAcceptanceTest {
    @TempDir
    Path temp;

    private ProjectMappingsImpl mappings;
    private int projects;

    @BeforeEach
    void setUp() {
        mappings = new ProjectMappingsImpl(new AppFolders(temp.resolve("app")));
    }

    private Path project() throws IOException {
        return Files.createDirectories(temp.resolve("project-" + projects++));
    }

    /** Stores {@code mapping} as a project's file and reads it back, as a run of that project does. */
    private RobotMapping stored(RobotMapping mapping) throws IOException {
        Path project = project();
        mappings.save(project, mapping);
        ProjectMappings.Found found = mappings.find(project).orElseThrow();
        assertTrue(found.usable(), String.valueOf(found.problem()));
        return found.mapping();
    }

    /** Opens a session on the robot that {@code design} builds from {@code mapping}, which is what the worker does for a request. */
    private static RobotSpec opened(RobotDesign design, RobotMapping mapping) throws IOException {
        assertEquals(List.of(), mapping.problems(design.turret()));
        RobotSpec profile = design.spec(mapping);
        try (TestSessions.Handle handle = TestSessions.open(profile, 0, 0, 0)) {
            assertNotNull(handle.session.rig(), mapping.name());
        }
        return profile;
    }

    @Test
    void theHornetMappingStoredByTheApplicationComesBackWholeIsBuiltByTheFixedRobotAndOpensASession() throws IOException {
        RobotMapping back = stored(HornetMapping.mapping());

        assertEquals(HornetMapping.mapping(), back, "every motor in the order it was mapped, and every number");
        RobotSpec profile = opened(RobotDesign.forField("decode"), back);
        assertEquals(HornetMapping.NAME, profile.name());
        assertEquals(
                HornetMapping.mapping().motors().stream().map(MotorMapping::name).toList(),
                new ArrayList<>(profile.motors().keySet()));
    }

    @Test
    void everyMotorTheSimulatorOffersOnTheWheelsAndOnTheMechanismsIsStoredBuiltAndOpened() throws IOException {
        List<MotorChoice> choices = MotorCatalog.choices();
        assertFalse(choices.isEmpty());
        for (MotorChoice choice : choices) {
            RobotMapping mapping = new RobotMapping(
                    "Team robot",
                    List.of(
                            new MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
                            new MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
                            new MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
                            new MotorMapping("backRight", Slot.REAR_RIGHT, "", false),
                            new MotorMapping("roller", Slot.INTAKE, choice.id(), false),
                            new MotorMapping("belt", Slot.CONVEYOR, choice.id(), true),
                            new MotorMapping("flywheel", Slot.SHOOTER, choice.id(), false)),
                    10.0,
                    104,
                    choice.id(),
                    RobotMapping.empty("x").turretRatio(),
                    "UP",
                    "FORWARD",
                    RobotMapping.empty("x").camera());

            RobotSpec profile = opened(RobotDesign.decode(), stored(mapping));

            assertEquals(choice.id(), profile.motors().get("flywheel").model());
            assertEquals(MotorCatalog.motor(choice.id(), RobotMapping.DEFAULT_DRIVE_RATIO), profile.chassis().motor(), "the wheels turn on the drive motor");
        }
    }

    @Test
    void aBiobuzzMappingWithATurretIsStoredBuiltAndOpenedAndTheTurretTurnsOnTheRatioItWasGiven() throws IOException {
        RobotMapping mapping = new RobotMapping(
                "Bee",
                List.of(
                        new MotorMapping("frontLeft", Slot.FRONT_LEFT, "", true),
                        new MotorMapping("backLeft", Slot.REAR_LEFT, "", true),
                        new MotorMapping("frontRight", Slot.FRONT_RIGHT, "", false),
                        new MotorMapping("backRight", Slot.REAR_RIGHT, "", false),
                        new MotorMapping("aim", Slot.TURRET, "", false)),
                11.3, 96, "goBILDA-bare-6000", 3.5, "UP", "FORWARD", RobotMapping.empty("x").camera());

        RobotSpec profile = opened(RobotDesign.forField("biobuzz"), stored(mapping));

        assertNotNull(profile.motors().get("aim").shaft(), "the turret motor is a shaft");
        assertTrue(RobotDesign.forField("biobuzz").turret());
        assertFalse(mapping.problems(RobotDesign.forField("decode").turret()).isEmpty(), "DECODE's robot has no turret");
    }

    @Test
    void aSchemaOneFileIsRefusedNotReadAsAMappingAndKeptWhenTheMappingIsSavedOverIt() throws IOException {
        Path project = project();
        String old = "{\"schema\":1,\"name\":\"Hornet 2026\",\"motors\":{\"frontLeft\":{\"port\":0,\"wheel\":{\"position\":\"FRONT_LEFT\",\"polarity\":-1}}},"
                + "\"run\":{\"field\":\"decode\"}}\n";
        Files.writeString(project.resolve("ftcsim.json"), old, StandardCharsets.UTF_8);

        ProjectMappings.Found found = mappings.find(project).orElseThrow();
        assertNull(found.mapping());
        assertEquals("ftcsim.json is from an older version", found.problem());

        mappings.save(project, HornetMapping.mapping());

        assertEquals(old, Files.readString(project.resolve("ftcsim.old.json"), StandardCharsets.UTF_8), "the old file is kept");
        ProjectMappings.Found now = mappings.find(project).orElseThrow();
        assertEquals(HornetMapping.mapping(), now.mapping());
        opened(RobotDesign.decode(), now.mapping());
    }

    @Test
    void theRunDefaultsSurviveSavingTheMappingAndTheMappingSurvivesSavingTheRunDefaults() throws IOException {
        Path project = project();
        mappings.save(project, HornetMapping.mapping());

        mappings.saveRunDefaults(project, new RunDefaults("decode", Alliance.BLUE, "BLUE goal side"));
        RobotMapping changed = HornetMapping.mapping().withMotors(HornetMapping.mapping().motors().subList(0, 4));
        mappings.save(project, changed);

        ProjectMappings.Found found = mappings.find(project).orElseThrow();
        assertEquals(changed, found.mapping());
        assertEquals(new RunDefaults("decode", Alliance.BLUE, "BLUE goal side"), found.run());
    }
}
