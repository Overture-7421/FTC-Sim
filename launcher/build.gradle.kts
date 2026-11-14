import java.time.Duration

plugins {
    id("ftcsim.java-conventions")
}

val stage0Scripts = layout.projectDirectory.dir("scripts")
val maintainerTools = layout.projectDirectory.dir("tools")

// This module is the double-clickable bootstrap: PowerShell scripts, which need no Java, and the manifest that pins the
// runtime. It has no Java code of its own; the :app:dist task lays the scripts out beside lib\app.jar. What it has are
// tests, which run the shipped scripts under Windows PowerShell 5.1, so the scripts are test inputs. The tools are the
// maintainers' (they move the pin) and are not shipped.
tasks.test {
    timeout.set(Duration.ofMinutes(20))
    inputs.dir(stage0Scripts).withPropertyName("stage0Scripts").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(maintainerTools).withPropertyName("maintainerTools").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("ftcsim.stage0.scripts", stage0Scripts.asFile.absolutePath)
    systemProperty("ftcsim.test.work", layout.buildDirectory.dir("test-work").get().asFile.absolutePath)
}
