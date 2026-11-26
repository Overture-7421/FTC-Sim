plugins {
    id("ftcsim.java-conventions")
}

dependencies {
    api(project(":sim-contracts"))
    implementation(libs.asm)
}

// Fixture classes are loaded a second time by the robot class loader under test.
// ReflectHooks uses sun.misc.Unsafe to match Android ART static-final reflection; suppress the JDK warning in tests.
tasks.test {
    jvmArgs("--sun-misc-unsafe-memory-access=allow")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dftcsim.fixtureClasses=${sourceSets.test.get().output.classesDirs.asPath}")
    })
}
