package dev.ftcsim.importer.pack;

import dev.ftcsim.importer.io.Trees;
import dev.ftcsim.importer.io.ZipBuilder;
import dev.ftcsim.importer.manifest.ManifestException;
import dev.ftcsim.importer.manifest.ManifestJson;
import dev.ftcsim.importer.manifest.PackageManifest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A prepared program as one zip file, for handing to drivers. The zip holds exactly the package folder's files,
 * in name order with fixed time stamps, so packing the same folder always gives the same bytes. Unpacking is
 * defensive: only files the manifest lists are written, each no larger than the manifest says, and the result is
 * then checked like any package, so a zip cannot write outside its target folder or grow without bound.
 */
public final class PackageZip {
    private static final long MAX_MANIFEST_BYTES = 64L * 1024 * 1024;

    private PackageZip() {}

    /** Writes the package to {@code zip}, replacing a file that is there. */
    public static void create(PreparedProgram program, Path zip) throws IOException {
        ZipBuilder archive = new ZipBuilder().add(PackageLayout.MANIFEST, program.directory().resolve(PackageLayout.MANIFEST));
        for (PackageManifest.PackageFile file : program.manifest().files()) {
            archive.add(file.path(), program.directory().resolve(file.path()));
        }
        archive.writeTo(zip);
    }

    /**
     * Unpacks {@code zip} into {@code target}, which must not exist or must be empty, and checks what came out.
     * Nothing is left in {@code target} when the zip is rejected.
     *
     * @throws PackageException when the zip is not a well-formed package
     */
    public static PreparedProgram extract(Path zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        if (Files.exists(root)) {
            if (!Files.isDirectory(root)) {
                throw new PackageException(root + " is a file; unpack into a new folder");
            }
            try (Stream<Path> contents = Files.list(root)) {
                if (contents.findAny().isPresent()) {
                    throw new PackageException(root + " already exists and is not empty; unpack into a new folder");
                }
            }
        }
        try {
            unpack(zip, root);
            return PreparedProgram.open(root);
        } catch (IOException | RuntimeException failure) {
            Trees.delete(root);
            throw failure;
        }
    }

    private static void unpack(Path zip, Path root) throws IOException {
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            ZipEntry manifestEntry = archive.getEntry(PackageLayout.MANIFEST);
            if (manifestEntry == null) {
                throw new PackageException(zip + " is not a prepared program: it has no " + PackageLayout.MANIFEST);
            }
            byte[] manifestBytes = readLimited(archive, manifestEntry, MAX_MANIFEST_BYTES, zip);
            PackageManifest manifest;
            try {
                manifest = ManifestJson.parse(new String(manifestBytes, StandardCharsets.UTF_8), zip.getFileName() + "!" + PackageLayout.MANIFEST);
            } catch (ManifestException invalid) {
                throw new PackageException(invalid.getMessage(), invalid);
            }

            Map<String, PackageManifest.PackageFile> listed = new TreeMap<>();
            manifest.files().forEach(file -> listed.put(file.path(), file));
            Set<String> seen = new HashSet<>();
            for (ZipEntry entry : Collections.list(archive.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (!seen.add(entry.getName())) {
                    throw new PackageException(zip + " holds " + entry.getName() + " twice");
                }
                if (!entry.getName().equals(PackageLayout.MANIFEST) && !listed.containsKey(entry.getName())) {
                    throw new PackageException(zip + " holds " + entry.getName() + ", which its manifest does not list");
                }
            }
            Files.createDirectories(root);
            Files.write(root.resolve(PackageLayout.MANIFEST), manifestBytes);
            for (PackageManifest.PackageFile file : manifest.files()) {
                ZipEntry entry = archive.getEntry(file.path());
                if (entry == null || entry.isDirectory()) {
                    throw new PackageException(zip + " lacks " + file.path() + ", which its manifest lists");
                }
                Path destination = root.resolve(file.path()).normalize();
                if (!destination.startsWith(root)) {
                    throw new PackageException(zip + " would write " + file.path() + " outside the package folder");
                }
                Files.createDirectories(destination.getParent());
                copyLimited(archive, entry, destination, file.size(), zip);
            }
        }
    }

    private static byte[] readLimited(ZipFile archive, ZipEntry entry, long limit, Path zip) throws IOException {
        try (InputStream in = archive.getInputStream(entry)) {
            byte[] bytes = in.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE));
            if (bytes.length > limit) {
                throw new PackageException(zip + ": " + entry.getName() + " is larger than " + limit / (1024 * 1024) + " MiB");
            }
            return bytes;
        }
    }

    /** Copies at most {@code size} bytes, the length the manifest declared; more is a zip that expands beyond what it claims. */
    private static void copyLimited(ZipFile archive, ZipEntry entry, Path destination, long size, Path zip) throws IOException {
        try (InputStream in = archive.getInputStream(entry); OutputStream out = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                copied += read;
                if (copied > size) {
                    throw new PackageException(zip + ": " + entry.getName() + " is longer than the " + size + " bytes its manifest lists");
                }
                out.write(buffer, 0, read);
            }
        }
    }
}
