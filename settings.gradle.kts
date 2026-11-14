pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Lets Gradle download a JDK 25 when none is installed, for the java toolchain and for the Gradle daemon.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Compose Multiplatform publishes its androidx.* building blocks only to Google's repository.
        google {
            content {
                includeGroupByRegex("androidx\\..*")
            }
        }
    }
}

rootProject.name = "ftc-sim"

include(
    "sim-contracts",
    "platform",
    "sim-core",
    "sim-physics",
    "sim-hardware",
    "sim-mechanisms",
    "sim-field",
    "game-decode",
    "game-biobuzz",
    "simulator",
    "sim-link",
    "worker",
    "robot-runtime",
    "android-shim",
    "sdk-common",
    "project-import",
    "desktop-ui",
    "app",
    "launcher",
    "acceptance-tests",
)
