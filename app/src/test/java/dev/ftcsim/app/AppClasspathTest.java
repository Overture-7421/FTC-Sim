package dev.ftcsim.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppClasspathTest {

    @TempDir
    Path folder;

    private Path jar(String name) throws IOException {
        return Files.writeString(folder.resolve(name), "jar");
    }

    private Path file(String name, String... lines) throws IOException {
        return Files.writeString(folder.resolve(name), String.join("\n", lines) + "\n");
    }

    @Test
    void anInstallationHasTheFileNextToItsJarWithRelativeEntriesSoItCanBeMovedAsAWhole() throws IOException {
        jar("worker.jar");
        jar("sim-link.jar");
        jar("sdk-common.jar");
        Path app = jar("app.jar");
        file(AppClasspath.FILE_NAME, "worker=worker.jar" + File.pathSeparator + "sim-link.jar", "robot=sdk-common.jar");

        AppClasspath classpath = AppClasspath.locate(null, app);

        assertEquals(List.of(folder.resolve("worker.jar"), folder.resolve("sim-link.jar")), classpath.worker());
        assertEquals(List.of(folder.resolve("sdk-common.jar")), classpath.robot());
    }

    @Test
    void theSystemPropertyNamesTheFileInDevelopmentAndWinsOverTheJar() throws IOException {
        Path worker = jar("worker.jar");
        Path robot = jar("robot.jar");
        Path elsewhere = file("elsewhere.properties", "worker=" + worker, "robot=" + robot);
        Path app = jar("app.jar");
        file(AppClasspath.FILE_NAME, "worker=missing.jar", "robot=missing.jar");

        assertEquals(new AppClasspath(List.of(worker), List.of(robot)), AppClasspath.locate(elsewhere.toString(), app));
    }

    @Test
    void whatIsMissingIsSaidInWordsThatNameTheFile() throws IOException {
        Path app = jar("app.jar");
        String beside = assertThrows(IOException.class, () -> AppClasspath.locate(null, app)).getMessage();
        assertTrue(beside.contains(AppClasspath.FILE_NAME) && beside.contains("is not next to"), beside);

        String property = assertThrows(IOException.class, () -> AppClasspath.locate(folder.resolve("nope.properties").toString(), app)).getMessage();
        assertTrue(property.contains(AppClasspath.FILE_PROPERTY) && property.contains("does not exist"), property);

        String classes = assertThrows(IOException.class, () -> AppClasspath.locate(null, folder)).getMessage();
        assertTrue(classes.contains("does not run from a jar"), classes);

        jar("worker.jar");
        Path partial = file("partial.properties", "worker=worker.jar", "robot=gone.jar");
        String missing = assertThrows(IOException.class, () -> AppClasspath.read(partial)).getMessage();
        assertTrue(missing.contains("partial.properties") && missing.contains("'robot' lists 1 entries that do not exist"), missing);
        Path incomplete = file("incomplete.properties", "worker=worker.jar");
        assertTrue(assertThrows(IOException.class, () -> AppClasspath.read(incomplete)).getMessage().contains("has no 'robot' line"));
    }

    /** What Gradle writes for the application ({@code :app:writeClasspath}) is what this reads, and it holds what each part needs. */
    @Test
    void theFileGradleWritesNamesTheWorkersJarsAndTheRobotWorldsAndKeepsTheWindowOutOfTheWorker() throws Exception {
        String property = System.getProperty(AppClasspath.FILE_PROPERTY);
        assumeTrue(property != null, "run through Gradle, which writes the file and names it in " + AppClasspath.FILE_PROPERTY);

        AppClasspath classpath = AppClasspath.read(Path.of(property));

        List<URL> workerUrls = classpath.worker().stream().map(AppClasspathTest::url).toList();
        try (URLClassLoader worker = new URLClassLoader(workerUrls.toArray(URL[]::new), null)) {
            assertNotNull(worker.getResource("dev/ftcsim/worker/WorkerMain.class"), "the worker's main class is in its jars");
            assertNotNull(worker.getResource("dev/ftcsim/simulator/RealtimeRunner.class"), "and the simulator it serves");
            assertNotNull(worker.getResource("dev/ftcsim/link/wire/Protocol.class"), "and the wire format it speaks");
            assertNotNull(worker.getResource("dev/ftcsim/importer/pack/PreparedProgram.class"), "and the reader of prepared programs");
            assertNull(worker.getResource("dev/ftcsim/app/Main.class"), "the application is not in the worker");
            assertNull(worker.getResource("dev/ftcsim/ui/App.class"), "the window is not in the worker");
        }
        List<String> robot = classpath.robot().stream().map(path -> path.getFileName().toString()).toList();
        assertTrue(robot.stream().anyMatch(name -> name.startsWith("sdk-common")), robot.toString());
        assertTrue(robot.stream().anyMatch(name -> name.startsWith("android-shim")), robot.toString());
        assertFalse(robot.stream().anyMatch(name -> name.startsWith("worker")), "the robot world has no worker: " + robot);
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
