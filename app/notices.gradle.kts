import groovy.json.JsonSlurper
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.w3c.dom.Element

// The licence notices of the distribution: THIRD-PARTY-NOTICES.txt, which says for every jar of lib which library it is and
// under which licence it is distributed, and a licenses folder with the licence texts that file points at.
//
// What a jar is comes from the resolved dependencies of the application, the worker and the robot world; the licence of a
// library from the POM that its publisher put beside the jar (the parent POMs too, as Maven reads them). Nothing is guessed:
// a jar whose licence the build cannot determine, or has no licence text for, fails the build with its name. The texts are
// in app/licenses, copied from the projects themselves, and are not generated.
//
// The JDK that FTC-Sim downloads on the first start is not in the zip, and gets its own section from the runtime manifest.

val runtimeManifestFile = rootProject.layout.projectDirectory.file("launcher/scripts/jdk-manifest.json").asFile
val pinnedRuntimeRelease: String = run {
    val runtime = (JsonSlurper().parse(runtimeManifestFile) as Map<*, *>)["runtime"] as Map<*, *>
    (runtime["exactRelease"] as String?) ?: ""
}

abstract class GenerateNotices : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val texts: DirectoryProperty

    @get:Input
    abstract val runtimeRelease: Property<String>

    @get:OutputDirectory
    abstract val notices: DirectoryProperty

    /** Which library each jar is: the resolved artifacts of the configurations whose jars are in lib. Not an input of its own; they are the jars above. */
    @get:Internal
    var resolved: List<ArtifactCollection> = emptyList()

    @get:Internal
    var dependencyHandler: DependencyHandler? = null

    /** A licence as the notices name it, and the text of it in app/licenses. */
    private class Licence(val id: String, val name: String, val url: String, val textFile: String)

    /** Something compiled into the native library of a jar, which no POM mentions. [jarSuffix] limits it to the jars that carry the library. */
    private class Bundled(
        val name: String,
        val licenceId: String,
        val copyright: String,
        val project: String,
        val textFile: String,
        val inside: String,
        val jarSuffix: String = "",
    )

    private class Library(
        val file: String,
        val coordinates: String,
        val licences: List<Licence>,
        val project: String?,
        val ownNotices: List<String>,
        val bundled: List<Bundled>,
    )

    private class Pom(val licences: List<Pair<String, String>>, val url: String?, val parent: Triple<String, String, String>?)

    // Licence texts that are the same for everybody, by the licence's own name.
    private val standardTexts = mapOf(
        "Apache-2.0" to "Apache-2.0.txt",
        "GPL-2.0-with-classpath-exception" to "GPL-2.0-with-classpath-exception.txt",
    )

    // A BSD or MIT licence carries the copyright notice of its project, so the text must be the one the project publishes.
    // Keyed by group, or group:artifact when a group has more than one.
    private val projectTexts = mapOf(
        "org.ow2.asm" to "BSD-3-Clause-ASM.txt",
        "org.lwjgl" to "BSD-3-Clause-LWJGL.txt",
        "com.github.stephengold" to "MIT-jolt-jni.txt",
    )

    // Where a licence's text can be read when a POM names the licence but not its address.
    private val canonicalUrls = mapOf(
        "Apache-2.0" to "https://www.apache.org/licenses/LICENSE-2.0",
        "BSD-3-Clause" to "https://opensource.org/licenses/BSD-3-Clause",
        "MIT" to "https://opensource.org/licenses/MIT",
        "GPL-2.0-with-classpath-exception" to "https://openjdk.org/legal/gplv2+ce.html",
    )

    // The licence of a library whose POM names none, or none that is recognised. Keyed by group:artifact, with the licence's
    // name and where it was checked. A library goes here only after a person has read its licence; the build never decides.
    private val overrides: Map<String, Licence> = mapOf()

    // What the native libraries of some jars contain, which is licensed on its own.
    private val bundledComponents = mapOf(
        "com.github.stephengold:jolt-jni-Windows64" to listOf(
            Bundled("Jolt Physics", "MIT", "Copyright 2021 Jorrit Rouwe", "https://github.com/jrouwe/JoltPhysics", "MIT-Jolt-Physics.txt", "the native library joltjni.dll", "-ReleaseSp.jar"),
        ),
        "org.jetbrains.skiko:skiko-awt-runtime-windows-x64" to listOf(
            Bundled("Skia", "BSD-3-Clause", "Copyright (c) 2011 Google Inc.", "https://skia.org", "BSD-3-Clause-Skia.txt", "the native library skiko-windows-x64.dll"),
            Bundled("ICU data", "Unicode-3.0", "Copyright (c) 1991-2026 Unicode, Inc.", "https://icu.unicode.org", "Unicode-ICU-data.txt", "the data file icudtl.dat"),
        ),
        "org.lwjgl:lwjgl-glfw" to listOf(
            Bundled("GLFW", "Zlib", "Copyright (c) 2002-2006 Marcus Geelnard, (c) 2006-2019 Camilla Löwy", "https://www.glfw.org", "Zlib-GLFW.txt", "the native library glfw.dll", "-natives-windows.jar"),
        ),
    )

    @TaskAction
    fun generate() {
        val output = notices.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val origins = HashMap<String, ComponentIdentifier>()
        for (collection in resolved) {
            for (artifact in collection.artifacts) {
                origins.putIfAbsent(artifact.file.name, artifact.id.componentIdentifier)
            }
        }
        val problems = mutableListOf<String>()
        val own = mutableListOf<Pair<String, String>>()
        val libraries = mutableListOf<Library>()
        for (jar in jars.files.sortedBy { it.name.lowercase() }) {
            val origin = origins[jar.name]
            when {
                origin is ProjectComponentIdentifier -> own += jar.name to "FTC-Sim module ${origin.projectPath}"
                origin == null && jar.name == "app.jar" -> own += jar.name to "FTC-Sim application"
                origin is ModuleComponentIdentifier -> {
                    try {
                        libraries += library(jar, origin)
                    } catch (failure: IllegalStateException) {
                        problems += "${jar.name} (${origin.group}:${origin.module}:${origin.version}): ${failure.message}"
                    }
                }
                else -> problems += "${jar.name}: no resolved dependency says which library this jar is"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The licence of these jars of the distribution cannot be determined, so the licence notices cannot be written:\n  " +
                    problems.joinToString("\n  ") +
                    "\nRead the library's licence and add it to GenerateNotices.overrides in app/notices.gradle.kts with the reason, " +
                    "and put its text in app/licenses (projectTexts, when it names its own copyright holder).",
            )
        }

        val usedTexts = sortedSetOf<String>()
        val text = StringBuilder()
        text.append("FTC-Sim third-party notices\n===========================\n\n")
        text.append("FTC-Sim is made of its own code and the libraries listed here. This file names every jar of the lib folder, the\n")
        text.append("licence it is distributed under and where its project is; the full text of each licence is in the licenses folder\n")
        text.append("beside this file. It is written by the build (Gradle task :app:thirdPartyNotices) from the metadata that each\n")
        text.append("library's publisher gives, and must not be edited by hand.\n\n")

        text.append("1. FTC-Sim's own jars\n---------------------\n\n")
        text.append("The licence of FTC-Sim's own code has not been chosen yet by the project owner; this paragraph is replaced when it is.\n\n")
        for ((file, what) in own) {
            text.append("File: $file ($what)\n")
        }
        text.append("\n")

        text.append("2. Libraries\n------------\n\n")
        for (library in libraries) {
            text.append("File: ${library.file}\n  Library: ${library.coordinates}\n")
            for (licence in library.licences) {
                usedTexts += licence.textFile
                val named = if (licence.name.equals(licence.id, ignoreCase = true)) licence.id else "${licence.id} (${licence.name})"
                text.append("  Licence: $named\n  Licence URL: ${licence.url}\n  Licence text: licenses/${licence.textFile}\n")
            }
            library.project?.let { text.append("  Project: $it\n") }
            if (library.ownNotices.isNotEmpty()) {
                text.append("  Notices inside the jar: ${library.ownNotices.joinToString(", ")}\n")
            }
            text.append("\n")
        }

        text.append("3. Components inside the native libraries of those jars\n-------------------------------------------------------\n\n")
        for (library in libraries) {
            for (component in library.bundled) {
                usedTexts += component.textFile
                text.append("${component.name}, in ${component.inside} of ${library.file}\n  Licence: ${component.licenceId}\n")
                text.append("  ${component.copyright}\n  Project: ${component.project}\n  Licence text: licenses/${component.textFile}\n\n")
            }
        }
        text.append("Skia, which Skiko builds in, itself contains further open-source libraries; their notices are those of Skia's\n")
        text.append("third_party folder, https://skia.googlesource.com/skia/+/refs/heads/main/third_party/. The native libraries of\n")
        text.append("LWJGL are listed on https://www.lwjgl.org/license.\n\n")

        usedTexts += "GPL-2.0-with-classpath-exception.txt"
        val release = runtimeRelease.get().ifEmpty { "the newest Temurin release of the pinned Java version" }
        text.append("4. The Java runtime that FTC-Sim downloads\n------------------------------------------\n\n")
        text.append("Eclipse Temurin $release (OpenJDK)\n")
        text.append("  FTC-Sim does not contain a Java runtime. On its first start, when the PC has no Java of exactly this build, it\n")
        text.append("  downloads Temurin from https://github.com/adoptium/temurin25-binaries into %LOCALAPPDATA%\\FTC-Sim\\jdks, checks its\n")
        text.append("  SHA-256 and uses it only for FTC-Sim. Nothing on the PC is changed.\n")
        text.append("  Licence: GPL-2.0-with-classpath-exception (GNU General Public License, version 2, with the Classpath Exception)\n")
        text.append("  Licence URL: https://openjdk.org/legal/gplv2+ce.html\n  Licence text: licenses/GPL-2.0-with-classpath-exception.txt\n")
        text.append("  Project: https://adoptium.net\n  Source code: https://github.com/adoptium/jdk25u\n")
        text.append("  The runtime has its own notices and the licences of its third-party parts in its legal folder and in its NOTICE file.\n\n")

        text.append("5. Licence texts\n----------------\n\n")
        for (name in usedTexts) {
            text.append("licenses/$name\n")
        }

        val licenses = File(output, "licenses")
        licenses.mkdirs()
        for (name in usedTexts) {
            val source = texts.get().asFile.resolve(name)
            check(source.isFile) { "The licence text $name is missing from app/licenses." }
            Files.copy(source.toPath(), licenses.resolve(name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        File(output, "THIRD-PARTY-NOTICES.txt").writeText(text.toString(), Charsets.UTF_8)
    }

    /** What the jar of a library is licensed under; fails, saying why, when that cannot be told. */
    private fun library(jar: File, module: ModuleComponentIdentifier): Library {
        val key = "${module.group}:${module.module}"
        val licences = overrides[key]?.let { listOf(it) } ?: licencesOf(module)
        val project = pom(module.group, module.module, module.version)?.let { parse(it).url }
        val bundled = (bundledComponents[key] ?: emptyList()).filter { jar.name.endsWith(it.jarSuffix) }
        return Library(jar.name, "$key:${module.version}", licences, project, noticesInside(jar), bundled)
    }

    private fun licencesOf(module: ModuleComponentIdentifier): List<Licence> {
        var group = module.group
        var artifact = module.module
        var version = module.version
        repeat(6) {
            val file = pom(group, artifact, version) ?: error("its POM $group:$artifact:$version cannot be had")
            val pom = parse(file)
            if (pom.licences.isNotEmpty()) {
                return pom.licences.map { (name, url) -> identify(name, url, module) }
            }
            val parent = pom.parent ?: error("neither its POM nor a parent POM names a licence")
            group = parent.first
            artifact = parent.second
            version = parent.third
        }
        error("its parent POMs are nested too deeply to find a licence")
    }

    private fun identify(name: String, url: String, module: ModuleComponentIdentifier): Licence {
        val n = name.lowercase()
        val u = url.lowercase()
        val id = when {
            "apache" in n && ("2.0" in n || "version 2" in n) -> "Apache-2.0"
            n == "apache-2.0" || "apache.org/licenses/license-2.0" in u -> "Apache-2.0"
            "bsd" in n && "3" in n -> "BSD-3-Clause"
            n == "mit" || n.startsWith("mit license") || "opensource.org/licenses/mit" in u -> "MIT"
            "gpl" in n && "classpath" in n -> "GPL-2.0-with-classpath-exception"
            else -> error("its POM names the licence '$name' ($url), which the build does not recognise")
        }
        val textFile = projectTexts["${module.group}:${module.module}"] ?: projectTexts[module.group] ?: standardTexts[id]
            ?: error("its licence $id has no text with the project's own copyright notice; put the project's text in app/licenses and name it in projectTexts")
        return Licence(id, name.ifEmpty { id }, url.ifEmpty { canonicalUrls.getValue(id) }, textFile)
    }

    private fun pom(group: String, artifact: String, version: String): File? {
        val handler = checkNotNull(dependencyHandler) { "dependencyHandler is not set" }
        val result = handler.createArtifactResolutionQuery().forModule(group, artifact, version)
            .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
        for (component in result.resolvedComponents) {
            for (found in component.getArtifacts(MavenPomArtifact::class.java)) {
                if (found is ResolvedArtifactResult) {
                    return found.file
                }
            }
        }
        return null
    }

    private fun parse(file: File): Pom {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val project = factory.newDocumentBuilder().parse(file).documentElement
        fun children(of: Element?, name: String): List<Element> {
            val nodes = of?.childNodes ?: return emptyList()
            return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == name }
        }
        fun text(of: Element?, name: String): String? = children(of, name).firstOrNull()?.textContent?.trim()?.ifEmpty { null }

        val licences = children(children(project, "licenses").firstOrNull(), "license").map { text(it, "name").orEmpty() to text(it, "url").orEmpty() }
        val parentElement = children(project, "parent").firstOrNull()
        val parent = parentElement?.let { Triple(text(it, "groupId").orEmpty(), text(it, "artifactId").orEmpty(), text(it, "version").orEmpty()) }
        return Pom(licences, text(project, "url"), parent)
    }

    private fun noticesInside(jar: File): List<String> = ZipFile(jar).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter { !it.endsWith("/") && !it.endsWith(".class") && Regex("(?i)^META-INF/(.*/)?(license|licence|notice)[^/]*$").matches(it) }
            .sorted().toList()
    }
}

val applicationJars = configurations.getByName("runtimeClasspath")
val workerJars = configurations.getByName("workerRuntime")
val robotJars = configurations.getByName("robotWorld")

tasks.register<GenerateNotices>("thirdPartyNotices") {
    group = "distribution"
    description = "Writes THIRD-PARTY-NOTICES.txt and the licenses folder of the distribution from the metadata of its libraries."
    jars.from(applicationJars, workerJars, robotJars, tasks.named("distJar"))
    texts.set(layout.projectDirectory.dir("licenses"))
    runtimeRelease.set(pinnedRuntimeRelease)
    notices.set(layout.buildDirectory.dir("dist-parts/notices"))
    resolved = listOf(applicationJars.incoming.artifacts, workerJars.incoming.artifacts, robotJars.incoming.artifacts)
    dependencyHandler = project.dependencies
}
