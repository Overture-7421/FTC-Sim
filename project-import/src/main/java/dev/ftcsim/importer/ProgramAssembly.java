package dev.ftcsim.importer;

import dev.ftcsim.importer.ImportException.Failure;
import dev.ftcsim.importer.gradle.ExportedProject;
import dev.ftcsim.importer.javares.JavaResourceMerger;
import dev.ftcsim.importer.javares.PackagingRules;
import dev.ftcsim.importer.pack.PackageBuilder;
import dev.ftcsim.importer.pack.PackageLayout;
import dev.ftcsim.importer.scan.Finding;
import dev.ftcsim.importer.scan.ProgramScan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides which of the exported class path entries are the team's program and which are libraries, and puts them
 * into the package. The program is the application module's own classes and the classes of any other module of the
 * project that declares OpModes, because the SDK has to scan them for OpModes. Everything else, including a project
 * module that holds no OpMode (the FTC template's {@code FtcRobotController} with its samples, for one), is a library
 * jar. The decision is read from the bytecode, not from names. The Java resources of all of them are merged into one
 * jar, because the class jars of the libraries do not hold them.
 */
final class ProgramAssembly {
    private static final int MAX_CONFLICT_FINDINGS = 50;

    /**
     * The jars of the package, as paths in the folder that was written.
     *
     * @param program   the team's classes
     * @param libraries every other jar, in class path order
     * @param sdk       those of the libraries that are artifacts of the FTC SDK itself
     */
    record Parts(List<Path> program, List<Path> libraries, List<Path> sdk) {}

    /**
     * What merging the Java resources did.
     *
     * @param merged   the counts
     * @param findings one for each resource that libraries ship with different contents
     */
    record JavaResources(JavaResourceMerger.Result merged, List<Finding> findings) {}

    private ProgramAssembly() {}

    /**
     * Merges the Java resources of the application, of the project's other modules and of the libraries, in the
     * priority the Android Gradle plugin gives them (the application's own, then the project's modules, then the
     * libraries, each in class path order), and adds the jar to the package unless there are none.
     *
     * @param folder where the merged jar is written first
     */
    static JavaResources addJavaResources(ExportedProject export, PackageBuilder builder, Path folder) throws IOException {
        List<JavaResourceMerger.Source> sources = new ArrayList<>();
        for (Path root : export.applicationJavaResources()) {
            sources.add(source(export.applicationProject(), root));
        }
        for (boolean ofTheProject : new boolean[] {true, false}) {
            for (ExportedProject.Library library : export.libraries()) {
                if ((library.origin() == ExportedProject.Origin.PROJECT) == ofTheProject) {
                    library.javaResources().forEach(root -> sources.add(source(library.id(), root)));
                }
            }
        }
        Path jar = folder.resolve(PackageLayout.JAVA_RESOURCES);
        JavaResourceMerger.Result merged = JavaResourceMerger.merge(sources, PackagingRules.forAnApk(), jar);
        if (merged.files() > 0) {
            builder.addJavaResources(jar);
        }
        List<Finding> findings = new ArrayList<>();
        for (JavaResourceMerger.Conflict conflict : merged.conflicts()) {
            if (findings.size() == MAX_CONFLICT_FINDINGS) {
                findings.add(Finding.general(
                        Finding.Codes.TRUNCATED,
                        (merged.conflicts().size() - MAX_CONFLICT_FINDINGS) + " more Java resources that libraries ship with different contents are not listed."));
                break;
            }
            findings.add(new Finding(
                    Finding.Codes.JAVA_RESOURCE_CONFLICT,
                    conflict.path(),
                    "",
                    "Different contents in " + conflict.keptFrom() + " and " + String.join(", ", conflict.others())
                            + "; the one in " + conflict.keptFrom() + " is used, as the first on the class path."));
        }
        return new JavaResources(merged, List.copyOf(findings));
    }

    private static JavaResourceMerger.Source source(String name, Path root) {
        if (!Files.exists(root)) {
            throw new ImportException(
                    Failure.BUILD_FAILED, "The build reported " + root + " as Java resources of " + name + ", but there is no such file.");
        }
        return new JavaResourceMerger.Source(name, root);
    }

    static Parts assemble(ExportedProject export, PackageBuilder builder, Path packageRoot, Path resources) throws IOException {
        List<Path> applicationClasses = export.applicationClasses().stream().filter(Files::isDirectory).toList();
        if (applicationClasses.isEmpty()) {
            throw new ImportException(
                    Failure.BUILD_FAILED, "The build reported no compiled classes for " + export.applicationProject() + ".");
        }
        List<ExportedProject.Library> libraries = new ArrayList<>();
        for (ExportedProject.Library library : export.libraries()) {
            if (library.classes().isPresent()) {
                if (!Files.isRegularFile(library.classes().get())) {
                    throw new ImportException(
                            Failure.BUILD_FAILED, "The build reported " + library.classes().get() + " for " + library.id() + ", but there is no such file.");
                }
                libraries.add(library);
            }
        }

        List<Path> program = new ArrayList<>();
        List<Path> libraryJars = new ArrayList<>();
        List<Path> sdkJars = new ArrayList<>();
        List<Path> classPath = new ArrayList<>(applicationClasses);
        libraries.forEach(library -> classPath.add(library.classes().get()));
        try (ProgramScan scan = ProgramScan.open(classPath)) {
            program.add(packageRoot.resolve(builder.addProgram(moduleName(export.applicationProject()), applicationClasses)));
            for (ExportedProject.Library library : libraries) {
                Path jar = library.classes().get();
                boolean team = library.origin() == ExportedProject.Origin.PROJECT && !scan.opModesIn(List.of(jar)).found().isEmpty();
                if (team) {
                    program.add(packageRoot.resolve(builder.addProgram(moduleName(library.id()), List.of(jar))));
                } else {
                    Path packaged = packageRoot.resolve(builder.addLibrary(library.id(), jar));
                    libraryJars.add(packaged);
                    if (library.isFtcSdk()) {
                        sdkJars.add(packaged);
                    }
                }
            }
        }
        builder.addResources(resources);
        return new Parts(List.copyOf(program), List.copyOf(libraryJars), List.copyOf(sdkJars));
    }

    /** {@code :TeamCode} becomes {@code TeamCode}. */
    private static String moduleName(String gradlePath) {
        return gradlePath.replace(':', '_').replaceAll("^_+", "");
    }
}
