package dev.ftcsim.importer.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ftcsim.importer.ImportException;
import dev.ftcsim.importer.ImportException.Failure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The diagnosis of a failed build, against the output Gradle printed. The files under {@code captured/} are real
 * output of Hornet's build with Gradle 8.9 and Android Gradle plugin 8.7.0, one per way the build was made to
 * fail; the ones under {@code representative/} are what Gradle and the plugin print for failures that could not
 * be provoked on the machine that wrote these tests (no older JDK, no flaky network), written from their messages.
 */
class GradleFailuresTest {
    private static final Path SDK = Path.of("C:\\Users\\student\\Android\\Sdk");
    private static final Path JDK = Path.of("C:\\Program Files\\Java\\jdk-17");

    private static List<String> output(String name) throws IOException {
        try (InputStream in = GradleFailuresTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    private static ImportException diagnose(String name, boolean offline) throws IOException {
        return GradleFailures.diagnose(output(name), new GradleFailures.Context(JDK, SDK, offline), Path.of("gradle.log"));
    }

    @Test
    void anAndroidSdkThatCannotBeFoundIsSaidToBeMissingAndWhereTheImporterLooked() throws IOException {
        ImportException failure = diagnose("captured/no-android-sdk.txt", false);

        assertEquals(Failure.NO_ANDROID_SDK, failure.failure());
        assertTrue(failure.getMessage().contains(SDK.toString()), failure.getMessage());
        assertTrue(failure.diagnostics().get(0).contains("SDK location not found"));
        assertEquals(Path.of("gradle.log"), failure.logFile().orElseThrow());
    }

    @Test
    void aPlatformThatIsNotInstalledNamesTheSdkAndWhatToDo() throws IOException {
        ImportException failure = diagnose("captured/sdk-platform-missing.txt", true);

        assertEquals(Failure.ANDROID_SDK_INCOMPLETE, failure.failure());
        assertTrue(failure.getMessage().contains("android-34"), failure.getMessage());
        assertTrue(failure.getMessage().contains("SDK Manager"));
    }

    @Test
    void licencesThatWereNotAcceptedListThePackagesAndTheCommandThatAcceptsThem() throws IOException {
        ImportException failure = diagnose("captured/licences-not-accepted.txt", false);

        assertEquals(Failure.LICENSES_NOT_ACCEPTED, failure.failure());
        assertTrue(failure.getMessage().contains("platforms;android-34, build-tools;34.0.0"), failure.getMessage());
        assertTrue(failure.getMessage().contains(SDK.resolve("cmdline-tools").resolve("latest").resolve("bin").resolve("sdkmanager.bat") + " --licenses"));
    }

    @Test
    void anOfflineImportWithAColdCacheSaysWhatIsMissingAndHowToFillTheCache() throws IOException {
        ImportException failure = diagnose("captured/offline-cold-cache.txt", true);

        assertEquals(Failure.OFFLINE_CACHE_COLD, failure.failure());
        assertTrue(failure.getMessage().contains("com.android.tools.build:gradle:8.7.0"), failure.getMessage());
        assertTrue(failure.getMessage().contains("network access"));
        assertEquals(Failure.OFFLINE_CACHE_COLD, diagnose("captured/dependency-not-cached-offline.txt", true).failure());
    }

    @Test
    void aDependencyThatDoesNotExistIsNamedWithTheVersionThatWasAskedFor() throws IOException {
        ImportException failure = diagnose("captured/dependency-not-found.txt", false);

        assertEquals(Failure.DEPENDENCY_RESOLUTION, failure.failure());
        assertTrue(failure.getMessage().contains("Could not find com.pedropathing:ftc:9.9.9"), failure.getMessage());
        assertTrue(failure.diagnostics().stream().anyMatch(line -> line.contains("repo.maven.apache.org")), "the repositories searched are in the evidence");
    }

    @Test
    void aJdkTheBuildCannotRunOnIsSaidToBeTheWrongJdk() throws IOException {
        ImportException failure = diagnose("representative/wrong-jdk.txt", false);

        assertEquals(Failure.WRONG_JDK, failure.failure());
        assertTrue(failure.getMessage().contains(JDK.toString()) && failure.getMessage().contains("Android Gradle plugin requires Java 17"), failure.getMessage());
    }

    @Test
    void aDownloadThatFailsIsANetworkProblemNotADependencyProblem() throws IOException {
        ImportException failure = diagnose("representative/network-unavailable.txt", false);

        assertEquals(Failure.NETWORK_UNAVAILABLE, failure.failure());
        assertTrue(failure.getMessage().contains("proxy"));
    }

    @Test
    void codeThatDoesNotCompileShowsTheCompilersErrorsAndTheTask() throws IOException {
        ImportException failure = diagnose("representative/compilation-failed.txt", false);

        assertEquals(Failure.COMPILATION_FAILED, failure.failure());
        assertTrue(failure.getMessage().contains("task :TeamCode:compileDebugJavaWithJavac"), failure.getMessage());
        assertTrue(failure.diagnostics().get(0).contains("Drive.java:12: error: cannot find symbol"));
    }

    @Test
    void memoryAndTheApplicationModuleHaveTheirOwnFailures() throws IOException {
        assertEquals(Failure.OUT_OF_MEMORY, diagnose("representative/out-of-memory.txt", false).failure());
        ImportException modules = diagnose("representative/no-application-module.txt", false);
        assertEquals(Failure.NO_APPLICATION_MODULE, modules.failure());
        assertTrue(modules.getMessage().contains("found :TeamCode, :Practice"), modules.getMessage());
    }

    @Test
    void whatIsNotRecognisedStillSaysWhatGradleSaidWentWrong() throws IOException {
        ImportException failure = diagnose("representative/unrecognised.txt", false);

        assertEquals(Failure.BUILD_FAILED, failure.failure());
        assertTrue(failure.getMessage().contains("The frobnicator is unplugged"), failure.getMessage());
        assertTrue(failure.getMessage().contains("in task ':TeamCode:frobnicate'"), failure.getMessage());
    }
}
