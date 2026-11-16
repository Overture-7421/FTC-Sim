# Working on FTC-Sim

How to build, change, test and hand over work in this repository. The picture of what the program is lives in
`architecture/overview.md`; the reasons for its shape in `architecture/decisions.md`; the editor setup in `vscode.md`;
how tests are organised in `testing.md`.

## What you need

- Windows 10 or 11, x64. Nothing else is supported.
- Any JDK 17 or newer, only so that `gradlew.bat` can start. The build forces **JDK 25** for compiling, testing and
  running (`jdk` in `gradle\libs.versions.toml`, the daemon in `gradle\gradle-daemon-jvm.properties`) and downloads one
  when none is installed (`--release 25`: the final foreign function API and Java 25 language features are available).
- To import a team's Android project, or to run the Hornet tests: an Android SDK (`ANDROID_HOME`, or Android Studio's)
  and a **JDK 17 to 21**, because a 2024 project's own Gradle cannot read Java 25 class files. The acceptance tests find
  or provision a JDK 21 themselves when a Hornet property is given; `-Pftcsim.jdk=<JDK home>` overrides it.

## The commands

Run them from the repository root. Add `--console=plain` when a script or an agent reads the output.

```text
.\gradlew.bat check                       every module's tests, checkModuleRules, the distribution's tests (about 4 minutes)
.\gradlew.bat checkModuleRules            only the dependency rules
.\gradlew.bat :sim-core:check             one module
.\gradlew.bat :sim-core:test --tests "dev.ftcsim.core.kernel.FixedRateWorldTest" --rerun      one class, even if nothing changed
.\gradlew.bat :acceptance-tests:test -PftcSdk=12.0.0       the fixture suite on another SDK (every ftcSdk* version of the catalog)
.\gradlew.bat :acceptance-tests:test -Pftcsim.hornetPackage=<folder> [-Pftcsim.hornet=<Hornet checkout>]
.\gradlew.bat :app:run                    the real application: window, worker process, importer
.\gradlew.bat :desktop-ui:runFake         the window alone, on the fake host of the test fixtures (add --args="--practice" for a fake session)
.\gradlew.bat :app:dist                   app\build\dist\FTC-Sim (with its runtime\) and FTC-Sim.zip; -Pftcsim.runtimeJdk=<JDK 25 home> links the runtime from another JDK
.\gradlew.bat :acceptance-tests:test -Pftcsim.runtime=app\build\dist\FTC-Sim\runtime --tests "*RealHost*Hornet*" -Pftcsim.hornetPackage=<folder>
                                          the real-host Hornet tests with the bundled runtime as the JVM (and so as the worker's)
```

- Test results are XML under `<module>\build\test-results\test\` and HTML under `<module>\build\reports\tests\test\`.
  Read them instead of scraping the console.
- Gradle restores results from its build cache and skips tasks whose inputs did not change: add `--rerun` to the task or
  `--no-build-cache` to the build when a test must actually run. `--continue` shows every failing task, and
  `--configure-on-demand` configures only the projects a build needs.
- The Hornet tests are skipped, not failed, without their properties; read the skipped count (`testing.md`).
  `-Pftcsim.hornetPackage` names a folder that survives between runs: the first run imports Hornet into it from
  `-Pftcsim.hornet` (about a minute; needs the Android SDK), later runs reuse it.

### Hazards of this build

- **Never run `gradlew clean`.** Class folders and jars that test JVMs, other builds and the editor's test runner are
  using disappear, and several tests read `build` folders. If a module's output is truly stale, delete that module's
  `build` folder while nothing else runs.
- **Two builds at once on one checkout need two private environments.** Gradle keeps the history of every task in the
  project's `.gradle` folder, and a task that runs again deletes the "previous outputs" recorded there, which can be
  the other build's class folders. A separate build directory alone does not help. Give each build both:

  ```text
  .\gradlew.bat --console=plain --configure-on-demand --no-build-cache --project-cache-dir C:\work\mine\gradle-cache -I C:\work\mine\private-build.init.gradle check
  ```

  with `private-build.init.gradle` redirecting every project's build directory:

  ```groovy
  allprojects { p ->
      p.layout.buildDirectory.set(new File('C:/work/mine/build/' + (p == p.rootProject ? 'root' : p.path.replace(':', '_'))))
  }
  ```

  The included build `build-logic` still builds in `build-logic\build`: do not start two builds at the same moment
  after changing it. Delete your private folders when you finish.
- **Windows will not replace a jar that a process has open.** A test JVM that is still running holds the jars it was
  started with; the build then fails to rewrite them. Find the process, confirm it is yours, `Stop-Process -Id <pid>`.
- **One robot world per JVM.** The FTC SDK keeps state in singletons, so the acceptance tests fork a JVM per test class
  (`forkEvery = 1`, 2 GB), because a Hornet session leaves library threads that keep the robot world alive.
- **Do not start the real window casually.** Use `FTC-Sim.cmd --self-test`, or `--no-focus --exit-after=<seconds>`
  (`.\gradlew.bat :app:run --args="--exit-after=30 --no-focus"`). Tests drive the UI with synthetic events inside the
  process; nothing here may use the real mouse or keyboard or take focus from the person at the machine.
- **Logs.** The window and the worker write `%LOCALAPPDATA%\FTC-Sim\logs\app-*.log` and `worker-*.log`: the first place to
  look when a real run went wrong.

## Rules for the code

What the product is, and what it rules out, was decided by the product owner: `architecture/decisions.md` has the reasons
and `AGENTS.md` the list of what is not yours to reverse (emulate hardware and the Android environment only, no special
cases for a team's classes, only DECODE and BIOBUZZ, lockstep time, no repeatability promise). Ask before going against it.

### Time

Simulated time is lockstep: robot code sees only the simulated clock (`architecture/decisions.md`, sections 2, 9 and 10).
In code that is part of a run:

- **No wall clock.** No `System.nanoTime`, `currentTimeMillis`, `Instant.now`, `LocalDateTime.now`. Time arrives as an
  argument, in simulated nanoseconds. The one place wall-clock time may touch a session is `RealtimeRunner`, and it only
  decides when simulated time moves; the window and `platform` pace themselves in real time.

Nothing else is promised about order or repeatability: hash-ordered collections, unseeded randomness and `Math` instead
of `StrictMath` are fine where they are harmless. The field's own seed (`FieldSetup.seed`) still decides how a field is
laid out.

### Structure

- **No logic duplication.** Search before you write (`grep`, `glob`): extend or reuse (`RegisterFilePeripheral` for
  register maps, `PieceReserve` and `Region` for fields, `EntityIds` for ids). If you find duplicated logic, remove it or
  say so.
- **Modules have one responsibility, and the rules are enforced.** The `moduleRules` block of the root
  `build.gradle.kts` lists what each module may depend on; anything else fails `check`. Do not widen the rules to get
  something done; move the code to where it belongs. A dependency on another module's test fixtures
  (`testFixtures(project(":x"))`) counts as a dependency on that module. A new module needs: a folder with a
  `build.gradle.kts` that applies `ftcsim.java-conventions`, an `include` in `settings.gradle.kts`, a `module(...)` line in
  `moduleRules`, an entry in the `module` list of `.vscode\tasks.json`, a `package-info.java` per package, and a row in
  the module table of `architecture/overview.md`. Packages that share a module can still be kept apart: the two games in
  `games` must not refer to each other (`GamePackagesTest`).
- **The two sides share only `dev.ftcsim.contracts.*`.** No SDK type may appear in the host, and the host's classes are
  not visible in the robot world.
- **Languages.** Java everywhere; Kotlin only for the Compose screens of `desktop-ui` (the renderer, link and host seams
  there are Java). Four-space indent, lines up to 120 columns, records for data, `final` by default.

### Compiler, comments and tests

- Every module compiles with `-Xlint:all,-serial,-processing -Werror -parameters` (Kotlin: all warnings are errors). Where
  a suppression is truly right, make the reason visible from the code: `restricted` on the class that exists to make
  native calls (`WindowsWaitableTimer`, `NativeLibrary`), `removal` on the hooks that must still intercept JDK members the
  JDK is removing (`NetworkHooks`).
- Comment only what needs clarification. Javadoc on public types and on non-obvious methods is welcome; narration is not.
- **Tests** (`testing.md`): JUnit 6, `org.junit.jupiter.api`. A test must be able to fail, tests behaviour you add and
  does not sleep.
- **Documentation is part of the change.** A change that alters behaviour updates the page that describes it; a new
  package gets a `package-info.java`; a new page is added to `docs/README.md`.

## How the build is put together

- `settings.gradle.kts` lists the modules, lets Gradle download a JDK 25 (the foojay resolver), and allows only Maven
  Central and, for `androidx.*` alone, Google's repository: a project cannot add repositories of its own.
- `build-logic` is an included build with three plugins. `ftcsim.java-conventions`: the JDK 25 toolchain, `--release 25`,
  the lint flags, JUnit 6 and the test JVMs' flags. `ftcsim.module-rules`: the dependency rules. `ftcsim.ftc-sdk`: the FTC
  SDK as the AARs it is published in, turned into jars by an artifact transform; `sdk-common` compiles against 11.1.0 and
  `acceptance-tests` runs on the release `-PftcSdk` selects.
- `gradle\libs.versions.toml` holds every version, exact: no ranges, and no Gradle lockfiles (the SDK-version
  configurations of the matrix would have to be exempted, and a lock that must be regenerated at every change costs more
  than it protects here). `gradle.properties` gives the daemon 3 GB and turns the build cache and parallel execution on.
- What must not depend on something at compile time is handed to it as a file or a system property: `app` gives the
  worker its jars as a class path file (`AppClasspath`, `:app:writeClasspath`, `architecture/app.md`), and
  `acceptance-tests` computes the `-Dftcsim.*Classpath` properties of its test JVMs from isolated configurations.
- Third-party licence notices are generated from the jars of the distribution (`:app:thirdPartyNotices`); a jar whose
  licence cannot be determined fails the build with its name. The bundled Java runtime is made by `:app:runtimeImage`
  (`architecture/distribution.md`).

## Recipes

| To | Read | The short version |
|---|---|---|
| Add an ideal sensor (an I2C model or a camera) | `architecture/adding-a-device.md`, `devices/` | One model class, one registry line (`DeviceModels.standard()`, `CameraModels.standard()`), a `HardwareDirectory.Sensor`, the binding in `I2cDriverProvider`, the rig's attachment; the SDK's own driver is the specification |
| Add a motor to the catalogue | `robots/fixed-robots.md` (Mapping) | `MotorCatalog` in `simulator\robot`, one entry with the motor's constants and its gearbox |
| Map another robot | `robots/fixed-robots.md`, `robots/hornet-profile.md` | The **Robot** screen writes `ftcsim.json` (schema 2) at the root of the robot project; no code |
| Virtualise another JDK member | package-info of `dev.ftcsim.runtime`; `decisions.md` section 10 | A row in `TimeRules`, `NetworkRules` or `RewriteRules`, a method with the implied descriptor in the hooks class (`contracts.hooks`), a probe and a rewrite test; the rule tests scan the JDK and fail for a member that is missing |
| Support another FTC SDK release | `sdk-versions.md` | A version named `ftcSdk...` in `gradle\libs.versions.toml`, the fixture suite with it, then the table in `sdk-versions.md`; keep any difference behind one seam |
| Change the link between window and worker | `architecture/link.md`, package `link.wire` | A message that gains, loses or reorders a field bumps `Protocol.VERSION`; update the codec, the table in the package description and `link.md` together |
| Change what the importer does | `architecture/prepared-program.md` | A change that makes packages come out different or better raises `IMPORTER_REVISION`, so that kept packages are made again |
| Change what the bundled runtime holds, or move to another Java major version | `architecture/distribution.md`, `decisions.md` section 7 | `runtimeModules` in `app\runtime.gradle.kts`, or `jdk` in `gradle\libs.versions.toml` and `gradle\gradle-daemon-jvm.properties` (the only pin); then `:app:dist`, `--self-test`, the real-host Hornet tests on the runtime, and `DistributionLayoutTest`'s module list |
| Refit the fixed robot | `robots/hornet-profile.md` | Edit the constants of `RobotDesign` in `simulator`; the chassis numbers are fitted and should not move; change the numbers `RobotDesignTest` spells out in the same change; re-run the shot sweep and `HornetAutoRunTest` |

## Before you hand work over

1. `.\gradlew.bat check` is green. It includes the module rules.
2. If you touched the robot world, the SDK host, the hardware emulation, the mechanisms, physics, a field, the importer
   or the link: run the acceptance suite with Hornet (`-Pftcsim.hornetPackage=...`) on the default SDK, and, for anything
   that the SDK sees, the fixture suite on 11.0.0, 11.2.0, 11.2.1 and 12.0.0 (each about 3.5 minutes; Hornet about 7.5).
3. If you touched `app`, the launcher or the window: `.\gradlew.bat :app:dist`, then `FTC-Sim.cmd --self-test` from
   `app\build\dist\FTC-Sim` (with no Java on the PATH and no JAVA_HOME: it must run on its own `runtime`).
4. The documentation that describes your change is updated, and `docs/README.md` still lists every page.
5. You left no stray process, private build folder or temporary file behind, and did not change git state unless asked.
   `git status --ignored` shows no ignored file under a `src` folder (a package folder named `build` would be ignored).
6. Your report says what you ran, with counts, and what you could **not** verify. Do not claim what you did not run.

## When something is odd

| You see | Likely cause |
|---|---|
| `Could not execute test class` for a class that has no source | Stale class files in `<module>\build\classes` left by builds whose history was lost; delete that module's `build` folder (nothing else may be building) and run again |
| Hornet or OpenGL tests show as skipped | The property is not given, or there is no OpenGL 3.3 context; skipped is not passed |
| An acceptance test runs out of memory | Sessions of a Hornet-like program leak their robot world inside one JVM; keep one test class per JVM |
| A session shows a problem (`SessionStatus.problems`) | The kernel noticed something about the robot thread; `decisions.md` sections 9 and 10 |
