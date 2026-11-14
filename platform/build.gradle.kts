plugins {
    id("ftcsim.java-conventions")
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
