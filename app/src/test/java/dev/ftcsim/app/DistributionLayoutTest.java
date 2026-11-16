package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The distribution as {@code :app:dist} built it: the folder a driver unzips and the zip of that folder. Gradle names both
 * (and src/dist, which the folder has to match) in system properties.
 */
class DistributionLayoutTest {

    private static final Pattern JAR_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar");

    private Path dist;
    private Path lib;
    private Path zip;
    private Path sources;

    @BeforeEach
    void locate() {
        String folder = System.getProperty("ftcsim.dist");
        assumeTrue(folder != null, "run through Gradle, which builds the distribution and names it in ftcsim.dist");
        dist = Path.of(folder);
        lib = dist.resolve("lib");
        zip = Path.of(System.getProperty("ftcsim.distZip"));
        sources = Path.of(System.getProperty("ftcsim.distSources"));
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static Set<String> relativeFiles(Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).map(file -> folder.relativize(file).toString().replace('\\', '/')).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    void theFolderHasTheCmdFileItsOptionsTheRuntimeTheNoticesAndLibAndNothingElse() throws IOException {
        assertEquals(new TreeSet<>(List.of("FTC-Sim.cmd", "runtime-options", "runtime", "lib", "THIRD-PARTY-NOTICES.txt", "licenses")), new TreeSet<>(names(dist)));
        Set<String> expected = relativeFiles(sources);
        Set<String> actual = relativeFiles(dist);
        actual.removeIf(file -> file.startsWith("lib/") || file.startsWith("runtime/") || file.startsWith("licenses/") || file.equals("THIRD-PARTY-NOTICES.txt"));
        assertEquals(expected, actual, "the files of src/dist, no more and no fewer");
        for (String file : expected) {
            String source = Files.readString(sources.resolve(file), StandardCharsets.UTF_8);
            String shipped = Files.readString(dist.resolve(file), StandardCharsets.UTF_8);
            assertEquals(source.replace("\r\n", "\n"), shipped.replace("\r\n", "\n"), file + " differs from src/dist");
        }
        String cmd = Files.readString(dist.resolve("FTC-Sim.cmd"), StandardCharsets.UTF_8);
        assertFalse(cmd.replace("\r\n", "").contains("\n"), "FTC-Sim.cmd has Windows line endings only, whatever the checkout has");
    }

    @Test
    void theRuntimeIsAJlinkImageThatHasTheLaunchersTheStandardModulesAndTheExtrasAndNoDevelopmentTools() throws IOException {
        Path runtime = dist.resolve("runtime");
        for (String file : List.of("bin/java.exe", "bin/javaw.exe", "bin/server/jvm.dll", "lib/modules", "release", "legal/java.base/LICENSE")) {
            assertTrue(Files.isRegularFile(runtime.resolve(file)), "runtime/" + file);
        }
        Matcher modules = Pattern.compile("MODULES=\"([^\"]*)\"").matcher(Files.readString(runtime.resolve("release")));
        assertTrue(modules.find(), "the runtime's release file lists its modules");
        Set<String> inImage = Set.of(modules.group(1).split(" "));
        for (String module : List.of("java.base", "java.desktop", "java.instrument", "java.management", "java.net.http", "java.sql", "java.xml", "java.logging",
                "jdk.unsupported", "jdk.management", "jdk.crypto.ec", "jdk.zipfs", "jdk.net", "jdk.httpserver", "jdk.charsets")) {
            assertTrue(inImage.contains(module), module + " is missing from the runtime: " + inImage);
        }
        assertFalse(inImage.contains("jdk.compiler") || inImage.contains("jdk.jfr"), "no development tools in the runtime: " + inImage);
        for (String absent : List.of("include", "jmods", "man", "bin/javac.exe", "bin/jlink.exe", "lib/src.zip")) {
            assertFalse(Files.exists(runtime.resolve(absent)), "runtime/" + absent + " is not part of the runtime");
        }
    }

    @Test
    void libHoldsOnlyJarsAndTheClassPathFileEachNameOnceAndNothingOfTheBuild() throws IOException {
        List<String> files = names(lib);

        assertTrue(files.contains("app.jar") && files.contains(AppClasspath.FILE_NAME), files.toString());
        Set<String> lowerCase = new HashSet<>();
        for (String name : files) {
            assertTrue(Files.isRegularFile(lib.resolve(name)), name + " is not a file");
            assertTrue(name.equals(AppClasspath.FILE_NAME) || JAR_NAME.matcher(name).matches(), name + " is neither a jar nor the class path file");
            assertTrue(lowerCase.add(name.toLowerCase(Locale.ROOT)), name + " appears twice");
            assertFalse(name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar") || name.contains("-tests") || name.equals("launcher.jar"), name);
        }
        try (Stream<Path> everything = Files.walk(dist)) {
            List<String> strays = everything.filter(path -> !path.startsWith(dist.resolve("runtime"))).map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("(?i).*\\.(java|kt|kts|class|log|lock|bin)|build|\\.gradle|src|test|tests")).toList();
            assertEquals(List.of(), strays, "no source, test, build output or cache");
        }
    }

    @Test
    void theClassPathFileNamesJarsOfLibByBareNameAndTheApplicationsOwnReaderAcceptsIt() throws IOException {
        Path file = lib.resolve(AppClasspath.FILE_NAME);
        List<String> lines = Files.readAllLines(file).stream().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();

        assertEquals(2, lines.size(), lines.toString());
        for (String line : lines) {
            String[] entries = line.substring(line.indexOf('=') + 1).split(";");
            assertTrue(entries.length > 0, line);
            for (String entry : entries) {
                assertTrue(JAR_NAME.matcher(entry).matches(), "'" + entry + "' must be a bare jar name: no drive, no folder, no ..");
                assertTrue(Files.isRegularFile(lib.resolve(entry)), entry + " is not in lib");
            }
            assertEquals(entries.length, Arrays.stream(entries).distinct().count(), "a jar listed twice in " + line);
        }

        AppClasspath classpath = AppClasspath.read(file);
        for (Path jar : Stream.concat(classpath.worker().stream(), classpath.robot().stream()).toList()) {
            assertTrue(jar.normalize().startsWith(lib.toAbsolutePath().normalize()), jar + " resolves outside the folder");
        }
        List<String> worker = classpath.worker().stream().map(jar -> jar.getFileName().toString()).toList();
        List<String> robot = classpath.robot().stream().map(jar -> jar.getFileName().toString()).toList();
        for (String needed : List.of("worker.jar", "simulator.jar", "sim-core.jar", "sim-physics.jar", "sim-hardware.jar", "games.jar",
                "robot-runtime.jar", "sim-link.jar", "sim-contracts.jar", "project-import.jar", "platform.jar")) {
            assertTrue(worker.contains(needed), needed + " is missing from the worker's jars: " + worker);
        }
        assertTrue(worker.stream().anyMatch(name -> name.startsWith("jolt-jni-Windows64-") && name.endsWith("-ReleaseSp.jar")), "the physics engine's native jar: " + worker);
        for (String needed : List.of("sdk-common.jar", "android-shim.jar", "sim-contracts.jar")) {
            assertTrue(robot.contains(needed), needed + " is missing from the robot world's jars: " + robot);
        }
        assertFalse(robot.contains("worker.jar"), "the robot world has no worker");
    }

    @Test
    void theApplicationJarStartsTheMainClassAndNamesTheJarsOfTheWindowInTheOrderOfDevelopmentWithoutTheWorkersOrTheRobotWorlds() throws IOException {
        try (JarFile app = new JarFile(lib.resolve("app.jar").toFile())) {
            var attributes = app.getManifest().getMainAttributes();
            assertEquals("dev.ftcsim.app.Main", attributes.getValue("Main-Class"));
            List<String> classPath = Arrays.asList(attributes.getValue("Class-Path").split(" "));
            assertEquals(classPath.size(), classPath.stream().distinct().count(), "a jar listed twice: " + classPath);
            for (String entry : classPath) {
                assertTrue(JAR_NAME.matcher(entry).matches(), "'" + entry + "' must be a bare jar name");
                assertTrue(Files.isRegularFile(lib.resolve(entry)), entry + " is not in lib");
            }
            for (String window : List.of("desktop-ui.jar", "sim-link.jar", "project-import.jar", "sim-contracts.jar", "platform.jar")) {
                assertTrue(classPath.contains(window), window + " is not on the application's class path: " + classPath);
            }
            assertTrue(classPath.stream().anyMatch(name -> name.startsWith("skiko-awt-runtime-windows-x64-")), "Skia's native library: " + classPath);
            for (String suffix : List.of("lwjgl-", "lwjgl-glfw-", "lwjgl-opengl-")) {
                assertTrue(classPath.stream().anyMatch(name -> name.startsWith(suffix) && name.endsWith("-natives-windows.jar")), suffix + " natives: " + classPath);
            }
            for (String notTheWindows : List.of("worker.jar", "simulator.jar", "sim-core.jar", "sim-physics.jar", "sdk-common.jar", "android-shim.jar", "robot-runtime.jar")) {
                assertFalse(classPath.contains(notTheWindows), notTheWindows + " belongs to the worker or the robot world, not to the window's class path");
            }
            assertTrue(app.getEntry("dev/ftcsim/app/Main.class") != null && app.getEntry("dev/ftcsim/app/runtime.properties") != null
                            && app.getEntry("dev/ftcsim/app/jdk-patterns.txt") != null,
                    "the application's classes and the descriptions of the runtime and of the JDK places it carries");
            try (var carried = app.getInputStream(app.getEntry("dev/ftcsim/app/runtime-options"))) {
                assertEquals(Files.readString(dist.resolve("runtime-options")).replace("\r\n", "\n"),
                        new String(carried.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"),
                        "the options the launcher passes are the ones the self test checks");
            }
        }
    }

    @Test
    void theZipHoldsTheFolderUnderOneTopFolderWithFixedTimestampsAndNoPathThatEscapes() throws IOException {
        Set<String> expected = relativeFiles(dist).stream().map(file -> "FTC-Sim/" + file).collect(Collectors.toCollection(TreeSet::new));
        Set<String> inZip = new TreeSet<>();
        Set<Long> times = new HashSet<>();
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            for (Enumeration<? extends ZipEntry> entries = archive.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                assertTrue(name.startsWith("FTC-Sim/") && !name.contains("..") && !name.contains(":") && !name.contains("\\") && !name.startsWith("/"), name);
                times.add(entry.getTime());
                if (!entry.isDirectory()) {
                    assertTrue(inZip.add(name), name + " is in the zip twice");
                    assertEquals(Files.size(dist.resolve(name.substring("FTC-Sim/".length()))), entry.getSize(), name);
                }
            }
        }
        assertEquals(expected, inZip, "the zip lists exactly the files of the folder");
        assertEquals(1, times.size(), "one timestamp for every entry, so that the same jars always give the same zip: " + times);
    }
}
