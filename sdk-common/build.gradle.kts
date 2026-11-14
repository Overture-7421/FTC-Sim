plugins {
    id("ftcsim.java-conventions")
    id("ftcsim.ftc-sdk")
}

ftcSdk {
    version.set(libs.versions.ftcSdk11)
}

dependencies {
    api(project(":sim-contracts"))
    implementation(project(":android-shim"))
    compileOnly(ftcSdk.jars)
    compileOnly(libs.androidx.annotation)
}
