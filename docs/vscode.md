# Working on FTC-Sim in VS Code

Checked on Windows against VS Code 1.146, Java 1.58.0 (language server 1.63), Gradle for Java 3.17.3, Test Runner 0.46.0, Debugger 0.59.0. Gradle, JDWP and the language server were driven from the command line and over LSP, not by clicking in a VS Code window. The JDK, the commands and the build's hazards are in `development.md`; this page is only what is specific to the editor.

## Setup
- A wrong or missing `JAVA_HOME` does not matter as long as it is a valid JDK 17 or newer (Gradle finds or downloads the JDK 25, `development.md`). Gradle may choose the JDK 25 that the Java extension bundles; it must be a JDK with `jlink`.
- Extensions (`.vscode/extensions.json`): Java, Debugger, Test Runner, Project Manager, Gradle for Java, Kotlin by JetBrains (optional, see limits).
- Open the folder and choose "Trust" (tasks and the terminal environment need a trusted workspace); install the `@recommended` extensions and reload; wait for the status bar to say `Java: Ready` (18 projects, about a minute); run `ftc-sim: build (compile everything)` once, because some tests read Gradle's `build\classes` folders. If the Java output says `Failed to connect to build server using named pipe`, the import fell back to Buildship (see limits); reloading the window is the first thing to try.

## Tasks (Terminal > Run Task, Ctrl+Shift+B, the Testing view)
| Goal | Do |
|---|---|
| Build, all tests, checks | `Ctrl+Shift+B` (classes, test classes and scenario classes of every module); `Terminal > Run Test Task`; `ftc-sim: check`; `ftc-sim: checkModuleRules` |
| One module / one class | `ftc-sim: test one module`, `ftc-sim: test one class` (type `dev.ftcsim.core.kernel.FixedRateWorldTest` or `*FixedRateWorldTest`; passes `--rerun`) |
| Run | `ftc-sim: run app` (`:app:run`, the real application); `ftc-sim: run UI (fake host)` (`:desktop-ui:runFake`, the window alone, which says FAKE HOST); `ftc-sim: build distribution` (`:app:dist`) |
| Debug | Run and Debug > `Debug UI (Gradle run with --debug-jvm)` or `Debug app ...`, F5: the task waits for `Listening for transport dt_socket at address: 5005`, then VS Code attaches. The app's worker is a process of its own and is not attached. `Debug one test class`, or the Debug button in the Testing view, for a test |
| Hornet's acceptance tests | `ftc-sim: Hornet acceptance tests (prepared package)` asks for the prepared program's folder (`-Pftcsim.hornetPackage`); nothing is saved |
| Any other task | `ftc-sim: Gradle (type the arguments)`, e.g. `:sim-core:test --tests *FooTest`; `debug any Gradle task` adds `--debug-jvm` |

javac and Kotlin compiler errors and warnings land in the Problems panel. A new module must be added to the `module` list of `.vscode/tasks.json`; until then use `ftc-sim: Gradle (type the arguments)`.

## Why the settings are what they are
- A user-level `terminal.integrated.env.windows` that points `JAVA_HOME` at a JDK that does not exist makes every `gradlew.bat` in a VS Code terminal fail with "JAVA_HOME is set to an invalid directory". The workspace resets `JAVA_HOME` and `PATH` to VS Code's own environment. Tasks run in `cmd.exe`, so quoting does not depend on your terminal profile.
- `gradle.autoDetect` is off: Gradle for Java found 703 tasks in this build and would add them all to Run Task, where they run on the extension's bundled JRE, not your JDK. Use the `ftc-sim:` tasks.
- `java.configuration.updateBuildConfiguration` is `automatic`; `java.compile.nullAnalysis.mode` is `disabled` (otherwise the server asks about null analysis because of the SDK's annotations).
- `kotlin.languageServer.enabled` and `kotlin.diagnostics.enabled` are `false`: they are the settings of `fwcd.kotlin`, an older community Kotlin server whose compiler cannot read this project's Kotlin 2.x and Compose code. It reports "Unresolved reference" on every Compose and project symbol, and those errors in the Problems panel make VS Code refuse to start the debug tasks ("errors exist after running preLaunchTask"). The workspace switches it off (reload the window after pulling) and does not recommend it; `JetBrains.kotlin-server` does not read these settings. Do not work around the prompt with `debug.onTaskErrors`: that also hides real errors.
- `java.test.config` copies the JVM settings of the `Test` tasks that a plain JUnit launch can reproduce. The distribution tests need the properties Gradle gives them (`ftcsim.dist`, ...) and skip themselves in a plain launch.
- `build`, `.gradle`, `.kotlin` and `bin` folders of the modules are hidden from Explorer, search and the file watcher.

## Known limits
- **Testing view**: cannot run all of `acceptance-tests` (class paths and `ftcsim.*` properties computed by its Gradle task), `NativeImportsTest` in `sim-physics`, nor the distribution tests of `app` (skipped: Gradle builds the distribution and names it in `ftcsim.dist`). Every other test class passed when replayed with the server's own launch description. Use the Gradle tasks; the "Delegate Test to Gradle" profile (from Gradle for Java) is untried.
- **Kotlin** (`desktop-ui\src\main\kotlin`): the Java extension ignores `.kt` files. `JetBrains.kotlin-server` (alpha) is the recommended one and was not tried with Compose or this project; `fwcd.kotlin` gives false errors, so do not run both. Without a Kotlin server there is no highlighting, navigation or live errors, only task output; breakpoints in `.kt` files are untested.
- **Import fallback**: with Buildship the Java extension writes a `bin\` folder per module (`.gitignore` ignores it). The Gradle scripts configure Eclipse's Java 25 target, `:desktop-ui`'s Kotlin classes and `:acceptance-tests`'s scenario dependencies so that it compiles cleanly once `ftc-sim: build (compile everything)` has run; if stale workspace metadata remains, run `Java: Clean Java Language Server Workspace`.
- Gradle runs started by Gradle for Java itself (sidebar) use the extension's bundled JRE, so a second Gradle daemon (up to 3 GB) can be alive.
- `--debug-jvm` always uses port 5005: one debug session at a time (re-running the same debug task terminates its previous instance; stop another debug task's terminal before switching).
- `ftc-sim: run UI (fake host)` was checked up to the JVM start under `--debug-jvm`; no window was opened.
