plugins {
    base
}

/**
 * Enforces the module ownership boundaries declared in the root build script's moduleRules block,
 * so a module cannot quietly start depending on a project it is not meant to know about.
 *
 * A plain entry ("sim-link") allows the dependency in every configuration. An entry "worker@workerRuntime" allows it
 * in the configuration workerRuntime only, and only while that configuration is isolated: resolvable but not
 * consumable, extending no configuration and extended by none. That is how a module may hold the jars of a program it
 * starts as a process, to hand their paths to that process, without ever compiling or running against them.
 */
abstract class CheckModuleRules : DefaultTask() {
    @get:Input
    abstract val allowedEdges: MapProperty<String, List<String>>

    @get:Input
    abstract val actualEdges: MapProperty<String, List<String>>

    @TaskAction
    fun verify() {
        val allowed = allowedEdges.get()
        val violations = mutableListOf<String>()
        actualEdges.get().forEach { (module, edges) ->
            val permitted = allowed[module.removePrefix(":")]
            if (permitted == null) {
                violations += "$module has no entry in the moduleRules block of the root build script"
                return@forEach
            }
            if ("*" in permitted) return@forEach
            edges.map { it.removePrefix(":") }
                .filter { it !in permitted && it.substringBefore('@') !in permitted }
                .forEach { edge ->
                    val target = edge.substringBefore('@')
                    violations += if ('@' in edge) {
                        "$module must not depend on :$target, not even through its isolated configuration " +
                            "${edge.substringAfter('@')}; if handing its jars to another process is the point, " +
                            "permit \"$edge\" in the moduleRules block"
                    } else {
                        "$module must not depend on :$target"
                    }
                }
        }
        check(violations.isEmpty()) { violations.joinToString("\n", prefix = "Module boundary violations:\n") }
    }
}

abstract class ModuleRulesExtension {
    abstract val allowed: MapProperty<String, List<String>>

    /** [name] may depend on the projects in [dependsOn]; an entry "project@configuration" is described at [CheckModuleRules]. */
    fun module(name: String, vararg dependsOn: String) {
        allowed.put(name, dependsOn.toList())
    }
}

val moduleRules = extensions.create<ModuleRulesExtension>("moduleRules")

val checkModuleRules = tasks.register<CheckModuleRules>("checkModuleRules") {
    group = "verification"
    description = "Fails when a module depends on a project not permitted by the moduleRules block."
    allowedEdges.set(moduleRules.allowed)
    actualEdges.set(provider {
        subprojects.associate { sub ->
            val all = sub.configurations
            // A dependency in an isolated configuration is recorded with the configuration's name.
            fun isolated(configuration: Configuration) = configuration.isCanBeResolved && !configuration.isCanBeConsumed &&
                configuration.extendsFrom.isEmpty() && all.none { configuration in it.extendsFrom }
            sub.path to all
                .flatMap { configuration ->
                    configuration.dependencies.withType<ProjectDependency>().map {
                        if (isolated(configuration)) "${it.path}@${configuration.name}" else it.path
                    }
                }
                .distinct()
                .sorted()
        }
    })
}

tasks.named("check") {
    dependsOn(checkModuleRules)
}
