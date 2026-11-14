plugins {
    id("ftcsim.module-rules")
}

// Each module has one responsibility; a dependency not listed here fails `check`.
moduleRules {
    module("sim-contracts")
    module("sim-core", "sim-contracts")
    module("sim-physics", "sim-contracts")
    module("sim-hardware", "sim-contracts")
    module("sim-mechanisms", "sim-contracts", "sim-physics")
    module("sim-field", "sim-contracts", "sim-physics")
    module("game-decode", "sim-contracts", "sim-physics", "sim-field")
    module("game-biobuzz", "sim-contracts", "sim-physics", "sim-field")
    module(
        "simulator",
        "sim-contracts", "sim-core", "sim-physics", "sim-hardware", "sim-mechanisms", "sim-field",
        "game-decode", "game-biobuzz", "robot-runtime", "platform",
    )
    module("sim-link", "sim-contracts")
    module("worker", "sim-contracts", "sim-link", "simulator", "game-decode", "game-biobuzz", "project-import")
    module("robot-runtime", "sim-contracts")
    module("android-shim")
    module("sdk-common", "sim-contracts", "android-shim")
    module("project-import", "sim-contracts")
    module("desktop-ui", "sim-contracts", "platform")
    // The application reaches the worker, and the simulator's robot-world jars, as processes and as class path files:
    // it holds their jars in isolated configurations to name them, and compiles and runs against neither.
    module(
        "app",
        "sim-contracts", "sim-link", "desktop-ui", "project-import",
        "worker@workerRuntime", "sdk-common@robotWorld",
    )
    module("platform")
    module("launcher")
    module("acceptance-tests", "*")
}
