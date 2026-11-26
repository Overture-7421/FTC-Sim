package dev.ftcsim.importer.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.importer.Fixtures;
import dev.ftcsim.importer.io.Sha256;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceBuilderTest {

    private static final String RTXT_A = String.join("\n",
            "int anim fade 0x7f010000",
            "int attr size 0x7f020000",
            "int bool flag 0x7f050000",
            "int color red 0x7f060000",
            "int integer answer 0x7f070000",
            "int string hello 0x7f080000",
            "int[] styleable Thing { 0x7f020000, 0x0101000e }",
            "int styleable Thing_size 0",
            "int styleable Thing_android_enabled 1",
            "");

    private static Path libraryA(Path folder, String hello, Map<String, String> more) throws IOException {
        Map<String, String> files = new LinkedHashMap<>(more);
        files.put("res/values/values.xml", Fixtures.values(
                "<string name=\"hello\">" + hello + "</string>",
                "<integer name=\"answer\">42</integer>",
                "<bool name=\"flag\">true</bool>",
                "<color name=\"red\">#ff0000</color>",
                "<string name=\"ignored\">not in R.txt, so no id</string>"));
        return Fixtures.aar(folder.resolve("a.aar"), "com.acme.a", RTXT_A, files);
    }

    private static Path libraryB(Path folder, Map<String, String> more) throws IOException {
        Map<String, String> files = new LinkedHashMap<>(more);
        files.put("res/values/values.xml", Fixtures.values(
                "<string name=\"bye\">@string/hello</string>", "<string name=\"hello\">Hello from b</string>"));
        return Fixtures.aar(folder.resolve("b.aar"), "com.acme.b", "int string bye 0x7f010000\nint string hello 0x7f010001\n", files);
    }

    private static URLClassLoader loaderOf(Path jar) throws IOException {
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
    }

    private static int field(ClassLoader loader, String className, String name) throws ReflectiveOperationException {
        return loader.loadClass(className).getField(name).getInt(null);
    }

    @Test
    void everyResourceGetsOneIdFromItsTypeAndNameAndTheTableAndTheClassesAgree(@TempDir Path temp) throws Exception {
        Path out = temp.resolve("out");
        ResourceBuilder.Summary summary = new ResourceBuilder().addAar(libraryA(temp, "Hello a", Map.of())).addAar(libraryB(temp, Map.of())).writeTo(out);

        assertEquals(new ResourceBuilder.Summary(2, 2, 7, 5), summary);
        assertEquals(List.of(
                        "bool\t0x7f030000\tflag\ttrue",
                        "color\t0x7f040000\tred\t#ff0000",
                        "integer\t0x7f050000\tanswer\t42",
                        "string\t0x7f060000\tbye\tHello from b",
                        "string\t0x7f060001\thello\tHello from b"),
                Files.readAllLines(out.resolve("ftcsim-resources.tsv")), "the string 'ignored' is in no R.txt, so it has no id and no row");
        try (URLClassLoader loader = loaderOf(out.resolve("r.jar"))) {
            assertEquals(0x7f060001, field(loader, "com.acme.a.R$string", "hello"));
            assertEquals(0x7f060001, field(loader, "com.acme.b.R$string", "hello"));
            assertEquals(0x7f060000, field(loader, "com.acme.b.R$string", "bye"));
            assertEquals(0x7f010000, field(loader, "com.acme.a.R$anim", "fade"));
            assertEquals(0x7f020000, field(loader, "com.acme.a.R$attr", "size"));
            assertThrows(NoSuchFieldException.class, () -> loader.loadClass("com.acme.a.R$string").getField("bye"));
        }
    }

    @Test
    void rClassesHoldPlainStaticIntsAndStyleablesUseTheIdsOfTheAttributes(@TempDir Path temp) throws Exception {
        Path out = temp.resolve("out");
        new ResourceBuilder().addAar(libraryA(temp, "Hello", Map.of())).writeTo(out);

        try (URLClassLoader loader = loaderOf(out.resolve("r.jar"))) {
            Field hello = loader.loadClass("com.acme.a.R$string").getField("hello");
            assertTrue(Modifier.isStatic(hello.getModifiers()) && Modifier.isPublic(hello.getModifiers()));
            assertFalse(Modifier.isFinal(hello.getModifiers()), "a library's R fields are not constants");
            assertEquals(int.class, hello.getType());
            int[] thing = (int[]) loader.loadClass("com.acme.a.R$styleable").getField("Thing").get(null);
            assertEquals(List.of(field(loader, "com.acme.a.R$attr", "size"), 0x0101000e), List.of(thing[0], thing[1]));
            assertEquals(0, field(loader, "com.acme.a.R$styleable", "Thing_size"));
            assertEquals(1, field(loader, "com.acme.a.R$styleable", "Thing_android_enabled"));
            assertTrue(Modifier.isPrivate(loader.loadClass("com.acme.a.R").getDeclaredConstructor().getModifiers()));
            assertEquals("R", loader.loadClass("com.acme.a.R$string").getEnclosingClass().getSimpleName());
        }
    }

    @Test
    void hugeStyleablesSplitTheirInitialisationAcrossMethods(@TempDir Path temp) throws Exception {
        StringBuilder rTxt = new StringBuilder();
        int[] sizes = {2500, 2500, 7000};
        int attribute = 0;
        for (int array = 0; array < sizes.length; array++) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < sizes[array]; i++) {
                String name = "a" + attribute;
                String id = "0x7f02" + String.format("%04x", attribute++);
                rTxt.append("int attr ").append(name).append(' ').append(id).append('\n');
                ids.add(id);
            }
            rTxt.append("int[] styleable Big").append(array).append(" { ").append(String.join(", ", ids)).append(" }\n");
        }
        Path out = temp.resolve("out");
        new ResourceBuilder().addAar(Fixtures.aar(temp.resolve("big.aar"), "com.big", rTxt.toString(), Map.of())).writeTo(out);

        try (URLClassLoader loader = loaderOf(out.resolve("r.jar"))) {
            Class<?> styleable = loader.loadClass("com.big.R$styleable");
            for (int array = 0; array < sizes.length; array++) {
                int[] elements = (int[]) styleable.getField("Big" + array).get(null);
                assertEquals(sizes[array], elements.length);
            }
            int[] last = (int[]) styleable.getField("Big2").get(null);
            assertEquals(field(loader, "com.big.R$attr", "a" + (sizes[0] + sizes[1] + 6999)), last[6999]);
        }
    }

    @Test
    void laterLibrariesOverrideEarlierValuesAndAssets(@TempDir Path temp) throws Exception {
        Path first = libraryA(temp, "from first", Map.of("assets/shared.txt", "first", "assets/only-first.txt", "1"));
        Path second = Fixtures.aar(temp.resolve("second.aar"), "com.acme.a", RTXT_A, Map.of(
                "res/values/values.xml", Fixtures.values("<string name=\"hello\">from second</string>"),
                "assets/shared.txt", "second"));
        Path out = temp.resolve("out");

        new ResourceBuilder().addAar(first).addAar(second).writeTo(out);

        assertTrue(Files.readAllLines(out.resolve("ftcsim-resources.tsv")).contains("string\t0x7f060000\thello\tfrom second"));
        assertEquals(1, Files.readAllLines(out.resolve("ftcsim-resources.tsv")).stream().filter(line -> line.contains("\thello\t")).count());
        assertEquals("second", Files.readString(out.resolve("assets/shared.txt")));
        assertEquals("1", Files.readString(out.resolve("assets/only-first.txt")));
    }

    @Test
    void theClassesOfTheProjectsModulesHoldEveryResourceOfTheApp(@TempDir Path temp) throws Exception {
        Path res = temp.resolve("res");
        Files.createDirectories(res.resolve("values"));
        Files.createDirectories(res.resolve("layout-land"));
        Files.createDirectories(res.resolve("raw"));
        Files.createDirectories(res.resolve("xml"));
        Files.writeString(res.resolve("values/strings.xml"), Fixtures.values(
                "<string name=\"team.name\">  Team   \\\"7421\\\"  </string>",
                "<dimen name=\"activity_horizontal_margin\">16dp</dimen>",
                "<dimen name=\"margin_alias\">@dimen/activity_horizontal_margin</dimen>",
                "<style name=\"AppTheme.Dark\" parent=\"@android:style/Theme\"/>",
                "<item type=\"id\" name=\"declared\"/>"));
        Files.writeString(res.resolve("layout-land/main.xml"), "<LinearLayout android:id=\"@+id/button\"><x android:id=\"@+id/label\"/></LinearLayout>");
        Files.writeString(res.resolve("raw/readme.md"), "text");
        Files.writeString(res.resolve("xml/calibration.xml"), "<calibrations/>");
        Path assets = Files.createDirectories(temp.resolve("assets"));
        Files.writeString(assets.resolve("model.txt"), "weights");
        Path out = temp.resolve("out");

        new ResourceBuilder()
                .addAar(libraryA(temp, "Hello", Map.of()))
                .addModule("org.team", List.of(res), List.of(assets))
                .writeTo(out);

        try (URLClassLoader loader = loaderOf(out.resolve("r.jar"))) {
            assertEquals(
                    field(loader, "com.acme.a.R$string", "hello"),
                    field(loader, "org.team.R$string", "hello"),
                    "the resources of the libraries are in the R of the app");
            for (String name : List.of("button", "label", "declared")) {
                loader.loadClass("org.team.R$id").getField(name);
            }
            loader.loadClass("org.team.R$string").getField("team_name");
            loader.loadClass("org.team.R$layout").getField("main");
            loader.loadClass("org.team.R$raw").getField("readme");
            loader.loadClass("org.team.R$xml").getField("calibration");
            loader.loadClass("org.team.R$style").getField("AppTheme_Dark");
            assertThrows(NoSuchFieldException.class, () -> loader.loadClass("com.acme.a.R$string").getField("team_name"));
        }
        List<String> rows = Files.readAllLines(out.resolve("ftcsim-resources.tsv"));
        assertTrue(rows.stream().anyMatch(line -> line.endsWith("\tteam_name\tTeam \"7421\"")));
        assertTrue(rows.stream().anyMatch(line -> line.startsWith("dimen\t") && line.endsWith("\tactivity_horizontal_margin\t16dp")), rows.toString());
        assertTrue(rows.stream().anyMatch(line -> line.startsWith("dimen\t") && line.endsWith("\tmargin_alias\t16dp")), rows.toString());
        assertEquals("weights", Files.readString(out.resolve("assets/model.txt")));
    }

    @Test
    void sameInputsGiveByteIdenticalOutputs(@TempDir Path temp) throws Exception {
        Path a = libraryA(temp, "Hello", Map.of("assets/x/y.txt", "y"));
        Path b = libraryB(temp, Map.of());

        new ResourceBuilder().addAar(a).addAar(b).writeTo(temp.resolve("one"));
        new ResourceBuilder().addAar(a).addAar(b).writeTo(temp.resolve("two"));

        assertEquals(describe(temp.resolve("one")), describe(temp.resolve("two")));
    }

    @Test
    void generatingUnchangedResourcesAgainWorksWhileTheOldJarIsOpen(@TempDir Path temp) throws Exception {
        Path a = libraryA(temp, "Hello", Map.of());
        Path out = temp.resolve("out");
        new ResourceBuilder().addAar(a).writeTo(out);

        // A zip file that is open cannot be replaced on Windows, as when a robot world is running on this jar.
        try (ZipFile open = new ZipFile(out.resolve("r.jar").toFile())) {
            new ResourceBuilder().addAar(a).writeTo(out);

            assertTrue(open.size() > 0);
        }
        assertFalse(Files.exists(out.resolve("r.jar.partial")));
    }

    private static List<String> describe(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            List<String> described = new ArrayList<>();
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                described.add(directory.relativize(file) + ":" + Sha256.of(file));
            }
            return described;
        }
    }

    @Test
    void anArchiveWithoutAPackageIsRefusedByName(@TempDir Path temp) throws IOException {
        Path broken = Fixtures.jar(temp.resolve("broken.aar"), Map.of("AndroidManifest.xml", "<manifest/>".getBytes(StandardCharsets.UTF_8)));

        ResourceException failure = assertThrows(ResourceException.class, () -> new ResourceBuilder().addAar(broken));

        assertTrue(failure.getMessage().contains("broken.aar"), failure.getMessage());
    }
}
