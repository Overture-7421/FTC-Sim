package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THIRD-PARTY-NOTICES.txt and the licenses folder as {@code :app:thirdPartyNotices} wrote them into the distribution: every jar of lib
 * is covered by one entry, and every entry points at a licence text that is the licence it names. That the build fails for a jar whose
 * licence it cannot determine is the generator's own check; it has no output to look at here.
 */
class ThirdPartyNoticesTest {

    private static final String NOTICES = "THIRD-PARTY-NOTICES.txt";
    private static final Pattern COORDINATES = Pattern.compile("([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)");
    private static final Pattern COMPONENT = Pattern.compile("(.+), in the (?:native library|data file) (\\S+) of (\\S+\\.jar)");

    // The licence texts come from the projects' sites and are not generated, so this is what catches an error page, an empty file
    // or the text of another project under the right name.
    private static final Map<String, List<String>> TEXT_MARKERS = Map.ofEntries(
            Map.entry("Apache-2.0.txt", List.of("Apache License", "Version 2.0, January 2004", "END OF TERMS AND CONDITIONS")),
            Map.entry("BSD-3-Clause-ASM.txt", List.of("INRIA, France Telecom", "Redistribution and use in source and binary forms")),
            Map.entry("BSD-3-Clause-LWJGL.txt", List.of("Lightweight Java Game Library", "Redistribution and use in source and binary forms")),
            Map.entry("BSD-3-Clause-Skia.txt", List.of("Google Inc.", "Redistribution and use in source and binary forms")),
            Map.entry("GPL-2.0-with-classpath-exception.txt", List.of("The GNU General Public License (GPL)", "Version 2, June 1991", "\"CLASSPATH\" EXCEPTION TO THE GPL")),
            Map.entry("MIT-jolt-jni.txt", List.of("Stephen Gold", "Permission is hereby granted, free of charge")),
            Map.entry("MIT-Jolt-Physics.txt", List.of("Jorrit Rouwe", "Permission is hereby granted, free of charge")),
            Map.entry("Unicode-ICU-data.txt", List.of("UNICODE LICENSE V3", "Unicode, Inc.")),
            Map.entry("Zlib-GLFW.txt", List.of("Marcus Geelnard", "This software is provided 'as-is'")));

    /** One "File:" block of the notices: the jar it is about and the indented lines under it. */
    private record Entry(String jar, List<String> details) {
        List<String> values(String key) {
            String prefix = "  " + key + ": ";
            return details.stream().filter(line -> line.startsWith(prefix)).map(line -> line.substring(prefix.length())).toList();
        }

        boolean isLibrary() {
            return !values("Library").isEmpty();
        }
    }

    private Path dist;
    private Path zip;
    private Path manifest;
    private List<String> lines;
    private List<Entry> entries;

    @BeforeEach
    void locate() throws IOException {
        String folder = System.getProperty("ftcsim.dist");
        assumeTrue(folder != null, "run through Gradle, which builds the distribution and names it in ftcsim.dist");
        dist = Path.of(folder);
        zip = Path.of(System.getProperty("ftcsim.distZip"));
        manifest = Path.of(System.getProperty("ftcsim.launcherScripts")).resolve("jdk-manifest.json");
        lines = Files.readAllLines(dist.resolve(NOTICES), StandardCharsets.UTF_8);
        entries = fileEntries(lines);
    }

    private static List<Entry> fileEntries(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        String jar = null;
        List<String> details = new ArrayList<>();
        for (String line : lines) {
            if (jar != null && !line.startsWith("  ")) {
                entries.add(new Entry(jar, details));
                jar = null;
            }
            if (line.startsWith("File: ")) {
                jar = line.substring("File: ".length()).split(" ")[0];
                details = new ArrayList<>();
            } else if (jar != null) {
                details.add(line);
            }
        }
        if (jar != null) {
            entries.add(new Entry(jar, details));
        }
        return entries;
    }

    private List<String> namesIn(String folder) throws IOException {
        try (Stream<Path> files = Files.list(dist.resolve(folder))) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static String only(List<String> values, String what) {
        assertEquals(1, values.size(), what + ": " + values);
        return values.get(0);
    }

    @Test
    void everyJarOfLibIsCoveredByExactlyOneEntryAndEveryEntryIsAJarOfLibInStableOrder() throws IOException {
        List<String> inLib = namesIn("lib").stream().filter(name -> name.endsWith(".jar")).toList();
        List<String> named = entries.stream().map(Entry::jar).toList();

        assertEquals(new TreeSet<>(inLib), new TreeSet<>(named), "the jars of lib against the jars the notices name");
        assertEquals(named.size(), new HashSet<>(named).size(), "a jar covered twice: " + named);
        for (boolean libraries : new boolean[] {false, true}) {
            List<String> group = entries.stream().filter(entry -> entry.isLibrary() == libraries).map(Entry::jar).toList();
            assertEquals(group.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), group, "sorted, so that the same jars always give the same file");
        }
        assertFalse(Pattern.compile("[A-Za-z]:\\\\").matcher(String.join("\n", lines)).find(), "no path of the machine that built it");
    }

    @Test
    void everyLibraryGivesItsCoordinatesItsLicenceWithAnAddressAndATextThatIsInTheLicensesFolder() throws IOException {
        List<Entry> libraries = entries.stream().filter(Entry::isLibrary).toList();

        assertFalse(libraries.isEmpty(), "the notices name no library");
        for (Entry entry : libraries) {
            String jar = entry.jar();
            Matcher library = COORDINATES.matcher(only(entry.values("Library"), jar));
            assertTrue(library.matches(), jar + ": group:artifact:version");
            assertTrue(jar.startsWith(library.group(2) + "-" + library.group(3)), jar + " is not the jar of " + library.group());
            List<String> licences = entry.values("Licence");
            assertFalse(licences.isEmpty(), jar + " names no licence");
            assertEquals(licences.size(), entry.values("Licence URL").size(), jar + ": an address for each licence");
            for (String url : entry.values("Licence URL")) {
                assertTrue(url.startsWith("https://") || url.startsWith("http://"), jar + ": " + url);
            }
            assertEquals(licences.size(), entry.values("Licence text").size(), jar + ": a text for each licence");
            for (String text : entry.values("Licence text")) {
                Path file = dist.resolve(text);
                assertTrue(text.startsWith("licenses/") && Files.isRegularFile(file) && Files.size(file) > 500, jar + ": " + text);
            }
        }
    }

    @Test
    void projectsThatPublishTheirOwnCopyrightNoticeHaveItAndTheRestTheStandardText() {
        Map<String, String> textOf = Map.of(
                "asm-", "licenses/BSD-3-Clause-ASM.txt",
                "lwjgl-", "licenses/BSD-3-Clause-LWJGL.txt",
                "jolt-jni-Windows64-", "licenses/MIT-jolt-jni.txt",
                "jackson-core-", "licenses/Apache-2.0.txt",
                "kotlin-stdlib-", "licenses/Apache-2.0.txt",
                "skiko-awt-runtime-windows-x64-", "licenses/Apache-2.0.txt");
        for (Map.Entry<String, String> expected : textOf.entrySet()) {
            List<Entry> matching = entries.stream().filter(entry -> entry.jar().startsWith(expected.getKey())).toList();
            assertFalse(matching.isEmpty(), "no jar " + expected.getKey() + "* in lib: this table is out of date");
            for (Entry entry : matching) {
                assertEquals(List.of(expected.getValue()), entry.values("Licence text"), entry.jar());
            }
        }
    }

    @Test
    void everyCodeThatIsBuiltIntoANativeLibraryIsNamedWithTheJarThatReallyHoldsIt() throws IOException {
        int components = 0;
        for (String line : lines) {
            Matcher component = COMPONENT.matcher(line);
            if (!component.matches()) {
                continue;
            }
            components++;
            try (JarFile jar = new JarFile(dist.resolve("lib").resolve(component.group(3)).toFile())) {
                assertTrue(jar.stream().anyMatch(entry -> entry.getName().equals(component.group(2)) || entry.getName().endsWith("/" + component.group(2))), line);
            }
        }
        assertTrue(components >= 4, "Jolt, GLFW, Skia and the ICU data are built into native libraries: " + components);
    }

    @Test
    void theLicensesFolderHoldsExactlyTheTextsTheNoticesPointAtAndEachIsTheLicenceItIsNamedFor() throws IOException {
        String pointer = "Licence text: licenses/";
        Set<String> pointedAt = lines.stream().map(String::strip).filter(line -> line.startsWith(pointer))
                .map(line -> line.substring(pointer.length())).collect(Collectors.toCollection(TreeSet::new));
        int list = lines.indexOf("5. Licence texts");
        assertTrue(list >= 0, "the notices end with the list of licence texts");
        Set<String> listed = lines.subList(list, lines.size()).stream().filter(line -> line.startsWith("licenses/"))
                .map(line -> line.substring("licenses/".length())).collect(Collectors.toCollection(TreeSet::new));
        Set<String> present = new TreeSet<>(namesIn("licenses"));

        assertEquals(pointedAt, present, "the texts the entries point at against the texts in the folder");
        assertEquals(present, listed, "the list at the end of the notices");
        assertEquals(TEXT_MARKERS.keySet(), present, "a licence text without markers: add what it must contain to TEXT_MARKERS");
        for (Map.Entry<String, List<String>> text : TEXT_MARKERS.entrySet()) {
            String content = Files.readString(dist.resolve("licenses").resolve(text.getKey()), StandardCharsets.UTF_8);
            for (String marker : text.getValue()) {
                assertTrue(content.contains(marker), text.getKey() + " does not contain '" + marker + "'");
            }
        }
    }

    @Test
    void theNoticesNameTheJavaRuntimeThatTheManifestPinsAndItsLicence() throws IOException {
        Matcher pin = Pattern.compile("\"exactRelease\"\\s*:\\s*\"([^\"]+)\"").matcher(Files.readString(manifest, StandardCharsets.UTF_8));
        assertTrue(pin.find(), "the launcher's manifest pins the runtime release");
        String text = String.join("\n", lines);

        assertTrue(text.contains("Eclipse Temurin " + pin.group(1) + " (OpenJDK)"), "the pinned release " + pin.group(1));
        assertTrue(text.contains("Licence: GPL-2.0-with-classpath-exception"));
    }

    @Test
    void theZipCarriesTheNoticesAndEveryLicenceTextUnchanged() throws IOException {
        List<String> files = new ArrayList<>(List.of(NOTICES));
        namesIn("licenses").forEach(name -> files.add("licenses/" + name));
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            for (String file : files) {
                var entry = archive.getEntry("FTC-Sim/" + file);
                assertNotNull(entry, file + " is not in the zip");
                try (InputStream content = archive.getInputStream(entry)) {
                    assertArrayEquals(Files.readAllBytes(dist.resolve(file)), content.readAllBytes(), file);
                }
            }
        }
    }
}
