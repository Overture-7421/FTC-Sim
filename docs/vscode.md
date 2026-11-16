# Working on FTC-Sim in VS Code

Checked on Windows against VS Code 1.146, Java 1.58.0 (language server 1.63), Gradle for Java 3.17.3, Test Runner 0.46.0, Debugger 0.59.0. Gradle, JDWP and the language server were driven from the command line and over LSP, not by clicking in a VS Code window.

## Prerequisites
- **JDK 25 is forced; you do not choose it.** `gradle/libs.versions.toml` (`jdk = "25"`) sets a Java 25 toolchain for compiling, testing and `:desktop-ui:runFake`, and `gradle/gradle-daemon-jvm.properties` holds the Gradle daemon itself to Java 25. Gradle finds an installed JDK 25 (the Windows registry, `Program Files\Java`, and so on) and downloads one into `%USERPROFILE%\.gradle\jdks` when there is none, so a wrong or missing `JAVA_HOME` does not matter as long as it is a valid JDK 17 or newer, which `gradlew.bat` needs only to start. Code is compiled for that JDK only (`--release 25`, read from the same `jdk` entry), so it uses the final foreign function API directly. `desktop-ui` passes `--sun-misc-unsafe-memory-access`. The installed program carries a Java 25 runtime of its own, linked by `:app:dist` with the `jlink` of that same toolchain JDK (`docs/architecture/distribution.md`). Importing a team's own Android project is separate: it runs that project's Gradle build on a JDK 17 to 21 (Android Studio's bundled JDK is fine).
- Gradle may choose the JDK 25 that the Java extension bundles; it must be a JDK with `jlink` (the runtime of the distribution is made with it).
- Extensions (`.vscode/extensions.json`): Java, Debugger, Test Runner, Project Manager, Gradle for Java, Kotlin by JetBrains (optional, see limits).

## One time
1. Open the folder and choose "Trust" (tasks and the terminal environment need a trusted workspace).
2. Extensions view, filter `@recommended`, install what is missing, reload.
3. Wait for the status bar to say `Java: Ready` (18 projects: 17 modules plus `build-logic`, about a minute). If the Java output says `Failed to connect to build server using named pipe`, the import fell back to Buildship (see limits); reloading the window is the first thing to try.
4. Run `ftc-sim: build (compile everything)` once. Some tests read Gradle's `build\classes` folders.

## Recipes (Terminal > Run Task, Ctrl+Shift+B, the Testing view)
| Goal | Do |
|---|---|
| Build | `Ctrl+Shift+B`: classes, test classes and scenario classes of every module |
| All tests, `check`, module rules | `Terminal > Run Test Task`; `ftc-sim: check`; `ftc-sim: checkModuleRules` |
| One module / one class | `ftc-sim: test one module`, `ftc-sim: test one class` (pick `sim-core`, then type `dev.ftcsim.core.kernel.FixedRateWorldTest` or `*FixedRateWorldTest`; passes `--rerun` so cached results do not skip it) |
| Run the UI | `ftc-sim: run UI (fake host)` (`:desktop-ui:runFake`): the window alone, on the fake host of `desktop-ui`'s test fixtures (a fake simulator and a fake importer), so no robot code runs; the window says FAKE HOST. For your own robot project use `ftc-sim: run app` |
| Run the app | `ftc-sim: run app` (`:app:run`): FTC-Sim as a user runs it, the window on a real worker process and the real project importer; it opens the project that was open last time (see `docs/architecture/app.md`) |
| Build the distribution | `ftc-sim: build distribution` (`:app:dist`): `app\build\dist\FTC-Sim` and `FTC-Sim.zip`, the folder a driver unzips and starts with `FTC-Sim.cmd` (see `docs/architecture/distribution.md`) |
| Debug the UI | Run and Debug > `Debug UI (Gradle run with --debug-jvm)`, F5. The task starts Gradle with `--debug-jvm` and waits for `Listening for transport dt_socket at address: 5005`, then VS Code attaches |
| Debug the app | `Debug app (Gradle run with --debug-jvm)`, the same way. It attaches to the application's JVM; the worker is a process of its own and is not attached |
| Debug a test | `Debug one test class`, or the Debug button in the Testing view |
| Hornet's acceptance tests | `ftc-sim: Hornet acceptance tests (prepared package)`: asks for the folder of the prepared Hornet program (`-Pftcsim.hornetPackage`); nothing is saved. `-Pftcsim.hornet=<checkout>` instead prepares the unmodified checkout first (about a minute, needs Android Studio's JDK and Android SDK) |
| Any other task | `ftc-sim: Gradle (type the arguments)`, e.g. `:sim-core:test --tests *FooTest`, `:app:writeClasspath`; `debug any Gradle task` adds `--debug-jvm` |

javac and Kotlin compiler errors and warnings land in the Problems panel. A new module must be added to the `module` list of `.vscode/tasks.json`; until then use `ftc-sim: Gradle (type the arguments)`.

## Why the settings are what they are
- A user-level `terminal.integrated.env.windows` that points `JAVA_HOME` at another JDK (on this machine: `C:\Users\Public\wpilib\2023\jdk`, which does not exist) makes every `gradlew.bat` in a VS Code terminal fail with "JAVA_HOME is set to an invalid directory". The workspace resets `JAVA_HOME` and `PATH` to VS Code's own environment.
- Tasks run in `cmd.exe`, so quoting does not depend on your default terminal profile.
- `gradle.autoDetect` is off: Gradle for Java found 703 tasks in this build and would add them all to Run Task, where they run on the extension's bundled JRE, not your JDK. Use the `ftc-sim:` tasks.
- `java.configuration.updateBuildConfiguration` is `automatic`, so Gradle build script changes resynchronise without prompting.
- `java.compile.nullAnalysis.mode` is `disabled`; otherwise the server asks about null analysis because of the SDK's annotations.
- `kotlin.languageServer.enabled` and `kotlin.diagnostics.enabled` are `false`: they are the settings of `fwcd.kotlin`, an older
  community Kotlin server whose compiler cannot read this project's Kotlin 2.x and Compose code. It reports "Unresolved
  reference" on every Compose and project symbol (and a `FileNotFoundException` for a class file Gradle just rewrote), and
  those errors in the Problems panel make VS Code refuse to start the debug tasks ("errors exist after running
  preLaunchTask"). The workspace therefore switches it off (reload the window after pulling this) and does not recommend it;
  `JetBrains.kotlin-server` does not read these settings. Do not work around the prompt with `debug.onTaskErrors`: that also
  hides real errors.
- `java.test.config` copies the JVM settings of the `Test` tasks that a plain JUnit launch can reproduce (native access, locale, time zone, `ftcsim.nativeDirectory`, `ftcsim.fixtureClasses`). The distribution tests (`Distribution*Test`, `ThirdPartyNoticesTest`) need the properties Gradle gives them (`ftcsim.dist`, ...) and skip themselves in a plain launch.
- `build`, `.gradle`, `.kotlin` and `bin` folders of the modules are hidden from Explorer, search and the file watcher.

## Known limits
- **Testing view**: cannot run all of `acceptance-tests` (class paths and `ftcsim.*` properties computed by its Gradle task) nor `NativeImportsTest` in `sim-physics`; the distribution tests of `app` (`DistributionLayoutTest`, `ThirdPartyNoticesTest`, `DistributionEndToEndTest`) are skipped there, because Gradle builds the distribution and names it in `ftcsim.dist`. Every other test class passed when replayed with the server's own launch description. Use the Gradle tasks; the "Delegate Test to Gradle" profile (from Gradle for Java) is untried.
- **Kotlin** (`desktop-ui\src\main\kotlin`): the Java extension ignores `.kt` files. `JetBrains.kotlin-server` (JetBrains' own, alpha, for Kotlin JVM Gradle projects) is the recommended one and was not tried with Compose or this project. `fwcd.kotlin` was tried on this machine and gives false "Unresolved reference" errors (the Gradle build compiles the same code fine), so the workspace turns it off; do not run both. Without a Kotlin server there is no highlighting, navigation or live errors, only task output. Breakpoints in `.kt` files are untested.
- **Import fallback**: when the build-server connection fails (it did on the first opens after a Java extension update), the Java extension imports with Buildship and writes a `bin\` folder per module (`.gitignore` ignores it). The Gradle scripts configure Eclipse's Java 25 target, `:desktop-ui`'s compiled Kotlin classes and `:acceptance-tests`'s scenario dependencies so Buildship compiles cleanly once `ftc-sim: build (compile everything)` has run; if stale workspace metadata remains from an older module layout or Java extension upgrade, run `Java: Clean Java Language Server Workspace`.
- Gradle runs started by Gradle for Java itself (sidebar) use the extension's bundled JRE, not your JDK, so a second Gradle daemon (up to 3 GB, see `gradle.properties`) can be alive.
- `--debug-jvm` always uses port 5005: one debug session at a time (re-running the same debug task terminates its previous instance; stop another debug task's terminal before switching).
- `ftc-sim: run UI (fake host)` was checked up to the JVM start under `--debug-jvm`; no window was opened.
