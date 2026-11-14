package dev.ftcsim.importer.io;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Recursive deletion that never follows a link out of the tree it was asked to remove. */
public final class Trees {
    private Trees() {}

    /** Removes {@code root} and everything below it; a link (symbolic link or Windows junction) is removed, not entered. */
    public static void delete(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    deleteOne(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                deleteOne(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                deleteOne(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteOne(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (AccessDeniedException readOnly) {
            if (!path.toFile().setWritable(true)) {
                throw readOnly;
            }
            Files.deleteIfExists(path);
        }
    }
}
