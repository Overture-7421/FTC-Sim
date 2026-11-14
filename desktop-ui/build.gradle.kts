import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("ftcsim.java-conventions")
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

    implementation(compose.desktop.windows_x64)
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

    testImplementation(libs.compose.ui.test)
}

// LWJGL and Skiko call restricted native methods and Unsafe; without these flags the JDK prints warnings.
val nativeAccessFlags = listOf("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")

compose.desktop {
    application {
        mainClass = "dev.ftcsim.ui.App"
        jvmArgs += nativeAccessFlags
        // `run` starts the app on the project's JDK 25, not on whichever JVM runs Gradle.
        javaHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs(nativeAccessFlags)
}
