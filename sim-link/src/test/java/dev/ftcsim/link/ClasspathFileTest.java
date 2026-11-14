package dev.ftcsim.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathFileTest {
    private static final List<String> KEYS = List.of("worker", "robot", "note");

    @TempDir
    Path folder;

    private Path write(String... lines) throws IOException {
        return Files.writeString(folder.resolve("paths.properties"), String.join("\n", lines) + "\n");
    }

    private Path jar(String name) throws IOException {
        return Files.writeString(folder.resolve(name), "jar");
    }

    @Test
    void entriesAreTakenAsWrittenAndRelativeOnesAreRelativeToTheFile() throws IOException {
        Path absolute = jar("absolute.jar");
        jar("beside.jar");
        Files.createDirectories(folder.resolve("lib"));
        jar("lib\\inner.jar");
        ClasspathFile file = ClasspathFile.read(
                write("# the jars", "", "worker = " + absolute + File.pathSeparator + "beside.jar" + File.pathSeparator + "lib\\inner.jar", "note=C:\\no escapes\\here"),
                KEYS);

        assertEquals(List.of(absolute, folder.resolve("beside.jar"), folder.resolve("lib").resolve("inner.jar")), file.classpath("worker"));
        assertEquals("C:\\no escapes\\here", file.text("note", "none"));
        assertEquals("none", file.text("robot", "none"));
        assertTrue(file.has("worker"));
        assertFalse(file.has("robot"));
    }

    @Test
    void whatIsWrongIsSaidWithTheFileAndTheLine() throws IOException {
        jar("a.jar");
        String unknown = assertThrows(IOException.class, () -> ClasspathFile.read(write("worker=a.jar", "robots=a.jar"), KEYS)).getMessage();
        assertTrue(unknown.contains("line 2") && unknown.contains("unknown key 'robots'") && unknown.contains("worker, robot and note"), unknown);
        String twice = assertThrows(IOException.class, () -> ClasspathFile.read(write("worker=a.jar", "worker=a.jar"), KEYS)).getMessage();
        assertTrue(twice.contains("line 2") && twice.contains("a second time"), twice);
        String bare = assertThrows(IOException.class, () -> ClasspathFile.read(write("worker=a.jar", "just words"), KEYS)).getMessage();
        assertTrue(bare.contains("line 2") && bare.contains("is not 'key=value'"), bare);

        ClasspathFile file = ClasspathFile.read(write("worker=a.jar" + File.pathSeparator + "gone.jar", "robot=" + File.pathSeparator), KEYS);
        String missing = assertThrows(IOException.class, () -> file.classpath("worker")).getMessage();
        assertTrue(missing.contains("1 entries that do not exist") && missing.contains("gone.jar"), missing);
        assertTrue(assertThrows(IOException.class, () -> file.classpath("robot")).getMessage().contains("lists no class path entries"));
        assertTrue(assertThrows(IOException.class, () -> file.classpath("note")).getMessage().contains("has no 'note' line"));
    }
}
