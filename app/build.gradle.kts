import org.apache.tools.ant.filters.FixCrLfFilter
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.EclipseModel
import org.gradle.plugins.ide.eclipse.model.Library

plugins {
    id("ftcsim.java-conventions")
}

dependencies {
    implementation(project(":sim-contracts"))
    implementation(project(":sim-link"))
    implementation(project(":desktop-ui"))
    implementation(project(":project-import"))
    implementation(libs.jackson.databind)
}

// The window's classes are Kotlin and carry annotations of Compose's runtime, which this module compiles against the
// window's entry point but not against Compose itself; javac would warn about each of them.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}

// What the application needs from the Java it runs on besides its version is said once, in src/dist/runtime-options: the
// launcher starts the Java with it as an argument file, the application carries it as a resource so that the self test can
// check the running JVM against it, and `run` uses it. The major version is the toolchain's, which the runtime is made from.
val runtimeOptionsFile = layout.projectDirectory.file("src/dist/runtime-options")
val jdkMajor = libs.versions.jdk.get()

// The version of a release is given on the command line (-PappVersion=1.2.3, which the release workflow does) and ends up in
// version.properties inside the jar; every other build is 0.0.0-dev, which never looks for updates.
val appVersion = providers.gradleProperty("appVersion").orElse("0.0.0-dev").get()
require(Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?""").matches(appVersion)) { "appVersion must look like 1.2.3 or 1.2.3-rc.1: $appVersion" }

tasks.processResources {
    from(runtimeOptionsFile) { into("dev/ftcsim/app") }
    inputs.property("jdkMajor", jdkMajor)
    inputs.property("appVersion", appVersion)
    filesMatching("**/runtime.properties") { expand("jdkMajor" to jdkMajor) }
    filesMatching("**/version.properties") { expand("appVersion" to appVersion) }
}

// The worker is a program the application starts, and the robot world is a set of jars the worker and the importer read:
// neither is on the application's own class path. Their jars are resolved here, in configurations that nothing
// extends, and handed over as a file of paths. See docs/architecture/app.md; the module rules allow exactly this.
val workerRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val robotWorld by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    workerRuntime(project(":worker"))
    robotWorld(project(":sdk-common"))
}

// The one writer of the class path file, for development (absolute paths into the build folders of the modules) and for
// the distribution (the bare names of the jars that lie beside the file): only what the entries look like differs.
abstract class WriteClasspathFile : DefaultTask() {
    @get:Input
    abstract val worker: ListProperty<String>

    @get:Input
    abstract val robot: ListProperty<String>

    @get:OutputFile
    abstract val target: RegularFileProperty

    @TaskAction
    fun write() {
        val separator = File.pathSeparator
        val writer = name
        target.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "# Written by :app:$writer and read by dev.ftcsim.app.AppClasspath: class paths, one line each.\n" +
                    "worker=${worker.get().joinToString(separator)}\n" +
                    "robot=${robot.get().joinToString(separator)}\n",
            )
        }
    }
}

val classpathFile = layout.buildDirectory.file("classpath/ftcsim-classpath.properties")

val writeClasspath = tasks.register<WriteClasspathFile>("writeClasspath") {
    group = "build"
    description = "Writes the file that tells the application where the worker's jars and the robot world's jars are."
    worker.set(workerRuntime.elements.map { jars -> jars.map { it.asFile.absolutePath } })
    robot.set(robotWorld.elements.map { jars -> jars.map { it.asFile.absolutePath } })
    target.set(classpathFile)
    dependsOn(workerRuntime, robotWorld)
}

val applicationJvmArguments: List<String> = runtimeOptionsFile.asFile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs FTC-Sim as a user would: the window, a worker process and the importer, on the project's JDK 25 (the toolchain's; " +
        "the distribution has its own runtime). Options go after --args, for example --args=\"--exit-after=30 --no-focus\"."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.ftcsim.app.Main")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    jvmArgs(applicationJvmArguments)
    dependsOn(writeClasspath)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dftcsim.classpathFile=${classpathFile.get().asFile}")
    })
}

// The distribution: the folder a driver unzips (build/dist/FTC-Sim) and its zip. Every jar the application, the worker
// and the robot world run on is in lib once; src/dist (FTC-Sim.cmd and the options) is laid out beside lib as it is, and the
// Java runtime that the application and the worker run on is in runtime (runtime.gradle.kts).
val distSources = layout.projectDirectory.dir("src/dist")
val distFolder = layout.buildDirectory.dir("dist/FTC-Sim")
val distZipFile = layout.buildDirectory.file("dist/FTC-Sim.zip")
val applicationJars = configurations.runtimeClasspath

val writeDistClasspath = tasks.register<WriteClasspathFile>("writeDistClasspath") {
    description = "Writes the class path file of the distribution: bare jar names, relative to the file, which sits in lib."
    worker.set(workerRuntime.elements.map { jars -> jars.map { it.asFile.name } })
    robot.set(robotWorld.elements.map { jars -> jars.map { it.asFile.name } })
    target.set(layout.buildDirectory.file("dist-parts/ftcsim-classpath.properties"))
}

// The launcher starts the application with `-jar lib\app.jar`. The jar's manifest names the other jars of lib in the
// order the application's own class path has, so the command line stays short and nothing of the worker's or the robot
// world's jars (which have classes of their own, such as android.*) lands on the application's class path.
val distJar = tasks.register<Jar>("distJar") {
    description = "The application's jar of the distribution: its classes, its main class and the class path of lib."
    archiveFileName.set("app.jar")
    destinationDirectory.set(layout.buildDirectory.dir("dist-parts"))
    from(sourceSets.main.get().output)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest.attributes(
        "Main-Class" to "dev.ftcsim.app.Main",
        "Class-Path" to applicationJars.map { jars -> jars.files.joinToString(" ") { it.name } },
    )
}

// The licence notices (THIRD-PARTY-NOTICES.txt and the licenses folder) are written from the metadata of the jars of lib and
// from the release file of the JDK the runtime is made from.
apply(from = "runtime.gradle.kts")
apply(from = "notices.gradle.kts")

val distFolderTask = tasks.register<Sync>("distFolder") {
    group = "distribution"
    description = "Lays out the folder of the distribution: FTC-Sim.cmd and its options, the Java runtime, the licence notices and lib with every jar once."
    into(distFolder)
    // A batch file with Unix line endings misreads its labels, whatever the checkout did to the source.
    from(distSources) {
        filesMatching("*.cmd") { filter(mapOf("eol" to FixCrLfFilter.CrLf.newInstance("crlf")), FixCrLfFilter::class.java) }
    }
    from(tasks.named("thirdPartyNotices"))
    into("runtime") {
        from(tasks.named("runtimeImage"))
    }
    into("lib") {
        from(distJar)
        from(applicationJars, workerRuntime, robotWorld)
        from(writeDistClasspath)
        val firstOfName = mutableMapOf<String, File>()
        eachFile {
            val earlier = firstOfName.putIfAbsent(name, file)
            when {
                earlier == null -> require(name.endsWith(".jar") || name == "ftcsim-classpath.properties") { "lib may hold only jars: $file" }
                earlier == file -> exclude()
                else -> throw GradleException("Two different files are both called $name in lib: $earlier and $file")
            }
        }
    }
}

val distZip = tasks.register<Zip>("distZip") {
    group = "distribution"
    description = "Zips the distribution folder, byte for byte the same on every run."
    from(distFolderTask) { into("FTC-Sim") }
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    archiveFileName.set("FTC-Sim.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register("dist") {
    group = "distribution"
    description = "Assembles build/dist/FTC-Sim, the folder a driver unzips, and build/dist/FTC-Sim.zip."
    dependsOn(distFolderTask, distZip)
}

tasks.test {
    // The test that checks the file Gradle writes against what the application reads, and the ones that check the
    // distribution as built: its layout, and the real application started from a copy of it.
    dependsOn(writeClasspath, distFolderTask, distZip)
    inputs.dir(distFolder).withPropertyName("distFolder").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(distZipFile).withPropertyName("distZip")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dftcsim.classpathFile=${classpathFile.get().asFile}",
            "-Dftcsim.dist=${distFolder.get().asFile}",
            "-Dftcsim.distZip=${distZipFile.get().asFile}",
            "-Dftcsim.distSources=${distSources.asFile}",
            "-Dftcsim.installerScript=${layout.projectDirectory.file("installer/FTC-Sim.iss").asFile}",
            "-Dftcsim.releaseWorkflow=${rootProject.layout.projectDirectory.file(".github/workflows/release.yml").asFile}",
        )
    })
}

// Buildship links :desktop-ui's Java output folder only; add Gradle's compiled Kotlin classes so Main resolves App.
plugins.withType<EclipsePlugin> {
    val desktopUiKotlinClasses = project(":desktop-ui").layout.buildDirectory.dir("classes/kotlin/main").get().asFile
    extensions.configure<EclipseModel> {
        classpath.file.whenMerged {
            desktopUiKotlinClasses.mkdirs()
            val entries = (this as Classpath).entries
            if (entries.none { it is Library && it.library.file == desktopUiKotlinClasses }) {
                entries.add(Library(fileReference(desktopUiKotlinClasses)))
            }
        }
    }
}
