package dev.ftcsim.importer.gradle;

import dev.ftcsim.importer.ImportException;
import dev.ftcsim.importer.ImportException.Failure;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the project's own Gradle build told the importer about the team's application: where its classes are, the
 * libraries on its runtime class path in order, and the resources of the project's modules. It is read from the
 * file that {@code ftcsim-export.init.gradle} writes; see that script for the format.
 *
 * @param gradleVersion           the Gradle version that ran the build
 * @param variant                 the exported variant, normally {@code debug}
 * @param applicationProject      Gradle path of the application module, for example {@code :TeamCode}
 * @param namespace               the application's package, which names its {@code R} class
 * @param applicationClasses      the compiled classes of the application module
 * @param applicationJavaResources the application module's own Java resources ({@code src/main/resources}), as AGP processes them
 * @param modules                 the project's Android modules that the application uses, the application last
 * @param libraries               the runtime class path, in order, without the application's own classes
 */
public record ExportedProject(
        String gradleVersion,
        String variant,
        String applicationProject,
        String namespace,
        List<Path> applicationClasses,
        List<Path> applicationJavaResources,
        List<ProjectModule> modules,
        List<Library> libraries) {

    /** Where a library on the class path comes from. */
    public enum Origin {
        /** A published artifact, named {@code group:name:version}. */
        MODULE,
        /** Another module of the project, named by its Gradle path. */
        PROJECT,
        /** A jar or AAR file that the build lists directly. */
        FILE
    }

    /**
     * @param origin        where it comes from
     * @param id            {@code group:name:version} for a module, the Gradle path for a project, a file name for a file
     * @param classes       its classes (an AAR's {@code classes.jar} and {@code libs/*.jar} merged), if it has any
     * @param aar           the Android library archive it was taken from, if it is one
     * @param javaResources the jars or folders that hold its non-class files, as AGP merges them into an APK; the
     *                      class jars above do not hold them
     */
    public record Library(Origin origin, String id, Optional<Path> classes, Optional<Path> aar, List<Path> javaResources) {
        public Library {
            javaResources = List.copyOf(javaResources);
        }

        public Library(Origin origin, String id, Optional<Path> classes, Optional<Path> aar) {
            this(origin, id, classes, aar, List.of());
        }

        /** Whether this is one of the artifacts of the FTC SDK itself, which the simulator's SDK host stands in for. */
        public boolean isFtcSdk() {
            return origin == Origin.MODULE && id.startsWith(FTC_GROUP + ":");
        }
    }

    /**
     * An Android module of the project.
     *
     * @param projectPath its Gradle path
     * @param application whether it is the application module
     * @param namespace   its package, empty when it declares none
     * @param resources   its {@code res} folders, the less specific first
     * @param assets      its {@code assets} folders, the less specific first
     * @param jniLibs     its {@code jniLibs} folders
     */
    public record ProjectModule(
            String projectPath, boolean application, String namespace, List<Path> resources, List<Path> assets, List<Path> jniLibs) {}

    private static final String FTC_GROUP = "org.firstinspires.ftc";

    /** The FTC SDK the project depends on, found among its resolved libraries. */
    public record Sdk(String version, List<String> components, boolean consistent) {}

    /**
     * The FTC SDK version, read from the resolved {@code org.firstinspires.ftc} artifacts, which all carry the one
     * SDK version; when they disagree the version of RobotCore counts and {@link Sdk#consistent()} is false.
     */
    public Optional<Sdk> ftcSdk() {
        List<String> components = new ArrayList<>();
        Set<String> versions = new LinkedHashSet<>();
        String robotCore = null;
        for (Library library : libraries) {
            String[] parts = library.id().split(":");
            if (library.isFtcSdk() && parts.length == 3) {
                components.add(library.id());
                versions.add(parts[2]);
                if (parts[1].equals("RobotCore")) {
                    robotCore = parts[2];
                }
            }
        }
        if (components.isEmpty()) {
            return Optional.empty();
        }
        String version = robotCore != null ? robotCore : versions.iterator().next();
        return Optional.of(new Sdk(version, components, versions.size() == 1));
    }

    /** Reads the export file written by the init script. */
    public static ExportedProject parse(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).equals("ftcsim-export\t1")) {
            throw new ImportException(
                    Failure.BUILD_FAILED,
                    "The project's build wrote an export that this version of FTC-Sim does not understand (first line: '"
                            + (lines.isEmpty() ? "" : lines.get(0)) + "').");
        }
        Builder builder = new Builder();
        for (String line : lines.subList(1, lines.size())) {
            if (!line.isBlank()) {
                builder.add(line.split("\t", -1));
            }
        }
        return builder.build();
    }

    private static final class Builder {
        private String gradle = "";
        private String variant = "debug";
        private String applicationProject;
        private String namespace = "";
        private final List<Path> classes = new ArrayList<>();
        private final List<Path> applicationJavaResources = new ArrayList<>();
        private final List<Library> libraries = new ArrayList<>();
        private final List<ModuleBuilder> modules = new ArrayList<>();

        void add(String[] fields) {
            switch (fields[0]) {
                case "gradle" -> gradle = field(fields, 1);
                case "variant" -> variant = field(fields, 1);
                case "application" -> {
                    applicationProject = field(fields, 1);
                    namespace = field(fields, 2);
                    applicationJavaResources.addAll(pathsFrom(fields, 3));
                }
                case "classes" -> classes.add(Path.of(field(fields, 1)));
                case "library" -> libraries.add(new Library(
                        origin(field(fields, 1)),
                        field(fields, 2),
                        optionalPath(field(fields, 3)),
                        optionalPath(field(fields, 4)),
                        pathsFrom(fields, 5)));
                case "module" -> modules.add(new ModuleBuilder(
                        field(fields, 1), field(fields, 2).equals("application"), field(fields, 3)));
                case "res", "assets", "jni" -> module(field(fields, 1)).folders(fields[0]).add(Path.of(field(fields, 2)));
                default -> throw new ImportException(Failure.BUILD_FAILED, "The project's build wrote an unknown export record '" + fields[0] + "'.");
            }
        }

        private ModuleBuilder module(String projectPath) {
            return modules.stream()
                    .filter(module -> module.projectPath.equals(projectPath))
                    .findFirst()
                    .orElseThrow(() -> new ImportException(Failure.BUILD_FAILED, "The export names folders of the unknown module " + projectPath + "."));
        }

        ExportedProject build() {
            if (applicationProject == null || namespace.isBlank()) {
                throw new ImportException(
                        Failure.NO_APPLICATION_MODULE,
                        "The project's build did not say which module is the application, or the application declares no namespace.");
            }
            return new ExportedProject(
                    gradle,
                    variant,
                    applicationProject,
                    namespace,
                    List.copyOf(classes),
                    List.copyOf(applicationJavaResources),
                    modules.stream().map(ModuleBuilder::build).toList(),
                    List.copyOf(libraries));
        }

        private static String field(String[] fields, int index) {
            if (index >= fields.length) {
                throw new ImportException(Failure.BUILD_FAILED, "The project's build wrote a short export record: " + String.join(" ", fields));
            }
            return fields[index];
        }

        /** The paths in the fields from {@code from} on, which a record may or may not have. */
        private static List<Path> pathsFrom(String[] fields, int from) {
            List<Path> paths = new ArrayList<>();
            for (int index = from; index < fields.length; index++) {
                if (!fields[index].isBlank() && !fields[index].equals("-")) {
                    paths.add(Path.of(fields[index]));
                }
            }
            return paths;
        }

        private static Optional<Path> optionalPath(String field) {
            return field.equals("-") ? Optional.empty() : Optional.of(Path.of(field));
        }

        private static Origin origin(String field) {
            try {
                return Origin.valueOf(field.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new ImportException(Failure.BUILD_FAILED, "The project's build wrote a library of the unknown kind '" + field + "'.");
            }
        }
    }

    private static final class ModuleBuilder {
        private final String projectPath;
        private final boolean application;
        private final String namespace;
        private final List<Path> resources = new ArrayList<>();
        private final List<Path> assets = new ArrayList<>();
        private final List<Path> jniLibs = new ArrayList<>();

        ModuleBuilder(String projectPath, boolean application, String namespace) {
            this.projectPath = projectPath;
            this.application = application;
            this.namespace = namespace;
        }

        List<Path> folders(String record) {
            return switch (record) {
                case "res" -> resources;
                case "assets" -> assets;
                default -> jniLibs;
            };
        }

        ProjectModule build() {
            return new ProjectModule(projectPath, application, namespace, List.copyOf(resources), List.copyOf(assets), List.copyOf(jniLibs));
        }
    }
}
