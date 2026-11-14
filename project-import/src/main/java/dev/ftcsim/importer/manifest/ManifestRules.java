package dev.ftcsim.importer.manifest;

import dev.ftcsim.contracts.robot.OpModeDescriptor;
import dev.ftcsim.importer.io.Sha256;
import dev.ftcsim.importer.scan.Finding;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What must hold for a manifest to be accepted, before any file of the package is looked at: the format and
 * version are the ones this reader knows, every value is usable, every path is safe and lists no file twice, and
 * everything the manifest points at is one of the files it lists. A violation is reported with the name of the
 * property, for example {@code libraries[3].path}.
 */
final class ManifestRules {
    private static final String RESOURCE_TABLE = "ftcsim-resources.tsv";

    private final String source;

    private ManifestRules(String source) {
        this.source = source;
    }

    static void validate(PackageManifest manifest, String source) {
        new ManifestRules(source).check(manifest);
    }

    private ManifestException problem(String property, String what) {
        return new ManifestException(source + ": " + property + " " + what);
    }

    private void check(PackageManifest manifest) {
        checkHeader(source, manifest.format(), manifest.formatVersion());
        if (manifest.importerRevision() < 1) {
            throw problem("importerRevision", "is " + manifest.importerRevision() + ", which no importer has had");
        }
        nonBlank("project.name", manifest.project().name());
        nonBlank("project.applicationModule", manifest.project().applicationModule());
        nonBlank("project.namespace", manifest.project().namespace());
        digest("project.sourceFingerprint", manifest.project().sourceFingerprint());
        if (manifest.project().sourceFiles() < 0) {
            throw problem("project.sourceFiles", "is negative");
        }
        nonBlank("sdk.version", manifest.sdk().version());

        Set<String> listed = checkFiles(manifest.files());
        if (manifest.program().isEmpty()) {
            throw problem("program", "is empty: a program holds at least the team's own classes");
        }
        for (int i = 0; i < manifest.program().size(); i++) {
            listedFile("program[" + i + "]", manifest.program().get(i), listed);
        }
        for (int i = 0; i < manifest.libraries().size(); i++) {
            PackageManifest.Library library = manifest.libraries().get(i);
            listedFile("libraries[" + i + "].path", library.path(), listed);
            nonBlank("libraries[" + i + "].id", library.id());
        }
        checkResources(manifest.resources(), listed);
        checkOpModes(manifest.opModes());
        for (int i = 0; i < manifest.warnings().size(); i++) {
            Finding warning = manifest.warnings().get(i);
            nonBlank("warnings[" + i + "].code", warning.code());
        }
        for (Map.Entry<String, Object> extra : manifest.extras().entrySet()) {
            nonBlank("extras key", extra.getKey());
        }
    }

    /**
     * Checks what kind of file this is and whether this reader knows its version; done before anything else of the
     * file is read, because a newer or foreign file has no reason to look like this one.
     */
    static void checkHeader(String source, String format, int formatVersion) {
        if (!PackageManifest.FORMAT.equals(format)) {
            throw new ManifestException(
                    source + ": format is '" + format + "', not '" + PackageManifest.FORMAT + "': this is not a prepared FTC-Sim program");
        }
        if (formatVersion > PackageManifest.VERSION) {
            throw new ManifestException(source + ": formatVersion is " + formatVersion + ", but this version of FTC-Sim reads versions "
                    + PackageManifest.OLDEST_VERSION + " to " + PackageManifest.VERSION
                    + ": the package was made by a newer FTC-Sim, so update FTC-Sim to open it");
        }
        if (formatVersion < PackageManifest.OLDEST_VERSION) {
            throw new ManifestException(source + ": formatVersion is " + formatVersion + ", older than the versions "
                    + PackageManifest.OLDEST_VERSION + " to " + PackageManifest.VERSION
                    + " this FTC-Sim reads: prepare the program again from its project");
        }
    }

    /** @return the paths of the files, which are unique even when Windows would treat names that differ in case as one */
    private Set<String> checkFiles(List<PackageManifest.PackageFile> files) {
        Set<String> exact = new HashSet<>();
        Set<String> folded = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            PackageManifest.PackageFile file = files.get(i);
            String where = "files[" + i + "]";
            String unusable = PackagePaths.problemWith(file.path());
            if (unusable != null) {
                throw problem(where + ".path", "('" + file.path() + "') " + unusable);
            }
            if (file.path().equals("manifest.json")) {
                throw problem(where + ".path", "lists the manifest itself, which cannot contain its own hash");
            }
            digest(where + ".sha256", file.sha256());
            if (file.size() < 0) {
                throw problem(where + ".size", "is negative");
            }
            if (!folded.add(file.path().toLowerCase(Locale.ROOT)) || !exact.add(file.path())) {
                throw problem(where + ".path", "('" + file.path() + "') is listed twice, or twice under names that differ only in case");
            }
        }
        return exact;
    }

    private void listedFile(String property, String path, Set<String> listed) {
        String unusable = PackagePaths.problemWith(path);
        if (unusable != null) {
            throw problem(property, "('" + path + "') " + unusable);
        }
        if (!listed.contains(path)) {
            throw problem(property, "('" + path + "') is not one of the package's listed files");
        }
    }

    private void checkResources(PackageManifest.Resources resources, Set<String> listed) {
        listedFile("resources.classes", resources.classes(), listed);
        if (!resources.javaResources().isEmpty()) {
            listedFile("resources.javaResources", resources.javaResources(), listed);
        }
        String directory = resources.directory();
        String unusable = PackagePaths.problemWith(directory);
        if (unusable != null) {
            throw problem("resources.directory", "('" + directory + "') " + unusable);
        }
        if (!listed.contains(directory + "/" + RESOURCE_TABLE)) {
            throw problem("resources.directory", "('" + directory + "') does not hold the listed file " + RESOURCE_TABLE);
        }
    }

    private void checkOpModes(PackageManifest.OpModes opModes) {
        if (opModes.count() != opModes.list().size()) {
            throw problem("opModes.count", "is " + opModes.count() + " but opModes.list has " + opModes.list().size() + " entries");
        }
        for (int i = 0; i < opModes.list().size(); i++) {
            OpModeDescriptor opMode = opModes.list().get(i);
            nonBlank("opModes.list[" + i + "].name", opMode.name());
            nonBlank("opModes.list[" + i + "].className", opMode.className());
            if (opMode.group() == null || opMode.kind() == null) {
                throw problem("opModes.list[" + i + "]", "lacks its group or kind");
            }
        }
    }

    private void nonBlank(String property, String value) {
        if (value == null || value.isBlank()) {
            throw problem(property, "is empty");
        }
    }

    private void digest(String property, String value) {
        if (!Sha256.isDigest(value)) {
            throw problem(property, "('" + value + "') is not a SHA-256 of 64 lower-case hexadecimal digits");
        }
    }
}
