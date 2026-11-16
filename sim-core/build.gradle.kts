plugins {
    id("ftcsim.java-conventions")
}

dependencies {
    api(project(":sim-contracts"))
}

// Raise with -PkernelStress=200 to hunt for rare scheduling races in the kernel.
tasks.test {
    systemProperty("ftcsim.kernelStress", providers.gradleProperty("kernelStress").getOrElse("4"))
}
