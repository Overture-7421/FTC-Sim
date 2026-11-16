plugins {
    id("ftcsim.java-conventions")
}

dependencies {
    api(project(":sim-contracts"))
    api(project(":sim-core"))
    api(project(":sim-physics"))
    api(project(":sim-hardware"))
    api(project(":games"))
    implementation(project(":robot-runtime"))
    implementation(project(":platform"))
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("ftcsim.nativeDirectory", layout.buildDirectory.dir("natives").get().asFile.path)
}
