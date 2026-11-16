package dev.ftcsim.build

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.util.zip.ZipFile

/**
 * Extracts the JVM classes of an Android library (AAR) so that plain Gradle can compile and run against
 * the FTC SDK. Only `classes.jar` and the jars bundled under `libs` are taken; resources, manifests and
 * native code are not needed on a desktop JVM.
 */
@CacheableTransform
abstract class AarToJar : TransformAction<TransformParameters.None> {

    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    @get:InputArtifact
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val aar = inputArtifact.get().asFile
        val base = aar.nameWithoutExtension
        ZipFile(aar).use { zip ->
            for (entry in zip.entries()) {
                val target = when {
                    entry.name == "classes.jar" -> "$base-classes.jar"
                    entry.name.startsWith("libs/") && entry.name.endsWith(".jar") && entry.name.count { it == '/' } == 1 ->
                        "$base-${entry.name.removePrefix("libs/")}"
                    else -> continue
                }
                zip.getInputStream(entry).use { input ->
                    outputs.file(target).outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
