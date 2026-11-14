plugins {
    id("ftcsim.java-conventions")
}

dependencies {
    // org.json is part of the Android platform, so it belongs to the platform shim; this is Android's own implementation.
    api(libs.android.json)
}
