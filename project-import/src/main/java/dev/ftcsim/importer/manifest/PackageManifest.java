package dev.ftcsim.importer.manifest;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.importer.scan.Finding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The manifest of a prepared program ({@code manifest.json} at the top of a practice package): what the package
 * holds, where it came from, and the SHA-256 of every file in it. A package is trusted only as far as its files
 * match this list, so the reader checks every one; see {@code PreparedProgram}.
 *
 * <p>Every property is required. Where a value can be absent it is empty (an empty string, list or object), so
 * that a manifest never depends on defaults a newer or older reader would fill in differently.
 *
 * @param format          always {@value #FORMAT}
 * @param formatVersion   the version of this format, {@value #VERSION}; a reader rejects a newer one and upgrades an
 *                        older one it still knows (see {@link #OLDEST_VERSION})
 * @param importerRevision which revision of the importer made the package, {@value #IMPORTER_REVISION} for this one;
 *                        a package is only as good as the importer that made it, so a cache of packages can tell a stale
 *                        one (see {@link #JAVA_RESOURCES_REVISION}). A package of format version 1 is revision 1.
 * @param project         where the program came from
 * @param sdk             the FTC SDK it was built against
 * @param program         the jars that hold the team's own code, as paths inside the package; the SDK scans these for
 *                        OpModes and libraries that register with it
 * @param libraries       the jars of the libraries, in class path order
 * @param resources       the generated Android resources and the Java resources of the libraries
 * @param opModes         the OpModes the program declares, found by scanning its bytecode
 * @param warnings        pointers to code that may not work in the simulator; not claims that it fails
 * @param files           every file of the package except this manifest, with its hash
 * @param extras          room for what later parts of the simulator attach, such as a robot mapping or field
 *                        references; the importer writes it empty and keeps what it finds
 */
public record PackageManifest(
        String format,
        int formatVersion,
        int importerRevision,
        Project project,
        Sdk sdk,
        List<String> program,
        List<Library> libraries,
        Resources resources,
        OpModes opModes,
        List<Finding> warnings,
        List<PackageFile> files,
        Map<String, Object> extras) {

    public static final String FORMAT = "ftcsim-prepared-program";
    public static final int VERSION = 2;
    /** The oldest format version that is still read; it is read as a version {@value #VERSION} manifest of importer revision 1 without Java resources. */
    public static final int OLDEST_VERSION = 1;
    /** The revision of the importer in this build: raise it whenever a package would come out different or better. */
    public static final int IMPORTER_REVISION = 3;
    /** The first importer revision that puts the Java resources of the libraries into a package; packages of older ones lack them. */
    public static final int JAVA_RESOURCES_REVISION = 2;

    /**
     * @param name              the project's folder name, for people
     * @param applicationModule the Gradle path of the application module that was built, for example {@code :TeamCode}
     * @param namespace         the application's package
     * @param sourceFingerprint SHA-256 over the hashes of the original project's source and build files, so two
     *                          packages made from identical sources can be recognised as such
     * @param sourceFiles       how many files that fingerprint covers
     */
    public record Project(String name, String applicationModule, String namespace, String sourceFingerprint, int sourceFiles) {}

    /**
     * @param version    the FTC SDK version, for example {@code 11.1.0}
     * @param components the {@code org.firstinspires.ftc} artifacts it was resolved from, as {@code group:name:version}
     */
    public record Sdk(String version, List<String> components) {
        public Sdk {
            components = List.copyOf(components);
        }
    }

    /**
     * @param path the jar's path inside the package
     * @param id   what it is: {@code group:name:version}, a Gradle project path, or a file name
     */
    public record Library(String path, String id) {}

    /**
     * @param classes       the jar of generated {@code R} classes
     * @param directory     the folder that holds the resource table {@code ftcsim-resources.tsv} and the assets below
     *                      {@code assets/}; it belongs on the class path next to the jar
     * @param javaResources the jar that holds the Java resources of the libraries and of the project (images, service
     *                      provider files and other non-class files, merged as the Android Gradle plugin merges them),
     *                      which belongs on the class path after the libraries; empty when there are none
     */
    public record Resources(String classes, String directory, String javaResources) {}

    /**
     * @param count how many OpModes there are; always the size of the list
     * @param list  the OpModes
     */
    public record OpModes(int count, List<OpModeDescriptor> list) {
        public OpModes {
            list = List.copyOf(list);
        }
    }

    /**
     * @param path   the file's path inside the package, with '/' separators
     * @param sha256 its SHA-256, lower-case hexadecimal
     * @param size   its length in bytes
     */
    public record PackageFile(String path, String sha256, long size) {}

    public PackageManifest {
        program = List.copyOf(program);
        libraries = List.copyOf(libraries);
        warnings = List.copyOf(warnings);
        files = List.copyOf(files);
        extras = Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    /** The same manifest with one more entry in {@code extras}. */
    public PackageManifest withExtra(String key, Object value) {
        Map<String, Object> more = new LinkedHashMap<>(extras);
        more.put(key, value);
        return new PackageManifest(format, formatVersion, importerRevision, project, sdk, program, libraries, resources, opModes, warnings, files, more);
    }

    /** The same manifest listing more files, for a part of the simulator that adds files to the package. */
    public PackageManifest withFiles(List<PackageFile> more) {
        List<PackageFile> all = new ArrayList<>(files);
        all.addAll(more);
        all.sort(Comparator.comparing(PackageFile::path));
        return new PackageManifest(format, formatVersion, importerRevision, project, sdk, program, libraries, resources, opModes, warnings, all, extras);
    }
}
