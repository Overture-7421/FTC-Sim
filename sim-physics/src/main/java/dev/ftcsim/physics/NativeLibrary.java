package dev.ftcsim.physics;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Extracts the jolt-jni DLL from the classpath into a folder named after its SHA-256 and loads it once per JVM. The
 * folder name keeps the DLLs of different builds apart: Windows cannot replace one that another JVM has loaded.
 */
final class NativeLibrary {

    private static final String RESOURCE = "/windows/x86-64/com/github/stephengold/joltjni.dll";

    private static String loadedSha256;

    private NativeLibrary() {}

    /**
     * @param directory extraction root; the DLL lands in a subdirectory named after its hash
     * @return the SHA-256 of the loaded DLL
     */
    @SuppressWarnings("restricted")
    static synchronized String load(Path directory) {
        if (loadedSha256 != null) {
            return loadedSha256;
        }
        byte[] dll = readResource();
        String actual = Sha256.hex(dll);
        System.load(extract(directory.resolve(actual), dll).toAbsolutePath().toString());
        loadedSha256 = actual;
        return actual;
    }

    private static byte[] readResource() {
        try (InputStream in = NativeLibrary.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("joltjni.dll is not on the classpath: " + RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // A DLL that another JVM has already loaded cannot be replaced on Windows, so an existing file
    // with identical content is reused and a lost extraction race is tolerated.
    private static Path extract(Path directory, byte[] dll) {
        Path target = directory.resolve("joltjni.dll");
        try {
            if (hasContent(target, dll)) {
                return target;
            }
            Files.createDirectories(directory);
            Path partial = Files.createTempFile(directory, "joltjni", ".part");
            try {
                Files.write(partial, dll);
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(partial);
                if (!hasContent(target, dll)) {
                    throw e;
                }
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean hasContent(Path file, byte[] expected) throws IOException {
        return Files.isRegularFile(file) && Arrays.equals(Files.readAllBytes(file), expected);
    }
}
