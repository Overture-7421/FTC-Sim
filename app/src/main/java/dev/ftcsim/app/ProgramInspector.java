package dev.ftcsim.app;

import dev.ftcsim.importer.pack.PackageException;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.ui.host.ProgramPreparer.Inspection;
import dev.ftcsim.ui.host.ProgramPreparer.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Says what a folder holds, by looking at it and running nothing. */
final class ProgramInspector {
    /** What the importer builds a project with, and so what a project has to have. */
    static final String WRAPPER_JAR = "gradle/wrapper/gradle-wrapper.jar";

    static final String MANIFEST = "manifest.json";

    Inspection inspect(Path folder) {
        String name = AppFolders.leaf(folder);
        if (!Files.exists(folder)) {
            return neither(name, folder + " does not exist");
        }
        if (!Files.isDirectory(folder)) {
            return neither(name, name + " is a file, not a folder");
        }
        boolean hasSettings = Files.isRegularFile(folder.resolve("settings.gradle")) || Files.isRegularFile(folder.resolve("settings.gradle.kts"));
        if (hasSettings && Files.isRegularFile(folder.resolve(WRAPPER_JAR))) {
            return new Inspection(
                    Kind.PROJECT,
                    name,
                    "Team project");
        }
        if (Files.isRegularFile(folder.resolve(MANIFEST))) {
            try {
                PreparedProgram program = PreparedProgram.open(folder);
                int opModes = program.opModes().size();
                return new Inspection(
                        Kind.PREPARED_PROGRAM,
                        program.manifest().project().name(),
                        "Built program: FTC SDK " + program.sdkVersion() + ", " + opModes + (opModes == 1 ? " OpMode" : " OpModes"));
            } catch (PackageException damaged) {
                return neither(name, "Damaged built program: " + firstProblem(damaged));
            }
        }
        if (hasSettings) {
            return neither(
                    name,
                    "No Gradle wrapper (" + WRAPPER_JAR.replace('/', '\\') + "). Open the project in Android Studio once.");
        }
        return neither(
                name,
                "No settings.gradle here. Choose the project's root folder.");
    }

    private static Inspection neither(String name, String reason) {
        return new Inspection(Kind.NOT_RECOGNISED, name, reason);
    }

    /** The first thing a package check complains about, on one line and without a closing full stop. */
    static String firstProblem(PackageException damaged) {
        List<String> lines = damaged.getMessage().lines().toList();
        List<String> problems = lines.stream().filter(line -> line.startsWith("  - ")).toList();
        if (problems.isEmpty()) {
            return stripFullStop(lines.get(0));
        }
        String first = stripFullStop(problems.get(0).substring(4));
        return problems.size() == 1 ? first : first + " (and " + (problems.size() - 1) + " more)";
    }

    private static String stripFullStop(String text) {
        String trimmed = text.strip();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
