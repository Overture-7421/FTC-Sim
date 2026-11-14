plugins {
    id("ftcsim.java-conventions")
}

dependencies {
    api(project(":sim-contracts"))
    implementation(libs.asm)
}

// Fixture classes are loaded a second time by the robot class loader under test.
tasks.test {
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dftcsim.fixtureClasses=${sourceSets.test.get().output.classesDirs.asPath}")
    })
}
