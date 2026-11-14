import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("ftcsim.java-conventions")
}

val debugNatives by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    api(project(":sim-contracts"))
    implementation(libs.jolt.jni)
    runtimeOnly(variantOf(libs.jolt.jni) { classifier("ReleaseSp") })
    debugNatives(variantOf(libs.jolt.jni) { classifier("DebugSp") })
}

// The DLL is extracted below build/ so that no test or benchmark writes outside the project.
val joltNativeDirectory = layout.buildDirectory.dir("jolt-natives")

val joltJvmArguments = CommandLineArgumentProvider {
    listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Dftcsim.nativeDirectory=${joltNativeDirectory.get().asFile}",
        "-Dftcsim.releaseNativesJar=${configurations.runtimeClasspath.get().files.single { it.name.endsWith("-ReleaseSp.jar") }}",
        "-Dftcsim.debugNativesJar=${debugNatives.singleFile}",
        "-Dftcsim.childClasspath=${sourceSets.test.get().runtimeClasspath.asPath}",
    )
}

tasks.test {
    jvmArgumentProviders.add(joltJvmArguments)
    // The debug natives are only loaded by child JVMs, so Gradle would not see them on the test classpath.
    inputs.files(debugNatives).withPropertyName("debugNatives")
}

// Developer entry points: the first prints the lines of the golden hash resource, the second the
// timing report of the final golden scenario.
for ((taskName, main) in mapOf(
    "goldenHashes" to "dev.ftcsim.physics.GoldenRunner",
    "goldenBenchmark" to "dev.ftcsim.physics.GoldenBenchmark",
)) {
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass = main
        jvmArgumentProviders.add(joltJvmArguments)
    }
}
