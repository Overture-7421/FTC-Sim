package dev.ftcsim.importer.gradle;

import java.nio.file.Path;
import java.time.Duration;

/**
 * How to run the project's Gradle build.
 *
 * @param jdkHome           the JDK that runs Gradle and compiles the team's code, a folder with {@code bin\javac.exe}
 * @param androidSdk        the Android SDK folder; handed to the build as {@code ANDROID_HOME}, so the project's own
 *                          {@code local.properties} is neither needed nor touched
 * @param gradleUserHome    Gradle's cache folder, or null for Gradle's default ({@code %USERPROFILE%\.gradle})
 * @param offline           whether Gradle may not use the network, which works only when its cache is complete
 * @param timeout           how long the build may take before it is stopped
 * @param applicationModule Gradle path of the application module such as {@code :TeamCode}, or null to use the only
 *                          module that is an Android application
 */
public record GradleSettings(
        Path jdkHome, Path androidSdk, Path gradleUserHome, boolean offline, Duration timeout, String applicationModule) {}
