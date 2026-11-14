package dev.ftcsim.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.contracts.field.Alliance;
import dev.ftcsim.contracts.field.FieldDescription;
import dev.ftcsim.contracts.field.HumanAction;
import dev.ftcsim.contracts.field.StartPose;
import dev.ftcsim.contracts.session.Catalogue;
import dev.ftcsim.contracts.session.FieldChoice;
import dev.ftcsim.contracts.session.RobotInfo;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.field.Field;
import dev.ftcsim.field.FieldRun;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.simulator.SessionConfig;
import dev.ftcsim.simulator.profile.ProfileException;
import dev.ftcsim.simulator.profile.RobotProfile;
import dev.ftcsim.simulator.profile.RobotProfileLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionFactoryTest {

    @TempDir
    Path folder;

    private static final class FakeField implements Field {
        @Override
        public FieldDescription description() {
            return new FieldDescription(
                    "fake", "Fake", List.of(), List.of(), List.of(), List.of(new StartPose("origin", 0, 0, 0)),
                    List.of(new HumanAction("feed", "Feed")));
        }

        @Override
        public Map<Integer, PieceSpec> pieceSpecs() {
            return Map.of();
        }

        @Override
        public FieldRun start(PhysicsWorld world, FieldSetup setup) {
            throw new UnsupportedOperationException();
        }
    }

    private SessionFactory factory(FieldRegistry fields) {
        return new SessionFactory(List.of(new DirectoryProgramResolver()), fields, folder.resolve("natives"), line -> {});
    }

    private Path program() throws IOException {
        Path classes = Files.createDirectories(folder.resolve("classes"));
        Files.writeString(folder.resolve("program.properties"), "robot=" + classes + "\nprogram=" + classes + "\n");
        return folder;
    }

    private SessionRequest request(String profile, String program, String field) {
        return new SessionRequest(profile, program, field, Alliance.BLUE, 99L, 1.5, -2.0, 0.5, 2.0);
    }

    /** A profile that ships with the simulator, whichever it ships first. */
    private static String bundled() {
        return RobotProfileLoader.bundledNames().get(0);
    }

    @Test
    void aRequestBecomesTheConfigurationTheSimulatorRuns() throws IOException {
        Path storage = folder.resolve("sdcard");

        SessionConfig config = factory(new FieldRegistry()).config(request(bundled(), program().toString(), "none"), storage);

        assertEquals(RobotProfileLoader.loadBundled(bundled()).name(), config.profile().name());
        assertEquals(List.of(folder.resolve("classes")), config.program().robotClasspath());
        assertNull(config.field());
        assertEquals(new FieldSetup(99L, Alliance.BLUE), config.fieldSetup());
        assertEquals(1.5, config.startX());
        assertEquals(-2.0, config.startY());
        assertEquals(0.5, config.startHeading());
        assertEquals(storage, config.storageRoot());
        assertEquals(folder.resolve("natives"), config.nativeDirectory());
    }

    @Test
    void aFieldIdBecomesAFreshFieldEachTimeItIsAsked() throws IOException {
        FieldRegistry fields = new FieldRegistry().register("fake", FakeField::new);
        SessionFactory factory = factory(fields);
        String program = program().toString();

        Field first = factory.config(request(bundled(), program, "fake"), folder).field();
        Field second = factory.config(request(bundled(), program, "fake"), folder).field();

        assertEquals("fake", first.description().id());
        assertNotSame(first, second);
    }

    @Test
    void theCatalogueListsTheBareFloorFirstThenEachFieldWithWhatItOffersAndTheBundledProfiles() {
        Catalogue catalogue = factory(new FieldRegistry().register("fake", FakeField::new)).catalogue();

        assertEquals(
                List.of(
                        new FieldChoice("none", "Bare floor", List.of(), List.of()),
                        new FieldChoice("fake", "Fake", List.of(new StartPose("origin", 0, 0, 0)), List.of(new HumanAction("feed", "Feed")))),
                catalogue.fields());
        assertEquals(RobotProfileLoader.bundledNames(), catalogue.profiles());
        assertFalse(catalogue.profiles().isEmpty(), "the simulator ships at least one robot profile");
    }

    @Test
    void aNameWithoutADirectoryIsABundledProfileAndAnythingElseIsTheFileItNames() throws IOException {
        Path file = folder.resolve("my robot.json");
        try (InputStream bundled = SessionFactoryTest.class.getResourceAsStream("/dev/ftcsim/simulator/profiles/" + bundled())) {
            Files.copy(bundled, file);
        }
        SessionFactory factory = factory(new FieldRegistry());

        RobotProfile fromFile = factory.profile(file.toString());

        assertEquals(factory.profile(bundled()), fromFile);
    }

    @Test
    void whatCannotBeFoundIsSaidInTheMessageWithWhatWasLookedFor() throws IOException {
        SessionFactory factory = factory(new FieldRegistry().register("fake", FakeField::new));
        String program = program().toString();
        Path empty = Files.createDirectories(folder.resolve("empty"));

        assertTrue(assertThrows(ProfileException.class, () -> factory.profile("nobody.json")).getMessage().contains("no bundled robot profile 'nobody.json'"));
        String missingFile = folder.resolve("missing.json").toString();
        assertTrue(assertThrows(IOException.class, () -> factory.profile(missingFile)).getMessage().contains("missing.json does not exist"));
        assertTrue(assertThrows(IOException.class, () -> factory.profile(" ")).getMessage().contains("no robot profile"));
        assertTrue(assertThrows(IOException.class, () -> factory.profile("C:\\a<b>.json")).getMessage().contains("not a valid path"));

        String missingProgram = folder.resolve("gone").toString();
        assertTrue(assertThrows(IOException.class, () -> factory.program(missingProgram)).getMessage().contains("gone does not exist"));
        String notAProgram = empty.toString();
        String layoutMessage = assertThrows(IOException.class, () -> factory.program(notAProgram)).getMessage();
        assertTrue(layoutMessage.contains("is not a prepared robot program") && layoutMessage.contains("program.properties"), layoutMessage);

        String fieldMessage =
                assertThrows(IOException.class, () -> factory.config(request(bundled(), program, "decode"), folder)).getMessage();
        assertTrue(fieldMessage.contains("no field 'decode'") && fieldMessage.contains("none, fake"), fieldMessage);
    }

    @Test
    void theRobotIsDescribedByItsProfileWithItsHardwareInTheOrderOfTheProfile() {
        RobotProfile driving = RobotProfileLoader.parse(
                """
                {"schema": 1, "name": "Driver", "chassis": {"lengthM": 0.5, "widthM": 0.4, "heightM": 0.3},
                 "motors": {
                   "fl": {"port": 0, "wheel": {"position": "FRONT_LEFT", "polarity": -1}},
                   "bl": {"port": 1, "wheel": {"position": "REAR_LEFT", "polarity": -1}},
                   "fr": {"port": 2, "wheel": {"position": "FRONT_RIGHT", "polarity": 1}},
                   "br": {"port": 3, "wheel": {"position": "REAR_RIGHT", "polarity": 1}}},
                 "servos": {"hood": {"port": 0}, "claw": {"port": 1}},
                 "devices": {"odometry": {"type": "gobilda-pinpoint", "bus": 1}}}
                """,
                "test");
        RobotProfile arm = RobotProfileLoader.parse(
                """
                {"schema": 1, "name": "Arm only", "motors": {"arm": {"port": 0, "shaft": {"loadInertiaKgM2": 0.001}}}}
                """,
                "test");

        RobotInfo info = SessionFactory.robotInfo(driving);

        assertEquals(new RobotInfo("Driver", 0.5, 0.4, 0.3, List.of("fl", "bl", "fr", "br"), List.of("hood", "claw"), List.of("odometry")), info);
        assertEquals(
                new RobotInfo("Arm only", 0, 0, 0, List.of("arm"), List.of(), List.of()),
                SessionFactory.robotInfo(arm),
                "a robot that does not drive has no body to draw");
    }
}
