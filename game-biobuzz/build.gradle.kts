plugins {
    id("ftcsim.java-conventions")
}

dependencies {
    api(project(":sim-field"))
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
