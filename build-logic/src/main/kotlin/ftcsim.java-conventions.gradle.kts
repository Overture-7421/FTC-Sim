import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
}

val catalog = versionCatalogs.named("libs")
val jdk = catalog.findVersion("jdk").get().requiredVersion.toInt()

group = "dev.ftcsim"

java {
    // JDK 25 whatever JAVA_HOME and PATH say; Gradle finds an installed one or downloads one (see settings.gradle.kts).
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jdk))
    }
}

tasks.withType<JavaCompile>().configureEach {
    // The simulator only ever runs on that JDK, so its bytecode and library API are that JDK's own.
    options.release.set(jdk)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = 1
    jvmArgs("-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", "-Duser.timezone=UTC")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

dependencies {
    testImplementation(platform(catalog.findLibrary("junit-bom").get()))
    testImplementation(catalog.findLibrary("junit-jupiter").get())
    testRuntimeOnly(catalog.findLibrary("junit-platform-launcher").get())
}
