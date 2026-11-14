package dev.ftcsim.importer.gradle;

import dev.ftcsim.importer.ImportException;
import dev.ftcsim.importer.ImportException.Failure;
import dev.ftcsim.importer.ImportProgress;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks a project's own Gradle build for what the simulator needs. The importer runs the project's wrapper, so
 * the Gradle and Android Gradle plugin versions, repositories and dependency rules are the project's, with one
 * init script ({@code ftcsim-export.init.gradle}, from this module's resources) added on the command line. The
 * script is kept outside the project and changes nothing in it.
 *
 * <p>The wrapper is started through its jar rather than {@code gradlew.bat}, exactly as that script does, so that
 * no command interpreter has to quote paths. The build runs once, without a daemon, so no Gradle process outlives
 * the import. {@code ANDROID_HOME} is set for the child process, which is all the Android Gradle plugin needs to
 * find the SDK; the project's {@code local.properties} is not used and nothing is written to the project.
 *
 * <p>The project's build scripts execute during this call. See {@code BuildTrust}: a child process is not a sandbox.
 */
public final class GradleExport {
    private static final String INIT_SCRIPT = "ftcsim-export.init.gradle";
    private static final String WRAPPER_JAR = "gradle/wrapper/gradle-wrapper.jar";

    private GradleExport() {}

    /**
     * Checks what can be checked before anything is copied or started: that the project has a Gradle wrapper, that the
     * JDK is a JDK and that the Android SDK folder exists.
     *
     * @throws ImportException saying which of them is wrong
     */
    public static void check(Path projectDirectory, GradleSettings settings) {
        if (!Files.isRegularFile(projectDirectory.resolve(WRAPPER_JAR))) {
            throw new ImportException(
                    Failure.NOT_A_GRADLE_PROJECT,
                    "The project has no Gradle wrapper (" + WRAPPER_JAR + "), which is how it is built. Open the project in "
                            + "Android Studio once, or run 'gradle wrapper' in it, and import again.");
        }
        Path bin = settings.jdkHome().resolve("bin");
        if (!Files.isRegularFile(bin.resolve("java.exe")) || !Files.isRegularFile(bin.resolve("javac.exe"))) {
            throw new ImportException(
                    Failure.INVALID_REQUEST,
                    settings.jdkHome() + " is not a JDK: it has no bin\\java.exe and bin\\javac.exe. Give the folder of a JDK "
                            + "17 or 21, not of a JRE.");
        }
        if (!Files.isDirectory(settings.androidSdk())) {
            throw new ImportException(
                    Failure.NO_ANDROID_SDK,
                    "The Android SDK folder " + settings.androidSdk() + " does not exist. Android Studio shows where its SDK is "
                            + "under Settings > Languages & Frameworks > Android SDK.");
        }
    }

    /**
     * Builds the application module of the project in {@code projectDirectory} and reads what the build exports.
     *
     * @param projectDirectory the copy of the project to build, not the original
     * @param workDirectory    where the init script, the export file and the build log ({@code gradle.log}) are written
     * @throws ImportException when the build fails; its failure says why as far as the build's output shows
     */
    public static ExportedProject run(Path projectDirectory, Path workDirectory, GradleSettings settings, ImportProgress progress)
            throws IOException {
        check(projectDirectory, settings);
        Path wrapperJar = projectDirectory.resolve(WRAPPER_JAR);
        Path java = settings.jdkHome().resolve("bin").resolve("java.exe");
        Files.createDirectories(workDirectory);
        Path script = workDirectory.resolve(INIT_SCRIPT);
        try (InputStream in = GradleExport.class.getResourceAsStream(INIT_SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException(INIT_SCRIPT + " is missing from the importer");
            }
            Files.write(script, in.readAllBytes());
        }
        Path exportFile = workDirectory.resolve("export.txt");
        Path logFile = workDirectory.resolve("gradle.log");
        Files.deleteIfExists(exportFile);

        List<String> command = command(java, wrapperJar, script, exportFile, settings);
        progress.message("Running the project's Gradle build with " + settings.jdkHome());
        ChildProcess.Result result = ChildProcess.run(
                command, projectDirectory, environment(settings), logFile, settings.timeout(), progress);
        if (result.exitCode() != 0) {
            throw GradleFailures.diagnose(
                    result.lines(), new GradleFailures.Context(settings.jdkHome(), settings.androidSdk(), settings.offline()), logFile);
        }
        if (!Files.isRegularFile(exportFile)) {
            throw new ImportException(
                    Failure.BUILD_FAILED,
                    "The project's build finished but did not write the export; see " + logFile + ".",
                    result.lines().subList(Math.max(0, result.lines().size() - 20), result.lines().size()),
                    logFile);
        }
        return ExportedProject.parse(Files.readAllLines(exportFile, StandardCharsets.UTF_8));
    }

    private static List<String> command(Path java, Path wrapperJar, Path script, Path exportFile, GradleSettings settings) {
        List<String> command = new ArrayList<>(List.of(
                java.toString(),
                "-Xmx64m",
                "-Xms64m",
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-Dorg.gradle.appname=gradlew",
                "-classpath",
                wrapperJar.toString(),
                "org.gradle.wrapper.GradleWrapperMain",
                "--console=plain",
                "--no-daemon",
                "--no-configuration-cache",
                "--warning-mode=none"));
        if (settings.offline()) {
            command.add("--offline");
        }
        // Whatever the project's gradle.properties says, this JDK builds it; and a Kotlin compiler daemon, which
        // would outlive the import, is not started.
        command.add("-Dorg.gradle.java.home=" + settings.jdkHome());
        command.add("-Pkotlin.compiler.execution.strategy=in-process");
        command.add("-Pftcsim.export.file=" + exportFile);
        if (settings.applicationModule() != null) {
            command.add("-Pftcsim.app.project=" + settings.applicationModule());
        }
        command.add("--init-script");
        command.add(script.toString());
        command.add("ftcsimExport");
        return command;
    }

    private static Map<String, String> environment(GradleSettings settings) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JAVA_HOME", settings.jdkHome().toString());
        environment.put("ANDROID_HOME", settings.androidSdk().toString());
        environment.put("ANDROID_SDK_ROOT", settings.androidSdk().toString());
        if (settings.gradleUserHome() != null) {
            environment.put("GRADLE_USER_HOME", settings.gradleUserHome().toString());
        }
        return environment;
    }
}
