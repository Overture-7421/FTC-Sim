package dev.ftcsim.importer.javares;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.ftcsim.importer.Fixtures;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaResourceMergerTest {
    private static final byte[] CLASS = {(byte) 0xca, (byte) 0xfe};

    private static Path jar(Path folder, String name, Map<String, String> entries) throws IOException {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        entries.forEach((entry, text) -> bytes.put(entry, text.getBytes(UTF_8)));
        return Fixtures.jar(folder.resolve(name), bytes);
    }

    private static Map<String, String> read(Path jar) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), new String(in.readAllBytes(), UTF_8));
                }
            }
        }
        return entries;
    }

    private static JavaResourceMerger.Result merge(Path target, JavaResourceMerger.Source... sources) throws IOException {
        return JavaResourceMerger.merge(List.of(sources), PackagingRules.forAnApk(), target);
    }

    @Test
    void theResourcesOfALibraryJarAreKeptAndItsClassesAndNativeLibrariesAreNot(@TempDir Path temp) throws IOException {
        Path library = Fixtures.jar(temp.resolve("field.jar"), Map.of(
                "com/bylazar/FieldImages.class", CLASS,
                "decode-dark.png", "dark".getBytes(UTF_8),
                "config/settings.properties", "a=b".getBytes(UTF_8),
                "jni/libfield.so", "ELF".getBytes(UTF_8)));
        Path target = temp.resolve("out/java-res.jar");

        JavaResourceMerger.Result result = merge(target, new JavaResourceMerger.Source("com.bylazar:field:1.0.6", library));

        assertEquals(Map.of("config/settings.properties", "a=b", "decode-dark.png", "dark"), read(target));
        assertEquals(new JavaResourceMerger.Result(2, 0, List.of()), result);
    }

    @Test
    void filesOfOneNameKeepTheFirstSourceAndTheOthersAreReportedOnlyWhenTheyDiffer(@TempDir Path temp) throws IOException {
        Path first = jar(temp, "first.jar", Map.of("a.txt", "first", "same.txt", "same"));
        Path second = jar(temp, "second.jar", Map.of("a.txt", "second", "same.txt", "same"));
        Path third = jar(temp, "third.jar", Map.of("a.txt", "third"));
        Path target = temp.resolve("java-res.jar");

        JavaResourceMerger.Result result = merge(
                target,
                new JavaResourceMerger.Source("one", first),
                new JavaResourceMerger.Source("two", second),
                new JavaResourceMerger.Source("three", third));

        assertEquals(Map.of("a.txt", "first", "same.txt", "same"), read(target));
        assertEquals(List.of(new JavaResourceMerger.Conflict("a.txt", "one", List.of("two", "three"))), result.conflicts());
    }

    @Test
    void serviceProviderFilesOfSeveralSourcesAreJoinedWithALineFeedWhereOneIsMissing(@TempDir Path temp) throws IOException {
        String services = "META-INF/services/com.acme.Provider";
        Path target = temp.resolve("java-res.jar");

        JavaResourceMerger.Result result = merge(
                target,
                new JavaResourceMerger.Source("a", jar(temp, "a.jar", Map.of(services, "a.A"))),
                new JavaResourceMerger.Source("b", jar(temp, "b.jar", Map.of(services, "b.B\n"))),
                new JavaResourceMerger.Source("c", jar(temp, "c.jar", Map.of(services, ""))),
                new JavaResourceMerger.Source("d", jar(temp, "d.jar", Map.of(services, "d.D"))));

        assertEquals(Map.of(services, "a.A\nb.B\n\nd.D"), read(target), "as the plugin joins them: a line feed after a file that does not end with one, an empty file included");
        assertEquals(new JavaResourceMerger.Result(1, 1, List.of()), result);
    }

    @Test
    void theAndroidGradlePluginsDefaultExcludesAndTheApksKotlinModuleExcludeAreApplied(@TempDir Path temp) throws IOException {
        Map<String, String> excluded = new LinkedHashMap<>();
        for (String name : List.of(
                "META-INF/MANIFEST.MF", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/ABC.SF", "META-INF/ABC.RSA",
                "META-INF/maven/g/a/pom.xml", "META-INF/proguard/x.pro", "META-INF/com.android.tools/y", "LICENSE", "NOTICE.txt", ".hidden",
                "dir/.hidden", ".svn/entries", "x/_private.txt", "_top.txt", "backup~", "a/thumbs.db", "pkg/package.html", "pkg/about.html",
                "m.kotlin_metadata", "META-INF/foo.kotlin_module", "../evil.txt")) {
            excluded.put(name, "x");
        }
        Map<String, String> kept = new LinkedHashMap<>();
        for (String name : List.of(
                "META-INF/LICENSE.md", "META-INF/AL2.0", "META-INF/versions/9/data.txt", "kotlin/kotlin.kotlin_builtins", "pkg/foo.kotlin_module",
                "images/a.png", "about.txt", "pkg/readme.html")) {
            kept.put(name, "kept");
        }
        Map<String, String> all = new LinkedHashMap<>(excluded);
        all.putAll(kept);
        Path target = temp.resolve("java-res.jar");

        merge(target, new JavaResourceMerger.Source("library", jar(temp, "library.jar", all)));

        assertEquals(new TreeMap<>(kept), read(target));
    }

    @Test
    void aFolderIsASourceTooAndAnEarlierSourceWins(@TempDir Path temp) throws IOException {
        Path own = Files.createDirectories(temp.resolve("src-main-resources/nested"));
        Files.writeString(own.resolve("a.txt"), "from the project");
        Path library = jar(temp, "library.jar", Map.of("nested/a.txt", "from a library", "b.txt", "b"));
        Path target = temp.resolve("java-res.jar");

        JavaResourceMerger.Result result = merge(
                target,
                new JavaResourceMerger.Source(":TeamCode", temp.resolve("src-main-resources")),
                new JavaResourceMerger.Source("com.acme:lib:1", library));

        assertEquals(Map.of("nested/a.txt", "from the project", "b.txt", "b"), read(target));
        assertEquals(List.of(new JavaResourceMerger.Conflict("nested/a.txt", ":TeamCode", List.of("com.acme:lib:1"))), result.conflicts());
    }

    @Test
    void theSameSourcesGiveTheSameBytesAndNoJarIsWrittenWhenThereAreNoResources(@TempDir Path temp) throws IOException {
        Path library = jar(temp, "library.jar", Map.of("b.txt", "b", "a.txt", "a", "META-INF/services/x", "x"));
        JavaResourceMerger.Source source = new JavaResourceMerger.Source("library", library);

        merge(temp.resolve("one.jar"), source);
        merge(temp.resolve("two.jar"), source);

        assertArrayEquals(Files.readAllBytes(temp.resolve("one.jar")), Files.readAllBytes(temp.resolve("two.jar")));
        Path onlyClasses = Fixtures.jar(temp.resolve("classes.jar"), Map.of("a/B.class", CLASS));
        JavaResourceMerger.Result none = merge(temp.resolve("none.jar"), new JavaResourceMerger.Source("classes", onlyClasses));
        assertEquals(new JavaResourceMerger.Result(0, 0, List.of()), none);
        assertFalse(Files.exists(temp.resolve("none.jar")));
    }

    @Test
    void aProjectsOwnPackagingRulesCanPickTheFirstFileOrMergeOtherFilesOrExcludeMore(@TempDir Path temp) throws IOException {
        Path first = jar(temp, "first.jar", Map.of("dup.txt", "first", "lines.cfg", "1", "drop.me", "x"));
        Path second = jar(temp, "second.jar", Map.of("dup.txt", "second", "lines.cfg", "2"));
        PackagingRules rules = new PackagingRules(List.of("drop.me"), List.of("/dup.txt"), List.of("**/*.cfg"));
        Path target = temp.resolve("java-res.jar");

        JavaResourceMerger.Result result = JavaResourceMerger.merge(
                List.of(new JavaResourceMerger.Source("one", first), new JavaResourceMerger.Source("two", second)), rules, target);

        assertEquals(Map.of("dup.txt", "first", "lines.cfg", "1\n2"), read(target));
        assertEquals(List.of(), result.conflicts(), "the project said which one to take");
        assertEquals(PackagingRules.Action.EXCLUDE, rules.actionFor("drop.me"));
        assertEquals(PackagingRules.Action.KEEP, rules.actionFor("anything/else.txt"));
    }
}
