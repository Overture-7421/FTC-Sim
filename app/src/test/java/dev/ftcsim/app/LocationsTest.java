package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocationsTest {

    @TempDir
    Path root;

    @Test
    void theResourceOfTheApplicationNamesTheUsualPlaces() throws IOException {
        List<String> patterns = JdkPatterns.bundled();

        assertTrue(patterns.contains("%ProgramFiles%\\Eclipse Adoptium\\*"), patterns.toString());
        assertTrue(patterns.contains("%ProgramFiles%\\Android\\Android Studio*\\jbr"), patterns.toString());
        assertTrue(patterns.stream().anyMatch(pattern -> pattern.contains(".gradle\\jdks")), patterns.toString());
        assertTrue(patterns.stream().noneMatch(pattern -> pattern.startsWith("HK") || pattern.startsWith("#")), "only folders: " + patterns);
    }

    @Test
    void commentsAndBlankLinesAreLeftOutOfTheListOfPlacesAndAListWithoutPlacesIsRefused() throws IOException {
        String text = "# a comment\r\nA\r\n\r\n  B  \r\n# C\r\n";

        assertEquals(List.of("A", "B"), JdkPatterns.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        assertTrue(assertThrows(IOException.class, () -> JdkPatterns.read(new ByteArrayInputStream("# nothing\n".getBytes(StandardCharsets.UTF_8))))
                .getMessage().contains("jdk-patterns.txt"));
    }

    @Test
    void wildcardsMatchWithinOneSegmentOnlyAndOnlyExistingFoldersCount() throws IOException {
        Files.createDirectories(root.resolve("Android").resolve("Android Studio 2025").resolve("jbr"));
        Files.createDirectories(root.resolve("Android").resolve("Android Studio").resolve("jbr"));
        Files.createDirectories(root.resolve("Android").resolve("Android Studio Preview").resolve("lib"));
        Files.writeString(root.resolve("Android").resolve("Android Studio file"), "not a folder");
        Files.createDirectories(root.resolve("a").resolve("one").resolve("deep"));

        assertEquals(
                List.of(root.resolve("Android").resolve("Android Studio").resolve("jbr"), root.resolve("Android").resolve("Android Studio 2025").resolve("jbr")).stream().sorted().toList(),
                PathPatterns.folders(root + "\\Android\\Android Studio*\\jbr").stream().sorted().toList());
        assertEquals(List.of(root.resolve("a").resolve("one")), PathPatterns.folders(root + "\\a\\*"));
        assertEquals(List.of(), PathPatterns.folders(root + "\\a\\*\\*\\deeper"), "a wildcard stands for one level");
        assertEquals(List.of(), PathPatterns.folders("relative\\*"), "only absolute paths are looked at");
        assertEquals(List.of(), PathPatterns.folders(root + "\\a\\<invalid>*"));
    }

    @Test
    void aVariableThatIsNotSetMakesAPatternMatchNothingAndQuotesAroundAValueAreDropped() {
        Machine machine = new Machine(name -> switch (name) {
            case "SET" -> " \"C:\\Program Files\" ";
            case "BLANK" -> "  ";
            default -> null;
        });

        assertEquals("C:\\Program Files\\x", machine.expand("%SET%\\x"));
        assertEquals(null, machine.expand("%SET%\\%MISSING%"));
        assertEquals(null, machine.expand("%BLANK%\\x"));
        assertEquals("plain", machine.expand("plain"));
    }

    @Test
    void theAndroidSdkIsTheFirstExistingOfTheUsualPlacesAndOtherwiseWhereTheUserLooksFirst() throws IOException {
        FakeMachine pc = new FakeMachine(root);
        Path standard = Files.createDirectories(pc.localAppData("Android", "Sdk"));
        assertEquals(standard, new AndroidSdkDiscovery(pc.machine()).find());

        Path root2 = Files.createDirectories(root.resolve("sdk-root"));
        pc.variables.put("ANDROID_SDK_ROOT", root2.toString());
        assertEquals(root2, new AndroidSdkDiscovery(pc.machine()).find(), "ANDROID_SDK_ROOT before the standard place");

        Path home = Files.createDirectories(root.resolve("sdk-home"));
        pc.variables.put("ANDROID_HOME", home.toString());
        assertEquals(home, new AndroidSdkDiscovery(pc.machine()).find(), "ANDROID_HOME before both");

        pc.variables.put("ANDROID_HOME", root.resolve("not-there").toString());
        assertEquals(root2, new AndroidSdkDiscovery(pc.machine()).find(), "a variable that names nothing is passed over");

        Files.delete(root2);
        Files.delete(standard);
        assertEquals(root.resolve("not-there"), new AndroidSdkDiscovery(pc.machine()).find(), "none exists: the importer says the first is missing");
    }
}
