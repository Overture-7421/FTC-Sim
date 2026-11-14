package dev.ftcsim.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.importer.manifest.PackageManifest;
import dev.ftcsim.importer.pack.PackageBuilder;
import dev.ftcsim.simulator.RobotProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedProgramResolverTest {

    @TempDir
    Path folder;

    private final List<Path> simulator = List.of(Path.of("shim.jar"), Path.of("adapters.jar"));

    /** A package as the importer writes it, with files that are nothing like what a real one holds. */
    private Path preparedPackage() throws IOException {
        Path classes = Files.createDirectories(folder.resolve("classes"));
        Files.writeString(classes.resolve("Team.class"), "not really a class");
        Path generated = Files.createDirectories(folder.resolve("generated"));
        Files.writeString(generated.resolve("r.jar"), "generated R classes");
        Files.writeString(generated.resolve("ftcsim-resources.tsv"), "string\t0x7f010000\tname\tvalue\n");
        Path library = Files.writeString(folder.resolve("library.jar"), "a library");
        Path root = folder.resolve("package");
        PackageBuilder builder = new PackageBuilder(root);
        builder.addProgram("TeamCode", List.of(classes));
        builder.addLibrary("org.firstinspires.ftc:RobotCore:11.1.0", library);
        builder.addResources(generated);
        builder.finish(
                new PackageManifest.Project("team-robot", ":TeamCode", "org.team", "0".repeat(64), 3),
                new PackageManifest.Sdk("11.1.0", List.of()),
                new PackageManifest.OpModes(0, List.of()),
                List.of());
        return root;
    }

    @Test
    void aPreparedProgramRunsOnTheSimulatorsOwnJarsFollowedByWhatThePackageHoldsAndTheSdkScansTheTeamsCodeAndLibraries()
            throws IOException {
        Path root = preparedPackage();

        RobotProgram program = new PreparedProgramResolver(simulator).resolve(root).orElseThrow();

        assertEquals(simulator, program.robotClasspath().subList(0, 2));
        assertEquals(root.resolve("program").resolve("TeamCode.jar"), program.robotClasspath().get(2));
        assertEquals(1, program.robotClasspath().stream().filter(entry -> entry.startsWith(root.resolve("libs"))).count());
        assertEquals(2, program.programClasspath().size(), "the team's code and its one library");
        assertEquals(root.resolve("program").resolve("TeamCode.jar"), program.programClasspath().get(0));
        assertTrue(program.robotClasspath().containsAll(program.programClasspath()), "the robot world can load what the SDK scans");
        assertEquals(
                List.of(root.resolve("program").resolve("TeamCode.jar")), program.teamClasspath(),
                "only the team's own code is reported class by class when the SDK's scan cannot load it");
        assertEquals("team-robot (FTC SDK 11.1.0)", program.description());
    }

    @Test
    void aFolderWithoutAManifestIsAnotherLayoutAndNeedsNoJars() throws IOException {
        assertEquals(Optional.empty(), new PreparedProgramResolver(List.of()).resolve(folder));
    }

    @Test
    void aPackageThatIsNotWhatItsManifestSaysIsRefusedWithWhatDiffers() throws IOException {
        Path root = preparedPackage();
        Files.writeString(root.resolve("program").resolve("TeamCode.jar"), "tampered", StandardOpenOption.APPEND);

        IOException refused = assertThrows(IOException.class, () -> new PreparedProgramResolver(simulator).resolve(root));

        assertTrue(refused.getMessage().contains("does not match its manifest") && refused.getMessage().contains("TeamCode.jar"), refused.getMessage());
    }

    @Test
    void withoutTheSimulatorsJarsAPreparedProgramCannotRunAndTheMessageSaysWhereToGiveThem() throws IOException {
        Path root = preparedPackage();

        IOException refused = assertThrows(IOException.class, () -> new PreparedProgramResolver(List.of()).resolve(root));

        assertTrue(refused.getMessage().contains("ftcsim.simulatorClasspath"), refused.getMessage());
    }
}
