package dev.ftcsim.acceptance;

import dev.ftcsim.app.AppClasspath;
import dev.ftcsim.app.AppFolders;
import dev.ftcsim.app.Machine;
import dev.ftcsim.app.RealHost;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** What the tests that start a real worker process share. */
final class WorkerFixtures {
    private WorkerFixtures() {}

    /**
     * The fixture robot program as the worker reads a prepared program from a folder: its class paths in a
     * {@code program.properties}, because they do not fit on a Windows command line.
     */
    static Path programFolder(Path temp) throws IOException {
        Path folder = Files.createDirectories(temp.resolve("program"));
        Files.writeString(
                folder.resolve("program.properties"),
                "robot=" + RobotWorld.classpathText("ftcsim.robotClasspath") + "\nprogram=" + RobotWorld.classpathText("ftcsim.programClasspath") + "\n");
        return folder;
    }

    static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * The application's host over the jars the build gives these tests: the class path file is written the way the
     * application's own is and read by the application's own reader, and the folders are the test's, not the user's.
     */
    static RealHost realHost(Path temp, Machine machine) throws IOException {
        Path file = temp.resolve(AppClasspath.FILE_NAME);
        Files.writeString(
                file, "worker=" + RobotWorld.classpathText("ftcsim.workerClasspath") + "\nrobot=" + RobotWorld.classpathText("ftcsim.simulatorClasspath") + "\n");
        return new RealHost(new AppFolders(temp.resolve("FTC-Sim")), machine, AppClasspath.read(file))
                .connectTimeout(Duration.ofMinutes(3))
                .shutdownGrace(Duration.ofSeconds(60));
    }
}
