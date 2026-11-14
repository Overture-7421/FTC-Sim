package dev.ftcsim.importer;

import dev.ftcsim.importer.ImportException.Failure;
import dev.ftcsim.importer.gradle.ExportedProject;
import dev.ftcsim.importer.gradle.GradleExport;
import dev.ftcsim.importer.gradle.GradleSettings;
import dev.ftcsim.importer.io.Trees;
import dev.ftcsim.importer.manifest.PackageManifest;
import dev.ftcsim.importer.pack.PackageBuilder;
import dev.ftcsim.importer.pack.PackageZip;
import dev.ftcsim.importer.pack.PreparedProgram;
import dev.ftcsim.importer.resources.ResourceBuilder;
import dev.ftcsim.importer.resources.ResourceException;
import dev.ftcsim.importer.scan.AndroidApi;
import dev.ftcsim.importer.scan.Finding;
import dev.ftcsim.importer.scan.NativeLibraries;
import dev.ftcsim.importer.scan.ProgramScan;
import dev.ftcsim.importer.snapshot.ProjectSnapshot;
import dev.ftcsim.importer.snapshot.SourceManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** One import, from the check of the request to the verified package. */
final class ImportRun {
    private final ImportRequest request;
    private final ImportProgress progress;
    private final Path project;
    private final Path workspace;
    private final Path packageDirectory;
    private final Path packageZip;
    private boolean packageStarted;
    private boolean zipStarted;

    ImportRun(ImportRequest request, ImportProgress progress) {
        this.request = request;
        this.progress = progress;
        this.project = request.projectDirectory().toAbsolutePath().normalize();
        this.workspace = request.workspace().toAbsolutePath().normalize();
        this.packageDirectory = request.packageDirectory().toAbsolutePath().normalize();
        this.packageZip = request.packageZip() == null ? null : request.packageZip().toAbsolutePath().normalize();
    }

    ImportResult run() {
        validate();
        try {
            return perform();
        } catch (ResourceException failure) {
            discard();
            throw new ImportException(Failure.RESOURCES_FAILED, "The Android resources could not be processed: " + failure.getMessage(), failure);
        } catch (IOException | UncheckedIOException failure) {
            discard();
            throw new ImportException(Failure.IO_ERROR, "A file could not be read or written during the import: " + failure, failure);
        } catch (RuntimeException failure) {
            discard();
            throw failure;
        }
    }

    private void validate() {
        if (!Files.isDirectory(project)) {
            throw new ImportException(Failure.INVALID_REQUEST, "The project folder " + project + " does not exist.");
        }
        if (!Files.isRegularFile(project.resolve("settings.gradle")) && !Files.isRegularFile(project.resolve("settings.gradle.kts"))) {
            throw new ImportException(
                    Failure.NOT_A_GRADLE_PROJECT,
                    project + " has no settings.gradle, so it is not the top folder of a Gradle project. Choose the folder that "
                            + "holds settings.gradle, gradlew.bat and the TeamCode folder.");
        }
        for (Path outside : new Path[] {workspace, packageDirectory}) {
            if (outside.startsWith(project) || project.startsWith(outside)) {
                throw new ImportException(
                        Failure.INVALID_REQUEST,
                        outside + " and the project " + project + " must be separate folders, neither inside the other, so that "
                                + "the project is never written to.");
            }
        }
        if (packageZip != null && packageZip.startsWith(project)) {
            throw new ImportException(Failure.INVALID_REQUEST, "The package zip " + packageZip + " would be written inside the project.");
        }
        try (Stream<Path> existing = Files.isDirectory(packageDirectory) ? Files.list(packageDirectory) : Stream.empty()) {
            if (existing.findAny().isPresent()) {
                throw new ImportException(
                        Failure.INVALID_REQUEST,
                        "The package folder " + packageDirectory + " already exists and is not empty. Choose a new folder, or "
                                + "delete the old package first, so that nothing of it is mixed with the new one.");
            }
        } catch (IOException unreadable) {
            throw new ImportException(Failure.IO_ERROR, "The package folder " + packageDirectory + " cannot be read: " + unreadable, unreadable);
        }
        if (Files.exists(packageDirectory) && !Files.isDirectory(packageDirectory)) {
            throw new ImportException(Failure.INVALID_REQUEST, packageDirectory + " is a file, not a folder.");
        }
        GradleExport.check(project, gradleSettings());
    }

    private GradleSettings gradleSettings() {
        return new GradleSettings(
                request.jdkHome().toAbsolutePath().normalize(),
                request.androidSdk().toAbsolutePath().normalize(),
                request.gradleUserHome(),
                request.offline(),
                request.gradleTimeout(),
                request.applicationModule());
    }

    private ImportResult perform() throws IOException {
        progress.stage(ImportStage.SNAPSHOT);
        ProjectSnapshot.Copy copy = ProjectSnapshot.create(project, workspace.resolve("snapshot"), progress);
        Files.writeString(workspace.resolve("original-sha256.txt"), copy.manifest().toText(), StandardCharsets.UTF_8);
        progress.checkCancelled();

        progress.stage(ImportStage.GRADLE_EXPORT);
        Path exportDirectory = workspace.resolve("export");
        ExportedProject export = GradleExport.run(copy.directory(), exportDirectory, gradleSettings(), progress);
        ExportedProject.Sdk sdk = export.ftcSdk().orElseThrow(() -> new ImportException(
                Failure.NOT_AN_FTC_PROJECT,
                export.applicationProject() + " does not depend on the FTC SDK (no org.firstinspires.ftc artifacts are on its class path)."));
        progress.message("FTC SDK " + sdk.version() + " found in " + export.libraries().size() + " libraries");
        progress.checkCancelled();

        progress.stage(ImportStage.RESOURCES);
        Path resources = workspace.resolve("resources");
        ResourceBuilder.Summary generated = generateResources(export, resources);
        progress.message("Generated " + generated.resources() + " resource ids for " + generated.packages() + " packages");
        progress.checkCancelled();

        progress.stage(ImportStage.PACKAGE);
        PackageBuilder builder = new PackageBuilder(packageDirectory);
        packageStarted = true;
        ProgramAssembly.Parts parts = ProgramAssembly.assemble(export, builder, packageDirectory, resources);
        progress.message("Packaged " + parts.program().size() + " program and " + parts.libraries().size() + " library jars");
        ProgramAssembly.JavaResources javaResources = ProgramAssembly.addJavaResources(export, builder, workspace.resolve("java-res"));
        progress.message("Merged " + javaResources.merged().files() + " Java resources of the libraries and the project ("
                + javaResources.merged().mergedFiles() + " of them joined from several libraries)");
        progress.checkCancelled();

        progress.stage(ImportStage.ANALYSIS);
        List<Finding> warnings = new ArrayList<>();
        copy.skippedLinks().forEach(link -> warnings.add(Finding.general(
                Finding.Codes.LINK_SKIPPED, link + " is a link in the project folder and was not followed.")));
        if (!sdk.consistent()) {
            warnings.add(Finding.general(
                    Finding.Codes.SDK_VERSION_MIXED,
                    "The FTC SDK artifacts have different versions (" + String.join(", ", sdk.components()) + "); "
                            + sdk.version() + " is the version of RobotCore."));
        }
        warnings.addAll(javaResources.findings());
        PackageManifest.OpModes opModes = analyse(parts, export, warnings);
        progress.message("Found " + opModes.count() + " OpModes and " + warnings.size() + " findings");
        progress.checkCancelled();

        progress.stage(ImportStage.VERIFY);
        builder.finish(
                new PackageManifest.Project(
                        String.valueOf(project.getFileName()),
                        export.applicationProject(),
                        export.namespace(),
                        copy.manifest().fingerprint(),
                        copy.manifest().fileCount()),
                new PackageManifest.Sdk(sdk.version(), sdk.components()),
                opModes,
                warnings);
        PreparedProgram program = PreparedProgram.open(packageDirectory);
        SourceManifest.Differences changed = copy.manifest().compareWith(SourceManifest.scan(project, progress));
        if (!changed.isEmpty()) {
            throw new ImportException(
                    Failure.ORIGINAL_CHANGED,
                    "The original project changed while it was imported, so the package was not kept: " + changed.describe());
        }
        Optional<Path> zip = Optional.empty();
        if (packageZip != null) {
            Files.createDirectories(packageZip.getParent());
            zipStarted = true;
            PackageZip.create(program, packageZip);
            zip = Optional.of(packageZip);
        }
        return new ImportResult(program, zip, exportDirectory.resolve("gradle.log"), copy.manifest());
    }

    /** Libraries first, the one the build lists first having the most say, then the project's own modules, the application last. */
    private ResourceBuilder.Summary generateResources(ExportedProject export, Path directory) throws IOException {
        ResourceBuilder builder = new ResourceBuilder();
        List<ExportedProject.Library> byPriority = new ArrayList<>(export.libraries());
        Collections.reverse(byPriority);
        for (ExportedProject.Library library : byPriority) {
            if (library.aar().isPresent()) {
                builder.addAar(library.aar().get());
            }
        }
        for (ExportedProject.ProjectModule module : export.modules()) {
            if (module.namespace().isBlank()) {
                progress.message(module.projectPath() + " declares no namespace, so its resources get no R class");
            } else {
                builder.addModule(module.namespace(), module.resources(), module.assets());
            }
        }
        return builder.writeTo(directory);
    }

    /** Reads the package's own jars: the OpModes, the methods that register more, the pointers to code the simulator may not run, the native libraries. */
    private PackageManifest.OpModes analyse(ProgramAssembly.Parts parts, ExportedProject export, List<Finding> warnings)
            throws IOException {
        List<Path> classPath = new ArrayList<>(parts.program());
        classPath.addAll(parts.libraries());
        AndroidApi android = request.simulatorClasspath().isEmpty() ? null : AndroidApi.of(request.simulatorClasspath());
        try (ProgramScan scan = ProgramScan.open(classPath)) {
            ProgramScan.OpModes found = scan.opModesIn(parts.program());
            warnings.addAll(found.notes());
            // The SDK's own registrars are the SDK host's business; those of the team and its other libraries are the pointers.
            List<Path> registrarRoots = new ArrayList<>(parts.program());
            parts.libraries().stream().filter(jar -> !parts.sdk().contains(jar)).forEach(registrarRoots::add);
            warnings.addAll(scan.opModeRegistrarsIn(registrarRoots));
            for (ExportedProject.Library library : export.libraries()) {
                if (library.aar().isPresent()) {
                    NativeLibraries.inAar(library.id(), library.aar().get()).ifPresent(warnings::add);
                }
            }
            for (ExportedProject.ProjectModule module : export.modules()) {
                NativeLibraries.inFolders(module.projectPath(), module.jniLibs()).ifPresent(warnings::add);
            }
            warnings.addAll(scan.compatibilityOf(parts.program(), android));
            return new PackageManifest.OpModes(found.found().size(), found.found());
        }
    }

    /** Removes what this import created of the package; never anything that was there before. */
    private void discard() {
        try {
            if (packageStarted) {
                Trees.delete(packageDirectory);
            }
            if (zipStarted) {
                Files.deleteIfExists(packageZip);
            }
        } catch (IOException | RuntimeException cleanup) {
            progress.message("Could not remove the unfinished package " + packageDirectory + ": " + cleanup);
        }
    }
}
