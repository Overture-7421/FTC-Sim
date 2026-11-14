package dev.ftcsim.importer.snapshot;

import dev.ftcsim.importer.ImportException;
import dev.ftcsim.importer.ImportException.Failure;
import dev.ftcsim.importer.ImportProgress;
import dev.ftcsim.importer.io.Sha256;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A private copy of a project in a folder the application owns. The importer runs the project's Gradle build in
 * the copy and never in the original, so the original is only ever read. Reading the original is also how its
 * {@link SourceManifest} is taken: each file is hashed while it is copied, which is what the later check of the
 * original is compared with.
 *
 * <p>Folders of build tools ({@code build}, {@code .gradle} and so on, see {@code ProjectFiles}) are not copied. A
 * copy that already exists is brought up to date in place and keeps its own build folders, so importing the same
 * project again does not start Gradle from nothing.
 */
public final class ProjectSnapshot {

    /**
     * @param directory    the folder that holds the copy
     * @param manifest     the hashes of the original's files at the moment they were copied
     * @param skippedLinks paths of links in the original that were not followed
     */
    public record Copy(Path directory, SourceManifest manifest, List<String> skippedLinks) {}

    private static final int PROGRESS_EVERY = 200;

    private ProjectSnapshot() {}

    public static Copy create(Path original, Path directory, ImportProgress progress) throws IOException {
        Path source = original.toRealPath();
        Path target = directory.toAbsolutePath().normalize();
        if (target.startsWith(source) || source.startsWith(target)) {
            throw new ImportException(
                    Failure.INVALID_REQUEST,
                    "The workspace " + target + " and the project " + source + " must be separate folders, "
                            + "neither inside the other.");
        }
        Files.createDirectories(target);
        clear(target);

        SortedMap<String, String> hashes = new TreeMap<>();
        List<String> links = new ArrayList<>();
        long[] bytes = {0};
        ProjectFiles.walk(source, new ProjectFiles.Visitor() {
            @Override
            public void file(String relative, Path file, BasicFileAttributes attributes) throws IOException {
                progress.checkCancelled();
                Path copy = target.resolve(relative);
                Files.createDirectories(copy.getParent());
                hashes.put(relative, copyAndHash(file, copy));
                Files.setLastModifiedTime(copy, attributes.lastModifiedTime());
                bytes[0] += attributes.size();
                if (hashes.size() % PROGRESS_EVERY == 0) {
                    progress.message("Copied " + hashes.size() + " files");
                }
            }

            @Override
            public void link(String relative) {
                links.add(relative);
            }
        });
        progress.message("Copied " + hashes.size() + " files (" + bytes[0] / 1024 + " KiB) to " + target);
        return new Copy(target, new SourceManifest(hashes), links);
    }

    /** Removes the copy of an earlier import except for what build tools left behind. */
    private static void clear(Path target) throws IOException {
        ProjectFiles.walk(target, new ProjectFiles.Visitor() {
            @Override
            public void file(String relative, Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
            }

            @Override
            public void link(String relative) throws IOException {
                Files.delete(target.resolve(relative));
            }
        });
        ProjectFiles.deleteEmptyDirectories(target);
    }

    private static String copyAndHash(Path from, Path to) throws IOException {
        MessageDigest digest = Sha256.newDigest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(from), digest);
                OutputStream out = Files.newOutputStream(to)) {
            in.transferTo(out);
        }
        return Sha256.hex(digest);
    }
}
