package dev.ftcsim.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class JarSource implements ClassSource {
    private final Path path;
    private final JarFile jar;

    JarSource(Path path) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        this.jar = new JarFile(this.path.toFile());
    }

    @Override
    public Optional<byte[]> read(String resourceName) throws IOException {
        JarEntry entry = jar.getJarEntry(resourceName);
        if (entry == null || entry.isDirectory()) {
            return Optional.empty();
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return Optional.of(in.readAllBytes());
        }
    }

    @Override
    public Optional<URL> locate(String resourceName) throws IOException {
        JarEntry entry = jar.getJarEntry(resourceName);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(URI.create("jar:" + path.toUri() + "!/" + resourceName).toURL());
    }

    @Override
    public URL location() {
        try {
            return path.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws IOException {
        jar.close();
    }
}
