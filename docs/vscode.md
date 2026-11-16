# VS Code

Editor setup, tasks and known limits for anyone working on FTC-Sim in VS Code; the JDK, commands and build hazards are in [development.md](development.md).

Checked on Windows with VS Code 1.146, Java extension 1.58.0 (language server 1.63), Gradle for Java 3.17.3, Test Runner 0.46.0, Debugger 0.59.0. Gradle, JDWP and the language server were driven from the command line and over LSP, not by clicking in a VS Code window.

## Setup

1. Open the folder, choose **Trust** (tasks need it), install the `@recommended` extensions (`.vscode/extensions.json`) and reload.
2. Wait for `Java: Ready` (about a minute), then run `ftc-sim: build (compile everything)` once: some tests read Gradle's `build\classes` folders.
3. `JAVA_HOME` may be missing or wrong if it is a valid JDK 17 or newer: Gradle finds or downloads JDK 25. If it picks the JDK 25 bundled with the Java extension, that JDK must have `jlink`.

`Failed to connect to build server using named pipe` means the import fell back to Buildship (see limits); reload the window first.

## Tasks

Terminal > Run Task, `Ctrl+Shift+B` or the Testing view. A new module must be added to the `module` list in `.vscode/tasks.json`; until then use `ftc-sim: Gradle (type the arguments)`. javac and Kotlin errors go to the Problems panel.

| Goal | Do |
|---|---|
| Compile everything | `Ctrl+Shift+B` (`ftc-sim: build (compile everything)`: main, test and scenario classes of every module) |
| Tests and checks | `Terminal > Run Test Task` (`ftc-sim: test (all modules)`); `ftc-sim: check (tests + module rules)`; `ftc-sim: checkModuleRules` |
| One module or class | `ftc-sim: test one module`; `ftc-sim: test one class` (type `dev.ftcsim.core.kernel.FixedRateWorldTest` or `*FixedRateWorldTest`; passes `--rerun`) |
| Run | `ftc-sim: run app` (`:app:run`, the real application); `ftc-sim: run UI (fake host)` (`:desktop-ui:runFake`, says FAKE HOST); `ftc-sim: build distribution` (`:app:dist`) |
| Debug | `F5`: `Debug UI` or `Debug app` (Gradle run with `--debug-jvm`). The task waits for `Listening for transport dt_socket at address: 5005`, then VS Code attaches. The worker is a separate process and is not attached. For a test: `Debug one test class`, or the Debug button in the Testing view |
| Hornet tests | `ftc-sim: Hornet acceptance tests (prepared package)` asks for the prepared program's folder (`-Pftcsim.hornetPackage`); nothing is saved |
| Anything else | `ftc-sim: Gradle (type the arguments)`, e.g. `:sim-core:test --tests *FooTest`; `ftc-sim: debug any Gradle task` adds `--debug-jvm` |

## Why the settings are what they are

- **`JAVA_HOME` and `PATH` are reset** to VS Code's own. A user-level `terminal.integrated.env.windows` pointing `JAVA_HOME` at a missing JDK makes every `gradlew.bat` fail ("JAVA_HOME is set to an invalid directory"). Tasks run in `cmd.exe`, so quoting does not depend on your terminal profile.
- **`gradle.autoDetect` is off.** Gradle for Java would add every Gradle task to Run Task, on the extension's bundled JRE instead of your JDK. Use the `ftc-sim:` tasks.
- `java.configuration.updateBuildConfiguration` is `automatic`; `java.compile.nullAnalysis.mode` is `disabled` (the SDK's annotations make the server ask about null analysis).
- **The `fwcd.kotlin` server is off** (`kotlin.languageServer.enabled` and `kotlin.diagnostics.enabled` are `false`; the extension is listed as unwanted). It cannot read this project's Kotlin 2.x and Compose code, and its "Unresolved reference" errors make VS Code refuse the debug tasks ("errors exist after running preLaunchTask"). Reload the window after pulling. Do not use `debug.onTaskErrors` as a workaround: it hides real errors.
- `java.test.config` copies the JVM settings of the `Test` tasks that a plain JUnit launch can reproduce. The distribution tests need properties only Gradle sets (`ftcsim.dist`, ...) and skip themselves in a plain launch.
- `build`, `.gradle`, `.kotlin` and `bin` folders of the modules are hidden from Explorer, search and the file watcher.

## Known limits

- **Testing view** cannot run all of `acceptance-tests` (its class paths and `ftcsim.*` properties come from its Gradle task), `NativeImportsTest` in `sim-physics`, or `app`'s distribution tests (they skip). Every other test class passed when replayed with the server's own launch description. Use the Gradle tasks; the "Delegate Test to Gradle" profile is untried.
- **Kotlin** (`desktop-ui\src\main\kotlin`): the Java extension ignores `.kt` files. `JetBrains.kotlin-server` (alpha) is recommended but not tried with Compose or this project; do not run it with `fwcd.kotlin`. Without a Kotlin server there is no highlighting, navigation or live errors, only task output; breakpoints in `.kt` files are untested.
- **Import fallback.** With Buildship the Java extension writes a `bin\` folder per module (git ignores it). The Gradle scripts set Eclipse's Java 25 target and the Kotlin and scenario dependencies so it compiles cleanly after the build task. If stale metadata remains, run `Java: Clean Java Language Server Workspace`.
- Gradle runs started by Gradle for Java (sidebar) use the extension's bundled JRE, so a second Gradle daemon (up to 3 GB) can be alive.
- `--debug-jvm` always uses port 5005: one debug session at a time. Re-running a debug task ends its previous instance; stop another debug task's terminal before switching.
- `ftc-sim: run UI (fake host)` was checked only up to the JVM start under `--debug-jvm`; no window was opened.
