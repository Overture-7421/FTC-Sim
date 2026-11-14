package dev.ftcsim.acceptance;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.ftcsim.importer.BuildTrust;
import dev.ftcsim.importer.ImportProgress;
import dev.ftcsim.importer.ImportRequest;
import dev.ftcsim.importer.ImportStage;
import dev.ftcsim.importer.ProjectImporter;
import dev.ftcsim.importer.pack.PackageException;
import dev.ftcsim.importer.pack.PackageLayout;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.simulator.RobotProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Hornet as a prepared package in a folder that survives between runs: imported from the checkout the first time
 * ({@code -Pftcsim.hornet=<checkout>}) and reused afterwards ({@code -Pftcsim.hornetPackage=<folder>}), so that only
 * the first run pays for Hornet's own Gradle build. A package that an older importer made (see
 * {@link PreparedProgram#importerRevision()}) is stale: it is imported again, because what the importer left out of it
 * then, such as the Java resources of the libraries, shows up as failures in the robot program.
 */
final class HornetFixture {

    private static final String HOW_TO_ENABLE = "Skipped: give -Pftcsim.hornetPackage=<folder> (and, the first time, "
            + "-Pftcsim.hornet=<path to the Hornet checkout> so that Hornet is imported into it).";

    private HornetFixture() {}

    /**
     * The prepared package, importing it first if the folder does not hold one or holds one that is stale, damaged or
     * from a newer FTC-Sim than this. Skips the test when it cannot be had.
     */
    static PreparedProgram prepared() throws Exception {
        String folder = System.getProperty("ftcsim.hornetPackage");
        assumeTrue(folder != null, HOW_TO_ENABLE);
        Path packageFolder = Path.of(folder);
        String why;
        if (!Files.exists(packageFolder.resolve(PackageLayout.MANIFEST))) {
            why = "there is no package in " + packageFolder + " yet";
        } else {
            try {
                PreparedProgram cached = PreparedProgram.open(packageFolder);
                if (cached.importerRevision() >= ProjectImporter.IMPORTER_REVISION) {
                    return cached;
                }
                why = "the package in " + packageFolder + " was made by importer revision " + cached.importerRevision()
                        + " and the current one is " + ProjectImporter.IMPORTER_REVISION;
            } catch (PackageException unusable) {
                why = "the package in " + packageFolder + " cannot be used: " + unusable.getMessage();
            }
        }
        String checkout = System.getProperty("ftcsim.hornet");
        assumeTrue(checkout != null, "Skipped: " + why + "; give -Pftcsim.hornet=<path to the Hornet checkout> to import Hornet into it.");
        System.out.println("HORNET-FIXTURE> importing Hornet from " + checkout + ", because " + why);
        removeOldPackage(packageFolder);
        String sdk = System.getProperty("ftcsim.androidSdk", System.getenv("ANDROID_HOME"));
        assumeTrue(sdk != null, "No Android SDK: give -Pftcsim.androidSdk=<folder> or set ANDROID_HOME");
        Path work = packageFolder.resolveSibling(packageFolder.getFileName() + "-work");
        Files.createDirectories(work);
        ImportRequest request = ImportRequest.of(
                        Path.of(checkout),
                        work,
                        packageFolder,
                        Path.of(System.getProperty("ftcsim.jdk", System.getProperty("java.home"))),
                        Path.of(sdk),
                        BuildTrust.TRUST_BUILD_SCRIPTS)
                .withSimulatorClasspath(RobotWorld.classpath("ftcsim.simulatorClasspath"));
        List<ImportStage> stages = new ArrayList<>();
        ProjectImporter.importProject(request, new ImportProgress() {
            @Override
            public void stage(ImportStage stage) {
                stages.add(stage);
            }

            @Override
            public void message(String text) {}

            @Override
            public boolean isCancelled() {
                return false;
            }
        });
        return PreparedProgram.open(packageFolder);
    }

    /**
     * Empties the package folder so that the importer can write a new package there. It only deletes what is a package or
     * what an unfinished import leaves (no manifest, only the folders of a package), never an unrelated folder.
     */
    private static void removeOldPackage(Path packageFolder) throws IOException {
        if (!Files.exists(packageFolder)) {
            return;
        }
        boolean isPackage = Files.exists(packageFolder.resolve(PackageLayout.MANIFEST));
        boolean unfinished;
        try (Stream<Path> children = Files.list(packageFolder)) {
            unfinished = children.allMatch(child -> List.of(PackageLayout.PROGRAM, PackageLayout.LIBRARIES, PackageLayout.RESOURCES)
                    .contains(child.getFileName().toString()));
        }
        if (!isPackage && !unfinished) {
            throw new IllegalStateException(packageFolder + " is neither a prepared package nor empty, so it is not touched: "
                    + "give -Pftcsim.hornetPackage=<another folder>");
        }
        try (Stream<Path> tree = Files.walk(packageFolder)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** The robot program a prepared package makes: the simulator's own jars first, then the package. */
    static RobotProgram program(PreparedProgram prepared) throws IOException {
        List<Path> robot = new ArrayList<>(RobotWorld.classpath("ftcsim.simulatorClasspath"));
        robot.addAll(prepared.robotClasspath());
        return new RobotProgram(
                robot,
                prepared.applicationClasspath(),
                prepared.programClasspath(),
                prepared.manifest().project().name() + " (FTC SDK " + prepared.sdkVersion() + ")");
    }
}
