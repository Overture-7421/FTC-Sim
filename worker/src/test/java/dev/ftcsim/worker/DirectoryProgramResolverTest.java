package dev.ftcsim.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.simulator.RobotProgram;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryProgramResolverTest {

    @TempDir
    Path folder;

    private final DirectoryProgramResolver resolver = new DirectoryProgramResolver();

    private Path entry(String name) throws IOException {
        Path path = folder.resolve(name);
        Files.createDirectories(path);
        return path;
    }

    private void properties(String... lines) throws IOException {
        Files.writeString(folder.resolve("program.properties"), String.join("\r\n", lines), StandardCharsets.UTF_8);
    }

    @Test
    void aFolderWithoutTheFileIsNotThisLayoutAndSoIsAMissingFolder() throws IOException {
        assertEquals(Optional.empty(), resolver.resolve(folder));
        assertEquals(Optional.empty(), resolver.resolve(folder.resolve("nowhere")));
    }

    @Test
    void theRobotAndTheProgramClassPathsAreReadAsTheyAreWrittenWithRelativeEntriesRelativeToTheFolder() throws IOException {
        Path scenarios = entry("build\\classes\\scenarios");
        Path sdk = entry("libs\\sdk");
        properties(
                "# the fixture robot",
                "",
                "robot=" + scenarios + File.pathSeparator + "libs\\sdk" + File.pathSeparator,
                "program = build\\classes\\scenarios",
                "description=acceptance scenarios");

        RobotProgram program = resolver.resolve(folder).orElseThrow();

        assertEquals(List.of(scenarios, sdk), program.robotClasspath());
        assertEquals(List.of(scenarios), program.programClasspath());
        assertEquals("acceptance scenarios", program.description());
    }

    @Test
    void aClassPathLongerThanAWindowsCommandLineIsNoProblemInAFile() throws IOException {
        StringBuilder robot = new StringBuilder();
        List<Path> entries = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            Path path = entry("a library directory with a long name to make the line long " + "x".repeat(100) + i);
            entries.add(path);
            robot.append(path).append(File.pathSeparator);
        }
        properties("robot=" + robot, "program=" + entries.get(0));
        assertTrue(robot.length() > 60_000);

        RobotProgram program = resolver.resolve(folder).orElseThrow();

        assertEquals(entries, program.robotClasspath());
        assertEquals(folder.getFileName().toString(), program.description(), "without a description the folder's name stands for it");
    }

    @Test
    void whatIsWrongWithTheFileIsSaidWithTheLineAndNothingIsGuessed() throws IOException {
        entry("classes");
        String valid = "robot=classes";

        properties(valid);
        assertTrue(message().contains("has no 'program' line"), message());
        properties("program=classes");
        assertTrue(message().contains("has no 'robot' line"), message());
        properties(valid, "program=classes", "robots=classes");
        assertTrue(message().contains("line 3") && message().contains("unknown key 'robots'"), message());
        properties(valid, "program=classes", "robot=classes");
        assertTrue(message().contains("line 3") && message().contains("a second time"), message());
        properties(valid, "program classes");
        assertTrue(message().contains("line 2") && message().contains("is not 'key=value'"), message());
        properties(valid, "program=classes;nowhere;also-nowhere");
        assertTrue(message().contains("2 entries that do not exist") && message().contains("nowhere"), message());
        properties(valid, "program=;");
        assertTrue(message().contains("lists no class path entries"), message());
        entry("other");
        properties(valid, "program=other");
        assertTrue(message().contains("not on 'robot'") && message().contains("other"), message());
    }

    private String message() throws IOException {
        return assertThrows(IOException.class, () -> resolver.resolve(folder)).getMessage();
    }
}
