import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.eclipse.model.AbstractClasspathEntry
import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.EclipseModel
import org.gradle.plugins.ide.eclipse.model.Library
import org.gradle.plugins.ide.eclipse.model.ProjectDependency

plugins {
    id("ftcsim.java-conventions")
    id("ftcsim.ftc-sdk")
}

// The tests run on SDK 11.1.0 unless -PftcSdk selects another release the simulator supports: the other SDK 11 releases or 12.0.0.
val supportedSdks = listOf(
    libs.versions.ftcSdk11First.get(), libs.versions.ftcSdk11.get(), libs.versions.ftcSdk11Second.get(),
    libs.versions.ftcSdk11Last.get(), libs.versions.ftcSdk12.get(),
)
val sdkVersion = providers.gradleProperty("ftcSdk").orElse(libs.versions.ftcSdk11).get()
require(sdkVersion in supportedSdks) { "-PftcSdk=$sdkVersion is not an SDK release the simulator supports: $supportedSdks" }

ftcSdk {
    version.set(sdkVersion)
}

// The importer's own resource generator, a Java program in project-import (see ResourceBuilder): it stands in for
// what aapt2 does when it links an Android app, for the libraries the robot world runs. Its output directory holds
// r.jar (the R classes, as bytecode), the resource table and the assets, and both the jar and the directory are
// robot class path entries.
val resourceTool by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    resourceTool(project(":project-import"))
}

fun registerResources(taskName: String, aars: FileCollection, directory: Provider<Directory>) =
    tasks.register<JavaExec>(taskName) {
        // A list of AARs can be longer than a Windows command line, so the tool reads it from a file.
        val aarList = layout.buildDirectory.file("tmp/$taskName/aars.txt")
        inputs.files(aars).withPropertyName("aars").withPathSensitivity(PathSensitivity.NAME_ONLY)
        outputs.dir(directory).withPropertyName("resources")
        classpath(resourceTool)
        mainClass.set("dev.ftcsim.importer.resources.GenerateResources")
        doFirst {
            aarList.get().asFile.apply {
                parentFile.mkdirs()
                writeText(aars.files.joinToString("\n") { it.absolutePath })
            }
        }
        argumentProviders.add(CommandLineArgumentProvider { listOf(directory.get().asFile.path, aarList.get().asFile.path) })
    }

// One folder per SDK version, so that switching versions regenerates nothing, and a test JVM that still has one
// version's r.jar open (Windows will not replace an open jar) does not block a run on the other.
val sdkResourcesDirectory = layout.buildDirectory.dir("generated/sdk-resources/$sdkVersion")

val generateSdkResources = registerResources(
    "generateSdkResources",
    configurations.getByName("ftcSdkAars").incoming.artifactView {
        attributes { attribute(Attribute.of("artifactType", String::class.java), "aar") }
    }.files,
    sdkResourcesDirectory,
)

// Scenarios are code that runs inside the robot class loader, next to the SDK, exactly as a robot program does.
// The host-side tests start them and look only at the plain results they return.
val scenarios by sourceSets.creating

// Library hooks for the lifecycle tests. Only the tests that want them add this directory to the robot program,
// because the SDK finds hooks by scanning the program and runs every one it finds.
val hookScenarios by sourceSets.creating

val robotWorld by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// What the two-process test starts the real worker process with: the worker's jars and everything they need.
val workerRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    "scenariosCompileOnly"(ftcSdk.jars)
    "scenariosCompileOnly"(libs.androidx.annotation)
    "scenariosCompileOnly"(project(":sdk-common"))
    "scenariosCompileOnly"(project(":android-shim"))
    "hookScenariosCompileOnly"(ftcSdk.jars)
    "hookScenariosCompileOnly"(libs.androidx.annotation)
    "hookScenariosCompileOnly"(project(":android-shim"))
    robotWorld(project(":sdk-common"))
    workerRuntime(project(":worker"))

    testImplementation(project(":robot-runtime"))
    testImplementation(project(":sim-contracts"))
    testImplementation(project(":sim-core"))
    testImplementation(project(":sim-hardware"))
    testImplementation(project(":sim-mechanisms"))
    testImplementation(project(":simulator"))
    testImplementation(project(":sim-link"))
    testImplementation(project(":project-import"))
    // The application's host, which starts the real worker and prepares projects; its interfaces are the window's.
    testImplementation(project(":app"))
    testImplementation(project(":desktop-ui"))
    testImplementation(project(":game-decode"))
    testImplementation(project(":game-biobuzz"))
}

tasks.test {
    useJUnitPlatform()
    // A robot program's own threads (Hornet's Panels timer, its socket proxy) outlive a session inside a test JVM, and
    // with them the class loader of its world; in production the worker is a process that ends with its session. So
    // every test class gets a JVM of its own, and room for the sessions of the class that has the most.
    forkEvery = 1
    maxHeapSize = "2g"
    // Declared as inputs so Gradle builds the jars first and re-runs the tests when any of them change.
    inputs.files(robotWorld).withPropertyName("robotWorld")
    inputs.files(workerRuntime).withPropertyName("workerRuntime")
    inputs.files(scenarios.output.classesDirs).withPropertyName("scenarioClasses")
    inputs.files(hookScenarios.output.classesDirs).withPropertyName("hookScenarioClasses")
    inputs.files(ftcSdk.jars).withPropertyName("sdkJars")
    val sdkResources = files(
        sdkResourcesDirectory.map { it.file("r.jar") },
        sdkResourcesDirectory,
    ).builtBy(generateSdkResources)
    inputs.files(sdkResources).withPropertyName("sdkResources")
    // HornetPackageTest imports Hornet through project-import and boots the SDK host on the package; it runs only
    // with -Pftcsim.hornet=<checkout> (optionally -Pftcsim.jdk=<JDK home> and -Pftcsim.androidSdk=<SDK folder>).
    val hornetCheckout = providers.gradleProperty("ftcsim.hornet")
    hornetCheckout.orNull?.let {
        systemProperty("ftcsim.hornet", it)
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    doFirst {
        if (!hornetCheckout.isPresent) {
            logger.quiet(
                "acceptance-tests: HornetPackageTest is skipped. Enable it with -Pftcsim.hornet=<path to the Hornet checkout> " +
                    "(optionally -Pftcsim.jdk=<JDK home> -Pftcsim.androidSdk=<Android SDK folder>).",
            )
        }
    }
    providers.gradleProperty("ftcsim.jdk").orNull?.let { systemProperty("ftcsim.jdk", it) }
    providers.gradleProperty("ftcsim.androidSdk").orNull?.let { systemProperty("ftcsim.androidSdk", it) }
    // HornetBringupTest runs Hornet's OpModes on the simulated robot; the package it needs is imported once into this folder.
    providers.gradleProperty("ftcsim.hornetPackage").orNull?.let {
        systemProperty("ftcsim.hornetPackage", it)
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    // A team project's own Android build needs a JDK 17 to 21 (the Gradle of a 2024 project cannot read the class files of
    // Java 25), which is not the JDK the tests run on: one is found or provisioned like the other toolchains, but only
    // when a Hornet test is asked for and -Pftcsim.jdk does not name one.
    val projectBuildJdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    val hornetWanted = hornetCheckout.isPresent || providers.gradleProperty("ftcsim.hornetPackage").isPresent
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        if (!hornetWanted || providers.gradleProperty("ftcsim.jdk").isPresent) {
            emptyList()
        } else {
            listOf("-Dftcsim.jdk=${projectBuildJdk.get().metadata.installationPath.asFile}")
        }
    })
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dftcsim.simulatorClasspath=${robotWorld.files.joinToString(File.pathSeparator)}")
    })
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dftcsim.workerClasspath=${workerRuntime.files.joinToString(File.pathSeparator)}")
    })
    // The class path a test starts a JVM of its own with (HornetCrossJvmTest).
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dftcsim.childClasspath=${sourceSets.test.get().runtimeClasspath.asPath}")
    })
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val robotClasspath = scenarios.output.classesDirs.files + hookScenarios.output.classesDirs.files +
            robotWorld.files + ftcSdk.jars.files + sdkResources.files
        val program = scenarios.output.classesDirs.files
        listOf(
            "-Dftcsim.robotClasspath=${robotClasspath.joinToString(File.pathSeparator)}",
            "-Dftcsim.programClasspath=${program.joinToString(File.pathSeparator)}",
            "-Dftcsim.hookClasspath=${hookScenarios.output.classesDirs.files.joinToString(File.pathSeparator)}",
            "-Dftcsim.nativeDirectory=${layout.buildDirectory.dir("natives").get().asFile}",
            "-Dftcsim.sdkVersion=$sdkVersion",
            "--enable-native-access=ALL-UNNAMED",
        )
    })
}

// Buildship imports only main and test configurations by default; include the scenario projects and desktop-ui's
// compiled Kotlin classes, and expose every dependency across all test and scenario source folders in Eclipse.
plugins.withType<EclipsePlugin> {
    val desktopUiKotlinClasses = project(":desktop-ui").layout.buildDirectory.dir("classes/kotlin/main").get().asFile
    extensions.configure<EclipseModel> {
        classpath.file.whenMerged {
            desktopUiKotlinClasses.mkdirs()
            val entries = (this as Classpath).entries
            for (projectPath in listOf("/sdk-common", "/android-shim")) {
                if (entries.none { it is ProjectDependency && it.path == projectPath }) {
                    entries.add(
                        ProjectDependency(projectPath).apply {
                            entryAttributes["without_test_code"] = "true"
                        },
                    )
                }
            }
            if (entries.none { it is Library && it.library.file == desktopUiKotlinClasses }) {
                entries.add(Library(fileReference(desktopUiKotlinClasses)))
            }
            entries.filterIsInstance<AbstractClasspathEntry>()
                .filter { it.kind != "src" || it.path.startsWith("/") }
                .forEach { entry ->
                    entry.entryAttributes.remove("gradle_used_by_scope")
                    entry.entryAttributes.remove("test")
                }
        }
    }
}
