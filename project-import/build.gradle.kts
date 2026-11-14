plugins {
    id("ftcsim.java-conventions")
}

dependencies {
    api(project(":sim-contracts"))
    implementation(libs.asm)
    implementation(libs.jackson.databind)
}

// Importing a real project runs its Gradle build, needs a JDK and the Android SDK and takes a minute or more,
// so the end-to-end test of Hornet only runs when -Pftcsim.hornet names a checkout of it.
val hornetCheckout = providers.gradleProperty("ftcsim.hornet")

tasks.test {
    hornetCheckout.orNull?.let {
        systemProperty("ftcsim.hornet", it)
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    // The JDK that runs the project's Gradle build (default: the one running the tests) and the Android SDK
    // (default: ANDROID_HOME).
    providers.gradleProperty("ftcsim.jdk").orNull?.let { systemProperty("ftcsim.jdk", it) }
    providers.gradleProperty("ftcsim.androidSdk").orNull?.let { systemProperty("ftcsim.androidSdk", it) }
    doFirst {
        if (!hornetCheckout.isPresent) {
            logger.quiet(
                "project-import: the end-to-end import of Hornet is skipped. " +
                    "Enable it with -Pftcsim.hornet=<path to the Hornet checkout> " +
                    "(optionally -Pftcsim.jdk=<JDK 17 or 21 home> -Pftcsim.androidSdk=<Android SDK folder>).",
            )
        }
    }
}
