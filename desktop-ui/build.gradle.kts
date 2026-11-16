import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.eclipse.model.Classpath
import org.gradle.plugins.ide.eclipse.model.EclipseModel
import org.gradle.plugins.ide.eclipse.model.Library
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("ftcsim.java-conventions")
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get()))
        freeCompilerArgs.add("-Xjdk-release=${libs.versions.jdk.get()}")
        allWarningsAsErrors.set(true)
    }
}

// The contracts are plain immutable records; telling the Compose compiler so lets composables that take them skip
// recomposition whenever the new value equals the old one, which is what the 30 Hz status updates need.
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    implementation(project(":sim-contracts"))
    implementation(project(":platform"))

    implementation(libs.compose.desktop.windows.x64)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.coroutines.swing)

    implementation(platform(libs.lwjgl.bom))
    implementation(libs.lwjgl.core)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl.opengl)
    runtimeOnly(variantOf(libs.lwjgl.core) { classifier("natives-windows") })
    runtimeOnly(variantOf(libs.lwjgl.glfw) { classifier("natives-windows") })
    runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier("natives-windows") })

    // The fake host and its simulator: test-only, so the shipped window carries none of it.
    testFixturesImplementation(project(":sim-contracts"))
    testFixturesImplementation(project(":platform"))
    // The window's classes carry Compose types in their signatures, and the Compose compiler runs on every Kotlin source set.
    testFixturesImplementation(libs.compose.desktop.windows.x64)

    testImplementation(libs.compose.ui.test)
}

// LWJGL and Skiko call restricted native methods and Unsafe; without these flags the JDK prints warnings.
val nativeAccessFlags = listOf("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")

tasks.withType<Test>().configureEach {
    jvmArgs(nativeAccessFlags)
}

// The window alone, on the fake host of the test fixtures, for interface work. The shipped window has no main of its
// own: the real application is :app:run.
tasks.register<JavaExec>("runFake") {
    group = "application"
    description = "Opens the window on the fake host (fake link, fake preparer, no robot code); --args takes the window's options and --practice."
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("dev.ftcsim.ui.FakeApp")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    jvmArgs(nativeAccessFlags)
}

// Buildship compiles only Java sources; ensure Gradle's Kotlin output folders exist so JDT never flags missing paths.
plugins.withType<EclipsePlugin> {
    val kotlinMainClasses = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
    val kotlinTestClasses = layout.buildDirectory.dir("classes/kotlin/test").get().asFile
    extensions.configure<EclipseModel> {
        classpath.file.whenMerged {
            kotlinMainClasses.mkdirs()
            kotlinTestClasses.mkdirs()
            val entries = (this as Classpath).entries
            if (entries.none { it is Library && it.library.file == kotlinTestClasses }) {
                entries.add(
                    Library(fileReference(kotlinTestClasses)).apply {
                        entryAttributes["test"] = "true"
                    },
                )
            }
        }
    }
}
