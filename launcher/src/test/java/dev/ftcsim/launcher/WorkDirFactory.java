package dev.ftcsim.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/** Temporary directories inside the module's build directory, so tests never write outside the project. */
final class WorkDirFactory implements TempDirFactory {

    static Path base() throws IOException {
        Path base = Path.of(System.getProperty("ftcsim.test.work", System.getProperty("java.io.tmpdir")));
        return Files.createDirectories(base);
    }

    @Override
    public Path createTempDirectory(AnnotatedElementContext elementContext, ExtensionContext extensionContext)
            throws IOException {
        return Files.createTempDirectory(base(), "t-");
    }
}
