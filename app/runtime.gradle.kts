import javax.inject.Inject
import org.gradle.process.ExecOperations

// The Java runtime of the distribution: a jlink image of the JDK this project is compiled for, which lands in the folder
// runtime of the distribution. The application and the worker run on it, so a PC needs no Java, and the Java is the one the
// code was built and tested on. The JDK is the toolchain's (ftcsim.java-conventions), unless -Pftcsim.runtimeJdk=<JDK home>
// names another JDK of the same major version, for example the Eclipse Temurin that a release is made from.

// java.se is every standard module, so a team library that needs one of them runs. The rest are modules that java.se does
// not name and that something on the robot's side may reach: sun.misc.Unsafe (LWJGL, the FTC SDK), the platform MXBeans,
// TLS with elliptic curves, zip file systems, the extended socket options, com.sun.net.httpserver (a robot program's own
// web server, such as Panels') and the character sets beyond the standard ones. jdk.jfr, jdk.compiler, the debugging and
// monitoring tools and jdk.localedata (about 10 MB; the worker runs with the en-US locale) are left out.
val runtimeModules = listOf(
    "java.se", "jdk.unsupported", "jdk.management", "jdk.crypto.ec", "jdk.zipfs", "jdk.net", "jdk.httpserver", "jdk.charsets",
)

abstract class LinkRuntime : DefaultTask() {
    /** The `lib\modules` of the JDK, which jlink reads; it is the content of the JDK as far as the image is concerned. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jdkModules: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jdkRelease: RegularFileProperty

    @get:Input
    abstract val modules: ListProperty<String>

    /** The major version the image must have: the one the code is compiled for. */
    @get:Input
    abstract val major: Property<Int>

    @get:OutputDirectory
    abstract val image: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun link() {
        val jdkHome = jdkRelease.get().asFile.parentFile
        val jlink = jdkHome.resolve("bin/jlink.exe")
        check(jlink.isFile) { "$jlink is missing: the runtime is made with the jlink of a JDK, and $jdkHome is not one." }
        val version = jdkRelease.get().asFile.readLines().firstNotNullOfOrNull { Regex("""JAVA_VERSION="(\d+)[^"]*"""").matchEntire(it)?.groupValues?.get(1) }
        check(version?.toInt() == major.get()) {
            "$jdkHome is Java $version, and the code is compiled for Java ${major.get()}: the runtime would not be the Java the code was built on."
        }
        val output = image.get().asFile
        output.deleteRecursively()
        // No --module-path: jlink links the modules of the running image of its own JDK, which every JDK 24 or later can do,
        // so a JDK with or without jmods gives the same runtime.
        exec.exec {
            executable = jlink.path
            args(
                "--add-modules", modules.get().joinToString(","),
                "--output", output.path,
                "--strip-debug", "--no-header-files", "--no-man-pages", "--compress", "zip-6",
            )
        }
        check(output.resolve("bin/java.exe").isFile && output.resolve("bin/javaw.exe").isFile) { "jlink made no runtime in $output" }
    }
}

val jdkMajor = extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("jdk").get().requiredVersion.toInt()
val runtimeJdkHome: Provider<File> = providers.gradleProperty("ftcsim.runtimeJdk").map { File(it) }
    .orElse(
        extensions.getByType<JavaToolchainService>().launcherFor(extensions.getByType<JavaPluginExtension>().toolchain)
            .map { it.metadata.installationPath.asFile },
    )
val runtimeJdkRelease: Provider<RegularFile> = layout.file(runtimeJdkHome.map { it.resolve("release") })

// The JDK's own release file says who made it and which build it is; the licence notices name both.
extra["runtimeJdkRelease"] = runtimeJdkRelease

val runtimeImage = tasks.register<LinkRuntime>("runtimeImage") {
    group = "distribution"
    description = "Links the Java runtime of the distribution with the jlink of the toolchain's JDK."
    jdkModules.set(layout.file(runtimeJdkHome.map { it.resolve("lib/modules") }))
    jdkRelease.set(runtimeJdkRelease)
    modules.set(runtimeModules)
    major.set(jdkMajor)
    image.set(layout.buildDirectory.dir("dist-parts/runtime"))
}
