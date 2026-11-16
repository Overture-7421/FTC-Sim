package dev.ftcsim.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import dev.ftcsim.contracts.session.RobotMapping;
import dev.ftcsim.contracts.session.RobotMapping.MotorMapping;
import dev.ftcsim.contracts.session.RobotMapping.Slot;
import dev.ftcsim.contracts.session.SessionRequest;
import dev.ftcsim.field.Field;
import dev.ftcsim.field.FieldRun;
import dev.ftcsim.field.FieldSetup;
import dev.ftcsim.physics.MecanumConfig;
import dev.ftcsim.physics.PhysicsWorld;
import dev.ftcsim.physics.PieceSpec;
import dev.ftcsim.simulator.SessionConfig;
import dev.ftcsim.simulator.robot.MotorCatalog;
import dev.ftcsim.simulator.robot.RobotDesign;
import dev.ftcsim.simulator.robot.RobotSpec;
import java.io.IOException;
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

    private static RobotMapping wheels() {
        return RobotMapping.empty("Test robot").withMotors(List.of(
                new MotorMapping("fl", Slot.FRONT_LEFT, "", true),
                new MotorMapping("bl", Slot.REAR_LEFT, "", true),
                new MotorMapping("fr", Slot.FRONT_RIGHT, "", false),
                new MotorMapping("br", Slot.REAR_RIGHT, "", false),
                new MotorMapping("shooter", Slot.SHOOTER, "", false)));
    }

    private SessionRequest request(RobotMapping mapping, String program, String field) {
        return new SessionRequest(mapping, program, field, Alliance.BLUE, 99L, 1.5, -2.0, 0.5, 2.0);
    }

    @Test
    void aRequestBecomesTheConfigurationTheSimulatorRuns() throws IOException {
        Path storage = folder.resolve("sdcard");

        SessionConfig config = factory(new FieldRegistry()).config(request(wheels(), program().toString(), "none"), storage);

        assertEquals(RobotDesign.decode().spec(wheels()), config.robot());
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

        Field first = factory.config(request(wheels(), program, "fake"), folder).field();
        Field second = factory.config(request(wheels(), program, "fake"), folder).field();

        assertEquals("fake", first.description().id());
        assertNotSame(first, second);
    }

    @Test
    void theCatalogueListsTheBareFloorFirstThenEachFieldWithWhatItOffersAndTheMotorsAMappingMayName() {
        Catalogue catalogue = factory(new FieldRegistry().register("fake", FakeField::new)).catalogue();

        assertEquals(
                List.of(
                        new FieldChoice("none", "Bare floor", List.of(), List.of()),
                        new FieldChoice("fake", "Fake", List.of(new StartPose("origin", 0, 0, 0)), List.of(new HumanAction("feed", "Feed")))),
                catalogue.fields());
        assertEquals(MotorCatalog.choices(), catalogue.motors());
        assertEquals("goBILDA-5203-435", catalogue.motors().get(0).id(), "the default drivetrain motor comes first");
    }

    @Test
    void theRobotOfTheGameOfTheFieldIsBuiltAndAMappingWithProblemsIsRefusedWithAllOfThem() throws IOException {
        SessionFactory factory = factory(new FieldRegistry());
        RobotMapping turret = RobotMapping.empty("Test robot").withMotors(List.of(new MotorMapping("aim", Slot.TURRET, "", false)));

        assertNotNull(factory.robot("biobuzz", turret).motors().get("aim").shaft());
        IOException noTurret = assertThrows(IOException.class, () -> factory.robot("decode", turret));
        assertTrue(noTurret.getMessage().contains("aim: this game's robot has no turret"), noTurret.getMessage());
        RobotMapping wrong = RobotMapping.empty("").withMotors(List.of(new MotorMapping("fl", Slot.FRONT_LEFT, "", false)));
        String message = assertThrows(IOException.class, () -> factory.robot("decode", wrong)).getMessage();
        assertTrue(
                message.contains("the robot mapping is wrong") && message.contains("The robot has no name")
                        && message.contains("Map all four wheels or none"),
                message);
        RobotMapping unknownModel = RobotMapping.empty("Test").withMotors(List.of(new MotorMapping("fly", Slot.SHOOTER, "nonsense", false)));
        assertTrue(assertThrows(IOException.class, () -> factory.robot("decode", unknownModel)).getMessage().contains("nonsense"));
    }

    @Test
    void whatCannotBeFoundIsSaidInTheMessageWithWhatWasLookedFor() throws IOException {
        SessionFactory factory = factory(new FieldRegistry().register("fake", FakeField::new));
        String program = program().toString();
        Path empty = Files.createDirectories(folder.resolve("empty"));

        assertTrue(assertThrows(IOException.class, () -> factory.program(" ")).getMessage().contains("no robot program"));
        assertTrue(assertThrows(IOException.class, () -> factory.program("C:\\a<b>")).getMessage().contains("not a valid path"));
        String missingProgram = folder.resolve("gone").toString();
        assertTrue(assertThrows(IOException.class, () -> factory.program(missingProgram)).getMessage().contains("gone does not exist"));
        String notAProgram = empty.toString();
        String layoutMessage = assertThrows(IOException.class, () -> factory.program(notAProgram)).getMessage();
        assertTrue(layoutMessage.contains("is not a prepared robot program") && layoutMessage.contains("program.properties"), layoutMessage);

        String fieldMessage =
                assertThrows(IOException.class, () -> factory.config(request(wheels(), program, "decode"), folder)).getMessage();
        assertTrue(fieldMessage.contains("no field 'decode'") && fieldMessage.contains("none, fake"), fieldMessage);
    }

    @Test
    void theRobotIsDescribedByItsMotorsInTheOrderOfTheMappingAndItsBody() {
        RobotSpec driving = RobotDesign.decode().spec(wheels());
        RobotSpec arm = RobotDesign.decode().spec(RobotMapping.empty("Arm only").withMotors(List.of(new MotorMapping("arm", Slot.SHOOTER, "", false))));

        RobotInfo info = SessionFactory.robotInfo(driving);

        MecanumConfig body = driving.chassis();
        assertEquals(new RobotInfo("Test robot", body.lengthM(), body.widthM(), body.heightM(), List.of("fl", "bl", "fr", "br", "shooter"), List.of(), List.of()), info);
        assertTrue(body.lengthM() > 0 && body.widthM() > 0 && body.heightM() > 0);
        assertEquals(List.of("arm"), SessionFactory.robotInfo(arm).motors());
        assertEquals(0, SessionFactory.robotInfo(arm).lengthM(), "a robot that does not drive has no body to draw");
    }
}
