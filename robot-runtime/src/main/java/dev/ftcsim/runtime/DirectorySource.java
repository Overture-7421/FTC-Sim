package dev.ftcsim.runtime;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class DirectorySource implements ClassSource {
    private final Path root;

    DirectorySource(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Optional<byte[]> read(String resourceName) throws IOException {
        Path file = resolve(resourceName);
        return file != null && Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
    }

    @Override
    public Optional<URL> locate(String resourceName) throws IOException {
        Path file = resolve(resourceName);
        return file != null && Files.exists(file) ? Optional.of(file.toUri().toURL()) : Optional.empty();
    }

    @Override
    public URL location() {
        try {
            return root.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {}

    /** Resource names use '/', and must never climb out of the root. */
    private Path resolve(String resourceName) {
        Path file = root.resolve(resourceName).normalize();
        return file.startsWith(root) ? file : null;
    }
}
