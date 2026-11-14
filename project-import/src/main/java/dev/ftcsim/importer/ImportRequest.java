package dev.ftcsim.importer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Everything an import needs to know. The importer finds no JDK and no Android SDK by itself: whoever asks for an
 * import gives the folders, because finding or installing them is the launcher's job and may need the user's
 * consent.
 *
 * @param projectDirectory   the team's project, the folder with {@code settings.gradle}; it is only ever read
 * @param workspace          a folder owned by the application where the importer keeps its copy of the project, the
 *                           build log and what it generates; it must lie outside the project. Keep its path short:
 *                           the Android build nests files deeply and Windows limits paths to 260 characters
 * @param packageDirectory   where the prepared program is created; it must not exist or must be empty, and must
 *                           lie outside the project
 * @param packageZip         a zip of the package to write too, or null for none
 * @param jdkHome            the JDK 17 or 21 that runs the project's build
 * @param androidSdk         the Android SDK folder
 * @param trust              whether the caller accepts that the project's build scripts run, see {@link BuildTrust}
 * @param gradleUserHome     Gradle's cache folder, or null for Gradle's default; a cache that already holds the
 *                           project's dependencies is what makes an offline import possible
 * @param offline            whether Gradle may not use the network
 * @param gradleTimeout      how long the project's build may run before it is stopped
 * @param applicationModule  the Gradle path of the application module (for example {@code :TeamCode}), or null to use
 *                           the only application module of the project
 * @param simulatorClasspath the simulator's Android shim and SDK adapter jars or class folders, which tell the
 *                           compatibility check what the simulator provides; empty skips the Android check
 */
public record ImportRequest(
        Path projectDirectory,
        Path workspace,
        Path packageDirectory,
        Path packageZip,
        Path jdkHome,
        Path androidSdk,
        BuildTrust trust,
        Path gradleUserHome,
        boolean offline,
        Duration gradleTimeout,
        String applicationModule,
        List<Path> simulatorClasspath) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    public ImportRequest {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(packageDirectory, "packageDirectory");
        Objects.requireNonNull(jdkHome, "jdkHome");
        Objects.requireNonNull(androidSdk, "androidSdk");
        Objects.requireNonNull(trust, "trust: the caller has to decide whether to trust the project's build scripts");
        Objects.requireNonNull(gradleTimeout, "gradleTimeout");
        simulatorClasspath = List.copyOf(simulatorClasspath);
    }

    /** A request with the required folders and a decision about trust; the rest has defaults and can be changed with the {@code with} methods. */
    public static ImportRequest of(
            Path projectDirectory, Path workspace, Path packageDirectory, Path jdkHome, Path androidSdk, BuildTrust trust) {
        return new ImportRequest(
                projectDirectory, workspace, packageDirectory, null, jdkHome, androidSdk, trust, null, false, DEFAULT_TIMEOUT, null, List.of());
    }

    public ImportRequest withPackageZip(Path zip) {
        return new ImportRequest(projectDirectory, workspace, packageDirectory, zip, jdkHome, androidSdk, trust, gradleUserHome, offline, gradleTimeout, applicationModule, simulatorClasspath);
    }

    public ImportRequest withGradleUserHome(Path home) {
        return new ImportRequest(projectDirectory, workspace, packageDirectory, packageZip, jdkHome, androidSdk, trust, home, offline, gradleTimeout, applicationModule, simulatorClasspath);
    }

    public ImportRequest withOffline(boolean value) {
        return new ImportRequest(projectDirectory, workspace, packageDirectory, packageZip, jdkHome, androidSdk, trust, gradleUserHome, value, gradleTimeout, applicationModule, simulatorClasspath);
    }

    public ImportRequest withGradleTimeout(Duration timeout) {
        return new ImportRequest(projectDirectory, workspace, packageDirectory, packageZip, jdkHome, androidSdk, trust, gradleUserHome, offline, timeout, applicationModule, simulatorClasspath);
    }

    public ImportRequest withApplicationModule(String gradlePath) {
        return new ImportRequest(projectDirectory, workspace, packageDirectory, packageZip, jdkHome, androidSdk, trust, gradleUserHome, offline, gradleTimeout, gradlePath, simulatorClasspath);
    }

    public ImportRequest withSimulatorClasspath(List<Path> classpath) {
        return new ImportRequest(projectDirectory, workspace, packageDirectory, packageZip, jdkHome, androidSdk, trust, gradleUserHome, offline, gradleTimeout, applicationModule, classpath);
    }
}
