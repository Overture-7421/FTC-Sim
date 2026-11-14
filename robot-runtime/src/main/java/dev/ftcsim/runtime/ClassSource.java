package dev.ftcsim.runtime;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.Optional;

/** One entry of the robot's classpath: a directory of classes or a jar. */
interface ClassSource extends Closeable {

    /** Bytes of the named resource, for example {@code com/acme/Foo.class}. */
    Optional<byte[]> read(String resourceName) throws IOException;

    Optional<URL> locate(String resourceName) throws IOException;

    /** Where this source lives, reported as the code source of the classes it defines. */
    URL location();

    static ClassSource of(java.nio.file.Path path) throws IOException {
        return java.nio.file.Files.isDirectory(path) ? new DirectorySource(path) : new JarSource(path);
    }
}
