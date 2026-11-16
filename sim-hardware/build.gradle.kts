plugins {
    id("ftcsim.java-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":sim-contracts"))
}
