plugins {
    id("ftcsim.java-conventions")
}

dependencies {
    implementation(project(":sim-contracts"))
    implementation(project(":sim-link"))
    implementation(project(":simulator"))
    // The fields a request can name, and the reader of the prepared programs that the project importer writes.
    implementation(project(":games"))
    implementation(project(":project-import"))
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
