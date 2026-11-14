package dev.ftcsim.importer.pack;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.importer.io.Sha256;
import dev.ftcsim.importer.manifest.ManifestException;
import dev.ftcsim.importer.manifest.ManifestJson;
import dev.ftcsim.importer.manifest.PackageManifest;
import dev.ftcsim.importer.scan.Finding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A prepared robot program that has been checked: the manifest is well formed and of a version this reader
 * knows, and the folder holds exactly the files the manifest lists, each with the listed size and SHA-256. Opening
 * costs one pass over the files, so it is done once when a package is selected, not for every run.
 *
 * <p>The package's layout, relative to its folder:
 * <pre>
 * manifest.json                        the manifest; the one file the manifest does not list
 * program/&lt;module&gt;.jar                 the team's compiled code
 * libs/&lt;nnn&gt;-&lt;library&gt;.jar            the libraries, numbered in class path order
 * resources/r.jar                      generated R classes
 * resources/ftcsim-resources.tsv       strings, integers, booleans and colors by resource id
 * resources/assets/...                 raw assets of the libraries and of the project
 * resources/java-res.jar               the Java resources (images, service provider files, any non-class file)
 *                                      of the libraries and of the project, merged as the Android Gradle plugin
 *                                      merges them; absent when there are none and in packages of importer revision 1
 * </pre>
 *
 * <p>The robot class loader needs {@link #robotClasspath()} after the simulator's own jars, and the SDK is given
 * {@link #applicationClasspath()} as the code of the "application", because on a device the SDK, and libraries such
 * as Panels, scan everything the application package holds for OpModes, hooks and annotated settings.
 *
 * <p>A package made by an older importer (see {@link #importerRevision()}) is still opened; what it lacks is
 * reported by {@link #warnings()}, so that whoever caches packages can prepare the program again.
 */
public final class PreparedProgram {
    /** Files that Windows adds to folders on its own; they are ignored rather than reported as foreign. */
    private static final Set<String> SYSTEM_FILES = Set.of("desktop.ini", "thumbs.db", ".ds_store");
    private static final long MAX_MANIFEST_BYTES = 64L * 1024 * 1024;
    private static final int MAX_PROBLEMS = 10;

    private final Path directory;
    private final PackageManifest manifest;
    private final List<Finding> warnings;

    private PreparedProgram(Path directory, PackageManifest manifest) {
        this.directory = directory;
        this.manifest = manifest;
        List<Finding> all = new ArrayList<>(manifest.warnings());
        if (manifest.importerRevision() < PackageManifest.JAVA_RESOURCES_REVISION) {
            all.add(Finding.general(
                    Finding.Codes.JAVA_RESOURCES_MISSING,
                    "This package was made by importer revision " + manifest.importerRevision() + ", which left the Java resources of the"
                            + " libraries (images, service provider files and other non-class files) out, so code that loads them fails."
                            + " Prepare the program again with the current importer, revision " + PackageManifest.IMPORTER_REVISION + "."));
        }
        this.warnings = List.copyOf(all);
    }

    /**
     * Checks the package in {@code directory} and returns it.
     *
     * @throws PackageException with a message that says what is wrong and where
     */
    public static PreparedProgram open(Path directory) {
        Path root = directory.toAbsolutePath().normalize();
        PackageManifest manifest = readManifest(root);
        List<String> problems = new ArrayList<>();
        try {
            verify(root, manifest, problems);
        } catch (IOException | UncheckedIOException e) {
            throw new PackageException(root + ": the package cannot be read: " + e, e);
        }
        if (!problems.isEmpty()) {
            throw new PackageException(root + " does not match its manifest:\n  - " + String.join("\n  - ", problems));
        }
        return new PreparedProgram(root, manifest);
    }

    static PackageManifest readManifest(Path root) {
        Path file = root.resolve(PackageLayout.MANIFEST);
        try {
            if (!Files.isRegularFile(file)) {
                throw new PackageException(root + " is not a prepared program: it has no " + PackageLayout.MANIFEST);
            }
            if (Files.size(file) > MAX_MANIFEST_BYTES) {
                throw new PackageException(file + " is larger than " + MAX_MANIFEST_BYTES / (1024 * 1024) + " MiB, which no manifest is");
            }
            return ManifestJson.read(file);
        } catch (ManifestException invalid) {
            throw new PackageException(root + ": " + invalid.getMessage(), invalid);
        } catch (IOException unreadable) {
            throw new PackageException(file + " cannot be read: " + unreadable, unreadable);
        }
    }

    /** Compares the folder with the manifest, collecting what differs. */
    private static void verify(Path root, PackageManifest manifest, List<String> problems) throws IOException {
        Map<String, PackageManifest.PackageFile> listed = new HashMap<>();
        manifest.files().forEach(file -> listed.put(file.path(), file));
        Map<String, Path> present = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                if (path.equals(root)) {
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    problems.add(relative + " is a link, which a package does not contain");
                } else if (attributes.isRegularFile()) {
                    present.put(relative, path);
                }
            }
        }
        for (PackageManifest.PackageFile file : manifest.files()) {
            Path path = present.get(file.path());
            if (path == null) {
                problems.add(file.path() + " is listed in the manifest but missing");
            } else if (Files.size(path) != file.size()) {
                problems.add(file.path() + " has " + Files.size(path) + " bytes, the manifest says " + file.size());
            } else {
                String actual = Sha256.of(path);
                if (!actual.equals(file.sha256())) {
                    problems.add(file.path() + " has SHA-256 " + actual + ", the manifest says " + file.sha256());
                }
            }
            if (problems.size() >= MAX_PROBLEMS) {
                return;
            }
        }
        for (String relative : present.keySet()) {
            boolean foreign = !listed.containsKey(relative) && !relative.equals(PackageLayout.MANIFEST)
                    && !SYSTEM_FILES.contains(relative.substring(relative.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT));
            if (foreign) {
                problems.add(relative + " is in the package but not in the manifest");
                if (problems.size() >= MAX_PROBLEMS) {
                    return;
                }
            }
        }
    }

    public Path directory() {
        return directory;
    }

    public PackageManifest manifest() {
        return manifest;
    }

    /** The FTC SDK version the program was built against, for example {@code 11.1.0}. */
    public String sdkVersion() {
        return manifest.sdk().version();
    }

    /** The OpModes found in the program's bytecode when it was prepared. */
    public List<OpModeDescriptor> opModes() {
        return manifest.opModes().list();
    }

    /**
     * Which revision of the importer made the package; compare it with {@code ProjectImporter.IMPORTER_REVISION} to
     * tell a stale package, which {@link #warnings()} also says. Packages from before revisions were recorded are 1.
     */
    public int importerRevision() {
        return manifest.importerRevision();
    }

    /**
     * Pointers to code that may not work in the simulator, recorded at preparation; not claims that it fails. For a
     * package made by an older importer than this reader there is one more: what that importer left out.
     */
    public List<Finding> warnings() {
        return warnings;
    }

    /** The jars with the team's own code. */
    public List<Path> programClasspath() {
        return manifest.program().stream().map(directory::resolve).toList();
    }

    /**
     * The generated R classes, the folder with the resource table and the assets, the libraries' jars in class path
     * order, and last the jar with the Java resources of the libraries, which an APK holds in addition to their
     * classes. The simulator's own jars (the Android shim and the SDK adapters) are not part of the package.
     */
    public List<Path> libraryClasspath() {
        List<Path> entries = new ArrayList<>();
        entries.add(directory.resolve(manifest.resources().classes()));
        entries.add(directory.resolve(manifest.resources().directory()));
        manifest.libraries().forEach(library -> entries.add(directory.resolve(library.path())));
        javaResourcesJar().ifPresent(entries::add);
        return entries;
    }

    /**
     * What stands in for the installed application: the program followed by the library jars and the Java resources.
     * The SDK scans it for OpModes and hooks, and libraries such as Panels index it, as they do the whole APK on a device.
     */
    public List<Path> applicationClasspath() {
        List<Path> entries = new ArrayList<>(programClasspath());
        manifest.libraries().forEach(library -> entries.add(directory.resolve(library.path())));
        javaResourcesJar().ifPresent(entries::add);
        return entries;
    }

    /** The entries for the robot class loader: the program, then the libraries and resources. */
    public List<Path> robotClasspath() {
        List<Path> entries = new ArrayList<>(programClasspath());
        entries.addAll(libraryClasspath());
        return entries;
    }

    private Optional<Path> javaResourcesJar() {
        String jar = manifest.resources().javaResources();
        return jar.isEmpty() ? Optional.empty() : Optional.of(directory.resolve(jar));
    }
}
