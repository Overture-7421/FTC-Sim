import groovy.json.JsonSlurper
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

// The places to look for a JDK are defined once, in the launcher's manifest; the application carries a copy of that
// file as a resource, so it travels in the jar and needs no path to the launcher.
val jdkManifest = rootProject.layout.projectDirectory.file("launcher/scripts/jdk-manifest.json")

tasks.processResources {
    from(jdkManifest) { into("dev/ftcsim/app") }
    doFirst {
        require(jdkManifest.asFile.isFile) { "$jdkManifest is missing; the application reads the places to look for a JDK from it" }
    }
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

// What the application needs from the Java it runs on, besides the version, is said once in the launcher's manifest:
// the launcher starts the application with it, the self test checks the running JVM against it, and `run` uses it.
val applicationJvmArguments: List<String> = run {
    val manifest = JsonSlurper().parse(jdkManifest.asFile) as Map<*, *>
    val arguments = (manifest["application"] as? Map<*, *>)?.get("jvmArguments") as? List<*>
    requireNotNull(arguments) { "$jdkManifest has no application.jvmArguments" }.map { it as String }
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs FTC-Sim as a user would: the window, a worker process and the importer, on the project's JDK 25. " +
        "Options go after --args, for example --args=\"--exit-after=30 --no-focus\"."
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
// and the robot world run on is in lib once; the launcher's scripts are laid out beside lib exactly as they are in the
// launcher module, which is referenced by path and not as a dependency.
val launcherScripts = rootProject.layout.projectDirectory.dir("launcher/scripts")
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

// The licence notices (THIRD-PARTY-NOTICES.txt and the licenses folder) are written from the metadata of the jars of lib.
apply(from = "notices.gradle.kts")

val distFolderTask = tasks.register<Sync>("distFolder") {
    group = "distribution"
    description = "Lays out the folder of the distribution: the launcher's scripts, the licence notices and lib with every jar once."
    into(distFolder)
    from(launcherScripts)
    from(tasks.named("thirdPartyNotices"))
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
            "-Dftcsim.launcherScripts=${launcherScripts.asFile}",
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
