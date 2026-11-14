package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.ui.host.ProgramPreparer.Inspection;
import dev.ftcsim.ui.host.ProgramPreparer.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgramInspectorTest {
    private final ProgramInspector inspector = new ProgramInspector();

    @TempDir
    Path root;

    @Test
    void aFolderWithSettingsAndAWrapperIsAProjectWhetherItsSettingsAreGroovyOrKotlin() throws IOException {
        Path project = FakeBuild.project(root.resolve("RobotCode"));

        Inspection groovy = inspector.inspect(project);
        assertEquals(Kind.PROJECT, groovy.kind());
        assertEquals("RobotCode", groovy.name());
        assertTrue(groovy.summary().contains("Gradle build"), groovy.summary());

        Files.move(project.resolve("settings.gradle"), project.resolve("settings.gradle.kts"));
        assertEquals(Kind.PROJECT, inspector.inspect(project).kind());
    }

    @Test
    void aPreparedProgramSaysWhichSdkItIsForAndHowManyOpModesItHas() throws IOException {
        Path project = FakeBuild.project(root.resolve("RobotCode"));
        Path folder = root.resolve("package");
        FakeBuild.packageOf(project, root.resolve("work"), folder);

        Inspection inspection = inspector.inspect(folder);

        assertEquals(Kind.PREPARED_PROGRAM, inspection.kind());
        assertEquals("RobotCode", inspection.name(), "the project it was made from");
        assertEquals("A prepared program for FTC SDK 11.1.0 with 2 OpModes.", inspection.summary());
    }

    @Test
    void aPackageThatWasChangedIsNotRecognisedAndTheReasonIsOneSentence() throws IOException {
        Path project = FakeBuild.project(root.resolve("RobotCode"));
        Path folder = root.resolve("package");
        FakeBuild.packageOf(project, root.resolve("work"), folder);
        Files.writeString(folder.resolve("program").resolve("TeamCode.jar"), "tampered");

        Inspection inspection = inspector.inspect(folder);

        assertEquals(Kind.NOT_RECOGNISED, inspection.kind());
        assertTrue(inspection.summary().startsWith("This folder looks like a prepared program but cannot be used: program/TeamCode.jar has 8 bytes"), inspection.summary());
        assertEquals(1, inspection.summary().lines().count(), inspection.summary());
    }

    @Test
    void whatIsNeitherSaysWhyInWordsThatPointTheWay() throws IOException {
        Path noWrapper = FakeBuild.project(root.resolve("NoWrapper"));
        Files.delete(noWrapper.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar"));
        Path plain = Files.createDirectories(root.resolve("Documents"));
        Path file = Files.writeString(root.resolve("notes.txt"), "text");

        assertTrue(inspector.inspect(noWrapper).summary().contains("no Gradle wrapper (gradle\\wrapper\\gradle-wrapper.jar)"));
        assertTrue(inspector.inspect(plain).summary().contains("neither a team project (it has no settings.gradle)"));
        assertTrue(inspector.inspect(file).summary().contains("notes.txt is a file, not a folder"));
        assertTrue(inspector.inspect(root.resolve("gone")).summary().contains("gone does not exist"));
        for (Path path : new Path[] {noWrapper, plain, file, root.resolve("gone")}) {
            assertEquals(Kind.NOT_RECOGNISED, inspector.inspect(path).kind(), path.toString());
        }
    }
}
