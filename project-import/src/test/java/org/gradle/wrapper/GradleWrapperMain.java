package org.gradle.wrapper;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Properties;

/**
 * A stand-in for Gradle's wrapper, started by the importer's tests exactly as the real one is: through a
 * {@code gradle-wrapper.jar} on a command line. It reports its environment and arguments, then does what the
 * {@code fake-gradle.properties} in its working directory (the importer's copy of the project) says:
 *
 * <ul>
 *   <li>{@code pidFile}: write the process id there</li>
 *   <li>{@code touch}: append a byte to this file, as if someone edited the original project meanwhile</li>
 *   <li>{@code printFile}: print the lines of this file, which are output captured from a real failed build</li>
 *   <li>{@code exportFile}: copy this file to the path of {@code -Pftcsim.export.file}, with {@code ${cwd}}
 *       replaced by the working directory</li>
 *   <li>{@code sleepMillis}: then sleep, to be stopped by a timeout or a cancellation</li>
 *   <li>{@code exitCode}: exit with this code (default 0)</li>
 * </ul>
 */
public final class GradleWrapperMain {
    private GradleWrapperMain() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        Properties control = new Properties();
        try (Reader in = Files.newBufferedReader(Path.of("fake-gradle.properties"))) {
            control.load(in);
        }
        Path cwd = Path.of("").toAbsolutePath();
        System.out.println("fake gradle started in " + cwd);
        System.out.println("JAVA_HOME=" + System.getenv("JAVA_HOME"));
        System.out.println("ANDROID_HOME=" + System.getenv("ANDROID_HOME"));
        System.out.println("GRADLE_USER_HOME=" + System.getenv("GRADLE_USER_HOME"));
        System.out.println("args=" + String.join(" ", args));

        if (control.getProperty("pidFile") != null) {
            Files.writeString(Path.of(control.getProperty("pidFile")), Long.toString(ProcessHandle.current().pid()));
        }
        if (control.getProperty("touch") != null) {
            Files.write(Path.of(control.getProperty("touch")), new byte[] {'!'}, StandardOpenOption.APPEND);
        }
        if (control.getProperty("printFile") != null) {
            Files.readAllLines(Path.of(control.getProperty("printFile"))).forEach(System.out::println);
        }
        if (control.getProperty("exportFile") != null) {
            String target = null;
            for (String argument : args) {
                if (argument.startsWith("-Pftcsim.export.file=")) {
                    target = argument.substring("-Pftcsim.export.file=".length());
                }
            }
            List<String> lines = Files.readAllLines(Path.of(control.getProperty("exportFile"))).stream()
                    .map(line -> line.replace("${cwd}", cwd.toString()))
                    .toList();
            Files.write(Path.of(target), lines);
        }
        if (control.getProperty("sleepMillis") != null) {
            Thread.sleep(Long.parseLong(control.getProperty("sleepMillis")));
        }
        int exitCode = Integer.parseInt(control.getProperty("exitCode", "0"));
        if (exitCode == 0) {
            System.out.println("BUILD SUCCESSFUL in 1s");
        }
        System.exit(exitCode);
    }
}
