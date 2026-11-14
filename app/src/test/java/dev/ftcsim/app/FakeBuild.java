package dev.ftcsim.app;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.contracts.robot.OpModeKind;
import dev.ftcsim.importer.BuildTrust;
import dev.ftcsim.importer.ImportProgress;
import dev.ftcsim.importer.ImportRequest;
import dev.ftcsim.importer.ImportResult;
import dev.ftcsim.importer.ImportStage;
import dev.ftcsim.importer.manifest.PackageManifest;
import dev.ftcsim.importer.pack.PackageBuilder;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.importer.resources.ResourceBuilder;
import dev.ftcsim.importer.scan.Finding;
import dev.ftcsim.importer.snapshot.SourceManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** A team project and what the importer would make of it, without Gradle: a package made with the importer's own builder. */
final class FakeBuild {
    static final List<OpModeDescriptor> OP_MODES = List.of(
            new OpModeDescriptor("Drive", "Main", OpModeKind.TELEOP, "org.team.Drive"),
            new OpModeDescriptor("Park", "", OpModeKind.AUTONOMOUS, "org.team.Park"));

    static final List<Finding> FINDINGS = List.of(
            new Finding("THREAD_USE", "org.team.Drive", "run()V", "Creates a java.lang.Thread."),
            Finding.general("LINK_SKIPPED", "local is a link in the project folder and was not followed."));

    private FakeBuild() {}

    /** A project folder with what the application checks a project for, and one source file. */
    static Path project(Path folder) throws IOException {
        write(folder.resolve("settings.gradle"), "include ':TeamCode'");
        write(folder.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar"), "jar");
        write(folder.resolve("TeamCode").resolve("src").resolve("main").resolve("java").resolve("Drive.java"), "class Drive {}");
        return folder;
    }

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** A complete package of the project in {@code folder}. */
    static PreparedProgram packageOf(Path project, Path workspace, Path folder) {
        ImportRequest request = ImportRequest.of(project, workspace, folder, workspace, workspace, BuildTrust.TRUST_BUILD_SCRIPTS);
        return run(request, ImportProgress.SILENT).program();
    }

    /** What the importer does, minus Gradle: reports its stages and leaves a complete package where it was told to. */
    static ImportResult run(ImportRequest request, ImportProgress progress) {
        try {
            for (ImportStage stage : ImportStage.values()) {
                progress.stage(stage);
                progress.message("fake " + stage.name());
            }
            Path scratch = Files.createDirectories(request.workspace().resolve("scratch"));
            Path classes = Files.createDirectories(scratch.resolve("classes"));
            Files.writeString(classes.resolve("Drive.class"), "bytes of the team's code");
            Path generated = scratch.resolve("generated");
            new ResourceBuilder().writeTo(generated);
            SourceManifest original = SourceManifest.scan(request.projectDirectory(), progress);

            PackageBuilder builder = new PackageBuilder(request.packageDirectory());
            builder.addProgram("TeamCode", List.of(classes));
            builder.addResources(generated);
            builder.finish(
                    new PackageManifest.Project(
                            request.projectDirectory().getFileName().toString(), ":TeamCode", "org.team", original.fingerprint(), original.fileCount()),
                    new PackageManifest.Sdk("11.1.0", List.of("org.firstinspires.ftc:RobotCore:11.1.0")),
                    new PackageManifest.OpModes(OP_MODES.size(), OP_MODES),
                    FINDINGS);
            Path log = Files.createDirectories(request.workspace().resolve("export")).resolve("gradle.log");
            Files.writeString(log, "BUILD SUCCESSFUL (fake)\n");
            return new ImportResult(PreparedProgram.open(request.packageDirectory()), Optional.empty(), log, original);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
