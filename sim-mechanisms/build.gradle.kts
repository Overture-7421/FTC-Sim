plugins {
    id("ftcsim.java-conventions")
}

dependencies {
    api(project(":sim-contracts"))
    api(project(":sim-physics"))
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("ftcsim.nativeDirectory", layout.buildDirectory.dir("natives").get().asFile.path)
}
