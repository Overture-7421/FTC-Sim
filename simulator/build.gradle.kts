plugins {
    id("ftcsim.java-conventions")
}

dependencies {
    api(project(":sim-contracts"))
    api(project(":sim-core"))
    api(project(":sim-physics"))
    api(project(":sim-hardware"))
    api(project(":sim-mechanisms"))
    api(project(":sim-field"))
    implementation(project(":robot-runtime"))
    implementation(project(":platform"))
    // The profile records carry Jackson annotations, so whoever reads them needs the annotation classes too.
    api(libs.jackson.databind)

    // The shot sweep puts the bundled robot into a real DECODE field.
    testImplementation(project(":game-decode"))
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
