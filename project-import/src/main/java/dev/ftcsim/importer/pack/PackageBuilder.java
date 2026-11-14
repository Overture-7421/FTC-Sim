package dev.ftcsim.importer.pack;

import dev.ftcsim.importer.io.Sha256;
import dev.ftcsim.importer.io.ZipBuilder;
import dev.ftcsim.importer.manifest.ManifestJson;
import dev.ftcsim.importer.manifest.PackageManifest;
import dev.ftcsim.importer.resources.ResourceBuilder;
import dev.ftcsim.importer.scan.Finding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lays a prepared program out in a new folder: the team's code as jars, the libraries as numbered jars, the
 * generated resources and the merged Java resources of the libraries, and last the manifest with the hash of
 * everything else. The manifest comes last, so a folder that was interrupted has none and is not mistaken for a package.
 */
public final class PackageBuilder {
    private static final int NAME_LIMIT = 80;

    private final Path root;
    private final List<String> program = new ArrayList<>();
    private final List<PackageManifest.Library> libraries = new ArrayList<>();
    private boolean resourcesAdded;
    private String javaResources = "";

    /** @param root the package folder, which must not exist or must be empty */
    public PackageBuilder(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        if (Files.exists(this.root)) {
            try (Stream<Path> contents = Files.list(this.root)) {
                if (contents.findAny().isPresent()) {
                    throw new IOException("The package folder " + this.root + " already exists and is not empty");
                }
            }
        }
        Files.createDirectories(this.root);
    }

    /**
     * Adds a part of the team's code as {@code program/<name>.jar}. The sources are class folders and jars; when
     * several hold the same file the first wins, as it would on a class path.
     *
     * @return the jar's path inside the package
     */
    public String addProgram(String name, List<Path> sources) throws IOException {
        String path = PackageLayout.PROGRAM + "/" + fileName(name) + ".jar";
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        if (sources.size() == 1 && Files.isRegularFile(sources.get(0))) {
            Files.copy(sources.get(0), target);
        } else {
            ZipBuilder jar = new ZipBuilder();
            for (Path source : sources) {
                if (Files.isDirectory(source)) {
                    addFolder(jar, source);
                } else {
                    addJar(jar, source);
                }
            }
            jar.writeTo(target);
        }
        program.add(path);
        return path;
    }

    /** Adds a library jar as the next numbered file below {@code libs/}; call in class path order. */
    public String addLibrary(String id, Path jar) throws IOException {
        String path = String.format(Locale.ROOT, "%s/%03d-%s.jar", PackageLayout.LIBRARIES, libraries.size() + 1, fileName(id));
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.copy(jar, target);
        libraries.add(new PackageManifest.Library(path, id));
        return path;
    }

    /** Adds what {@link ResourceBuilder#writeTo} generated: the R classes, the resource table and the assets. */
    public void addResources(Path generated) throws IOException {
        Path target = root.resolve(PackageLayout.RESOURCES);
        Files.createDirectories(target);
        Files.copy(generated.resolve(ResourceBuilder.R_JAR), target.resolve(ResourceBuilder.R_JAR));
        Files.copy(generated.resolve(ResourceBuilder.TABLE), target.resolve(ResourceBuilder.TABLE));
        Path assets = generated.resolve(ResourceBuilder.ASSETS);
        if (Files.isDirectory(assets)) {
            try (Stream<Path> files = Files.walk(assets)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    Path destination = target.resolve(ResourceBuilder.ASSETS).resolve(assets.relativize(file).toString());
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        resourcesAdded = true;
    }

    /**
     * Adds the merged Java resources of the libraries and the project as {@code resources/java-res.jar}; see
     * {@code JavaResourceMerger}. A program without Java resources simply does not call this.
     */
    public void addJavaResources(Path jar) throws IOException {
        Path target = root.resolve(PackageLayout.RESOURCES).resolve(PackageLayout.JAVA_RESOURCES);
        Files.createDirectories(target.getParent());
        Files.copy(jar, target);
        javaResources = PackageLayout.RESOURCES + "/" + PackageLayout.JAVA_RESOURCES;
    }

    /** Hashes every file and writes {@code manifest.json}, which makes the folder a package. */
    public PackageManifest finish(
            PackageManifest.Project project, PackageManifest.Sdk sdk, PackageManifest.OpModes opModes, List<Finding> warnings)
            throws IOException {
        if (!resourcesAdded || program.isEmpty()) {
            throw new IllegalStateException("A package needs program code and resources before it can be finished");
        }
        PackageManifest manifest = new PackageManifest(
                PackageManifest.FORMAT,
                PackageManifest.VERSION,
                PackageManifest.IMPORTER_REVISION,
                project,
                sdk,
                program,
                libraries,
                new PackageManifest.Resources(
                        PackageLayout.RESOURCES + "/" + ResourceBuilder.R_JAR, PackageLayout.RESOURCES, javaResources),
                opModes,
                warnings,
                hashes(),
                new LinkedHashMap<>());
        ManifestJson.write(root.resolve(PackageLayout.MANIFEST), manifest);
        return manifest;
    }

    private List<PackageManifest.PackageFile> hashes() throws IOException {
        List<PackageManifest.PackageFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                files.add(new PackageManifest.PackageFile(relative, Sha256.of(file), Files.size(file)));
            }
        }
        files.sort(Comparator.comparing(PackageManifest.PackageFile::path));
        return files;
    }

    private static void addFolder(ZipBuilder jar, Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                jar.addIfAbsent(folder.relativize(file).toString().replace('\\', '/'), file);
            }
        }
    }

    private static void addJar(ZipBuilder jar, Path source) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.isDirectory()) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        jar.addIfAbsent(entry.getName(), in.readAllBytes());
                    }
                }
            }
        }
    }

    /** A name that is safe in a file name on Windows and still says what the file is. */
    static String fileName(String text) {
        String safe = text.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^[.]+", "_");
        if (safe.length() > NAME_LIMIT) {
            safe = safe.substring(0, NAME_LIMIT);
        }
        return safe.isEmpty() ? "_" : safe;
    }
}
